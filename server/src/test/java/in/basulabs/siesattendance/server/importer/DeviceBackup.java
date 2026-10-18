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
 * The only schema written out by hand here is version 6. Everything newer is produced by running
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

			for (String ddl : VERSION_6_DDL) {
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

	/**
	 * The version 6 schema, written out.
	 * <p>
	 * Taken from the version 10 DDL that Room generates, with the four things that happened
	 * after version 6 undone: the five main tables are still
	 * {@code INTEGER PRIMARY KEY AUTOINCREMENT}, {@code student_year} has no {@code division},
	 * and {@code course_instructor} does not exist. The indices are the ones migrations 1 to 5
	 * had created by then.
	 */
	private static final String[] VERSION_6_DDL = {

			"CREATE TABLE `students` (`studentID` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
					+ "`name` TEXT NOT NULL, `pronouns` TEXT NOT NULL, `email` TEXT)",
			"CREATE UNIQUE INDEX `index_students_email` ON `students` (`email`)",

			"CREATE TABLE `professors` (`profID` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
					+ "`name` TEXT NOT NULL, `email` TEXT NOT NULL, `department` TEXT NOT NULL, "
					+ "`designation` TEXT NOT NULL)",
			"CREATE UNIQUE INDEX `index_professors_email` ON `professors` (`email`)",

			"CREATE TABLE `academic_years` (`academicYear` INTEGER NOT NULL, "
					+ "`startDate` INTEGER NOT NULL, `endDate` INTEGER NOT NULL, "
					+ "`startEpoch` INTEGER NOT NULL, `endEpoch` INTEGER NOT NULL, "
					+ "PRIMARY KEY(`academicYear`))",

			"CREATE TABLE `student_year` (`studentID` INTEGER NOT NULL, "
					+ "`academicYear` INTEGER NOT NULL, `rollNo` TEXT NOT NULL, "
					+ "`year` TEXT NOT NULL, `stream` TEXT NOT NULL, "
					+ "`majorDept` TEXT NOT NULL, `minorDept` TEXT, `oeDept` TEXT, "
					+ "`semester` INTEGER, PRIMARY KEY(`studentID`, `academicYear`), "
					+ "FOREIGN KEY(`studentID`) REFERENCES `students`(`studentID`) "
					+ "ON UPDATE CASCADE ON DELETE RESTRICT)",

			"CREATE TABLE `courses` (`courseID` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
					+ "`profID` INTEGER NOT NULL, `courseCode` TEXT NOT NULL, "
					+ "`courseTopic` TEXT NOT NULL, `stream` TEXT NOT NULL, "
					+ "`offeringDepartment` TEXT NOT NULL, `year` TEXT NOT NULL, "
					+ "`semester` INTEGER NOT NULL, `courseType` TEXT NOT NULL, "
					+ "`remarks` TEXT, `dateIntroduced` TEXT, `dateDiscontinued` TEXT, "
					+ "FOREIGN KEY(`profID`) REFERENCES `professors`(`profID`) "
					+ "ON UPDATE CASCADE ON DELETE RESTRICT)",
			"CREATE UNIQUE INDEX `index_courses_courseCode` ON `courses` (`courseCode`)",
			"CREATE INDEX `index_courses_profID` ON `courses` (`profID`)",

			"CREATE TABLE `batch_desc` (`batchID` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
					+ "`batchName` TEXT NOT NULL, `batchDesc` TEXT, "
					+ "`academicYear` INTEGER NOT NULL)",

			"CREATE TABLE `batch_student_map` (`batchID` INTEGER NOT NULL, "
					+ "`studentID` INTEGER NOT NULL, `academicYear` INTEGER NOT NULL, "
					+ "PRIMARY KEY(`batchID`, `studentID`, `academicYear`), "
					+ "FOREIGN KEY(`batchID`) REFERENCES `batch_desc`(`batchID`) "
					+ "ON UPDATE CASCADE ON DELETE RESTRICT, "
					+ "FOREIGN KEY(`studentID`) REFERENCES `students`(`studentID`) "
					+ "ON UPDATE CASCADE ON DELETE RESTRICT)",
			"CREATE INDEX `index_batch_student_map_studentID` "
					+ "ON `batch_student_map` (`studentID`)",

			"CREATE TABLE `course_batch_map` (`courseID` INTEGER NOT NULL, "
					+ "`batchID` INTEGER NOT NULL, PRIMARY KEY(`courseID`, `batchID`), "
					+ "FOREIGN KEY(`courseID`) REFERENCES `courses`(`courseID`) "
					+ "ON UPDATE CASCADE ON DELETE RESTRICT, "
					+ "FOREIGN KEY(`batchID`) REFERENCES `batch_desc`(`batchID`) "
					+ "ON UPDATE CASCADE ON DELETE RESTRICT)",
			"CREATE INDEX `index_course_batch_map_batchID` ON `course_batch_map` (`batchID`)",

			"CREATE TABLE `lectures` (`lectureID` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
					+ "`courseID` INTEGER NOT NULL, `batchID` INTEGER, "
					+ "`startDateTime` TEXT NOT NULL, `endDateTime` TEXT NOT NULL, "
					+ "`notes` TEXT, "
					+ "FOREIGN KEY(`batchID`) REFERENCES `batch_desc`(`batchID`) "
					+ "ON UPDATE CASCADE ON DELETE RESTRICT, "
					+ "FOREIGN KEY(`courseID`) REFERENCES `courses`(`courseID`) "
					+ "ON UPDATE CASCADE ON DELETE RESTRICT)",
			"CREATE UNIQUE INDEX "
					+ "`index_lectures_courseID_batchID_startDateTime_endDateTime` "
					+ "ON `lectures` (`courseID`, `batchID`, `startDateTime`, `endDateTime`)",
			"CREATE INDEX `index_lectures_batchID` ON `lectures` (`batchID`)",
			"CREATE INDEX `index_lectures_courseID` ON `lectures` (`courseID`)",

			"CREATE TABLE `attendance` (`lectureID` INTEGER NOT NULL, "
					+ "`studentID` INTEGER NOT NULL, `present` INTEGER NOT NULL, "
					+ "PRIMARY KEY(`lectureID`, `studentID`), "
					+ "FOREIGN KEY(`lectureID`) REFERENCES `lectures`(`lectureID`) "
					+ "ON UPDATE NO ACTION ON DELETE RESTRICT, "
					+ "FOREIGN KEY(`studentID`) REFERENCES `students`(`studentID`) "
					+ "ON UPDATE NO ACTION ON DELETE RESTRICT)",
			"CREATE INDEX `index_attendance_studentID` ON `attendance` (`studentID`)"
	};
}
