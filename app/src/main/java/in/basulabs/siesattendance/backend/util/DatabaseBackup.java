package in.basulabs.siesattendance.backend.util;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;

import in.basulabs.siesattendance.backend.ConstantsAndStatics;
import in.basulabs.siesattendance.backend.MainDatabase;

/**
 * Whole-database export and import, for backing the app up and moving it to another device.
 * <p>
 * The payload is the SQLite file itself rather than a dump of rows to CSV or JSON. A byte-level
 * copy carries everything, including the indices, the foreign keys, the Room schema hash and the
 * {@code user_version} that drives migrations, so a restore reproduces the database exactly
 * instead of replaying inserts that would renumber primary keys and break the attendance records
 * pointing at them.
 *
 * <h3>File layout</h3>
 * <pre>
 * magic          8 bytes   "SIESBAK1"
 * format         1 byte    container revision, currently 1
 * encrypted      1 byte    0 or 1
 * schema         4 bytes   Room user_version at export time
 * createdAt      8 bytes   epoch millis
 * appVersion     4 bytes   versionCode that wrote the file
 * iterations     4 bytes   ) present only when encrypted
 * salt          16 bytes   )
 * iv            12 bytes   )
 * payload        rest      gzip(sqlite), then AES-GCM if encrypted
 * </pre>
 * Compression runs before encryption: ciphertext is incompressible, so the other order would
 * save nothing. The header is deliberately readable without the password, which is what lets
 * the UI ask for one only when the file actually needs it, and lets a backup from a newer
 * app version be rejected with a clear message instead of a decryption failure.
 *
 * <h3>Safety of the restore</h3>
 * A restore replaces live data, so the candidate file is fully decrypted, decompressed and
 * validated in the cache directory before anything is touched. The current database is then
 * moved aside rather than deleted, and put back if the copy fails, so a failure at any point
 * leaves the app with a working database.
 * <p>
 * Every method here blocks and must be called off the main thread.
 */
public final class DatabaseBackup {

	private static final byte[] MAGIC = {'S', 'I', 'E', 'S', 'B', 'A', 'K', '1'};

	private static final byte FORMAT_VERSION = 1;

	/**
	 * A ceiling on the encrypted payload, which is buffered whole (see
	 * {@link #readPayload}). Far above any realistic roster and attendance history, and only
	 * here so an absurd or hostile file fails with a message instead of an OutOfMemoryError.
	 */
	private static final long MAX_ENCRYPTED_PAYLOAD = 256L * 1024 * 1024;

	/** Tables that must exist for the file to be one of ours rather than some other database. */
	private static final Set<String> EXPECTED_TABLES = new HashSet<>(Arrays.asList(
			"students", "student_year", "courses", "lectures", "attendance",
			"professors", "academic_years", "batch_desc"));

	private DatabaseBackup() {
	}

	// ── Result types ─────────────────────────────────────────────────────

	/**
	 * How a backup is brought in.
	 */
	public enum Mode {
		/**
		 * Throw away what is there and take the backup wholesale. The right choice for moving
		 * to a new device, or for undoing a mess.
		 */
		REPLACE,
		/**
		 * Keep everything, and let the backup win where the two describe the same thing.
		 * Nothing local is ever deleted. The right choice for folding together two devices, or
		 * for pulling in an old backup without losing what has happened since.
		 */
		MERGE
	}

	/** Why a restore was refused, so the UI can say something specific. */
	public enum Failure {
		/** The file is not a backup produced by this app. */
		NOT_A_BACKUP,
		/** Written by a newer app release whose format this build cannot read. */
		FORMAT_TOO_NEW,
		/** Encrypted, and either no password was given or the wrong one was. */
		BAD_PASSWORD,
		/** Truncated, corrupted, or not a usable database once unpacked. */
		DAMAGED,
		/** Made by a newer app version whose schema this build cannot open. */
		SCHEMA_TOO_NEW,
		/** The rows could not be folded into the existing database. */
		MERGE,
		/** Ran out of room, or the file could not be read or written. */
		IO
	}

	/**
	 * The outcome of an export or a restore. Carries a reason rather than throwing, since every
	 * failure here is something the user needs a sentence about rather than a stack trace.
	 */
	public static class Result {

		public final boolean success;

		/** The reason, when {@link #success} is false. Null on success. */
		@Nullable
		public final Failure failure;

		/**
		 * Extra context for the message, where there is any worth showing. Often null even on a
		 * failure, because most of the {@link Failure} values say all there is to say.
		 */
		@Nullable
		public final String detail;

		/** Present only after a successful merge; null for a replace. */
		@Nullable
		public final DatabaseMerger.MergeReport report;

		private Result(boolean success, @Nullable Failure failure, @Nullable String detail,
		               @Nullable DatabaseMerger.MergeReport report) {
			this.success = success;
			this.failure = failure;
			this.detail = detail;
			this.report = report;
		}

		/** Success with nothing further to report: an export, or a completed replace. */
		static Result ok() {
			return new Result(true, null, null, null);
		}

		/** Success with a merge report, which the UI shows as a breakdown of what came across. */
		static Result merged(@NonNull DatabaseMerger.MergeReport report) {
			return new Result(true, null, null, report);
		}

		static Result failed(@NonNull Failure failure, @Nullable String detail) {
			return new Result(false, failure, detail, null);
		}
	}

	/**
	 * What the header says about a file, read without needing the password. This is what lets
	 * the UI ask for a password only when the file actually has one, and reject an unreadable
	 * file with a clear message rather than a decryption error.
	 */
	public static class Info {

		public final boolean encrypted;

		/**
		 * The Room {@code user_version} the file was written at. A negative value is the signal
		 * that the container format itself is too new to read, in which case nothing else here
		 * is meaningful.
		 */
		public final int schemaVersion;

		/** When the backup was written, as epoch milliseconds. */
		public final long createdAt;

		/** The app version that wrote it. Zero when it could not be determined. */
		public final int appVersionCode;

		Info(boolean encrypted, int schemaVersion, long createdAt, int appVersionCode) {
			this.encrypted = encrypted;
			this.schemaVersion = schemaVersion;
			this.createdAt = createdAt;
			this.appVersionCode = appVersionCode;
		}
	}

	// ── Export ───────────────────────────────────────────────────────────

	/**
	 * Writes the whole database to {@code destination}.
	 *
	 * @param password {@code null} or empty for an unencrypted backup; otherwise the file can
	 *                 only be restored with this exact password. The array is wiped before
	 *                 returning.
	 */
	@NonNull
	public static Result export(@NonNull Context context, @NonNull Uri destination,
	                            @Nullable char[] password) {

		boolean encrypted = password != null && password.length > 0;

		try {
			// Room keeps recent writes in the -wal sidecar. Without this the copied file
			// would be missing whatever was saved since the last automatic checkpoint,
			// which is exactly the attendance the user just took.
			MainDatabase.checkpoint(context);

			File dbFile = MainDatabase.databaseFile(context);

			if (!dbFile.exists()) {
				return Result.failed(Failure.IO, "There is no database to export yet.");
			}

			int schemaVersion = MainDatabase.schemaVersion(context);

			try (OutputStream raw = context.getContentResolver()
					.openOutputStream(destination, "wt")) {

				if (raw == null) {
					return Result.failed(Failure.IO, "Could not open the chosen file.");
				}

				DataOutputStream header = new DataOutputStream(new BufferedOutputStream(raw));

				header.write(MAGIC);
				header.writeByte(FORMAT_VERSION);
				header.writeBoolean(encrypted);
				header.writeInt(schemaVersion);
				header.writeLong(System.currentTimeMillis());
				header.writeInt(appVersionCode(context));

				OutputStream payload = header;

				if (encrypted) {
					byte[] salt = BackupCrypto.randomBytes(BackupCrypto.SALT_BYTES);
					byte[] iv = BackupCrypto.randomBytes(BackupCrypto.IV_BYTES);
					int iterations = BackupCrypto.DEFAULT_ITERATIONS;

					header.writeInt(iterations);
					header.write(salt);
					header.write(iv);
					header.flush();

					SecretKey key = BackupCrypto.deriveKey(password, salt, iterations);
					Cipher cipher = BackupCrypto.encryptCipher(key, iv);
					payload = new CipherOutputStream(header, cipher);
				}

				// Closing the gzip stream flushes its trailer and then closes the cipher
				// stream underneath, which is what writes the GCM authentication tag. The
				// order matters: leaving either unclosed produces a file that will not
				// verify on restore.
				try (GZIPOutputStream gzip = new GZIPOutputStream(payload);
				     InputStream in = new FileInputStream(dbFile)) {
					copy(in, gzip);
				}
			}

			return Result.ok();

		} catch (GeneralSecurityException e) {
			return Result.failed(Failure.IO, "Could not set up encryption: " + e.getMessage());
		} catch (IOException e) {
			return Result.failed(Failure.IO, e.getMessage());
		} finally {
			BackupCrypto.wipe(password);
		}
	}

	// ── Reading the header ───────────────────────────────────────────────

	/**
	 * Reads the header so the UI knows whether to ask for a password. Returns {@code null} if
	 * the file is not a backup this build understands.
	 */
	@Nullable
	public static Info inspect(@NonNull Context context, @NonNull Uri source) {

		try (InputStream raw = context.getContentResolver().openInputStream(source)) {

			if (raw == null) {
				return null;
			}
			return readHeader(new DataInputStream(new BufferedInputStream(raw)));

		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * Reads and checks the fixed header, leaving the stream positioned at the encryption
	 * parameters if there are any, and at the payload otherwise.
	 *
	 * @return null when the file is not one of ours at all, or an {@link Info} with a negative
	 *         schema version when the container format is newer than this build reads
	 */
	@Nullable
	private static Info readHeader(@NonNull DataInputStream in) throws IOException {

		byte[] magic = new byte[MAGIC.length];

		try {
			in.readFully(magic);
		} catch (IOException e) {
			return null;
		}

		if (!Arrays.equals(magic, MAGIC)) {
			return null;
		}

		int format = in.readUnsignedByte();

		if (format > FORMAT_VERSION) {
			// Signalled by the caller comparing against FORMAT_VERSION; a negative schema
			// marks the file as unreadable by this build.
			return new Info(false, -1, 0L, 0);
		}

		boolean encrypted = in.readBoolean();
		int schemaVersion = in.readInt();
		long createdAt = in.readLong();
		int appVersionCode = in.readInt();

		return new Info(encrypted, schemaVersion, createdAt, appVersionCode);
	}

	// ── Import ───────────────────────────────────────────────────────────

	/**
	 * Replaces the live database with the contents of {@code source}.
	 * <p>
	 * After a replace the Room instance has been closed and the file swapped, so the caller
	 * must restart the app rather than keep using the objects it already holds: every DAO,
	 * LiveData and cached row in memory belongs to the database that no longer exists. A merge
	 * leaves the instance open but changes rows underneath every cached list, so it wants a
	 * restart for the same practical reason.
	 *
	 * @param password required if the file is encrypted; wiped before returning.
	 * @param mode     whether to replace the database or merge into it
	 */
	@NonNull
	public static Result restore(@NonNull Context context, @NonNull Uri source,
	                             @Nullable char[] password, @NonNull Mode mode) {

		File staged = new File(context.getCacheDir(),
				"restore-" + System.currentTimeMillis() + ".sqlite3");

		try (InputStream raw = context.getContentResolver().openInputStream(source)) {

			if (raw == null) {
				return Result.failed(Failure.IO, "Could not open the chosen file.");
			}

			DataInputStream in = new DataInputStream(new BufferedInputStream(raw));
			Info info = readHeader(in);

			if (info == null) {
				return Result.failed(Failure.NOT_A_BACKUP, null);
			}

			if (info.schemaVersion < 0) {
				return Result.failed(Failure.FORMAT_TOO_NEW, null);
			}

			if (info.schemaVersion > ConstantsAndStatics.DATABASE_VERSION) {
				return Result.failed(Failure.SCHEMA_TOO_NEW,
						"The backup is from a newer version of the app (database version "
								+ info.schemaVersion + "; this build reads up to "
								+ ConstantsAndStatics.DATABASE_VERSION + ").");
			}

			if (info.encrypted && (password == null || password.length == 0)) {
				return Result.failed(Failure.BAD_PASSWORD, null);
			}

			Result unpacked = unpack(in, info, password, staged);

			if (!unpacked.success) {
				return unpacked;
			}

			Result validated = validate(staged);

			if (!validated.success) {
				return validated;
			}

			if (mode == Mode.MERGE) {
				try {
					return Result.merged(DatabaseMerger.merge(context, staged));
				} catch (IOException e) {
					// The merge runs in a single transaction, so a failure here has rolled
					// back and the live database is exactly as it was.
					return Result.failed(Failure.MERGE, e.getMessage());
				}
			}

			return swapIn(context, staged);

		} catch (IOException e) {
			return Result.failed(Failure.IO, e.getMessage());
		} finally {
			BackupCrypto.wipe(password);

			// The staged copy is a full clone of the user's data; it should not sit in the
			// cache after either outcome.
			if (staged.exists() && !staged.delete()) {
				staged.deleteOnExit();
			}
		}
	}

	/**
	 * Decrypts if needed, decompresses, and writes the result to {@code staged}.
	 */
	@NonNull
	private static Result unpack(@NonNull DataInputStream in, @NonNull Info info,
	                             @Nullable char[] password, @NonNull File staged) {

		try {
			InputStream compressed;

			if (info.encrypted) {
				int iterations = in.readInt();
				byte[] salt = new byte[BackupCrypto.SALT_BYTES];
				byte[] iv = new byte[BackupCrypto.IV_BYTES];
				in.readFully(salt);
				in.readFully(iv);

				if (iterations <= 0) {
					return Result.failed(Failure.DAMAGED, null);
				}

				byte[] ciphertext = readPayload(in);

				if (ciphertext == null) {
					return Result.failed(Failure.DAMAGED, "The file is unexpectedly large.");
				}

				SecretKey key = BackupCrypto.deriveKey(password, salt, iterations);
				Cipher cipher = BackupCrypto.decryptCipher(key, iv);

				// doFinal on the whole payload rather than a CipherInputStream. GCM only
				// knows the data was untampered once it reaches the tag at the very end, and
				// a streaming reader tends to report that as a plain end-of-stream. That
				// would look like a short but valid file and get written over the live
				// database. Buffering costs memory but turns a wrong password into a thrown
				// exception, which is the whole point of using an authenticated mode.
				byte[] plaintext = cipher.doFinal(ciphertext);
				Arrays.fill(ciphertext, (byte) 0);
				compressed = new ByteArrayInputStream(plaintext);
			} else {
				compressed = in;
			}

			try (GZIPInputStream gzip = new GZIPInputStream(compressed);
			     OutputStream out = new BufferedOutputStream(new FileOutputStream(staged))) {
				copy(gzip, out);
			}

			return Result.ok();

		} catch (AEADBadTagException e) {
			return Result.failed(Failure.BAD_PASSWORD, null);
		} catch (GeneralSecurityException e) {
			// Any other cipher failure on a file that passed the header check is far more
			// likely a wrong password than a broken device, so it is reported as such.
			return Result.failed(Failure.BAD_PASSWORD, null);
		} catch (IOException e) {
			return Result.failed(Failure.DAMAGED, e.getMessage());
		}
	}

	/**
	 * Reads the rest of the stream, or {@code null} if it exceeds
	 * {@link #MAX_ENCRYPTED_PAYLOAD}.
	 */
	@Nullable
	private static byte[] readPayload(@NonNull InputStream in) throws IOException {

		ByteArrayOutputStream buffer = new ByteArrayOutputStream(1 << 16);
		byte[] chunk = new byte[8192];
		long total = 0;
		int read;

		while ((read = in.read(chunk)) != -1) {
			total += read;

			if (total > MAX_ENCRYPTED_PAYLOAD) {
				return null;
			}
			buffer.write(chunk, 0, read);
		}
		return buffer.toByteArray();
	}

	/**
	 * Confirms the unpacked file really is one of our databases and is internally consistent,
	 * before it is allowed anywhere near the live data.
	 */
	@NonNull
	private static Result validate(@NonNull File candidate) {

		SQLiteDatabase probe = null;

		try {
			probe = SQLiteDatabase.openDatabase(candidate.getPath(), null,
					SQLiteDatabase.OPEN_READONLY);

			try (Cursor cursor = probe.rawQuery("PRAGMA integrity_check", null)) {
				if (!cursor.moveToFirst() || !"ok".equalsIgnoreCase(cursor.getString(0))) {
					return Result.failed(Failure.DAMAGED,
							"The database inside the backup is corrupted.");
				}
			}

			Set<String> tables = new HashSet<>();

			try (Cursor cursor = probe.rawQuery(
					"SELECT name FROM sqlite_master WHERE type = 'table'", null)) {
				while (cursor.moveToNext()) {
					tables.add(cursor.getString(0));
				}
			}

			if (!tables.containsAll(EXPECTED_TABLES)) {
				return Result.failed(Failure.NOT_A_BACKUP,
						"That file is a database, but not one from this app.");
			}

			// A second line of defence behind the header check: the header could have been
			// edited, the user_version inside the file could not without breaking the
			// integrity check.
			if (probe.getVersion() > ConstantsAndStatics.DATABASE_VERSION) {
				return Result.failed(Failure.SCHEMA_TOO_NEW, null);
			}

			return Result.ok();

		} catch (Exception e) {
			return Result.failed(Failure.DAMAGED, "The backup did not unpack into a database.");
		} finally {
			if (probe != null) {
				probe.close();
			}
		}
	}

	/**
	 * Puts the validated file in place of the live database.
	 * <p>
	 * The old file is moved aside instead of deleted, so a failure part-way through the copy
	 * can be undone. The {@code -wal} and {@code -shm} sidecars belong to the outgoing
	 * database and must go: leaving them would let SQLite replay a journal that describes
	 * pages of a file that is no longer there.
	 */
	@NonNull
	private static Result swapIn(@NonNull Context context, @NonNull File staged) {

		MainDatabase.closeInstance();

		File live = MainDatabase.databaseFile(context);
		File wal = sidecar(live, "-wal");
		File shm = sidecar(live, "-shm");
		File rescue = sidecar(live, ".replaced");

		if (rescue.exists() && !rescue.delete()) {
			return Result.failed(Failure.IO, "Could not clear a previous restore.");
		}

		boolean movedAside = live.exists() && live.renameTo(rescue);

		if (live.exists() && !movedAside) {
			return Result.failed(Failure.IO, "Could not set the current database aside.");
		}

		try (InputStream in = new FileInputStream(staged);
		     OutputStream out = new BufferedOutputStream(new FileOutputStream(live))) {

			copy(in, out);

		} catch (IOException e) {

			// Nothing has been lost: the outgoing database is still intact under its
			// sidecar name, so put it back and report the failure.
			if (live.exists()) {
				//noinspection ResultOfMethodCallIgnored
				live.delete();
			}
			if (movedAside) {
				//noinspection ResultOfMethodCallIgnored
				rescue.renameTo(live);
			}
			return Result.failed(Failure.IO, e.getMessage());
		}

		//noinspection ResultOfMethodCallIgnored
		wal.delete();
		//noinspection ResultOfMethodCallIgnored
		shm.delete();
		//noinspection ResultOfMethodCallIgnored
		rescue.delete();

		return Result.ok();
	}

	// ── Helpers ──────────────────────────────────────────────────────────

	/** A file beside the database with a suffix appended, for the WAL, SHM and rescue copies. */
	@NonNull
	private static File sidecar(@NonNull File dbFile, @NonNull String suffix) {
		return new File(dbFile.getParentFile(), dbFile.getName() + suffix);
	}

	/** Streams one file into another. Neither stream is closed; the caller owns both. */
	private static void copy(@NonNull InputStream in, @NonNull OutputStream out)
			throws IOException {

		byte[] buffer = new byte[8192];
		int read;

		while ((read = in.read(buffer)) != -1) {
			out.write(buffer, 0, read);
		}
		out.flush();
	}

	/**
	 * The running app's version code, recorded in the header. Returns 0 if it cannot be read,
	 * which is not worth failing an export over: the field is informational.
	 */
	private static int appVersionCode(@NonNull Context context) {
		try {
			return (int) context.getPackageManager()
					.getPackageInfo(context.getPackageName(), 0).getLongVersionCode();
		} catch (PackageManager.NameNotFoundException e) {
			return 0;
		}
	}

	/**
	 * A default file name carrying the date, so a folder of backups sorts sensibly and the
	 * user can tell at a glance which one is which.
	 */
	@NonNull
	public static String suggestedFileName(boolean encrypted) {

		java.time.LocalDateTime now = java.time.LocalDateTime.now();

		return String.format(java.util.Locale.US, "sies-attendance-%04d%02d%02d-%02d%02d%s.siesbak",
				now.getYear(), now.getMonthValue(), now.getDayOfMonth(),
				now.getHour(), now.getMinute(), encrypted ? "-enc" : "");
	}
}
