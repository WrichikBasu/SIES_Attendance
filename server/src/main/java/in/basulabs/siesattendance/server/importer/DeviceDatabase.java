package in.basulabs.siesattendance.server.importer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;

import in.basulabs.siesattendance.backend.migration.MigrationContext;
import in.basulabs.siesattendance.backend.migration.SchemaMigrations;
import in.basulabs.siesattendance.backend.util.BackupContainer;

/**
 * An empty phone database at the current schema, and the {@code .siesbak} around one.
 * <p>
 * The server writes backups now as well as reading them, and a backup is a Room database. Only
 * version 6 is written out by hand here; everything after it comes from running
 * {@link SchemaMigrations} over it, the same code a phone runs, so the current shape is never
 * hand-written and cannot drift from the migrations. The tests build their backups from the
 * same DDL.
 */
public final class DeviceDatabase {

	private DeviceDatabase() {
	}

	/**
	 * Creates {@code file} as an empty database at {@link SchemaMigrations#CURRENT_VERSION}
	 * and returns a connection to it, with foreign keys on.
	 *
	 * @param zone the zone the migrations derive local dates in
	 */
	@NonNull
	public static Connection createAtCurrentVersion(@NonNull Path file, @NonNull ZoneId zone)
			throws SQLException {

		Connection connection = DriverManager.getConnection(
				"jdbc:sqlite:" + file.toAbsolutePath());

		try (Statement statement = connection.createStatement()) {

			statement.execute("PRAGMA foreign_keys = OFF");

			for (String ddl : VERSION_6_DDL) {
				statement.execute(ddl);
			}
			statement.execute("PRAGMA user_version = 6");

			SchemaMigrations.migrate(new JdbcSqliteDb(connection), 6,
					new MigrationContext(zone, LocalDate.now(zone)));

			statement.execute("PRAGMA user_version = " + SchemaMigrations.CURRENT_VERSION);
			statement.execute("PRAGMA foreign_keys = ON");

		} catch (SQLException | RuntimeException e) {
			connection.close();
			throw e;
		}
		return connection;
	}

	/**
	 * Packs a closed SQLite file into a {@code .siesbak} at {@code destination}.
	 *
	 * @param password null or empty for an unencrypted file; not wiped here
	 */
	public static void pack(@NonNull Path sqlite, @NonNull Path destination, long createdAt,
	                        @Nullable char[] password)
			throws IOException, GeneralSecurityException {

		try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(destination));
		     InputStream database = Files.newInputStream(sqlite)) {

			DataOutputStream header = new DataOutputStream(raw);

			// App version 0: "could not be determined", which is the truth for a file the
			// server wrote.
			try (OutputStream body = BackupContainer.openForWriting(header,
					SchemaMigrations.CURRENT_VERSION, createdAt, 0, password)) {
				database.transferTo(body);
			}
		}
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
	static final String[] VERSION_6_DDL = {

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
