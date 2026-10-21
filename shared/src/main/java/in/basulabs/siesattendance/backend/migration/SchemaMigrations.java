package in.basulabs.siesattendance.backend.migration;

import androidx.annotation.NonNull;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import in.basulabs.siesattendance.backend.SessionYears;

/**
 * Schema migrations. These replace the destructive fallback that was in place during early
 * development: from version 2 onward, upgrading keeps the data.
 * <p>
 * The bodies live here, in the shared module, rather than in the app, because two different
 * programs have to run them. The app runs them through Room when a device upgrades. The server
 * runs them on a backup it is about to import, since a backup arrives at whatever version the
 * phone that wrote it was on, and the importer is written against the current schema and
 * nothing else. Having one copy is the whole point: a migration that behaved differently on the
 * two sides would mean the server's idea of an old database and the phone's had diverged, and
 * the symptom would be wrong attendance rather than an error.
 * <p>
 * Nothing here knows about Room or about JDBC. {@link SqliteDb} is the three methods they need
 * between them, and each side adapts its own connection to it.
 *
 * @see MigrationContext for the ambient state that used to be read off the running machine
 */
public final class SchemaMigrations {

	/**
	 * The oldest version that can be brought forward. Version 1 is the first schema that was
	 * ever released; anything claiming to be older than it is not one of ours.
	 */
	public static final int FIRST_SUPPORTED_VERSION = 1;

	/**
	 * The version the chain arrives at, and so the schema every reader here is written
	 * against. The app asserts this equals its own {@code DATABASE_VERSION}: if a migration is
	 * added and this is not raised, that test is what says so.
	 */
	public static final int CURRENT_VERSION = 12;

	private SchemaMigrations() {
	}

	/** One version-to-version step. */
	public interface Step {

		/** The version this step upgrades from. */
		int from();

		/** The version it arrives at, always {@link #from()} plus one. */
		int to();

		/** Runs it. The caller provides the transaction; this does not open one. */
		void apply(@NonNull SqliteDb db, @NonNull MigrationContext context);
	}

	/** The body of a step, so each one below can be written as a lambda. */
	private interface Body {
		void apply(@NonNull SqliteDb db, @NonNull MigrationContext context);
	}

	@NonNull
	private static Step step(int from, int to, @NonNull Body body) {

		return new Step() {

			@Override
			public int from() {
				return from;
			}

			@Override
			public int to() {
				return to;
			}

			@Override
			public void apply(@NonNull SqliteDb db, @NonNull MigrationContext context) {
				body.apply(db, context);
			}
		};
	}

	/**
	 * Every step, in order, from {@link #FIRST_SUPPORTED_VERSION} to {@link #CURRENT_VERSION}.
	 * <p>
	 * A fresh list each call, which costs nothing at the rate these are used and means no
	 * caller can hand another one a mutated copy.
	 */
	@NonNull
	public static List<Step> steps() {

		List<Step> steps = new ArrayList<>();

		steps.add(step(1, 2, SchemaMigrations::v1ToV2));
		steps.add(step(2, 3, SchemaMigrations::v2ToV3));
		steps.add(step(3, 4, SchemaMigrations::v3ToV4));
		steps.add(step(4, 5, SchemaMigrations::v4ToV5));
		steps.add(step(5, 6, SchemaMigrations::v5ToV6));
		steps.add(step(6, 7, SchemaMigrations::v6ToV7));
		steps.add(step(7, 8, SchemaMigrations::v7ToV8));
		steps.add(step(8, 9, SchemaMigrations::v8ToV9));
		steps.add(step(9, 10, SchemaMigrations::v9ToV10));
		steps.add(step(10, 11, SchemaMigrations::v10ToV11));
		steps.add(step(11, 12, SchemaMigrations::v11ToV12));

		return steps;
	}

	/**
	 * The one step that upgrades from the given version.
	 *
	 * @throws IllegalArgumentException if no step does, which for a caller iterating the chain
	 *                                  means the chain has a gap in it
	 */
	@NonNull
	public static Step stepFrom(int version) {

		for (Step step : steps()) {
			if (step.from() == version) {
				return step;
			}
		}
		throw new IllegalArgumentException("No migration from schema version " + version);
	}

	/**
	 * Brings a database from {@code fromVersion} to {@link #CURRENT_VERSION}, running each step
	 * in turn.
	 * <p>
	 * Does not open a transaction and does not touch {@code user_version}; both belong to the
	 * caller, which on the app's side is Room and on the server's is the importer. A database
	 * already at the current version is left completely alone, which is the common case: most
	 * backups come from phones running the current release.
	 *
	 * @throws IllegalArgumentException if the version is older than anything supported, or
	 *                                  newer than this code knows how to read — a backup from
	 *                                  a future release, which has to be refused rather than
	 *                                  guessed at
	 */
	public static void migrate(@NonNull SqliteDb db, int fromVersion,
	                           @NonNull MigrationContext context) {

		if (fromVersion > CURRENT_VERSION) {
			throw new IllegalArgumentException("Schema version " + fromVersion
					+ " is newer than this build reads (" + CURRENT_VERSION + ").");
		}

		if (fromVersion < FIRST_SUPPORTED_VERSION) {
			throw new IllegalArgumentException("Schema version " + fromVersion
					+ " is older than the first supported version ("
					+ FIRST_SUPPORTED_VERSION + ").");
		}

		for (int version = fromVersion; version < CURRENT_VERSION; version++) {
			stepFrom(version).apply(db, context);
		}
	}

	// ── The steps ────────────────────────────────────────────────────────

	/**
	 * Version 1 to 2: {@code lectures.batchID} became nullable, so that a lecture can be held
	 * for a course's whole class rather than always belonging to a batch.
	 * <p>
	 * SQLite cannot drop a NOT NULL constraint in place, so the table is rebuilt and copied.
	 * This is the standard recreate-and-copy dance, done inside the transaction the caller
	 * wraps the migration in.
	 */
	private static void v1ToV2(@NonNull SqliteDb db, @NonNull MigrationContext context) {

		db.exec("CREATE TABLE IF NOT EXISTS `lectures_new` ("
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

		db.exec("INSERT INTO `lectures_new` "
				+ "(`lectureID`, `courseID`, `batchID`, `startDateTime`, `endDateTime`, "
				+ " `notes`) "
				+ "SELECT `lectureID`, `courseID`, `batchID`, `startDateTime`, "
				+ "`endDateTime`, `notes` FROM `lectures`");

		db.exec("DROP TABLE `lectures`");
		db.exec("ALTER TABLE `lectures_new` RENAME TO `lectures`");

		db.exec("CREATE UNIQUE INDEX IF NOT EXISTS "
				+ "`index_lectures_courseID_batchID_startDateTime_endDateTime` "
				+ "ON `lectures` (`courseID`, `batchID`, `startDateTime`, `endDateTime`)");
		db.exec("CREATE INDEX IF NOT EXISTS `index_lectures_batchID` "
				+ "ON `lectures` (`batchID`)");
		db.exec("CREATE INDEX IF NOT EXISTS `index_lectures_courseID` "
				+ "ON `lectures` (`courseID`)");
	}

	/**
	 * Version 2 to 3: adds the {@code academic_years} table.
	 * <p>
	 * The table is seeded so that no existing data becomes unreachable. Statistics join
	 * lectures to sessions, so a lecture falling in no session would vanish from every report.
	 * The seed therefore covers three things:
	 * <ol>
	 *   <li>every {@code academicYear} value already referenced by students, batches and batch
	 *       membership;</li>
	 *   <li>every session needed to span the earliest and latest lecture actually recorded;</li>
	 *   <li>the session containing today, so the app has somewhere to file new attendance.</li>
	 * </ol>
	 * Each seeded session runs 1 June to 31 May, as agreed for the migration. That is a
	 * starting point only: the dates are editable afterwards, and nothing else in the code
	 * assumes June once this table exists.
	 */
	private static void v2ToV3(@NonNull SqliteDb db, @NonNull MigrationContext context) {

		db.exec("CREATE TABLE IF NOT EXISTS `academic_years` ("
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

		years.addAll(yearsSpanningLectures(db, context.zone()));
		years.add(SessionYears.defaultYearFor(context.today()));

		// A gap in the middle would leave lectures unreachable, so the range is filled in
		// rather than only the years that happened to be referenced.
		if (!years.isEmpty()) {
			int first = ((TreeSet<Integer>) years).first();
			int last = ((TreeSet<Integer>) years).last();
			for (int year = first; year <= last; year++) {
				years.add(year);
			}
		}

		ZoneId zone = context.zone();

		for (int year : years) {
			LocalDate start = LocalDate.of(year, 6, 1);
			LocalDate end = LocalDate.of(year + 1, 5, 31);

			long startEpoch = start.atStartOfDay(zone).toEpochSecond();
			long endEpoch = end.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1;

			db.exec("INSERT OR REPLACE INTO `academic_years` "
							+ "(`academicYear`, `startDate`, `endDate`, `startEpoch`, "
							+ " `endEpoch`) VALUES (?, ?, ?, ?, ?)",
					year, start.toEpochDay(), end.toEpochDay(), startEpoch, endEpoch);
		}
	}

	/**
	 * Version 3 to 4: indexes {@code attendance.studentID}.
	 * <p>
	 * The table's composite primary key is {@code (lectureID, studentID)}, which indexes
	 * lectureID first and so cannot serve a lookup by student alone. Every per-student
	 * statistic does exactly that lookup. No data changes, only the index.
	 */
	private static void v3ToV4(@NonNull SqliteDb db, @NonNull MigrationContext context) {
		db.exec("CREATE INDEX IF NOT EXISTS `index_attendance_studentID` "
				+ "ON `attendance` (`studentID`)");
	}

	/**
	 * Version 4 to 5: indexes the three foreign key columns Room warned about.
	 * <p>
	 * A foreign key column with no index means every change to the parent table triggers a
	 * full scan of the child to enforce the constraint. Two of these are composite-key columns
	 * that do not lead their key, so the key's own index cannot serve them; the third had no
	 * index at all. No data changes.
	 */
	private static void v4ToV5(@NonNull SqliteDb db, @NonNull MigrationContext context) {

		db.exec("CREATE INDEX IF NOT EXISTS `index_courses_profID` "
				+ "ON `courses` (`profID`)");

		db.exec("CREATE INDEX IF NOT EXISTS `index_batch_student_map_studentID` "
				+ "ON `batch_student_map` (`studentID`)");

		db.exec("CREATE INDEX IF NOT EXISTS `index_course_batch_map_batchID` "
				+ "ON `course_batch_map` (`batchID`)");
	}

	/**
	 * Adds the semester a student is in.
	 * <p>
	 * Nullable, and left null for every existing row. A semester was never asked for before,
	 * so there is nothing to backfill it from: the year narrows it to a pair (SY means 3 or 4)
	 * but cannot pick between them, and guessing would put a value in front of the user that
	 * looks recorded when it was invented. Rows fill in as students are edited.
	 */
	private static void v5ToV6(@NonNull SqliteDb db, @NonNull MigrationContext context) {
		db.exec("ALTER TABLE `student_year` ADD COLUMN `semester` INTEGER");
	}

	/**
	 * Version 6 to 7: takes the five auto-generated primary keys away from SQLite.
	 * <p>
	 * Students, professors, courses, batches and lectures were declared
	 * {@code INTEGER PRIMARY KEY AUTOINCREMENT}, so their IDs were whatever the next number in
	 * the file happened to be. They are now issued by {@code IdGenerator} before the row is
	 * written, from a layout that keeps keys from different devices apart, and the column is a
	 * plain {@code INTEGER PRIMARY KEY}.
	 * <p>
	 * SQLite cannot drop AUTOINCREMENT in place, so each of the five tables is rebuilt and
	 * copied, exactly as {@link #v1ToV2} rebuilt the lectures table. Existing rows keep the IDs
	 * they already have: renumbering them would rewrite every foreign key in the database to no
	 * purpose, and the two kinds of ID coexist perfectly well. New rows simply get much larger
	 * numbers, which is also what stops them colliding with the old ones.
	 * <p>
	 * Dropping a parent table with children pointing at it is safe here because foreign keys
	 * are not enforced while a migration runs — Room turns them on in {@code onOpen}, which is
	 * after, and the importer opens the staged file with them off for the same reason. The child
	 * tables refer to their parents by name, and the name is restored by the rename before
	 * anything can look again.
	 */
	private static void v6ToV7(@NonNull SqliteDb db, @NonNull MigrationContext context) {

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

	/**
	 * Version 7 to 8: adds the division a student's class is split into.
	 * <p>
	 * Nullable, and left null for every existing row, which is the honest value in both of the
	 * cases that exist. Most streams are not divided at all, so there is nothing to record; the
	 * ones that are were never asked for a division before, and nothing in the database implies
	 * which one a student sat in. Rows fill in as students are edited or re-imported.
	 */
	private static void v7ToV8(@NonNull SqliteDb db, @NonNull MigrationContext context) {
		// ALTER TABLE appends the column, so the field is declared last on the entity as
		// well and a migrated database has the same column order as a freshly created
		// one. Room compares columns by name and would accept either, but a difference
		// there is the sort of thing that only shows up in a positional insert written
		// years later.
		db.exec("ALTER TABLE `student_year` ADD COLUMN `division` TEXT");
	}

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
	private static void v8ToV9(@NonNull SqliteDb db, @NonNull MigrationContext context) {

		db.exec("CREATE TABLE IF NOT EXISTS `course_instructor` ("
				+ "`courseID` INTEGER NOT NULL, "
				+ "`profID` INTEGER NOT NULL, "
				+ "PRIMARY KEY(`courseID`, `profID`), "
				+ "FOREIGN KEY(`courseID`) REFERENCES `courses`(`courseID`) "
				+ "  ON UPDATE CASCADE ON DELETE RESTRICT, "
				+ "FOREIGN KEY(`profID`) REFERENCES `professors`(`profID`) "
				+ "  ON UPDATE CASCADE ON DELETE RESTRICT)");

		// courseID leads the primary key and is served by it; profID is not, and both the
		// foreign key check and the read-it-backwards lookup need the index.
		db.exec("CREATE INDEX IF NOT EXISTS `index_course_instructor_profID` "
				+ "ON `course_instructor` (`profID`)");

		db.exec("INSERT OR IGNORE INTO `course_instructor` (`courseID`, `profID`) "
				+ "SELECT `courseID`, `profID` FROM `courses`");
	}

	/**
	 * Version 9 to 10: the master's courses in Physics, Botany, Zoology, Bioanalytical
	 * Sciences, Microbiology and FTN became unaided, so their rows move from {@code MSc} to
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
	private static void v9ToV10(@NonNull SqliteDb db, @NonNull MigrationContext context) {

		// Enum columns hold constant names, which is why these are string literals.
		final String departments = "('PHYSICS', 'BOTANY', 'ZOOLOGY', "
				+ "'BIOANALYTICAL_SCIENCES', 'MICROBIOLOGY', 'FTN')";

		db.exec("UPDATE `student_year` SET `stream` = 'MSc_unaided' "
				+ "WHERE `stream` = 'MSc' AND `majorDept` IN " + departments);

		db.exec("UPDATE `courses` SET `stream` = 'MSc_unaided' "
				+ "WHERE `stream` = 'MSc' AND `offeringDepartment` IN " + departments);
	}

	/**
	 * Adds the outbox and the device's sync state.
	 * <p>
	 * The first migration that adds nothing to the attendance data. Both tables are device
	 * bookkeeping — a queue of local changes, and where the last pull from the server got to —
	 * and the schema version moves anyway, because they are in this database rather than beside
	 * it: the outbox is filled by triggers on the attendance tables, and a SQLite trigger cannot
	 * write to another file. That atomicity is the whole point of it, so the version bump is the
	 * price.
	 * <p>
	 * The statements are Room's own, copied from the generated {@code MainDatabase_Impl} rather
	 * than written by hand. Room validates every table against what it expected on each open, so
	 * a migration that produces so much as a different column order fails the next launch — and
	 * copying is the only way to be sure. The triggers are deliberately <em>not</em> here: they
	 * are created on every open, because an upgraded file, a fresh one and a restored one all
	 * have to end up with them. See {@code OutboxTriggers}.
	 * <p>
	 * Nothing is backfilled. An existing database's rows are not queued for sending, because
	 * this device's history reaches the server through its backup and the importer, not through
	 * the outbox — and queueing four years of attendance as "local changes" would be a claim
	 * that every row was marked today.
	 */
	private static void v10ToV11(@NonNull SqliteDb db, @NonNull MigrationContext context) {

		db.exec("CREATE TABLE IF NOT EXISTS `outbox` ("
				+ "`seq` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
				+ "`tableName` TEXT NOT NULL, "
				+ "`key1` INTEGER NOT NULL, "
				+ "`key2` INTEGER NOT NULL, "
				+ "`key3` INTEGER NOT NULL, "
				+ "`op` TEXT NOT NULL, "
				+ "`queuedAt` INTEGER NOT NULL, "
				+ "`attempts` INTEGER NOT NULL DEFAULT 0, "
				+ "`lastError` TEXT)");

		db.exec("CREATE UNIQUE INDEX IF NOT EXISTS "
				+ "`index_outbox_tableName_key1_key2_key3` "
				+ "ON `outbox` (`tableName`, `key1`, `key2`, `key3`)");

		db.exec("CREATE TABLE IF NOT EXISTS `sync_state` ("
				+ "`id` INTEGER NOT NULL, "
				+ "`applying` INTEGER NOT NULL, "
				+ "`watermark` TEXT, "
				+ "`lastPullAt` INTEGER NOT NULL, "
				+ "`lastPushAt` INTEGER NOT NULL, "
				+ "PRIMARY KEY(`id`))");

		// The row the triggers read. OR IGNORE so that running this against a database that
		// somehow already has it is not an error -- which is also what makes the step safe to
		// re-run on a staged backup.
		db.exec("INSERT OR IGNORE INTO `sync_state` "
				+ "(`id`, `applying`, `lastPullAt`, `lastPushAt`) VALUES (1, 0, 0, 0)");
	}

	/**
	 * A student's APAAR ID, and the day they joined and left a session's class.
	 * <p>
	 * Three nullable columns, which SQLite adds in place: no table rebuild, and every existing
	 * row reads as "not recorded", which for the dates means joined at the start of the session
	 * and not left. The index is Room's own name for it, so the next open validates.
	 */
	private static void v11ToV12(@NonNull SqliteDb db, @NonNull MigrationContext context) {

		db.exec("ALTER TABLE `students` ADD COLUMN `apaarId` INTEGER");
		db.exec("CREATE UNIQUE INDEX IF NOT EXISTS `index_students_apaarId` "
				+ "ON `students` (`apaarId`)");

		db.exec("ALTER TABLE `student_year` ADD COLUMN `joinDate` INTEGER");
		db.exec("ALTER TABLE `student_year` ADD COLUMN `leaveDate` INTEGER");
	}

	// ── Helpers ──────────────────────────────────────────────────────────

	/**
	 * The recreate-and-copy dance, for a table that has to change in a way SQLite cannot do in
	 * place. The new table is built beside the old one under a {@code _new} suffix, every row
	 * is copied across, the old table is dropped and the new one takes its name. Indices belong
	 * to the table they are on, so they go down with it and are recreated afterwards.
	 *
	 * @param columns the column list, identical on both sides, written out rather than left to
	 *                {@code SELECT *} so a mismatch in column order cannot silently shuffle the
	 *                data
	 */
	private static void rebuild(@NonNull SqliteDb db, @NonNull String table,
	                            @NonNull String createSql, @NonNull String columns,
	                            @NonNull String... indices) {

		db.exec(createSql);

		db.exec("INSERT INTO `" + table + "_new` (" + columns + ") "
				+ "SELECT " + columns + " FROM `" + table + "`");

		db.exec("DROP TABLE `" + table + "`");
		db.exec("ALTER TABLE `" + table + "_new` RENAME TO `" + table + "`");

		for (String index : indices) {
			db.exec(index);
		}
	}

	private static void collectYears(@NonNull SqliteDb db, @NonNull String sql,
	                                 @NonNull Set<Integer> into) {

		for (SqliteDb.Row row : db.query(sql)) {
			if (!row.isNull(0)) {
				into.add(row.getInt(0));
			}
		}
	}

	/**
	 * Works out which sessions are needed to cover the lectures already recorded, by reading
	 * the earliest and latest stored timestamp. Timestamps are stored as
	 * "epochSecond|nano|zone", so the leading integer is sliced out and cast.
	 */
	@NonNull
	private static Set<Integer> yearsSpanningLectures(@NonNull SqliteDb db,
	                                                  @NonNull ZoneId zone) {

		Set<Integer> years = new LinkedHashSet<>();

		String sql = "SELECT "
				+ "MIN(CAST(SUBSTR(startDateTime, 1, INSTR(startDateTime, '|') - 1) AS INTEGER)),"
				+ "MAX(CAST(SUBSTR(startDateTime, 1, INSTR(startDateTime, '|') - 1) AS INTEGER)) "
				+ "FROM lectures";

		List<SqliteDb.Row> rows = db.query(sql);

		if (!rows.isEmpty() && !rows.get(0).isNull(0)) {

			SqliteDb.Row row = rows.get(0);

			LocalDate earliest = Instant.ofEpochSecond(row.getLong(0))
					.atZone(zone).toLocalDate();
			LocalDate latest = Instant.ofEpochSecond(row.getLong(1))
					.atZone(zone).toLocalDate();

			years.add(SessionYears.defaultYearFor(earliest));
			years.add(SessionYears.defaultYearFor(latest));
		}

		return years;
	}
}
