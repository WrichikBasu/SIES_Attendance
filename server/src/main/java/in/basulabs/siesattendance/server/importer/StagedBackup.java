package in.basulabs.siesattendance.server.importer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

import in.basulabs.siesattendance.backend.migration.MigrationContext;
import in.basulabs.siesattendance.backend.migration.SchemaMigrations;
import in.basulabs.siesattendance.backend.util.BackupContainer;

/**
 * A device's backup, decrypted onto disk and brought up to the current schema, ready to be read
 * row by row.
 * <p>
 * This is the first step of an import and the one with the most ways to go wrong, so it does all
 * of them before the PostgreSQL transaction is opened: a file that is not a backup, a wrong
 * password, a truncated download, a database that does not unpack, a schema from a release newer
 * than this build. None of those should be discovered half way through writing rows.
 *
 * <h3>The migrations are the app's own</h3>
 * A backup carries whatever schema version the phone that wrote it was on. The importer is
 * written against the current one and knows nothing about older ones, so the staged file is
 * brought forward first — by {@link SchemaMigrations}, which is the very code the phone would
 * have run, not a second implementation of it. After this closes, nothing downstream has to
 * know that old versions exist.
 * <p>
 * Foreign keys are left off while that happens, which is deliberate and matches the app: the
 * v6-to-v7 step drops and recreates five parent tables, and enforcement during it would reject
 * the intermediate state. Room turns keys on in {@code onOpen}, after every migration, for the
 * same reason. They are turned on afterwards here, and a violation then is a real finding about
 * the device's data rather than an artefact of the upgrade.
 *
 * <h3>The zone</h3>
 * {@link MigrationContext} carries the zone the backup's local dates should be read at, and the
 * importer passes the zone the data was recorded in rather than the server's. The server runs in
 * UTC by configuration; seeding the session table derives epoch bounds from a date at a zone,
 * and the difference between Asia/Kolkata and UTC there is five and a half hours.
 */
public final class StagedBackup implements AutoCloseable {

	/** What went wrong, where the answer is about the file rather than about the database. */
	public enum Rejection {

		/** Not a backup produced by this app. */
		NOT_A_BACKUP,

		/** Written by a newer release whose container format this build cannot read. */
		FORMAT_TOO_NEW,

		/** Encrypted, and either no password was given or the wrong one was. */
		BAD_PASSWORD,

		/** Truncated, corrupted, or not a usable database once unpacked. */
		DAMAGED,

		/** From a newer release whose schema this build cannot bring forward. */
		SCHEMA_TOO_NEW,

		/** The file could not be read or the staging copy could not be written. */
		IO
	}

	/** A backup that will not be imported, with the reason the operator needs to see. */
	public static final class RejectedException extends Exception {

		private static final long serialVersionUID = 1L;

		@NonNull
		private final Rejection rejection;

		RejectedException(@NonNull Rejection rejection, @Nullable String detail) {
			super(detail == null ? rejection.name() : rejection.name() + ": " + detail);
			this.rejection = rejection;
		}

		@NonNull
		public Rejection rejection() {
			return rejection;
		}
	}

	@NonNull
	private final Path staged;

	@NonNull
	private final Connection connection;

	@NonNull
	private final BackupContainer.Info info;

	@NonNull
	private final String digest;

	private final int schemaVersionAsFound;

	private StagedBackup(@NonNull Path staged, @NonNull Connection connection,
	                     @NonNull BackupContainer.Info info, @NonNull String digest,
	                     int schemaVersionAsFound) {
		this.staged = staged;
		this.connection = connection;
		this.info = info;
		this.digest = digest;
		this.schemaVersionAsFound = schemaVersionAsFound;
	}

	/**
	 * Decrypts, unpacks, checks and migrates {@code backup}, leaving it open for reading.
	 *
	 * @param backup   the {@code .siesbak} file
	 * @param password the password it was encrypted with, or null for one that is not. Wiped
	 *                 before returning either way, because the caller has no further use for
	 *                 it and this is the last thing that does
	 * @param zone     the zone the device recorded its local dates at, used by the migrations
	 * @param workDir  where the decrypted copy is written. It is a full clone of somebody's
	 *                 data, so it belongs somewhere deliberate rather than in /tmp by default
	 * @throws RejectedException if the file cannot be imported, with the reason
	 */
	@NonNull
	public static StagedBackup open(@NonNull Path backup, @Nullable char[] password,
	                                @NonNull ZoneId zone, @NonNull Path workDir)
			throws RejectedException {

		String digest = digestOf(backup);
		Path staged = null;

		try {
			Files.createDirectories(workDir);
			staged = Files.createTempFile(workDir, "import-", ".sqlite3");

			BackupContainer.Info info = unpackInto(backup, password, staged);

			Connection connection = openSqlite(staged);

			try {
				validate(connection, staged);

				int found = userVersion(connection);

				if (found > SchemaMigrations.CURRENT_VERSION) {
					// The header said otherwise or said nothing; the user_version inside the
					// file is the one that cannot be edited without breaking the integrity
					// check, so it is the one that decides.
					throw new RejectedException(Rejection.SCHEMA_TOO_NEW,
							"the database inside the backup is at schema version " + found
									+ " and this build reads up to "
									+ SchemaMigrations.CURRENT_VERSION);
				}

				migrate(connection, found, zone);

				return new StagedBackup(staged, connection, info, digest, found);

			} catch (RejectedException | RuntimeException | SQLException e) {
				closeQuietly(connection);
				throw e instanceof RejectedException rejected
						? rejected
						: new RejectedException(Rejection.DAMAGED, e.getMessage());
			}

		} catch (RejectedException e) {
			deleteQuietly(staged);
			throw e;
		} catch (IOException e) {
			deleteQuietly(staged);
			throw new RejectedException(Rejection.IO, e.getMessage());
		} finally {
			if (password != null) {
				java.util.Arrays.fill(password, '\0');
			}
		}
	}

	/** The open connection to the staged copy, at the current schema. */
	@NonNull
	public Connection connection() {
		return connection;
	}

	/** What the header said. */
	@NonNull
	public BackupContainer.Info info() {
		return info;
	}

	/** SHA-256 of the file as it arrived, in lower-case hex. */
	@NonNull
	public String digest() {
		return digest;
	}

	/**
	 * The schema version the backup was at before being migrated, which is the interesting one
	 * for a report: it says which release the device was running.
	 */
	public int schemaVersionAsFound() {
		return schemaVersionAsFound;
	}

	/** Closes the connection and removes the decrypted copy. */
	@Override
	public void close() {
		closeQuietly(connection);
		deleteQuietly(staged);
	}

	// ── The steps ────────────────────────────────────────────────────────

	/**
	 * Reads the header, refuses what cannot be read, and writes the SQLite file out.
	 */
	@NonNull
	private static BackupContainer.Info unpackInto(@NonNull Path backup,
	                                               @Nullable char[] password,
	                                               @NonNull Path staged)
			throws RejectedException {

		try (InputStream raw = new BufferedInputStream(Files.newInputStream(backup))) {

			DataInputStream in = new DataInputStream(raw);
			BackupContainer.Info info = BackupContainer.readHeader(in);

			if (info == null) {
				throw new RejectedException(Rejection.NOT_A_BACKUP, null);
			}

			if (info.formatTooNew()) {
				throw new RejectedException(Rejection.FORMAT_TOO_NEW, null);
			}

			if (info.schemaVersion > SchemaMigrations.CURRENT_VERSION) {
				throw new RejectedException(Rejection.SCHEMA_TOO_NEW,
						"the header says schema version " + info.schemaVersion
								+ " and this build reads up to "
								+ SchemaMigrations.CURRENT_VERSION);
			}

			if (info.encrypted && (password == null || password.length == 0)) {
				throw new RejectedException(Rejection.BAD_PASSWORD,
						"the backup is encrypted and no password was given");
			}

			try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(staged))) {
				BackupContainer.unpack(in, info, password, out);
			}

			return info;

		} catch (GeneralSecurityException e) {
			// A cipher failure on a file whose header parsed is far more likely a wrong
			// password than a broken machine, and GCM gives no way to tell them apart.
			throw new RejectedException(Rejection.BAD_PASSWORD, null);
		} catch (BackupContainer.DamagedBackupException e) {
			throw new RejectedException(Rejection.DAMAGED, e.getMessage());
		} catch (IOException e) {
			throw new RejectedException(Rejection.IO, e.getMessage());
		}
	}

	/**
	 * Opens the staged file with foreign keys off, for the reason in the class notes.
	 */
	@NonNull
	private static Connection openSqlite(@NonNull Path staged) throws RejectedException {

		try {
			Connection connection = DriverManager.getConnection(
					"jdbc:sqlite:" + staged.toAbsolutePath());
			connection.setAutoCommit(false);

			try (Statement statement = connection.createStatement()) {
				statement.execute("PRAGMA foreign_keys = OFF");
			}
			return connection;

		} catch (SQLException e) {
			throw new RejectedException(Rejection.DAMAGED,
					"the backup did not unpack into a database: " + e.getMessage());
		}
	}

	/**
	 * Confirms the unpacked file really is one of our databases and is internally consistent.
	 * <p>
	 * The same two checks the app makes before letting a restore near the live data, and for
	 * the same reason: a file that passes the header check can still be a different
	 * application's database, or a half-downloaded copy of ours.
	 */
	private static void validate(@NonNull Connection connection, @NonNull Path staged)
			throws RejectedException, SQLException {

		try (Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery("PRAGMA integrity_check")) {

			if (!results.next() || !"ok".equalsIgnoreCase(results.getString(1))) {
				throw new RejectedException(Rejection.DAMAGED,
						"the database inside the backup is corrupted");
			}
		}

		Set<String> tables = new HashSet<>();

		try (Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(
				     "SELECT name FROM sqlite_master WHERE type = 'table'")) {

			while (results.next()) {
				tables.add(results.getString(1));
			}
		}

		if (!tables.containsAll(BackupContainer.EXPECTED_TABLES)) {

			Set<String> missing = new HashSet<>(BackupContainer.EXPECTED_TABLES);
			missing.removeAll(tables);

			throw new RejectedException(Rejection.NOT_A_BACKUP,
					staged.getFileName() + " is a database, but not one from this app; it has "
							+ "no " + missing);
		}
	}

	private static int userVersion(@NonNull Connection connection) throws SQLException {

		try (Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery("PRAGMA user_version")) {

			return results.next() ? results.getInt(1) : 0;
		}
	}

	/**
	 * Runs the shared migrations, then records the version reached and turns foreign keys on.
	 * <p>
	 * One transaction for the whole chain, like Room's, so a failure part way leaves the
	 * staged file unusable rather than half upgraded — and the staged file is a throwaway, so
	 * unusable is the right outcome.
	 */
	private static void migrate(@NonNull Connection connection, int fromVersion,
	                            @NonNull ZoneId zone) throws SQLException, RejectedException {

		if (fromVersion < SchemaMigrations.FIRST_SUPPORTED_VERSION) {
			throw new RejectedException(Rejection.DAMAGED,
					"the database inside the backup reports schema version " + fromVersion
							+ ", which is older than anything this app released");
		}

		if (fromVersion == SchemaMigrations.CURRENT_VERSION) {
			enableForeignKeys(connection);
			return;
		}

		try {
			// today() matters only for seeding a session to file new attendance under. The
			// server's own date is the honest answer: the import is happening now, and the
			// phone's "today" is not recoverable from the file.
			SchemaMigrations.migrate(new JdbcSqliteDb(connection), fromVersion,
					new MigrationContext(zone, LocalDate.now(zone)));

			try (Statement statement = connection.createStatement()) {
				// PRAGMA takes no parameters; the value is an int this code just checked
				// against CURRENT_VERSION.
				statement.execute("PRAGMA user_version = " + SchemaMigrations.CURRENT_VERSION);
			}

			connection.commit();

		} catch (JdbcSqliteDb.MigrationFailedException e) {
			connection.rollback();
			throw new RejectedException(Rejection.DAMAGED, e.getMessage());
		}

		enableForeignKeys(connection);
	}

	/**
	 * Turns foreign keys on now that the upgrade is done, and asks SQLite whether the device's
	 * data actually satisfies them.
	 * <p>
	 * Not a reason to refuse the backup. A violation here is a finding about the device's data
	 * and the importer reports it per row, where it can say which row; failing the whole file
	 * would leave the operator with nothing to act on.
	 */
	private static void enableForeignKeys(@NonNull Connection connection) throws SQLException {

		connection.commit();

		try (Statement statement = connection.createStatement()) {
			statement.execute("PRAGMA foreign_keys = ON");
		}
	}

	// ── Helpers ──────────────────────────────────────────────────────────

	@NonNull
	private static String digestOf(@NonNull Path file) throws RejectedException {

		try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {

			MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
			byte[] chunk = new byte[8192];
			int read;

			while ((read = in.read(chunk)) != -1) {
				sha256.update(chunk, 0, read);
			}

			StringBuilder hex = new StringBuilder(64);

			for (byte b : sha256.digest()) {
				hex.append(String.format("%02x", b));
			}
			return hex.toString();

		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required of every JVM", e);
		} catch (IOException e) {
			throw new RejectedException(Rejection.IO, e.getMessage());
		}
	}

	private static void closeQuietly(@Nullable Connection connection) {

		if (connection == null) {
			return;
		}

		try {
			connection.close();
		} catch (SQLException ignored) {
			// Nothing useful to do: the import is over either way, and the staged file is
			// about to be deleted.
		}
	}

	private static void deleteQuietly(@Nullable Path file) {

		if (file == null) {
			return;
		}

		try {
			Files.deleteIfExists(file);
		} catch (IOException ignored) {
			// The staged copy is somebody's whole database, so a failure to remove it is
			// worth knowing about — but it is not worth masking the error that got us here.
		}
	}
}
