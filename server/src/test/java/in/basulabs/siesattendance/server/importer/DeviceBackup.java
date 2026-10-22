package in.basulabs.siesattendance.server.importer;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import in.basulabs.siesattendance.backend.StoredTimes;
import in.basulabs.siesattendance.backend.migration.MigrationContext;
import in.basulabs.siesattendance.backend.migration.SchemaMigrations;
import in.basulabs.siesattendance.backend.util.BackupContainer;

/**
 * Builds a real {@code .siesbak} from a real SQLite database, the way a phone would.
 * <p>
 * The importer's tests need backups, and a backup is the one thing in this programme that
 * cannot be faked: it is an encrypted container around a Room database at some schema version,
 * and the whole first half of an import is about discovering what it actually is. So these are
 * built rather than stubbed.
 *
 * <h3>Why it starts at version 6</h3>
 * The only schema written out by hand is version 6, in {@link DeviceDatabase}, which the
 * server's own backup export starts from too. Everything newer is produced by running
 * {@link SchemaMigrations} over it — the same code the phone runs and the importer runs — so the
 * current shape is never hand-written and cannot drift from the migrations.
 * <p>
 * Version 6 is also the interesting starting point. It is the last version whose five main
 * tables were {@code INTEGER PRIMARY KEY AUTOINCREMENT}, which means a database built here can
 * carry the legacy keys that are the entire reason {@code id_remap} exists: small integers
 * counted from one, independently on every device, which will collide.
 */
final class DeviceBackup implements AutoCloseable {

	/** The version whose DDL is written out below. */
	static final int BASE_VERSION = 6;

	private final Path sqlite;

	private final Connection connection;

	private int schemaVersion = BASE_VERSION;

	private DeviceBackup(Path sqlite, Connection connection) {
		this.sqlite = sqlite;
		this.connection = connection;
	}

	/** An empty device database at version 6. */
	static DeviceBackup emptyAtVersion6(Path directory) throws Exception {

		Files.createDirectories(directory);
		Path file = Files.createTempFile(directory, "device-", ".sqlite3");
		Files.deleteIfExists(file);

		Connection connection = DriverManager.getConnection(
				"jdbc:sqlite:" + file.toAbsolutePath());
		connection.setAutoCommit(true);

		try (Statement statement = connection.createStatement()) {

			statement.execute("PRAGMA foreign_keys = OFF");

			for (String ddl : DeviceDatabase.VERSION_6_DDL) {
				statement.execute(ddl);
			}
			statement.execute("PRAGMA user_version = " + BASE_VERSION);
		}

		return new DeviceBackup(file, connection);
	}

	// ── Seeding ──────────────────────────────────────────────────────────

	void professor(long id, String name, String email, String department, String designation)
			throws SQLException {
		write("INSERT INTO professors (profID, name, email, department, designation) "
				+ "VALUES (?, ?, ?, ?, ?)", id, name, email, department, designation);
	}

	void session(int year, LocalDate start, LocalDate end, ZoneId zone) throws SQLException {
		write("INSERT INTO academic_years (academicYear, startDate, endDate, startEpoch, "
						+ "endEpoch) VALUES (?, ?, ?, ?, ?)",
				year, start.toEpochDay(), end.toEpochDay(),
				start.atStartOfDay(zone).toEpochSecond(),
				end.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1);
	}

	void student(long id, String name, String pronouns, String email) throws SQLException {
		write("INSERT INTO students (studentID, name, pronouns, email) VALUES (?, ?, ?, ?)",
				id, name, pronouns, email);
	}

	void studentYear(long studentId, int year, String rollNo, String studyYear, String stream,
	                 String majorDept, String minorDept, String oeDept, Integer semester)
			throws SQLException {
		write("INSERT INTO student_year (studentID, academicYear, rollNo, year, stream, "
						+ "majorDept, minorDept, oeDept, semester) "
						+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
				studentId, year, rollNo, studyYear, stream, majorDept, minorDept, oeDept,
				semester);
	}

	void course(long id, long profId, String code, String topic, String stream,
	            String offeringDepartment, String studyYear, int semester, String courseType)
			throws SQLException {
		write("INSERT INTO courses (courseID, profID, courseCode, courseTopic, stream, "
						+ "offeringDepartment, year, semester, courseType, remarks, "
						+ "dateIntroduced, dateDiscontinued) "
						+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL)",
				id, profId, code, topic, stream, offeringDepartment, studyYear, semester,
				courseType);
	}

	void batch(long id, String name, String description, int year) throws SQLException {
		write("INSERT INTO batch_desc (batchID, batchName, batchDesc, academicYear) "
				+ "VALUES (?, ?, ?, ?)", id, name, description, year);
	}

	void batchMember(long batchId, long studentId, int year) throws SQLException {
		write("INSERT INTO batch_student_map (batchID, studentID, academicYear) "
				+ "VALUES (?, ?, ?)", batchId, studentId, year);
	}

	void courseBatch(long courseId, long batchId) throws SQLException {
		write("INSERT INTO course_batch_map (courseID, batchID) VALUES (?, ?)",
				courseId, batchId);
	}

	void lecture(long id, long courseId, Long batchId, ZonedDateTime start, ZonedDateTime end,
	             String notes) throws SQLException {
		write("INSERT INTO lectures (lectureID, courseID, batchID, startDateTime, "
						+ "endDateTime, notes) VALUES (?, ?, ?, ?, ?, ?)",
				id, courseId, batchId, StoredTimes.format(start), StoredTimes.format(end),
				notes);
	}

	void attendance(long lectureId, long studentId, boolean present) throws SQLException {
		write("INSERT INTO attendance (lectureID, studentID, present) VALUES (?, ?, ?)",
				lectureId, studentId, present ? 1 : 0);
	}

	/** An arbitrary statement, for a test that needs to change a row after seeding it. */
	void write(String sql, Object... args) throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(sql)) {

			for (int i = 0; i < args.length; i++) {
				statement.setObject(i + 1, args[i]);
			}
			statement.executeUpdate();
		}
	}

	/**
	 * The open connection, for a test that has to look at the file itself rather than seed it.
	 * <p>
	 * One caller: {@code OutboxTriggerTest}, which installs the app's outbox triggers on a
	 * migrated device database and watches them fire.
	 */
	Connection connection() {
		return connection;
	}

	// ── Finishing ────────────────────────────────────────────────────────

	/**
	 * Runs the shared migrations so the database ends up at the current schema, as a backup
	 * from a phone running the current release would be.
	 */
	void migrateToCurrent(ZoneId zone) throws SQLException {

		SchemaMigrations.migrate(new JdbcSqliteDb(connection), schemaVersion,
				new MigrationContext(zone, LocalDate.of(2026, 3, 1)));

		try (Statement statement = connection.createStatement()) {
			statement.execute("PRAGMA user_version = " + SchemaMigrations.CURRENT_VERSION);
		}
		schemaVersion = SchemaMigrations.CURRENT_VERSION;
	}

	/**
	 * Packs the database into a {@code .siesbak} at {@code destination} and returns it.
	 *
	 * @param password null for an unencrypted backup
	 */
	Path pack(Path destination, char[] password) throws Exception {

		connection.close();

		try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(destination));
		     InputStream database = Files.newInputStream(sqlite)) {

			DataOutputStream header = new DataOutputStream(raw);

			try (OutputStream body = BackupContainer.openForWriting(header, schemaVersion,
					1_770_000_000_000L, 77, password)) {

				byte[] buffer = new byte[8192];
				int read;

				while ((read = database.read(buffer)) != -1) {
					body.write(buffer, 0, read);
				}
			}
		}
		return destination;
	}

	@Override
	public void close() throws SQLException, IOException {

		if (!connection.isClosed()) {
			connection.close();
		}
		Files.deleteIfExists(sqlite);
	}
}
