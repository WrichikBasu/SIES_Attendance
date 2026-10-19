package in.basulabs.siesattendance.server.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import in.basulabs.siesattendance.backend.StructuredId;
import in.basulabs.siesattendance.backend.migration.SchemaMigrations;
import in.basulabs.siesattendance.server.testing.TestPostgres;

/**
 * The importer, against a real PostgreSQL with the real migrations applied.
 * <p>
 * Half of what the importer does is find out whether a row is already there, and the other half
 * is finding out whether the database will accept it. Neither can be tested against a stub: the
 * first needs the natural keys to actually match rows, and the second is V2's constraints doing
 * their job. So every test here runs against a throwaway {@code postgres:18}.
 *
 * @see TestPostgres for how the container is started and removed
 * @see DeviceBackup for how a backup is built
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BackupImporterTest {

	/** The zone the devices are in, and so the one the importer is told to read dates at. */
	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	private static final int SESSION = 2025;

	/** Two devices, with node numbers in the range an install draws for itself. */
	private static final int DEVICE_A = 41231;

	private static final int DEVICE_B = 52000;

	private static TestPostgres postgres;

	@TempDir
	Path work;

	@BeforeAll
	void startDatabase() throws Exception {

		assumeTrue(TestPostgres.isAvailable(),
				"Docker is not available, so the importer cannot be tested against a real "
						+ "PostgreSQL. Run ./server/tools/verify-import.sh where it is.");

		postgres = TestPostgres.start();
	}

	@AfterAll
	void stopDatabase() {

		if (postgres != null) {
			postgres.close();
		}
	}

	@BeforeEach
	void emptyTheDatabase() throws Exception {

		assumeTrue(postgres != null, "no database");
		postgres.reset();
	}

	// ── A whole device, imported ─────────────────────────────────────────

	@Test
	void aCurrentBackupIsImportedWhole() throws Exception {

		Path backup = deviceWithStructuredKeys(DEVICE_A, "a");

		BackupImporter.Outcome outcome = importIt(backup, "device A", DEVICE_A, false);

		assertTrue(outcome.succeeded(), () -> "failed: " + outcome.failure());
		// The version the fixture packed at, not a literal: a backup from a phone on the
		// current release says whatever the current release is, and pinning the number here
		// makes the next schema bump fail in a test about importing rather than about
		// versions.
		assertEquals(SchemaMigrations.CURRENT_VERSION, outcome.schemaVersionAsFound(),
				"a current backup");

		assertRowCount("professors", 1);
		assertRowCount("academic_years", 1);
		assertRowCount("students", 2);
		assertRowCount("student_year", 2);
		assertRowCount("courses", 1);
		assertRowCount("course_instructor", 1);
		assertRowCount("batch_desc", 1);
		assertRowCount("batch_student_map", 2);
		assertRowCount("course_batch_map", 1);
		assertRowCount("lectures", 2);
		assertRowCount("attendance", 4);

		assertEquals(0, outcome.report().totalUpdated(), "nothing was there to update");
		assertEquals(0, outcome.report().totalUnchanged(), "and nothing to recognise");
		assertTrue(outcome.report().skipped().isEmpty(),
				() -> "nothing should have been skipped: " + outcome.report().skipped());
	}

	/**
	 * The requirement the phase states outright: importing the same backup twice must change
	 * nothing the second time.
	 */
	@Test
	void importingTheSameBackupTwiceChangesNothing() throws Exception {

		Path backup = deviceWithStructuredKeys(DEVICE_A, "a");

		BackupImporter.Outcome first = importIt(backup, "device A", DEVICE_A, false);
		assertTrue(first.succeeded(), () -> "first run failed: " + first.failure());

		int insertedFirst = first.report().totalInserted();
		assertTrue(insertedFirst > 0, "the first run should have inserted something");

		String before = snapshot();

		BackupImporter.Outcome second = importIt(backup, "device A", DEVICE_A, false);

		assertTrue(second.succeeded(), () -> "second run failed: " + second.failure());
		assertEquals(0, second.report().totalInserted(),
				"the second run must insert nothing");
		assertEquals(0, second.report().totalUpdated(),
				"and must change nothing, not merely rewrite it with the same values");
		assertEquals(insertedFirst, second.report().totalUnchanged(),
				"every row should be recognised and found identical");
		assertTrue(second.seenBefore(), "it should know it has seen this file");
		assertEquals(before, snapshot(), "no row may have changed");
	}

	// ── Old schemas ──────────────────────────────────────────────────────

	/**
	 * A backup from an old release is brought to the current schema by the app's own migrations
	 * before a single row is read, which is what lets the importer be written against one shape.
	 */
	@Test
	void anOldBackupIsMigratedBeforeItIsImported() throws Exception {

		Path backup = deviceWithLegacyKeys(DEVICE_A, "a", 1);

		BackupImporter.Outcome outcome = importIt(backup, "device A", DEVICE_A, false);

		assertTrue(outcome.succeeded(), () -> "failed: " + outcome.failure());
		assertEquals(DeviceBackup.BASE_VERSION, outcome.schemaVersionAsFound(),
				"the backup was at version 6");

		assertRowCount("students", 2);
		assertRowCount("lectures", 2);
		assertRowCount("attendance", 4);

		// Migration 8 to 9 creates course_instructor and seeds it from courses.profID, so a
		// version 6 backup arrives with one instructor per course without ever having had the
		// table.
		assertRowCount("course_instructor", 1);
	}

	// ── The reason id_remap exists ───────────────────────────────────────

	/**
	 * Two devices old enough to have let SQLite count both hold student 1, student 2 and so on,
	 * and they are different people. This is the collision the whole identity map exists for.
	 */
	@Test
	void legacyKeysFromTwoDevicesDoNotCollide() throws Exception {

		Path first = deviceWithLegacyKeys(DEVICE_A, "a", 1);
		Path second = deviceWithLegacyKeys(DEVICE_B, "b", 1);

		BackupImporter.Outcome a = importIt(first, "device A", DEVICE_A, false);
		assertTrue(a.succeeded(), () -> "device A failed: " + a.failure());

		BackupImporter.Outcome b = importIt(second, "device B", DEVICE_B, false);
		assertTrue(b.succeeded(), () -> "device B failed: " + b.failure());

		// Four distinct people, not two.
		assertRowCount("students", 4);
		assertRowCount("student_year", 4);
		assertRowCount("lectures", 4);
		assertRowCount("attendance", 8);

		// Every one of those keys was a small integer, so every one had to be replaced.
		assertTrue(queryLong("SELECT count(*) FROM id_remap WHERE source_node = "
				+ DEVICE_A) > 0, "device A's legacy keys should be recorded");
		assertTrue(queryLong("SELECT count(*) FROM id_remap WHERE source_node = "
				+ DEVICE_B) > 0, "device B's legacy keys should be recorded");

		// Both devices called their first student 1, and the two must have landed on
		// different server rows.
		long fromA = queryLong("SELECT server_id FROM id_remap WHERE table_name = 'students' "
				+ "AND source_node = " + DEVICE_A + " AND source_id = 1");
		long fromB = queryLong("SELECT server_id FROM id_remap WHERE table_name = 'students' "
				+ "AND source_node = " + DEVICE_B + " AND source_id = 1");

		assertNotEquals(fromA, fromB, "two devices' student 1 must not be one student");

		// And every minted key is the server's own, which is what makes it tellable apart
		// from a device's for ever.
		for (long id : new long[]{fromA, fromB}) {
			assertTrue(StructuredId.isStructured(id), id + " should be structured");
			assertEquals(StructuredId.SERVER_NODE, StructuredId.nodeOf(id),
					"a minted key belongs to the server's node");
		}
	}

	/**
	 * The point of keeping the map rather than rebuilding it: a second backup from the same
	 * phone has to land on the rows the first one created, even when the natural key it was
	 * matched by has since been corrected on the device.
	 */
	@Test
	void aSecondBackupFromTheSameDeviceLandsOnTheSameRowsAfterARollNumberIsCorrected()
			throws Exception {

		Path first = deviceWithLegacyKeys(DEVICE_A, "a", 1);
		assertTrue(importIt(first, "device A", DEVICE_A, false).succeeded());

		long serverId = queryLong("SELECT server_id FROM id_remap "
				+ "WHERE table_name = 'students' AND source_node = " + DEVICE_A
				+ " AND source_id = 1");

		// The same device again, with the roll number of student 1 corrected. Nothing the
		// natural key can see connects the two rows any more.
		try (DeviceBackup device = seedLegacyDevice(DEVICE_A, "a", 1)) {
			device.write("UPDATE student_year SET rollNo = ? WHERE studentID = 1",
					"CORRECTED-1");
			device.migrateToCurrent(IST);
			device.pack(work.resolve("a-second.siesbak"), null);
		}

		BackupImporter.Outcome second = importIt(work.resolve("a-second.siesbak"),
				"device A", DEVICE_A, false);

		assertTrue(second.succeeded(), () -> "second run failed: " + second.failure());

		assertRowCount("students", 2);
		assertEquals("CORRECTED-1", queryString("SELECT roll_no FROM student_year "
						+ "WHERE student_id = " + serverId),
				"the correction should have landed on the same student");
		assertRowCount("student_year", 2);

		// Exactly one row differed, and the report should say so rather than reporting
		// every row in the backup as touched.
		assertEquals(1, second.report().totalUpdated(),
				() -> "only the corrected roll number changed: " + second.report()
						.toPlainText());
	}

	/**
	 * A structured key is unique across every device by construction, so it comes across
	 * untouched and needs no entry in the map. A row arriving under its own key is a row every
	 * device will agree about for ever.
	 */
	@Test
	void structuredKeysAreCarriedAcrossUnchanged() throws Exception {

		Path backup = deviceWithStructuredKeys(DEVICE_A, "a");

		assertTrue(importIt(backup, "device A", DEVICE_A, false).succeeded());

		assertEquals(0, queryLong("SELECT count(*) FROM id_remap"),
				"no structured key should have needed remapping");

		assertEquals(DEVICE_A, StructuredId.nodeOf(
						queryLong("SELECT min(student_id) FROM students")),
				"the students should still carry the device's own node");

		assertEquals(2, queryLong("SELECT count(*) FROM students WHERE id_node(student_id) = "
				+ DEVICE_A), "and the SQL helper should agree");
	}

	// ── Refusals and safety ──────────────────────────────────────────────

	@Test
	void aDryRunWritesNothing() throws Exception {

		Path backup = deviceWithStructuredKeys(DEVICE_A, "a");

		BackupImporter.Outcome outcome = importIt(backup, "device A", DEVICE_A, true);

		assertTrue(outcome.succeeded(), () -> "failed: " + outcome.failure());
		assertTrue(outcome.report().totalInserted() > 0,
				"the report should say what it would have done");

		assertRowCount("students", 0);
		assertRowCount("lectures", 0);
		assertRowCount("id_remap", 0);
	}

	@Test
	void aWrongPasswordIsRefusedBeforeAnythingIsWritten() throws Exception {

		Path backup;

		try (DeviceBackup device = seedStructuredDevice(DEVICE_A, "a")) {
			device.migrateToCurrent(IST);
			backup = device.pack(work.resolve("encrypted.siesbak"), "right".toCharArray());
		}

		StagedBackup.RejectedException refused = assertThrows(
				StagedBackup.RejectedException.class,
				() -> importIt(backup, "device A", DEVICE_A, false, "wrong".toCharArray()));

		assertEquals(StagedBackup.Rejection.BAD_PASSWORD, refused.rejection());

		assertRowCount("students", 0);
		// A file that was never opened is not an import, so there is nothing to record.
		assertRowCount("import_run", 0);
	}

	@Test
	void anEncryptedBackupIsImportedWithTheRightPassword() throws Exception {

		Path backup;

		try (DeviceBackup device = seedStructuredDevice(DEVICE_A, "a")) {
			device.migrateToCurrent(IST);
			backup = device.pack(work.resolve("encrypted.siesbak"), "right".toCharArray());
		}

		BackupImporter.Outcome outcome = importIt(backup, "device A", DEVICE_A, false,
				"right".toCharArray());

		assertTrue(outcome.succeeded(), () -> "failed: " + outcome.failure());
		assertRowCount("students", 2);
	}

	@Test
	void somethingThatIsNotABackupIsRefused() throws Exception {

		Path notABackup = work.resolve("notes.txt");
		java.nio.file.Files.writeString(notABackup, "this is not a backup at all");

		StagedBackup.RejectedException refused = assertThrows(
				StagedBackup.RejectedException.class,
				() -> importIt(notABackup, "device A", DEVICE_A, false));

		assertEquals(StagedBackup.Rejection.NOT_A_BACKUP, refused.rejection());
	}

	/**
	 * Two batches sharing a name within one session are pooled into one, and the import says
	 * so.
	 * <p>
	 * Room's index on {@code (batchID, academicYear)} guarantees nothing, because
	 * {@code batchID} is already the primary key, so a device will happily hold both — while
	 * {@code DatabaseMerger} has always reconciled batches on {@code batchName + academicYear}.
	 * The merge on a phone therefore pools them silently. The import pools them too, because
	 * refusing would be rejecting data the app itself produced, but it is the only one of the
	 * two that mentions it.
	 */
	@Test
	void twoBatchesSharingANameArePooledAndReported() throws Exception {

		Path backup;

		try (DeviceBackup device = seedStructuredDevice(DEVICE_A, "a")) {

			device.batch(structured(DEVICE_A, 500), "Batch A", "a duplicate name", SESSION);

			device.migrateToCurrent(IST);
			backup = device.pack(work.resolve("clash.siesbak"), null);
		}

		BackupImporter.Outcome outcome = importIt(backup, "device A", DEVICE_A, false);

		assertTrue(outcome.succeeded(), () -> "failed: " + outcome.failure());

		// Two device batches, one server batch.
		assertRowCount("batch_desc", 1);

		assertTrue(outcome.report().skipped().stream()
						.anyMatch(reason -> reason.startsWith("batch_desc:")
								&& reason.contains("the same row here")),
				() -> "the pooling should be reported: " + outcome.report().skipped());
	}

	/**
	 * V2 states rules the device schema could not, and a historical row that breaks one is a
	 * real defect rather than a fault in the translation. The whole import is one transaction,
	 * so such a row stops everything — and the attempt still has to be on record afterwards,
	 * with the reason.
	 */
	@Test
	void aRowTheDatabaseRefusesRollsBackTheWholeImportAndIsRecorded() throws Exception {

		Path backup;

		try (DeviceBackup device = seedStructuredDevice(DEVICE_A, "a")) {

			// A second session whose dates run straight through the first. The device has no
			// way to state that sessions may not overlap; V2 says it with a range exclusion
			// constraint, and nothing in the importer checks it beforehand.
			device.session(SESSION + 1, LocalDate.of(SESSION, 9, 1),
					LocalDate.of(SESSION + 2, 5, 31), IST);

			device.migrateToCurrent(IST);
			backup = device.pack(work.resolve("overlap.siesbak"), null);
		}

		BackupImporter.Outcome outcome = importIt(backup, "device A", DEVICE_A, false);

		assertFalse(outcome.succeeded(), "overlapping sessions must be refused");
		assertTrue(outcome.failure().contains("academic_years"),
				() -> "the failure should name the table: " + outcome.failure());

		// Nothing landed, including the rows that were fine.
		assertRowCount("academic_years", 0);
		assertRowCount("students", 0);
		assertRowCount("professors", 0);

        // But the attempt is on record, because the run row is committed before the
        // transaction opens.
		assertRowCount("import_run", 1);
		assertEquals("false", queryString(
				"SELECT succeeded::text FROM import_run ORDER BY run_id DESC LIMIT 1"));
		assertTrue(queryString("SELECT failure FROM import_run ORDER BY run_id DESC LIMIT 1")
				.contains("academic_years"), "the reason should be stored too");
	}

	/** The node is what keys the identity map, so a nonsense one has to be refused outright. */
	@Test
	void theServersOwnNodeCannotBeClaimedByADevice() throws Exception {

		Path backup = deviceWithStructuredKeys(DEVICE_A, "a");

		assertThrows(IllegalArgumentException.class,
				() -> importIt(backup, "a liar", StructuredId.SERVER_NODE, false));
	}

	/**
	 * A minor and an open elective taught by departments that do not run the students' stream.
	 * <p>
	 * This is the shape V2's {@code courses_department_offers_stream_fkey} made impossible and
	 * V4 exempts. Mathematics is studied as BSc and never BSc_unaided, and Economics is a BA/MA
	 * department, so both courses carry a stream their own department does not offer — which is
	 * correct, because the stream is the class being taught and not a claim about who teaches
	 * it. No existing backup can contain such a row, the app's form having refused it too, so
	 * this covers what the corrected form will now produce.
	 */
	@Test
	void aCourseTaughtByAnotherDepartmentIsImported() throws Exception {

		Path backup;

		try (DeviceBackup device = DeviceBackup.emptyAtVersion6(work.resolve("devices"))) {

			long professor = structured(DEVICE_A, 1);
			long student = structured(DEVICE_A, 2);
			long minor = structured(DEVICE_A, 3);
			long elective = structured(DEVICE_A, 4);
			long lecture = structured(DEVICE_A, 5);

			device.professor(professor, "Prof M", "m.rao@sies.edu.in", "MATHEMATICS",
					"Assistant Professor");

			device.session(SESSION, LocalDate.of(SESSION, 6, 1),
					LocalDate.of(SESSION + 1, 5, 31), IST);

			device.student(student, "Student M", "she/her", "m.one@sies.edu.in");

			// A Computer Science student, therefore BSc_unaided, minoring in Mathematics and
			// taking an open elective in Economics.
			device.studentYear(student, SESSION, "cs-001", "FY", "BSc_unaided",
					"COMPUTER_SCIENCE", "MATHEMATICS", "ECONOMICS", 1);

			device.course(minor, professor, "MATH-MIN-1", "Discrete Mathematics",
					"BSc_unaided", "MATHEMATICS", "FY", 1, "MINOR");
			device.course(elective, professor, "ECO-OE-1", "Basic Economics",
					"BSc_unaided", "ECONOMICS", "FY", 1, "OE");

			ZonedDateTime start = ZonedDateTime.of(2025, 7, 14, 9, 0, 0, 0, IST);
			device.lecture(lecture, minor, null, start, start.plusHours(1), null);
			device.attendance(lecture, student, true);

			device.migrateToCurrent(IST);
			backup = device.pack(work.resolve("cross-department.siesbak"), null);
		}

		assertTrue(importIt(backup, "device A", DEVICE_A, false).succeeded(),
				"a course taught by a department that does not run the stream must be accepted");

		assertRowCount("courses", 2);

		// The generated column is the whole mechanism: null for these two, so the foreign key
		// onto department_stream is never checked for them.
		assertEquals(2L, queryLong("SELECT count(*) FROM courses "
						+ "WHERE own_department_stream IS NULL"),
				"neither cross-department course should be constrained on its stream");

		// And the exemption costs the course nothing else: it is still matched on its code, so
		// a second import of the same backup is still a no-op.
		String before = snapshot();
		assertTrue(importIt(backup, "device A", DEVICE_A, false).succeeded());
		assertEquals(before, snapshot(), "re-importing must change nothing");
	}

	// ── Building devices ─────────────────────────────────────────────────

	/** A key this device would have issued: structured, carrying its node. */
	private static long structured(int node, int sequence) {
		return StructuredId.compose(StructuredId.MIN_COUNTER + sequence, node);
	}

	/**
	 * One self-consistent device: a professor, a session, two students with roll numbers, a
	 * course, a batch, two lectures and the attendance for both.
	 * <p>
	 * {@code suffix} keeps two devices' people and course codes distinct, so that what the
	 * importer has to recognise is the identity of a row rather than a coincidence of names.
	 */
	private DeviceBackup seedDevice(int node, String suffix, boolean structuredKeys,
	                                long firstLegacyKey) throws Exception {

		DeviceBackup device = DeviceBackup.emptyAtVersion6(work.resolve("devices"));

		long professor = structuredKeys ? structured(node, 1) : firstLegacyKey;
		long student1 = structuredKeys ? structured(node, 2) : firstLegacyKey;
		long student2 = structuredKeys ? structured(node, 3) : firstLegacyKey + 1;
		long course = structuredKeys ? structured(node, 4) : firstLegacyKey;
		long batch = structuredKeys ? structured(node, 5) : firstLegacyKey;
		long lecture1 = structuredKeys ? structured(node, 6) : firstLegacyKey;
		long lecture2 = structuredKeys ? structured(node, 7) : firstLegacyKey + 1;

		device.professor(professor, "Prof " + suffix.toUpperCase(java.util.Locale.ROOT),
				"prof." + suffix + "@sies.edu.in", "COMPUTER_SCIENCE", "Assistant Professor");

		device.session(SESSION, LocalDate.of(SESSION, 6, 1),
				LocalDate.of(SESSION + 1, 5, 31), IST);

		device.student(student1, "Student One " + suffix, "she/her",
				"one." + suffix + "@sies.edu.in");
		device.student(student2, "Student Two " + suffix, "they/them", null);

		device.studentYear(student1, SESSION, suffix + "-001", "FY", "BSc_unaided",
				"COMPUTER_SCIENCE", null, null, 1);
		device.studentYear(student2, SESSION, suffix + "-002", "FY", "BSc_unaided",
				"COMPUTER_SCIENCE", null, null, 1);

		device.course(course, professor, "CS-101-" + suffix, "Programming", "BSc_unaided",
				"COMPUTER_SCIENCE", "FY", 1, "MAJOR");

		device.batch(batch, "Batch " + suffix.toUpperCase(java.util.Locale.ROOT),
				"the whole class", SESSION);
		device.batchMember(batch, student1, SESSION);
		device.batchMember(batch, student2, SESSION);
		device.courseBatch(course, batch);

		ZonedDateTime start = ZonedDateTime.of(2025, 7, 14, 9, 0, 0, 0, IST);

		device.lecture(lecture1, course, batch, start, start.plusHours(1), "first");
		// A whole-class lecture, with no batch: the case that makes the natural key contain a
		// null and so the one that would be inserted again on every import if the match used
		// equality rather than IS NOT DISTINCT FROM.
		device.lecture(lecture2, course, null, start.plusDays(1), start.plusDays(1).plusHours(1),
				null);

		device.attendance(lecture1, student1, true);
		device.attendance(lecture1, student2, false);
		device.attendance(lecture2, student1, true);
		device.attendance(lecture2, student2, true);

		return device;
	}

	private DeviceBackup seedStructuredDevice(int node, String suffix) throws Exception {
		return seedDevice(node, suffix, true, 0L);
	}

	private DeviceBackup seedLegacyDevice(int node, String suffix, long firstKey)
			throws Exception {
		return seedDevice(node, suffix, false, firstKey);
	}

	/** A packed backup from a device running the current release. */
	private Path deviceWithStructuredKeys(int node, String suffix) throws Exception {

		try (DeviceBackup device = seedStructuredDevice(node, suffix)) {
			device.migrateToCurrent(IST);
			return device.pack(work.resolve(suffix + "-current.siesbak"), null);
		}
	}

	/**
	 * A packed backup still at version 6, carrying the small counted-out keys that are the
	 * reason {@code id_remap} exists.
	 */
	private Path deviceWithLegacyKeys(int node, String suffix, long firstKey) throws Exception {

		try (DeviceBackup device = seedLegacyDevice(node, suffix, firstKey)) {
			return device.pack(work.resolve(suffix + "-legacy.siesbak"), null);
		}
	}

	// ── Running and looking ──────────────────────────────────────────────

	private BackupImporter.Outcome importIt(Path backup, String label, int node, boolean dryRun)
			throws Exception {
		return importIt(backup, label, node, dryRun, null);
	}

	private BackupImporter.Outcome importIt(Path backup, String label, int node, boolean dryRun,
	                                        char[] password) throws Exception {

		return new BackupImporter(postgres.dataSource()).run(new BackupImporter.Request(
				backup, password, label, node, IST, work.resolve("staging"), dryRun));
	}

	private void assertRowCount(String table, long expected) throws Exception {
		assertEquals(expected, queryLong("SELECT count(*) FROM " + table),
				"rows in " + table);
	}

	private long queryLong(String sql) throws SQLException {

		try (Connection connection = postgres.connection();
		     PreparedStatement statement = connection.prepareStatement(sql);
		     ResultSet results = statement.executeQuery()) {

			assertTrue(results.next(), () -> "no row from: " + sql);
			return results.getLong(1);
		}
	}

	private String queryString(String sql) throws SQLException {

		try (Connection connection = postgres.connection();
		     PreparedStatement statement = connection.prepareStatement(sql);
		     ResultSet results = statement.executeQuery()) {

			assertTrue(results.next(), () -> "no row from: " + sql);
			return results.getString(1);
		}
	}

	/**
	 * Every row of every data table, as text, so that "nothing changed" can be asserted rather
	 * than approximated by counting.
	 */
	private String snapshot() throws SQLException {

		StringBuilder out = new StringBuilder();

		String[] tables = {"academic_years", "students", "professors", "courses",
				"batch_desc", "student_year", "batch_student_map", "course_batch_map",
				"course_instructor", "lectures", "attendance", "id_remap"};

		try (Connection connection = postgres.connection()) {

			for (String table : tables) {

				// Ordered by every column the table actually has, so the comparison does
				// not depend on the order PostgreSQL happens to return rows in. Counting
				// them first, because two of these tables have only two columns and
				// "ORDER BY 3" is an error rather than a no-op.
				int columns = columnCount(connection, table);

				StringBuilder order = new StringBuilder();

				for (int i = 1; i <= columns; i++) {
					order.append(i == 1 ? "" : ", ").append(i);
				}

				String sql = "SELECT * FROM " + table + " ORDER BY " + order;

				try (PreparedStatement statement = connection.prepareStatement(sql);
				     ResultSet results = statement.executeQuery()) {

					out.append("== ").append(table).append('\n');

					while (results.next()) {
						for (int i = 1; i <= columns; i++) {
							out.append(results.getString(i)).append('|');
						}
						out.append('\n');
					}
				}
			}
		}
		return out.toString();
	}

	private int columnCount(Connection connection, String table) throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT * FROM " + table + " LIMIT 0");
		     ResultSet results = statement.executeQuery()) {

			return results.getMetaData().getColumnCount();
		}
	}
}
