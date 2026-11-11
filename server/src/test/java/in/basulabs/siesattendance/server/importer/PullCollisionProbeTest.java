package in.basulabs.siesattendance.server.importer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import in.basulabs.siesattendance.backend.StoredTimes;
import in.basulabs.siesattendance.backend.sync.DeviceDerived;
import in.basulabs.siesattendance.backend.sync.DeviceSql;
import in.basulabs.siesattendance.backend.sync.DeviceTables;
import in.basulabs.siesattendance.backend.sync.OutboxTriggers;

/**
 * White-box probes of what a pulled page does to a phone that already holds something.
 * <p>
 * {@link PullApplyTest} asks whether a row the server sends lands in an empty device. These ask
 * the next question: whether it lands in a device that has rows of its own, which is every device
 * after the first lecture is taken on it.
 * <p>
 * The statements are the ones {@code OutboxDrain.apply} runs ({@link DeviceSql#updateRow} then
 * {@link DeviceSql#insertRow}), against the real schema with foreign keys and the outbox triggers
 * on. What stands in for Android is sqlite-jdbc, as in {@link PullApplyTest}; the order of the
 * steps is copied from {@code OutboxDrain} and {@code SyncWorker} and is the part to check
 * against them.
 * <p>
 * Every test asserts the behaviour the phone should have. The ones that fail today are marked
 * BUG in their notes.
 */
class PullCollisionProbeTest {

	private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

	private static final long PROF = 1001;
	private static final long STUDENT = 2001;
	private static final long COURSE = 3001;
	private static final long BATCH = 5001;
	private static final int SESSION = 2025;

	/** Two ids for one thing: what two phones each issue when they both record it offline. */
	private static final long MINE = 4_000_001;
	private static final long THEIRS = 4_000_002;

	@TempDir
	Path work;

	private DeviceBackup device;

	private Connection connection;

	@BeforeEach
	void buildDeviceDatabase() throws Exception {

		device = DeviceBackup.emptyAtVersion6(work.resolve("devices"));
		device.migrateToCurrent(ZONE);
		connection = device.connection();

		execute("PRAGMA foreign_keys = ON");

		// What a first download leaves on the phone: a session, a professor, a student with an
		// enrolment, and a course.
		applyRow("academic_years", row("academicYear", (long) SESSION,
				"startDate", LocalDate.of(2025, 6, 1).toEpochDay(),
				"endDate", LocalDate.of(2026, 5, 31).toEpochDay()));
		applyRow("professors", row("profID", PROF, "name", "A CS",
				"email", "a.cs@sies.edu.in", "department", "COMPUTER_SCIENCE",
				"designation", "Assistant Professor"));
		applyRow("students", row("studentID", STUDENT, "name", "R RAO",
				"pronouns", "she/her", "email", "r.rao@sies.edu.in", "apaarId", null));
		applyRow("student_year", enrolment(STUDENT, "CSFY001"));
		applyRow("courses", row("courseID", COURSE, "profID", PROF, "courseCode", "CS101",
				"courseTopic", "Programming", "stream", "BSc_unaided",
				"offeringDepartment", "COMPUTER_SCIENCE", "year", "FY", "semester", 1L,
				"courseType", "MAJOR", "remarks", null, "dateIntroduced", null,
				"dateDiscontinued", null));

		applyRow("batch_desc", row("batchID", BATCH, "batchName", "FY CS A",
				"batchDesc", null, "academicYear", (long) SESSION,
				"department", "COMPUTER_SCIENCE"));

		// The phone's own triggers, live, as Room's callback installs them.
		for (String trigger : OutboxTriggers.statements()) {
			execute(trigger);
		}
	}

	@AfterEach
	void close() throws Exception {
		if (device != null) {
			device.close();
		}
	}

	// ── A row the server sends that collides with one this phone made ────

	/**
	 * BUG: the phone has recorded a lecture, and another phone recorded the same slot first.
	 * The server kept the other one (see
	 * {@code SyncConvergenceProbeTest.theSecondPhoneToRecordASlotIsToldSomethingFinal}) and the
	 * next pull brings it here. It has a different {@code lectureID}, so the update matches
	 * nothing, and the insert meets the device's own unique index on
	 * {@code (courseID, batchID, startDateTime, endDateTime)}.
	 * <p>
	 * {@code OutboxDrain.apply} catches a constraint failure only around a delete. This one
	 * leaves the transaction, so the page is not applied and the watermark does not move; the
	 * next pull fetches the same page and fails the same way. {@code PullApplyTest}'s header
	 * calls this "the worst shape a sync bug can have", and it is the same shape: the phone
	 * says "Could not sync" from then on and receives nothing more.
	 * <p>
	 * A fix has to let the page through: either the phone adopts the server's row for the slot
	 * (re-pointing its own marks at it and dropping its queued lecture), or it sets the row
	 * aside and carries on with the rest of the page.
	 */
	@Test
	void aLectureAnotherPhoneRecordedForTheSameSlotCanBeApplied() throws Exception {

		// Against a batch, as every lecture has been since a roll became its batches (V41).
		// A whole-class lecture does not collide here at all: SQLite counts two NULL batches
		// as different, so the phone would hold both and show the class twice.
		execute("INSERT INTO lectures (lectureID, courseID, batchID, startDateTime, "
				+ "endDateTime, notes) VALUES (" + MINE + ", " + COURSE + ", " + BATCH + ", '"
				+ at(9) + "', '" + at(10) + "', NULL)");
		execute("INSERT INTO attendance (lectureID, studentID, present) VALUES ("
				+ MINE + ", " + STUDENT + ", 1)");

		Map<String, Object> incoming = row("lectureID", THEIRS, "courseID", COURSE,
				"batchID", BATCH, "startDateTime", at(9), "endDateTime", at(10), "notes", null);

		assertDoesNotThrow(() -> applyPulled("lectures", incoming),
				"the page holding the other phone's lecture cannot be applied, so this phone "
						+ "never moves its watermark again");
	}

	/**
	 * BUG: the same collision one table up, and a likelier one. A professor adds a student on
	 * the phone; the office loads the roster in the meantime, and the same roll number now
	 * belongs to a student row with a different id. The pull brings that enrolment and the
	 * device's unique index on {@code (rollNo, academicYear)} refuses it.
	 */
	@Test
	void anEnrolmentTheOfficeLoadedUnderARollThisPhoneAlsoUsedCanBeApplied() throws Exception {

		long addedHere = 2_000_777;
		long loadedByTheOffice = 2_000_888;

		execute("INSERT INTO students (studentID, name, pronouns, email, apaarId) VALUES ("
				+ addedHere + ", 'S IYER', '', NULL, NULL)");
		applyRowQuietly("student_year", enrolment(addedHere, "CSFY002"));

		applyPulled("students", row("studentID", loadedByTheOffice, "name", "S IYER",
				"pronouns", "", "email", "s.iyer@sies.edu.in", "apaarId", null));

		assertDoesNotThrow(
				() -> applyPulled("student_year", enrolment(loadedByTheOffice, "CSFY002")),
				"the page holding the office's enrolment for that roll number cannot be "
						+ "applied, so this phone never moves its watermark again");
	}

	/** The same again for a course code two professors both typed in. */
	@Test
	void aCourseAnotherPhoneAddedUnderTheSameCodeCanBeApplied() throws Exception {

		execute("INSERT INTO courses (courseID, profID, courseCode, courseTopic, stream, "
				+ "offeringDepartment, year, semester, courseType) VALUES (3000777, " + PROF
				+ ", 'CS202', 'Data Structures', 'BSc_unaided', 'COMPUTER_SCIENCE', 'SY', 3, "
				+ "'MAJOR')");

		Map<String, Object> incoming = row("courseID", 3_000_888L, "profID", PROF,
				"courseCode", "CS202", "courseTopic", "Data structures", "stream", "BSc_unaided",
				"offeringDepartment", "COMPUTER_SCIENCE", "year", "SY", "semester", 3L,
				"courseType", "MAJOR", "remarks", null, "dateIntroduced", null,
				"dateDiscontinued", null);

		assertDoesNotThrow(() -> applyPulled("courses", incoming),
				"the page holding the other phone's course cannot be applied, so this phone "
						+ "never moves its watermark again");
	}

	// ── A row the server sends for one the user has just changed ─────────

	/**
	 * BUG: the feed sends a phone its own push back (pinned by
	 * {@code SyncConvergenceProbeTest.aPhonesOwnPushComesBackToItOnTheNextPull}), and
	 * {@code SyncWorker} pulls straight after it pushes. A mark the user changes again between
	 * those two requests is queued by the trigger -- and then overwritten by the echo of the
	 * value just sent, because {@code OutboxDrain.apply} writes a pulled row without asking
	 * whether the outbox still holds an intent for it. ({@code OutboxDrain.sweep} does ask;
	 * {@code apply} does not.)
	 * <p>
	 * An intent carries a key, not values, so the next push reads the row as the echo left it
	 * and sends the old value a second time. The correction is gone from the phone and never
	 * reaches the server, and nothing says so.
	 * <p>
	 * The steps below are {@code OutboxDrain.push}, a local edit, {@code OutboxDrain.pull} and
	 * {@code OutboxDrain.push} again, in that order.
	 */
	@Test
	void anEchoDoesNotUndoAnEditMadeSinceThePush() throws Exception {

		execute("INSERT INTO lectures (lectureID, courseID, batchID, startDateTime, "
				+ "endDateTime, notes) VALUES (" + MINE + ", " + COURSE + ", NULL, '"
				+ at(9) + "', '" + at(10) + "', NULL)");
		execute("INSERT INTO attendance (lectureID, studentID, present) VALUES ("
				+ MINE + ", " + STUDENT + ", 1)");

		DeviceTables.Table marks = DeviceTables.of("attendance");

		// push(): read each queued row, send it, and delete what was accepted by its seq.
		Map<String, Object> sent = readForPush(marks, List.of(MINE, STUDENT));
		assertEquals(1L, sent.get("present"));
		execute("DELETE FROM outbox");

		// The user corrects the mark while the pull is still on the wire.
		execute("UPDATE attendance SET present = 0 WHERE lectureID = " + MINE
				+ " AND studentID = " + STUDENT);
		assertEquals(1, count("SELECT count(*) FROM outbox WHERE tableName = 'attendance'"),
				"the correction is queued");

		// pull(): the echo of what was just pushed, applied under the flag.
		execute("UPDATE sync_state SET applying = 1 WHERE id = 1");
		applyRow("attendance", sent);
		execute("UPDATE sync_state SET applying = 0 WHERE id = 1");

		// push() again: the intent is still there, and the row is read as it now stands.
		assertEquals(1, count("SELECT count(*) FROM outbox WHERE tableName = 'attendance'"));

		assertEquals(0L, readForPush(marks, List.of(MINE, STUDENT)).get("present"),
				"the correction (absent) was overwritten by the echo of the first save "
						+ "(present); the phone now shows and sends the old mark");
	}

	// ── The device's own statements ──────────────────────────────────────

	/** A pulled row, applied the way a pull applies it: with the outbox triggers held off. */
	private void applyPulled(String table, Map<String, Object> row) throws SQLException {

		execute("UPDATE sync_state SET applying = 1 WHERE id = 1");

		try {
			applyRow(table, row);
		} finally {
			execute("UPDATE sync_state SET applying = 0 WHERE id = 1");
		}
	}

	/** A local write that should not leave anything in the queue this test then counts. */
	private void applyRowQuietly(String table, Map<String, Object> row) throws SQLException {
		applyPulled(table, row);
	}

	/** Exactly the pair {@code OutboxDrain.apply} runs: change it where it is, else insert. */
	private void applyRow(String name, Map<String, Object> incoming) throws SQLException {

		DeviceTables.Table table = DeviceTables.of(name);
		Map<String, Object> row = DeviceDerived.fill(table, incoming, ZONE);

		String update = DeviceSql.updateRow(table);

		if (update != null && run(update, DeviceSql.updateColumns(table), row) > 0) {
			return;
		}

		run(DeviceSql.insertRow(table), DeviceSql.insertColumns(table), row);
	}

	private int run(String sql, List<DeviceTables.Column> columns, Map<String, Object> row)
			throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(sql)) {

			for (int at = 0; at < columns.size(); at++) {
				statement.setObject(at + 1, row.get(columns.get(at).room()));
			}
			return statement.executeUpdate();
		}
	}

	/** What {@code OutboxDrain.readRow} hands to the push for one queued key. */
	private Map<String, Object> readForPush(DeviceTables.Table table, List<Long> key)
			throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				DeviceSql.selectRow(table))) {

			for (int at = 0; at < key.size(); at++) {
				statement.setLong(at + 1, key.get(at));
			}

			try (ResultSet results = statement.executeQuery()) {

				Map<String, Object> row = new LinkedHashMap<>();
				results.next();

				for (DeviceTables.Column column : table.carriedColumns()) {
					Object value = DeviceSql.readsAsText(column)
							? results.getString(column.room())
							: (Object) results.getLong(column.room());
					row.put(column.room(), results.wasNull() ? null : value);
				}
				return row;
			}
		}
	}

	private static Map<String, Object> enrolment(long student, String roll) {
		return row("studentID", student, "academicYear", (long) SESSION, "rollNo", roll,
				"year", "FY", "stream", "BSc_unaided", "majorDept", "COMPUTER_SCIENCE",
				"minorDept", null, "oeDept", null, "semester", 1L, "division", null,
				"joinDate", null, "leaveDate", null);
	}

	private static Map<String, Object> row(Object... pairs) {
		Map<String, Object> row = new LinkedHashMap<>();
		for (int at = 0; at < pairs.length; at += 2) {
			row.put(String.valueOf(pairs[at]), pairs[at + 1]);
		}
		return row;
	}

	private static String at(int hour) {
		return StoredTimes.format(ZonedDateTime.of(2025, 7, 14, hour, 0, 0, 0, ZONE));
	}

	private long count(String sql) throws SQLException {
		try (Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(sql)) {
			results.next();
			return results.getLong(1);
		}
	}

	private void execute(String sql) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}
}
