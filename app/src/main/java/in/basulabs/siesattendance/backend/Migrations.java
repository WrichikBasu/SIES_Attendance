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
