package in.basulabs.siesattendance.server.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import in.basulabs.siesattendance.backend.sync.DeviceTables;
import in.basulabs.siesattendance.backend.sync.OutboxOp;
import in.basulabs.siesattendance.backend.sync.OutboxTriggers;

/**
 * The outbox triggers, against real SQLite.
 * <p>
 * These are the app's triggers and this is the server's test module, which wants a word of
 * explanation: the server is the only module with a SQLite driver, because it reads a device's
 * backup, and {@link DeviceBackup} already builds a genuine device database and runs the shared
 * migrations over it. Testing the triggers here costs one fixture and nothing else; testing them
 * in the app module would mean an emulator, which is the difference between a check that runs on
 * every build and one that runs when somebody remembers.
 * <p>
 * What is being checked is the claim the whole outbox rests on: that a write to an attendance
 * table queues its own key, in the same statement, without anything in Java being involved.
 */
class OutboxTriggerTest {

	/** The device's own zone, for the migrations that need one. */
	private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

	@TempDir
	Path work;

	private DeviceBackup device;

	private Connection connection;

	@BeforeEach
	void buildDeviceDatabase() throws Exception {

		device = DeviceBackup.emptyAtVersion6(work.resolve("devices"));

		// Up to v11 through the shared steps, which is what creates the outbox and the sync
		// state. The same code the phone runs.
		device.migrateToCurrent(ZONE);
		connection = device.connection();

		for (String statement : OutboxTriggers.statements()) {
			execute(statement);
		}

		// A professor and a session to hang the rest of the fixture off, queued and then
		// cleared so each test starts from an empty outbox.
		execute("INSERT INTO professors (profID, name, email, department, designation) "
				+ "VALUES (1001, 'A CS', 'a.cs@sies.edu.in', 'COMPUTER_SCIENCE', 'Professor')");
		execute("DELETE FROM outbox");
	}

	@AfterEach
	void close() throws Exception {
		if (device != null) {
			device.close();
		}
	}

	@Test
	void everyTableHasATriggerForEveryWay() throws Exception {

		int tables = DeviceTables.inDependencyOrder().size();

		assertEquals(tables * 3, count("SELECT count(*) FROM sqlite_master "
						+ "WHERE type = 'trigger' AND name LIKE 'outbox_%'"),
				"insert, update and delete on every table the server knows about");

		// And they are the ones the class says they are, so a rename cannot leave a table
		// quietly unwatched.
		for (String name : OutboxTriggers.names()) {
			assertEquals(1, count("SELECT count(*) FROM sqlite_master "
					+ "WHERE type = 'trigger' AND name = '" + name + "'"), name);
		}
	}

	@Test
	void insertingARowQueuesItsKey() throws Exception {

		execute("INSERT INTO students (studentID, name, pronouns, email) "
				+ "VALUES (2001, 'R RAO', 'she/her', 'r.rao@sies.edu.in')");

		assertEquals(1, count("SELECT count(*) FROM outbox"));
		assertEquals(1, count("SELECT count(*) FROM outbox WHERE tableName = 'students' "
						+ "AND key1 = 2001 AND key2 = 0 AND key3 = 0 AND op = 'UPSERT'"),
				"a single-column key pads with " + OutboxTriggers.UNUSED_KEY
						+ ", because SQLite treats nulls as distinct in a unique index");
		assertTrue(count("SELECT queuedAt FROM outbox") > 0, "queuedAt should be a timestamp");
	}

	@Test
	void changingTheSameRowTwiceQueuesItOnce() throws Exception {

		execute("INSERT INTO students (studentID, name, pronouns, email) "
				+ "VALUES (2001, 'R RAO', 'she/her', NULL)");

		long first = count("SELECT seq FROM outbox WHERE key1 = 2001");

		execute("UPDATE students SET name = 'R RAO CORRECTED' WHERE studentID = 2001");
		execute("UPDATE students SET pronouns = 'they/them' WHERE studentID = 2001");

		assertEquals(1, count("SELECT count(*) FROM outbox"),
				"an intent is a dirty key, so three changes to one row are one message");

		assertNotEquals(first, count("SELECT seq FROM outbox WHERE key1 = 2001"),
				"the replacement takes a new sequence, which is what stops a drain in flight "
						+ "from deleting an intent it never saw");
	}

	@Test
	void deletingARowReplacesItsPendingUpsert() throws Exception {

		execute("INSERT INTO students (studentID, name, pronouns, email) "
				+ "VALUES (2001, 'R RAO', 'she/her', NULL)");
		execute("DELETE FROM students WHERE studentID = 2001");

		assertEquals(1, count("SELECT count(*) FROM outbox"));
		assertEquals(OutboxOp.DELETE.name(), text("SELECT op FROM outbox WHERE key1 = 2001"),
				"the last intent for a key is the only one that is true");
	}

	@Test
	void aCompositeKeyIsQueuedWhole() throws Exception {

		seedALecture();
		execute("DELETE FROM outbox");

		device.attendance(4001, 2001, true);

		assertEquals(1, count("SELECT count(*) FROM outbox WHERE tableName = 'attendance' "
						+ "AND key1 = 4001 AND key2 = 2001 AND key3 = 0"),
				"attendance is keyed on the lecture and the student");

		device.batch(5001, "Batch A", null, 2025);
		device.batchMember(5001, 2001, 2025);

		assertEquals(1, count("SELECT count(*) FROM outbox "
						+ "WHERE tableName = 'batch_student_map' "
						+ "AND key1 = 5001 AND key2 = 2001 AND key3 = 2025"),
				"and a three-column key fills all three");
	}

	@Test
	void marksAndTheirLectureAreBothQueued() throws Exception {

		seedALecture();
		execute("DELETE FROM outbox");

		// What taking attendance looks like: one lecture, one sheet, in one transaction.
		connection.setAutoCommit(false);

		try {
			device.lecture(4002, 3001, null,
					ZonedDateTime.of(2025, 7, 15, 9, 0, 0, 0, ZONE),
					ZonedDateTime.of(2025, 7, 15, 10, 0, 0, 0, ZONE), null);
			device.attendance(4002, 2001, false);
			connection.commit();

		} finally {
			connection.setAutoCommit(true);
		}

		assertEquals(2, count("SELECT count(*) FROM outbox"));
		assertEquals(1, count("SELECT count(*) FROM outbox WHERE tableName = 'lectures' "
				+ "AND key1 = 4002"));
		assertEquals(1, count("SELECT count(*) FROM outbox WHERE tableName = 'attendance' "
				+ "AND key1 = 4002 AND key2 = 2001"));
	}

	@Test
	void nothingIsQueuedBackWhileAPullIsApplying() throws Exception {

		execute("UPDATE sync_state SET applying = 1 WHERE id = 1");

		execute("INSERT INTO students (studentID, name, pronouns, email) "
				+ "VALUES (2002, 'S SHAH', 'they/them', NULL)");

		assertEquals(0, count("SELECT count(*) FROM outbox"),
				"a row that arrived from the server must not be announced back to it");

		execute("UPDATE sync_state SET applying = 0 WHERE id = 1");

		execute("INSERT INTO students (studentID, name, pronouns, email) "
				+ "VALUES (2003, 'T TARE', 'he/him', NULL)");

		assertEquals(1, count("SELECT count(*) FROM outbox"),
				"and the guard has to come back off");
	}

	@Test
	void aMissingGuardRowQueuesRatherThanGoesSilent() throws Exception {

		// The failure direction that matters. Without COALESCE the condition would be
		// NULL = 0, which is not true, and the device would write attendance for ever while
		// queueing none of it -- looking, from the inside, exactly like a device with nothing
		// to send.
		execute("DELETE FROM sync_state");

		execute("INSERT INTO students (studentID, name, pronouns, email) "
				+ "VALUES (2004, 'U UDAY', 'she/her', NULL)");

		assertEquals(1, count("SELECT count(*) FROM outbox"),
				"a queued row too many is recoverable; a mark nobody receives is not");
	}

	@Test
	void theTriggersSurviveTheMigrationBeingRunAgain() throws Exception {

		// The migration is idempotent and the triggers are created with IF NOT EXISTS, which
		// together are what let the app install them on every open rather than once.
		for (String statement : OutboxTriggers.statements()) {
			execute(statement);
		}

		assertEquals(DeviceTables.inDependencyOrder().size() * 3,
				count("SELECT count(*) FROM sqlite_master WHERE type = 'trigger' "
						+ "AND name LIKE 'outbox_%'"));

		execute("INSERT INTO students (studentID, name, pronouns, email) "
				+ "VALUES (2005, 'V VORA', 'they/them', NULL)");

		assertEquals(1, count("SELECT count(*) FROM outbox"),
				"re-installing must not double the intents either");
	}

	@Test
	void droppingTheTriggersLeavesTheTablesAlone() throws Exception {

		for (String statement : OutboxTriggers.dropStatements()) {
			execute(statement);
		}

		execute("INSERT INTO students (studentID, name, pronouns, email) "
				+ "VALUES (2006, 'W WADIA', 'he/him', NULL)");

		assertEquals(0, count("SELECT count(*) FROM outbox"));
		assertEquals(1, count("SELECT count(*) FROM students WHERE studentID = 2006"),
				"the row itself still lands: dropping the triggers is for a restore, which "
						+ "replaces the file and must not announce every row in it");
	}

	// ── Fixture ──────────────────────────────────────────────────────────

	/**
	 * A session, a student, a course and one lecture, seeded through {@link DeviceBackup}'s own
	 * methods rather than by hand: the column names are the device's, and writing them twice is
	 * how a test comes to pass against a schema that is not the app's.
	 */
	private void seedALecture() throws SQLException {

		device.session(2025, LocalDate.of(2025, 6, 1), LocalDate.of(2026, 5, 31), ZONE);
		device.student(2001, "R RAO", "she/her", null);
		device.studentYear(2001, 2025, "CS-FY-001", "FY", "BSc_unaided", "COMPUTER_SCIENCE",
				null, null, 1);
		device.course(3001, 1001, "CS-101", "Programming", "BSc_unaided", "COMPUTER_SCIENCE",
				"FY", 1, "MAJOR");
		device.lecture(4001, 3001, null,
				ZonedDateTime.of(2025, 7, 14, 9, 0, 0, 0, ZONE),
				ZonedDateTime.of(2025, 7, 14, 10, 0, 0, 0, ZONE), null);
	}

	private void execute(String sql) throws SQLException {

		try (Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	private long count(String sql) throws SQLException {

		try (Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(sql)) {

			assertTrue(results.next(), () -> "no row from: " + sql);
			return results.getLong(1);
		}
	}

	private String text(String sql) throws SQLException {

		try (Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(sql)) {

			assertTrue(results.next(), () -> "no row from: " + sql);
			return results.getString(1);
		}
	}

	/** Kept so an unused-import warning cannot hide a list that stopped being checked. */
	@Test
	void thereIsATriggerListAtAll() {

		List<String> names = OutboxTriggers.names();

		assertEquals(DeviceTables.inDependencyOrder().size() * 3, names.size());
		assertTrue(names.contains("outbox_attendance_insert"), names.toString());
	}
}
