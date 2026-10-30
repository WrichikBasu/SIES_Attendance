package in.basulabs.siesattendance.server.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import in.basulabs.siesattendance.backend.AuditAction;
import in.basulabs.siesattendance.server.security.ActorSession;
import in.basulabs.siesattendance.server.security.AuditLog;
import in.basulabs.siesattendance.server.testing.TestPostgres;

/**
 * Phase 5's gate: that marking a sheet, editing a lecture and deleting a batch each leave a
 * complete, readable before-and-after record, and that the application role cannot alter one.
 * <p>
 * The second half is the part that makes the first half worth anything, so it is tested as the
 * constrained role rather than as the superuser. A log the application can rewrite is not
 * evidence, and the only way to know it cannot is to try.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditLogTest {

	private static final int SESSION = 2025;

	private static final long PROF = 1001;
	private static final long STUDENT = 2001;
	private static final long COURSE = 3001;
	private static final long BATCH = 4001;
	private static final long LECTURE = 5001;

	private static TestPostgres postgres;

	private long account;

	@BeforeAll
	void startDatabase() throws Exception {
		assumeTrue(TestPostgres.isAvailable(), "Docker is not available");
		postgres = TestPostgres.start();
	}

	@AfterAll
	void stopDatabase() {
		if (postgres != null) {
			postgres.close();
		}
	}

	@BeforeEach
	void buildFixture() throws Exception {

		assumeTrue(postgres != null, "no database");
		postgres.reset();

		postgres.asSuperuser("""
				INSERT INTO academic_years (academic_year, start_date, end_date)
				    VALUES (2025, '2025-06-01', '2026-05-31');

				INSERT INTO professors (prof_id, name, email, department, designation)
				    VALUES (1001, 'A RAO', 'a.rao@sies.edu.in', 'COMPUTER_SCIENCE', 'Professor');

				INSERT INTO students (student_id, name, pronouns, email)
				    VALUES (2001, 'R RAO', 'she/her', 'r.rao@sies.edu.in');

				INSERT INTO student_year
				    (student_id, academic_year, roll_no, year, stream, major_dept, semester)
				    VALUES (2001, 2025, 'CS-FY-001', 'FY', 'BSc_unaided',
				            'COMPUTER_SCIENCE', 1);

				INSERT INTO courses
				    (course_id, prof_id, course_code, course_topic, stream,
				     offering_department, year, semester, course_type)
				    VALUES (3001, 1001, 'CS-101', 'Programming', 'BSc_unaided',
				            'COMPUTER_SCIENCE', 'FY', 1, 'MAJOR');

				INSERT INTO course_instructor (course_id, prof_id) VALUES (3001, 1001)
				    ON CONFLICT DO NOTHING;

				INSERT INTO batch_desc (batch_id, batch_name, batch_desc, academic_year)
				    VALUES (4001, 'Batch A', 'the whole class', 2025);

				INSERT INTO lectures
				    (lecture_id, course_id, batch_id, start_at, start_zone, end_at, end_zone)
				    VALUES (5001, 3001, NULL, '2025-07-14 09:00:00+05:30', 'Asia/Kolkata',
				                              '2025-07-14 10:00:00+05:30', 'Asia/Kolkata');

				INSERT INTO app_account (kind, prof_id, display_name, login_email,
				                         password_hash)
				    VALUES ('PROFESSOR', 1001, 'A RAO', 'a.rao@sies.edu.in', 'not-a-real-hash');
				""");

		account = scalar("SELECT account_id FROM app_account LIMIT 1");

		// The fixture itself is audited, which is correct but noise for these tests. Clear it
		// so each one reads only what it caused. Possible here only because this connection
		// is the owner's; nothing in the service can do it.
		postgres.asSuperuser("TRUNCATE audit_log");
	}

	// ── The gate ─────────────────────────────────────────────────────────

	@Test
	void markingASheetIsRecordedWithTheActorAndTheRow() throws Exception {

		try (Connection as = actingAsProfessor()) {
			execute(as, "INSERT INTO attendance (lecture_id, student_id, present) "
					+ "VALUES (" + LECTURE + ", " + STUDENT + ", true)");
			as.commit();
		}

		Entry entry = lastEntry("attendance");

		assertEquals("INSERT", entry.action);
		assertEquals(account, entry.actorAccount);
		assertEquals("A RAO", entry.actorLabel);

		// The row is findable again without knowing the table's shape.
		assertTrue(entry.rowKey.contains("\"lecture_id\": " + LECTURE),
				"row_key should name the lecture: " + entry.rowKey);
		assertTrue(entry.rowKey.contains("\"student_id\": " + STUDENT),
				"row_key should name the student: " + entry.rowKey);

		assertNull(entry.beforeImage, "an INSERT has nothing before it");
		assertTrue(entry.afterImage.contains("\"present\": true"),
				"the after image should carry the mark: " + entry.afterImage);
	}

	@Test
	void editingALectureIsRecordedWithBothImages() throws Exception {

		try (Connection as = actingAsProfessor()) {
			execute(as, "UPDATE lectures SET notes = 'moved to the lab' "
					+ "WHERE lecture_id = " + LECTURE);
			as.commit();
		}

		Entry entry = lastEntry("lectures");

		assertEquals("UPDATE", entry.action);
		assertNotNull(entry.beforeImage);
		assertNotNull(entry.afterImage);

		// Readable, which is the word the gate uses: the old value and the new one are both
		// there, in one row, without having to reconstruct either from anything else.
		assertTrue(entry.beforeImage.contains("\"notes\": null"),
				"before: " + entry.beforeImage);
		assertTrue(entry.afterImage.contains("\"notes\": \"moved to the lab\""),
				"after: " + entry.afterImage);
	}

	@Test
	void deletingABatchIsRecordedWithWhatWasThere() throws Exception {

		try (Connection as = actingAsAdmin()) {
			execute(as, "DELETE FROM batch_desc WHERE batch_id = " + BATCH);
			as.commit();
		}

		Entry entry = lastEntry("batch_desc");

		assertEquals("DELETE", entry.action);
		assertNull(entry.afterImage, "a DELETE has nothing after it");
		assertTrue(entry.beforeImage.contains("\"batch_name\": \"Batch A\""),
				"the batch that went away should still be legible: " + entry.beforeImage);
		assertTrue(entry.beforeImage.contains("\"batch_desc\": \"the whole class\""),
				"including its description: " + entry.beforeImage);
	}

	@Test
	void theApplicationRoleCannotAlterOrRemoveAnEntry() throws Exception {

		try (Connection as = actingAsProfessor()) {
			execute(as, "INSERT INTO attendance (lecture_id, student_id, present) "
					+ "VALUES (" + LECTURE + ", " + STUDENT + ", true)");
			as.commit();
		}

		assertEquals(1, scalar("SELECT count(*) FROM audit_log"));

		// As the role the service connects as. Not merely filtered to nothing: refused, with
		// a privilege error, because the service holds no rights on this table whatsoever.
		try (Connection as = postgres.appConnection()) {

			assertThrows(SQLException.class, () -> execute(as,
					"UPDATE audit_log SET after_image = '{}'::jsonb"),
					"the service must not be able to rewrite an entry");

			assertThrows(SQLException.class, () -> execute(as,
					"DELETE FROM audit_log"),
					"the service must not be able to remove an entry");

			assertThrows(SQLException.class, () -> execute(as, "TRUNCATE audit_log"),
					"nor empty the table");

			// Not even read it. An audit log the application can read is a copy of every
			// change in the college available to whatever is holding its connection.
			assertThrows(SQLException.class, () -> execute(as,
					"SELECT count(*) FROM audit_log"),
					"the service has no SELECT either");

			// And the one way in does not let it name somebody else.
			assertThrows(SQLException.class, () -> execute(as,
					"INSERT INTO audit_log (actor_label, action) VALUES ('nobody', 'LOGIN')"),
					"not even INSERT: the only way in is audit_event()");
		}

		assertEquals(1, scalar("SELECT count(*) FROM audit_log"),
				"the entry should still be exactly as it was");
	}

	// ── What it records, and what it must not ────────────────────────────

	@Test
	void anUpdateThatChangedNothingIsNotAnEvent() throws Exception {

		try (Connection as = actingAsProfessor()) {
			// The same value it already had. The importer and the phone's merge both write
			// rows back unchanged as a matter of course; an entry each would bury the real
			// ones.
			execute(as, "UPDATE lectures SET notes = notes WHERE lecture_id = " + LECTURE);
			as.commit();
		}

		assertEquals(0, scalar("SELECT count(*) FROM audit_log WHERE table_name = 'lectures'"),
				"a no-op UPDATE should leave no entry");
	}

	@Test
	void aPasswordHashIsNeverWrittenToTheLog() throws Exception {

		// app_account is audited, because an account appearing or changing which person it
		// points at is a thing a dispute turns on. But the auditor can read this table, and an
		// unredacted log would hand them every hash the college has ever stored.
		postgres.asSuperuser("UPDATE app_account SET password_hash = 'a-brand-new-hash' "
				+ "WHERE account_id = " + account);

		Entry entry = lastEntry("app_account");

		assertEquals("UPDATE", entry.action);
		assertFalse(entry.beforeImage.contains("password_hash"),
				"the before image still names the column: " + entry.beforeImage);
		assertFalse(entry.afterImage.contains("a-brand-new-hash"),
				"the new hash reached the log: " + entry.afterImage);
		assertFalse(entry.beforeImage.contains("not-a-real-hash"),
				"the old hash reached the log: " + entry.beforeImage);

		// The rest of the row is still there, so redaction has not cost the entry its meaning.
		assertTrue(entry.afterImage.contains("\"login_email\""),
				"the entry should still be about an account: " + entry.afterImage);
	}

	@Test
	void anApplicationEventCannotNameSomebodyElse() throws Exception {

		try (Connection as = actingAsProfessor()) {

			AuditLog.record(as, AuditAction.EXPORT, "attendance", null,
					AuditLog.detail("course_id", COURSE, "rows", 42));
			as.commit();
		}

		Entry entry = lastEntry(null);

		assertEquals("EXPORT", entry.action);

		// audit_event() takes no actor; it reads one from the session. So the entry names the
		// professor whether or not the caller wanted it to.
		assertEquals(account, entry.actorAccount);
		assertEquals("A RAO", entry.actorLabel);
		assertTrue(entry.detail.contains("\"rows\": 42"), "detail: " + entry.detail);
	}

	@Test
	void aRowChangeCannotBeFakedThroughTheEventFunction() throws Exception {

		try (Connection as = actingAsProfessor()) {

			// Refused in Java, because an INSERT entry with no images describes a change
			// nobody can see.
			assertThrows(IllegalArgumentException.class, () ->
					AuditLog.record(as, AuditAction.INSERT, "attendance", null, null));

			// And refused again in SQL, for a caller that does not go through AuditLog.
			assertThrows(SQLException.class, () -> execute(as,
					"SELECT audit_event('DELETE'::audit_action, 'attendance', NULL, NULL)"));
		}
	}

	@Test
	void anUnauthenticatedChangeIsRecordedAsSystemRatherThanAsNobody() throws Exception {

		// A migration, or a psql session at midnight. The entry still has to exist, and
		// "system" is a truthful label where a null one would read as a missing entry.
		postgres.asSuperuser("UPDATE students SET pronouns = 'they/them' "
				+ "WHERE student_id = " + STUDENT);

		Entry entry = lastEntry("students");

		assertEquals("UPDATE", entry.action);
		assertEquals(0, entry.actorAccount, "no account was announced");
		assertEquals("system", entry.actorLabel);
	}

	@Test
	void entriesInOneTransactionAreDistinguishableInOrder() throws Exception {

		// now() is the transaction's start time, identical for every statement in it. Marking
		// a register is one transaction of sixty inserts, and with now() their order would be
		// unrecoverable. clock_timestamp() advances.
		try (Connection as = actingAsProfessor()) {
			execute(as, "INSERT INTO attendance (lecture_id, student_id, present) "
					+ "VALUES (" + LECTURE + ", " + STUDENT + ", true)");
			execute(as, "UPDATE attendance SET present = false "
					+ "WHERE lecture_id = " + LECTURE);
			as.commit();
		}

		assertEquals(2, scalar("SELECT count(*) FROM audit_log"));
		assertEquals(2, scalar("SELECT count(DISTINCT occurred_at) FROM audit_log"),
				"two entries in one transaction must not share an instant");

		// And they are in the order they happened.
		assertEquals("INSERT", text(
				"SELECT action FROM audit_log ORDER BY occurred_at, audit_id LIMIT 1"));
	}

	@Test
	void aDelegatedChangeRecordsTheGrantThatAllowedIt() throws Exception {

		// On the course's roll, which is its batch: nobody else can be its representative.
		postgres.asSuperuser("INSERT INTO batch_student_map (batch_id, student_id, academic_year) "
				+ "VALUES (4001, " + STUDENT + ", " + SESSION + "); "
				+ "INSERT INTO course_batch_map (course_id, batch_id) VALUES (" + COURSE
				+ ", 4001)");
		postgres.asSuperuser(
				"INSERT INTO attendance_delegate (student_id, course_id, academic_year, "
						+ "granted_by, granted_as) VALUES (" + STUDENT + ", " + COURSE + ", "
						+ SESSION + ", " + account + ", 'INSTRUCTOR')");
		postgres.asSuperuser("TRUNCATE audit_log");

		try (Connection as = postgres.appConnection()) {

			as.setAutoCommit(false);

			// A CR marking the register, under the grant. onDelegatedCourse is what puts the
			// authority in the entry, so "what allowed this" is answerable without guessing
			// from whatever roles the person holds by the time somebody asks.
			ActorSession.announce(as, new ActorSession.Actor(account, null, STUDENT,
					"R RAO", null, 41231, "127.0.0.1", "1.4.0", COURSE));

			execute(as, "INSERT INTO attendance (lecture_id, student_id, present) "
					+ "VALUES (" + LECTURE + ", " + STUDENT + ", true)");
			as.commit();
		}

		Entry entry = lastEntry("attendance");

		assertEquals(COURSE, entry.delegateCourse);
		assertEquals(41231, entry.deviceNode, "which phone");
		assertEquals("1.4.0", entry.appVersion);

		// The query the decision to let a CR edit their own attendance makes load-bearing.
		assertEquals(1, scalar("""
				SELECT count(*)
				FROM   audit_log a
				JOIN   app_account ac ON ac.account_id = a.actor_account
				WHERE  a.table_name = 'attendance'
				  AND  a.delegate_course IS NOT NULL
				  AND  (a.after_image ->> 'student_id')::bigint = %d
				""".formatted(STUDENT)),
				"a CR's mark under a grant must be findable in one query");
	}

	@Test
	void theAuditorReadsTheLogAndCannotTidyIt() throws Exception {

		try (Connection as = actingAsProfessor()) {
			execute(as, "INSERT INTO attendance (lecture_id, student_id, present) "
					+ "VALUES (" + LECTURE + ", " + STUDENT + ", true)");
			as.commit();
		}

		try (Connection as = postgres.auditorConnection()) {

			// Reading is the whole job, and the only role that can do it over a connection.
			try (Statement statement = as.createStatement();
			     ResultSet results = statement.executeQuery(
					     "SELECT count(*) FROM audit_log")) {

				assertTrue(results.next());
				assertEquals(1, results.getLong(1),
						"the auditor should see the entry the professor caused");
			}

			// An auditor who can tidy the record is not an auditor. V7 revokes everything
			// and grants SELECT back, by name.
			assertThrows(SQLException.class, () -> execute(as,
					"UPDATE audit_log SET actor_label = 'somebody else'"));
			assertThrows(SQLException.class, () -> execute(as, "DELETE FROM audit_log"));
			assertThrows(SQLException.class, () -> execute(as,
					"INSERT INTO audit_log (actor_label, action) VALUES ('me', 'LOGIN')"));
		}
	}

	@Test
	void theAuditorCannotChangeTheDataEither() throws Exception {

		// Read-only across the college, which is the other half of what the role means. Not a
		// policy question: there is simply no grant.
		try (Connection as = postgres.auditorConnection()) {
			assertThrows(SQLException.class, () -> execute(as,
					"UPDATE students SET name = 'EDITED' WHERE student_id = " + STUDENT));
			assertThrows(SQLException.class, () -> execute(as, "DELETE FROM lectures"));
		}
	}

	// ── The table itself ─────────────────────────────────────────────────

	@Test
	void everyMonthForTwoYearsHasAPartitionAndThereIsAnOverflow() throws Exception {

		assertEquals(24, scalar("""
				SELECT count(*) FROM pg_class c
				JOIN pg_inherits i ON i.inhrelid = c.oid
				JOIN pg_class p ON p.oid = i.inhparent
				WHERE p.relname = 'audit_log' AND c.relname ~ '^audit_log_[0-9]{4}_[0-9]{2}$'
				"""),
				"two years of monthly partitions should have been created");

		// The safety net. Without it an insert past the last month fails -- and because these
		// inserts come from triggers, that failure is "attendance cannot be marked", not "the
		// log is behind".
		assertEquals(1, scalar("""
				SELECT count(*) FROM pg_class c
				JOIN pg_inherits i ON i.inhrelid = c.oid
				JOIN pg_class p ON p.oid = i.inhparent
				WHERE p.relname = 'audit_log' AND c.relname = 'audit_log_overflow'
				"""));

		// An entry twenty years out still lands, in the overflow.
		postgres.asSuperuser("""
				INSERT INTO audit_log (occurred_at, actor_label, action)
				VALUES (now() + interval '20 years', 'the distant future', 'LOGIN')
				""");

		assertEquals(1, scalar("SELECT count(*) FROM audit_log_overflow"));
	}

	@Test
	void addingPartitionsTwiceIsHarmless() throws Exception {

		// So a scheduled job can simply run it rather than work out what is missing.
		assertEquals(0, scalar(
				"SELECT audit_log_add_months(date_trunc('month', now())::date, 12)"),
				"the first twelve months already exist, so nothing should be created");

		assertTrue(scalar(
				"SELECT audit_log_add_months(date_trunc('month', now() + interval '3 years')"
						+ "::date, 2)") == 2,
				"two new months should have been created");
	}

	// ── Helpers ──────────────────────────────────────────────────────────

	private Connection actingAsProfessor() throws SQLException {

		Connection connection = postgres.appConnection();
		connection.setAutoCommit(false);

		// prof_id, not just the account: actor_instructs() joins through it, and without it
		// no policy on attendance or lectures matches anything at all.
		ActorSession.announce(connection, new ActorSession.Actor(account, PROF, null,
				"A RAO", null, null, null, null, null));

		return connection;
	}

	/** The same account, granted ADMIN, for the deletions a professor's policy does not cover. */
	private Connection actingAsAdmin() throws SQLException {

		postgres.asSuperuser("INSERT INTO user_role (account_id, role, granted_by) "
				+ "VALUES (" + account + ", 'ADMIN', " + account + ") "
				+ "ON CONFLICT DO NOTHING");
		postgres.asSuperuser("DELETE FROM audit_log WHERE table_name = 'user_role'");

		return actingAsProfessor();
	}

	private static final class Entry {
		String action;
		long actorAccount;
		String actorLabel;
		long delegateCourse;
		int deviceNode;
		String appVersion;
		String rowKey;
		String beforeImage;
		String afterImage;
		String detail;
	}

	/** The newest entry, optionally for one table. Read as the owner, as an auditor would. */
	private Entry lastEntry(String table) throws SQLException {

		String sql = "SELECT action::text, coalesce(actor_account, 0) AS actor_account, "
				+ "actor_label, coalesce(delegate_course, 0) AS delegate_course, "
				+ "coalesce(device_node, -1) AS device_node, "
				+ "coalesce(app_version, '') AS app_version, "
				+ "row_key::text, before_image::text, after_image::text, detail::text "
				+ "FROM audit_log "
				+ (table == null ? "" : "WHERE table_name = '" + table + "' ")
				+ "ORDER BY occurred_at DESC, audit_id DESC LIMIT 1";

		try (Connection connection = postgres.connection();
		     Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(sql)) {

			assertTrue(results.next(), () -> "no audit entry"
					+ (table == null ? "" : " for " + table));

			Entry entry = new Entry();
			entry.action = results.getString("action");
			entry.actorAccount = results.getLong("actor_account");
			entry.actorLabel = results.getString("actor_label");
			entry.delegateCourse = results.getLong("delegate_course");
			entry.deviceNode = results.getInt("device_node");
			entry.appVersion = results.getString("app_version");
			entry.rowKey = results.getString("row_key");
			entry.beforeImage = results.getString("before_image");
			entry.afterImage = results.getString("after_image");
			entry.detail = results.getString("detail");
			return entry;
		}
	}

	private long scalar(String sql) throws SQLException {

		try (Connection connection = postgres.connection();
		     Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(sql)) {

			assertTrue(results.next(), () -> "no row from: " + sql);
			return results.getLong(1);
		}
	}

	private String text(String sql) throws SQLException {

		try (Connection connection = postgres.connection();
		     Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(sql)) {

			assertTrue(results.next(), () -> "no row from: " + sql);
			return results.getString(1);
		}
	}

	private static void execute(Connection connection, String sql) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}
}
