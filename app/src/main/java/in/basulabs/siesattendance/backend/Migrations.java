package in.basulabs.siesattendance.backend;

import android.database.Cursor;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Schema migrations. These replace the destructive fallback that was in place during early
 * development: from version 2 onward, upgrading keeps the data.
 */
public final class Migrations {

	private Migrations() {
	}

	/**
	 * Version 1 to 2: {@code lectures.batchID} became nullable, so that a lecture can be held
	 * for a course's whole class rather than always belonging to a batch.
	 * <p>
	 * SQLite cannot drop a NOT NULL constraint in place, so the table is rebuilt and copied.
	 * This is the standard recreate-and-copy dance, done inside the transaction Room wraps the
	 * migration in.
	 */
	public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
		@Override
		public void migrate(@NonNull SupportSQLiteDatabase db) {

			db.execSQL("CREATE TABLE IF NOT EXISTS `lectures_new` ("
					+ "`lectureID` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
					+ "`courseID` INTEGER NOT NULL, "
					+ "`batchID` INTEGER, "
					+ "`startDateTime` TEXT NOT NULL, "
					+ "`endDateTime` TEXT NOT NULL, "
					+ "`notes` TEXT, "
					+ "FOREIGN KEY(`batchID`) REFERENCES `batch_desc`(`batchID`) "
					+ "  ON UPDATE CASCADE ON DELETE RESTRICT, "
					+ "FOREIGN KEY(`courseID`) REFERENCES `courses`(`courseID`) "
					+ "  ON UPDATE CASCADE ON DELETE RESTRICT)");

			db.execSQL("INSERT INTO `lectures_new` "
					+ "(`lectureID`, `courseID`, `batchID`, `startDateTime`, `endDateTime`, "
					+ " `notes`) "
					+ "SELECT `lectureID`, `courseID`, `batchID`, `startDateTime`, "
					+ "`endDateTime`, `notes` FROM `lectures`");

			db.execSQL("DROP TABLE `lectures`");
			db.execSQL("ALTER TABLE `lectures_new` RENAME TO `lectures`");

			db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS "
					+ "`index_lectures_courseID_batchID_startDateTime_endDateTime` "
					+ "ON `lectures` (`courseID`, `batchID`, `startDateTime`, `endDateTime`)");
			db.execSQL("CREATE INDEX IF NOT EXISTS `index_lectures_batchID` "
					+ "ON `lectures` (`batchID`)");
			db.execSQL("CREATE INDEX IF NOT EXISTS `index_lectures_courseID` "
					+ "ON `lectures` (`courseID`)");
		}
	};

	/**
	 * Version 2 to 3: adds the {@code academic_years} table.
	 * <p>
	 * The table is seeded so that no existing data becomes unreachable. Statistics join lectures
	 * to sessions, so a lecture falling in no session would vanish from every report. The seed
	 * therefore covers three things:
	 * <ol>
	 *   <li>every {@code academicYear} value already referenced by students, batches and batch
	 *       membership;</li>
	 *   <li>every session needed to span the earliest and latest lecture actually recorded;</li>
	 *   <li>the session containing today, so the app has somewhere to file new attendance.</li>
	 * </ol>
	 * Each seeded session runs 1 June to 31 May, as agreed for the migration. That is a starting
	 * point only: the dates are editable afterwards, and nothing else in the code assumes June
	 * once this table exists.
	 */
	public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
		@Override
		public void migrate(@NonNull SupportSQLiteDatabase db) {

			db.execSQL("CREATE TABLE IF NOT EXISTS `academic_years` ("
					+ "`academicYear` INTEGER NOT NULL, "
					+ "`startDate` INTEGER NOT NULL, "
					+ "`endDate` INTEGER NOT NULL, "
					+ "`startEpoch` INTEGER NOT NULL, "
					+ "`endEpoch` INTEGER NOT NULL, "
					+ "PRIMARY KEY(`academicYear`))");

			Set<Integer> years = new TreeSet<>();

			collectYears(db, "SELECT DISTINCT academicYear FROM student_year", years);
			collectYears(db, "SELECT DISTINCT academicYear FROM batch_desc", years);
			collectYears(db, "SELECT DISTINCT academicYear FROM batch_student_map", years);

			years.addAll(yearsSpanningLectures(db));
			years.add(EntityAcademicYear.defaultYearFor(LocalDate.now()));

			// A gap in the middle would leave lectures unreachable, so the range is filled in
			// rather than only the years that happened to be referenced.
			if (!years.isEmpty()) {
				int first = ((TreeSet<Integer>) years).first();
				int last = ((TreeSet<Integer>) years).last();
				for (int year = first; year <= last; year++) {
					years.add(year);
				}
			}

			ZoneId zone = ZoneId.systemDefault();

			for (int year : years) {
				LocalDate start = LocalDate.of(year, 6, 1);
				LocalDate end = LocalDate.of(year + 1, 5, 31);

				long startEpoch = start.atStartOfDay(zone).toEpochSecond();
				long endEpoch = end.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1;

				db.execSQL("INSERT OR REPLACE INTO `academic_years` "
								+ "(`academicYear`, `startDate`, `endDate`, `startEpoch`, "
								+ " `endEpoch`) VALUES (?, ?, ?, ?, ?)",
						new Object[]{year, start.toEpochDay(), end.toEpochDay(),
								startEpoch, endEpoch});
			}
		}
	};

	/**
	 * Version 3 to 4: indexes {@code attendance.studentID}.
	 * <p>
	 * The table's composite primary key is {@code (lectureID, studentID)}, which indexes
	 * lectureID first and so cannot serve a lookup by student alone. Every per-student
	 * statistic does exactly that lookup. No data changes, only the index.
	 */
	public static final Migration MIGRATION_3_4 = new Migration(3, 4) {
		@Override
		public void migrate(@NonNull SupportSQLiteDatabase db) {
			db.execSQL("CREATE INDEX IF NOT EXISTS `index_attendance_studentID` "
					+ "ON `attendance` (`studentID`)");
		}
	};

	/**
	 * Version 4 to 5: indexes the three foreign key columns Room warned about.
	 * <p>
	 * A foreign key column with no index means every change to the parent table triggers a full
	 * scan of the child to enforce the constraint. Two of these are composite-key columns that
	 * do not lead their key, so the key's own index cannot serve them; the third had no index at
	 * all. No data changes.
	 */
	public static final Migration MIGRATION_4_5 = new Migration(4, 5) {
		@Override
		public void migrate(@NonNull SupportSQLiteDatabase db) {

			db.execSQL("CREATE INDEX IF NOT EXISTS `index_courses_profID` "
					+ "ON `courses` (`profID`)");

			db.execSQL("CREATE INDEX IF NOT EXISTS `index_batch_student_map_studentID` "
					+ "ON `batch_student_map` (`studentID`)");

			db.execSQL("CREATE INDEX IF NOT EXISTS `index_course_batch_map_batchID` "
					+ "ON `course_batch_map` (`batchID`)");
		}
	};

	/**
	 * Adds the semester a student is in.
	 * <p>
	 * Nullable, and left null for every existing row. A semester was never asked for before,
	 * so there is nothing to backfill it from: the year narrows it to a pair (SY means 3 or 4)
	 * but cannot pick between them, and guessing would put a value in front of the user that
	 * looks recorded when it was invented. Rows fill in as students are edited.
	 */
	public static final Migration MIGRATION_5_6 = new Migration(5, 6) {
		@Override
		public void migrate(@NonNull SupportSQLiteDatabase db) {
			db.execSQL("ALTER TABLE `student_year` ADD COLUMN `semester` INTEGER");
		}
	};

	/**
	 * Version 6 to 7: takes the five auto-generated primary keys away from SQLite.
	 * <p>
	 * Students, professors, courses, batches and lectures were declared
	 * {@code INTEGER PRIMARY KEY AUTOINCREMENT}, so their IDs were whatever the next number in
	 * the file happened to be. They are now issued by {@link IdGenerator} before the row is
	 * written, from a layout that keeps keys from different devices apart, and the column is a
	 * plain {@code INTEGER PRIMARY KEY}.
	 * <p>
	 * SQLite cannot drop AUTOINCREMENT in place, so each of the five tables is rebuilt and
	 * copied, exactly as {@link #MIGRATION_1_2} rebuilt the lectures table. Existing rows keep
	 * the IDs they already have: renumbering them would rewrite every foreign key in the
	 * database to no purpose, and the two kinds of ID coexist perfectly well. New rows simply
	 * get much larger numbers, which is also what stops them colliding with the old ones.
	 * <p>
	 * Dropping a parent table with children pointing at it is safe here because Room enables
	 * foreign keys in {@code onOpen}, which runs after every migration: nothing is enforcing
	 * the constraints while this executes. The child tables refer to their parents by name, and
	 * the name is restored by the rename before anything can look again.
	 */
	public static final Migration MIGRATION_6_7 = new Migration(6, 7) {
		@Override
		public void migrate(@NonNull SupportSQLiteDatabase db) {

			rebuild(db, "students",
					"CREATE TABLE IF NOT EXISTS `students_new` ("
							+ "`studentID` INTEGER NOT NULL, "
							+ "`name` TEXT NOT NULL, "
							+ "`pronouns` TEXT NOT NULL, "
							+ "`email` TEXT, "
							+ "PRIMARY KEY(`studentID`))",
					"`studentID`, `name`, `pronouns`, `email`",
					"CREATE UNIQUE INDEX IF NOT EXISTS `index_students_email` "
							+ "ON `students` (`email`)");

			rebuild(db, "professors",
					"CREATE TABLE IF NOT EXISTS `professors_new` ("
							+ "`profID` INTEGER NOT NULL, "
							+ "`name` TEXT NOT NULL, "
							+ "`email` TEXT NOT NULL, "
							+ "`department` TEXT NOT NULL, "
							+ "`designation` TEXT NOT NULL, "
							+ "PRIMARY KEY(`profID`))",
					"`profID`, `name`, `email`, `department`, `designation`",
					"CREATE INDEX IF NOT EXISTS `index_professors_name_department` "
							+ "ON `professors` (`name`, `department`)",
					"CREATE UNIQUE INDEX IF NOT EXISTS `index_professors_profID` "
							+ "ON `professors` (`profID`)",
					"CREATE UNIQUE INDEX IF NOT EXISTS `index_professors_email` "
							+ "ON `professors` (`email`)",
					"CREATE UNIQUE INDEX IF NOT EXISTS "
							+ "`index_professors_name_department_email` "
							+ "ON `professors` (`name`, `department`, `email`)",
					"CREATE INDEX IF NOT EXISTS `index_professors_department` "
							+ "ON `professors` (`department`)");

			rebuild(db, "courses",
					"CREATE TABLE IF NOT EXISTS `courses_new` ("
							+ "`courseID` INTEGER NOT NULL, "
							+ "`profID` INTEGER NOT NULL, "
							+ "`courseCode` TEXT NOT NULL, "
							+ "`courseTopic` TEXT NOT NULL, "
							+ "`stream` TEXT NOT NULL, "
							+ "`offeringDepartment` TEXT NOT NULL, "
							+ "`year` TEXT NOT NULL, "
							+ "`semester` INTEGER NOT NULL, "
							+ "`courseType` TEXT NOT NULL, "
							+ "`remarks` TEXT, "
							+ "`dateIntroduced` TEXT, "
							+ "`dateDiscontinued` TEXT, "
							+ "PRIMARY KEY(`courseID`), "
							+ "FOREIGN KEY(`profID`) REFERENCES `professors`(`profID`) "
							+ "  ON UPDATE CASCADE ON DELETE RESTRICT)",
					"`courseID`, `profID`, `courseCode`, `courseTopic`, `stream`, "
							+ "`offeringDepartment`, `year`, `semester`, `courseType`, "
							+ "`remarks`, `dateIntroduced`, `dateDiscontinued`",
					"CREATE UNIQUE INDEX IF NOT EXISTS `index_courses_courseCode` "
							+ "ON `courses` (`courseCode`)",
					"CREATE INDEX IF NOT EXISTS `index_courses_profID` "
							+ "ON `courses` (`profID`)");

			rebuild(db, "batch_desc",
					"CREATE TABLE IF NOT EXISTS `batch_desc_new` ("
							+ "`batchID` INTEGER NOT NULL, "
							+ "`batchName` TEXT NOT NULL, "
							+ "`batchDesc` TEXT, "
							+ "`academicYear` INTEGER NOT NULL, "
							+ "PRIMARY KEY(`batchID`))",
					"`batchID`, `batchName`, `batchDesc`, `academicYear`",
					"CREATE UNIQUE INDEX IF NOT EXISTS "
							+ "`index_batch_desc_batchID_academicYear` "
							+ "ON `batch_desc` (`batchID`, `academicYear`)");

			rebuild(db, "lectures",
					"CREATE TABLE IF NOT EXISTS `lectures_new` ("
							+ "`lectureID` INTEGER NOT NULL, "
							+ "`courseID` INTEGER NOT NULL, "
							+ "`batchID` INTEGER, "
							+ "`startDateTime` TEXT NOT NULL, "
							+ "`endDateTime` TEXT NOT NULL, "
							+ "`notes` TEXT, "
							+ "PRIMARY KEY(`lectureID`), "
							+ "FOREIGN KEY(`batchID`) REFERENCES `batch_desc`(`batchID`) "
							+ "  ON UPDATE CASCADE ON DELETE RESTRICT, "
							+ "FOREIGN KEY(`courseID`) REFERENCES `courses`(`courseID`) "
							+ "  ON UPDATE CASCADE ON DELETE RESTRICT)",
					"`lectureID`, `courseID`, `batchID`, `startDateTime`, `endDateTime`, "
							+ "`notes`",
					"CREATE UNIQUE INDEX IF NOT EXISTS "
							+ "`index_lectures_courseID_batchID_startDateTime_endDateTime` "
							+ "ON `lectures` (`courseID`, `batchID`, `startDateTime`, "
							+ "`endDateTime`)",
					"CREATE INDEX IF NOT EXISTS `index_lectures_batchID` "
							+ "ON `lectures` (`batchID`)",
					"CREATE INDEX IF NOT EXISTS `index_lectures_courseID` "
							+ "ON `lectures` (`courseID`)");
		}
	};

	/**
	 * Version 7 to 8: adds the division a student's class is split into.
	 * <p>
	 * Nullable, and left null for every existing row, which is the honest value in both of the
	 * cases that exist. Most streams are not divided at all, so there is nothing to record; the
	 * ones that are were never asked for a division before, and nothing in the database implies
	 * which one a student sat in. Rows fill in as students are edited or re-imported.
	 */
	public static final Migration MIGRATION_7_8 = new Migration(7, 8) {
		@Override
		public void migrate(@NonNull SupportSQLiteDatabase db) {
			// ALTER TABLE appends the column, so the field is declared last on the entity as
			// well and a migrated database has the same column order as a freshly created
			// one. Room compares columns by name and would accept either, but a difference
			// there is the sort of thing that only shows up in a positional insert written
			// years later.
			db.execSQL("ALTER TABLE `student_year` ADD COLUMN `division` TEXT");
		}
	};

	/**
	 * Version 8 to 9: adds the {@code course_instructor} table, so a course can be taught by
	 * more than one professor.
	 * <p>
	 * {@code courses.profID} stays where it is and keeps its foreign key, but it now means the
	 * course's coordinator rather than the sole instructor. Every existing course is seeded
	 * with one instructor row naming that same professor, which is what makes the new table
	 * answer the question on its own: after this runs, "who teaches this course" is a read of
	 * {@code course_instructor} and never a union of the two.
	 * <p>
	 * The seed cannot lose anything. {@code profID} is NOT NULL and enforced by a foreign key,
	 * so every course has exactly one, and the insert produces exactly one row per course.
	 */
	public static final Migration MIGRATION_8_9 = new Migration(8, 9) {
		@Override
		public void migrate(@NonNull SupportSQLiteDatabase db) {

			db.execSQL("CREATE TABLE IF NOT EXISTS `course_instructor` ("
					+ "`courseID` INTEGER NOT NULL, "
					+ "`profID` INTEGER NOT NULL, "
					+ "PRIMARY KEY(`courseID`, `profID`), "
					+ "FOREIGN KEY(`courseID`) REFERENCES `courses`(`courseID`) "
					+ "  ON UPDATE CASCADE ON DELETE RESTRICT, "
					+ "FOREIGN KEY(`profID`) REFERENCES `professors`(`profID`) "
					+ "  ON UPDATE CASCADE ON DELETE RESTRICT)");

			// courseID leads the primary key and is served by it; profID is not, and both the
			// foreign key check and the read-it-backwards lookup need the index.
			db.execSQL("CREATE INDEX IF NOT EXISTS `index_course_instructor_profID` "
					+ "ON `course_instructor` (`profID`)");

			db.execSQL("INSERT OR IGNORE INTO `course_instructor` (`courseID`, `profID`) "
					+ "SELECT `courseID`, `profID` FROM `courses`");
		}
	};

	/**
	 * Version 9 to 10: the master's courses in Physics, Botany, Zoology, Bioanalytical Sciences,
	 * Microbiology and FTN became unaided, so their rows move from {@code MSc} to
	 * {@code MSc_unaided}.
	 * <p>
	 * Nothing about the schema changes here; this is a data migration, and it is needed because
	 * {@code AcademicRules} no longer pairs those departments with the aided MSc. A row left
	 * behind would be a combination the app considers impossible, and could not be saved again
	 * from any screen.
	 * <p>
	 * Students and courses have to move together. The roll for a course is an equality join on
	 * year and stream ({@code DaoStudent.getRollForCourseSync}), so rewriting one side alone
	 * would empty out every roll in those departments. A student's class follows the department
	 * they major in, and a course's follows the department that owns it, which is what the two
	 * statements below say.
	 * <p>
	 * The one thing this cannot repair is a course owned by one of these departments but taken
	 * by students majoring elsewhere, an open elective being the way that happens: those
	 * students keep the aided MSc their own department still runs, and drop off the roll. That
	 * is the change itself rather than a fault in the migration, since the aided and unaided
	 * master's are now two different classes, and which one such a course belongs to is a
	 * question only a human can answer. FTN undergraduate rows are left untouched for the same
	 * reason: FTN no longer has a BSc at all, so there is no honest value to move them to.
	 */
	public static final Migration MIGRATION_9_10 = new Migration(9, 10) {
		@Override
		public void migrate(@NonNull SupportSQLiteDatabase db) {

			// Enum columns hold constant names, which is why these are string literals.
			final String departments = "('PHYSICS', 'BOTANY', 'ZOOLOGY', "
					+ "'BIOANALYTICAL_SCIENCES', 'MICROBIOLOGY', 'FTN')";

			db.execSQL("UPDATE `student_year` SET `stream` = 'MSc_unaided' "
					+ "WHERE `stream` = 'MSc' AND `majorDept` IN " + departments);

			db.execSQL("UPDATE `courses` SET `stream` = 'MSc_unaided' "
					+ "WHERE `stream` = 'MSc' AND `offeringDepartment` IN " + departments);
		}
	};

	/**
	 * The recreate-and-copy dance, for a table that has to change in a way SQLite cannot do in
	 * place. The new table is built beside the old one under a {@code _new} suffix, every row is
	 * copied across, the old table is dropped and the new one takes its name. Indices belong to
	 * the table they are on, so they go down with it and are recreated afterwards.
	 *
	 * @param columns the column list, identical on both sides, written out rather than left to
	 *                {@code SELECT *} so a mismatch in column order cannot silently shuffle the
	 *                data
	 */
	private static void rebuild(@NonNull SupportSQLiteDatabase db, @NonNull String table,
	                            @NonNull String createSql, @NonNull String columns,
	                            @NonNull String... indices) {

		db.execSQL(createSql);

		db.execSQL("INSERT INTO `" + table + "_new` (" + columns + ") "
				+ "SELECT " + columns + " FROM `" + table + "`");

		db.execSQL("DROP TABLE `" + table + "`");
		db.execSQL("ALTER TABLE `" + table + "_new` RENAME TO `" + table + "`");

		for (String index : indices) {
			db.execSQL(index);
		}
	}

	private static void collectYears(@NonNull SupportSQLiteDatabase db, @NonNull String sql,
	                                 @NonNull Set<Integer> into) {
		try (Cursor cursor = db.query(sql)) {
			while (cursor.moveToNext()) {
				if (!cursor.isNull(0)) {
					into.add(cursor.getInt(0));
				}
			}
		}
	}

	/**
	 * Works out which sessions are needed to cover the lectures already recorded, by reading the
	 * earliest and latest stored timestamp. Timestamps are stored as "epochSecond|nano|zone", so
	 * the leading integer is sliced out and cast.
	 */
	@NonNull
	private static Set<Integer> yearsSpanningLectures(@NonNull SupportSQLiteDatabase db) {

		Set<Integer> years = new LinkedHashSet<>();

		String sql = "SELECT "
				+ "MIN(CAST(SUBSTR(startDateTime, 1, INSTR(startDateTime, '|') - 1) AS INTEGER)),"
				+ "MAX(CAST(SUBSTR(startDateTime, 1, INSTR(startDateTime, '|') - 1) AS INTEGER)) "
				+ "FROM lectures";

		try (Cursor cursor = db.query(sql)) {
			if (cursor.moveToFirst() && !cursor.isNull(0)) {

				ZoneId zone = ZoneId.systemDefault();

				LocalDate earliest = Instant.ofEpochSecond(cursor.getLong(0))
						.atZone(zone).toLocalDate();
				LocalDate latest = Instant.ofEpochSecond(cursor.getLong(1))
						.atZone(zone).toLocalDate();

				years.add(EntityAcademicYear.defaultYearFor(earliest));
				years.add(EntityAcademicYear.defaultYearFor(latest));
			}
		}

		return years;
	}
}
