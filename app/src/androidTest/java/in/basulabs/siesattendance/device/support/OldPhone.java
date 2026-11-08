package in.basulabs.siesattendance.device.support;

import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import in.basulabs.siesattendance.backend.StoredTimes;
import in.basulabs.siesattendance.backend.migration.MigrationContext;
import in.basulabs.siesattendance.backend.migration.SchemaMigrations;

/**
 * A database file as an older release of the app would have left it.
 * <p>
 * Version 6 is written out by hand; anything later is reached by running the shared
 * {@link SchemaMigrations} over it on the framework's SQLite. This mirrors the server's
 * {@code DeviceDatabase} / {@code DeviceBackup}, and {@link #VERSION_6_DDL} is a copy of
 * {@code DeviceDatabase.VERSION_6_DDL}: the app cannot depend on {@code :server}. Keep the two
 * identical; {@code ServerBuiltFileDeviceTest} is what notices on a phone when the server's
 * copy stops producing a file Room accepts.
 * <p>
 * Foreign keys are off while building, as they are on a framework connection by default and
 * as they were for Room before it opens a file.
 */
public final class OldPhone implements AutoCloseable {

	public static final int BASE_VERSION = 6;

	@NonNull
	private final SQLiteDatabase db;

	private int version = BASE_VERSION;

	private OldPhone(@NonNull SQLiteDatabase db) {
		this.db = db;
	}

	/** A new file called {@code name}, empty, at version 6. */
	@NonNull
	public static OldPhone atVersion6(@NonNull String name) {

		SQLiteDatabase db = StagingFiles.openRaw(name);
		db.execSQL("PRAGMA foreign_keys = OFF");

		for (String ddl : VERSION_6_DDL) {
			db.execSQL(ddl);
		}
		db.setVersion(BASE_VERSION);
		return new OldPhone(db);
	}

	/** Runs the shared steps up to {@code target}, and records it as the file's version. */
	public void migrateTo(int target, @NonNull MigrationContext context) {

		FrameworkSqliteDb adapter = new FrameworkSqliteDb(db);

		db.beginTransaction();
		try {
			for (; version < target; version++) {
				SchemaMigrations.stepFrom(version).apply(adapter, context);
			}
			db.setVersion(target);
			db.setTransactionSuccessful();
		} finally {
			db.endTransaction();
		}
	}

	/** Stamps a version without migrating anything, for pretending to be a newer release. */
	public void claimVersion(int claimed) {
		db.setVersion(claimed);
		version = claimed;
	}

	@NonNull
	public SQLiteDatabase raw() {
		return db;
	}

	// ── Seeding, in the columns every version from 6 on has ─────────────

	public void professor(long id, String name, String email, String department,
	                      String designation) {
		db.execSQL("INSERT INTO professors (profID, name, email, department, designation) "
				+ "VALUES (?, ?, ?, ?, ?)", new Object[]{id, name, email, department, designation});
	}

	public void session(int year, LocalDate start, LocalDate end, ZoneId zone) {
		db.execSQL("INSERT INTO academic_years (academicYear, startDate, endDate, startEpoch, "
						+ "endEpoch) VALUES (?, ?, ?, ?, ?)",
				new Object[]{year, start.toEpochDay(), end.toEpochDay(),
						start.atStartOfDay(zone).toEpochSecond(),
						end.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1});
	}

	public void student(long id, String name, String pronouns, @Nullable String email) {
		db.execSQL("INSERT INTO students (studentID, name, pronouns, email) VALUES (?, ?, ?, ?)",
				new Object[]{id, name, pronouns, email});
	}

	public void studentYear(long studentId, int year, String rollNo, String studyYear,
	                        String stream, String majorDept, @Nullable Integer semester) {
		db.execSQL("INSERT INTO student_year (studentID, academicYear, rollNo, year, stream, "
						+ "majorDept, minorDept, oeDept, semester) "
						+ "VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, ?)",
				new Object[]{studentId, year, rollNo, studyYear, stream, majorDept, semester});
	}

	public void course(long id, long profId, String code, String topic, String stream,
	                   String department, String studyYear, int semester, String courseType) {
		db.execSQL("INSERT INTO courses (courseID, profID, courseCode, courseTopic, stream, "
						+ "offeringDepartment, year, semester, courseType, remarks, "
						+ "dateIntroduced, dateDiscontinued) "
						+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL)",
				new Object[]{id, profId, code, topic, stream, department, studyYear, semester,
						courseType});
	}

	public void batch(long id, String name, int year) {
		db.execSQL("INSERT INTO batch_desc (batchID, batchName, batchDesc, academicYear) "
				+ "VALUES (?, ?, NULL, ?)", new Object[]{id, name, year});
	}

	public void batchMember(long batchId, long studentId, int year) {
		db.execSQL("INSERT INTO batch_student_map (batchID, studentID, academicYear) "
				+ "VALUES (?, ?, ?)", new Object[]{batchId, studentId, year});
	}

	public void courseBatch(long courseId, long batchId) {
		db.execSQL("INSERT INTO course_batch_map (courseID, batchID) VALUES (?, ?)",
				new Object[]{courseId, batchId});
	}

	public void lecture(long id, long courseId, @Nullable Long batchId, ZonedDateTime start,
	                    ZonedDateTime end) {
		db.execSQL("INSERT INTO lectures (lectureID, courseID, batchID, startDateTime, "
						+ "endDateTime, notes) VALUES (?, ?, ?, ?, ?, NULL)",
				new Object[]{id, courseId, batchId, StoredTimes.format(start),
						StoredTimes.format(end)});
	}

	public void attendance(long lectureId, long studentId, boolean present) {
		db.execSQL("INSERT INTO attendance (lectureID, studentID, present) VALUES (?, ?, ?)",
				new Object[]{lectureId, studentId, present ? 1 : 0});
	}

	public void exec(@NonNull String sql) {
		db.execSQL(sql);
	}

	@Override
	public void close() {
		if (db.isOpen()) {
			db.close();
		}
	}

	/** A copy of the server's {@code DeviceDatabase.VERSION_6_DDL}. See the class comment. */
	public static final String[] VERSION_6_DDL = {

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
			// Room created these at version 1; no migration does. See the server's copy.
			"CREATE UNIQUE INDEX `index_student_year_rollNo_academicYear` "
					+ "ON `student_year` (`rollNo`, `academicYear`)",
			"CREATE INDEX `index_student_year_academicYear_year_stream_majorDept` "
					+ "ON `student_year` (`academicYear`, `year`, `stream`, `majorDept`)",
			"CREATE INDEX `index_student_year_studentID` ON `student_year` (`studentID`)",

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
