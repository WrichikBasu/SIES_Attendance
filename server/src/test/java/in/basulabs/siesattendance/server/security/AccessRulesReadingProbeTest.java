package in.basulabs.siesattendance.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;

import in.basulabs.siesattendance.server.testing.TestPostgres;

/**
 * Probes for findings about the access rules that had only been read. Each test states the
 * behaviour that would be right, or the stricter of two readings where the review said the
 * rule needs a decision, so a failure here is the finding confirmed. Numbers are the
 * review's.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccessRulesReadingProbeTest {

	private static TestPostgres postgres;

	private long profCs, coInstructor, headCs, cr;

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
				INSERT INTO academic_years (academic_year, start_date, end_date) VALUES
				    (2025, '2025-06-01', '2026-05-31');
				INSERT INTO professors (prof_id, name, email, department, designation) VALUES
				    (1001, 'A CS',   'a.cs@sies.edu.in',   'COMPUTER_SCIENCE', 'Assistant Professor'),
				    (1002, 'B CHEM', 'b.chem@sies.edu.in', 'CHEMISTRY',        'Assistant Professor'),
				    (1003, 'C HEAD', 'c.head@sies.edu.in', 'COMPUTER_SCIENCE', 'Professor'),
				    (1004, 'D CS',   'd.cs@sies.edu.in',   'COMPUTER_SCIENCE', 'Assistant Professor');
				INSERT INTO students (student_id, name, pronouns, email) VALUES
				    (2001, 'R RAO',  'she/her',   'r.rao@sies.edu.in'),
				    (2002, 'S SHAH', 'they/them', 's.shah@sies.edu.in'),
				    (2003, 'T TEN',  'they/them', NULL);
				INSERT INTO student_year
				    (student_id, academic_year, roll_no, year, stream, major_dept, minor_dept,
				     semester) VALUES
				    (2001, 2025, 'CS-FY-001', 'FY', 'BSc_unaided', 'COMPUTER_SCIENCE', NULL, 1),
				    (2002, 2025, 'CH-FY-001', 'FY', 'BSc',         'CHEMISTRY',        NULL, 1),
				    (2003, 2025, 'CS-FY-003', 'FY', 'BSc_unaided', 'COMPUTER_SCIENCE',
				     'CHEMISTRY', 1);
				INSERT INTO courses
				    (course_id, prof_id, course_code, course_topic, stream,
				     offering_department, year, semester, course_type) VALUES
				    (3001, 1001, 'CS-101', 'Programming', 'BSc_unaided',
				     'COMPUTER_SCIENCE', 'FY', 1, 'MAJOR'),
				    (3002, 1002, 'CH-101', 'Inorganic',   'BSc',
				     'CHEMISTRY',        'FY', 1, 'MINOR'),
				    (3003, 1001, 'CS-102', 'Unused',      'BSc_unaided',
				     'COMPUTER_SCIENCE', 'FY', 1, 'MAJOR');
				INSERT INTO course_instructor (course_id, prof_id) VALUES
				    (3001, 1001), (3002, 1002), (3003, 1001), (3001, 1004), (3003, 1004)
				    ON CONFLICT DO NOTHING;
				INSERT INTO batch_desc (batch_id, batch_name, academic_year, department) VALUES
				    (5901, 'FY CS',   2025, 'COMPUTER_SCIENCE'),
				    (5902, 'FY CHEM', 2025, 'CHEMISTRY');
				INSERT INTO batch_student_map (batch_id, student_id, academic_year) VALUES
				    (5901, 2001, 2025), (5901, 2003, 2025),
				    (5902, 2002, 2025), (5902, 2003, 2025);
				INSERT INTO course_batch_map (course_id, batch_id) VALUES
				    (3001, 5901), (3002, 5902), (3003, 5901);
				INSERT INTO lectures
				    (lecture_id, course_id, batch_id, start_at, start_zone, end_at, end_zone) VALUES
				    (4001, 3001, 5901, '2025-07-14 09:00:00+05:30', 'Asia/Kolkata',
				                       '2025-07-14 10:00:00+05:30', 'Asia/Kolkata'),
				    (4002, 3002, 5902, '2025-07-14 11:00:00+05:30', 'Asia/Kolkata',
				                       '2025-07-14 12:00:00+05:30', 'Asia/Kolkata');
				INSERT INTO attendance (lecture_id, student_id, present) VALUES
				    (4001, 2001, true), (4001, 2003, false),
				    (4002, 2002, true), (4002, 2003, true);
				""");

		profCs = createAccount("PROFESSOR", 1001L, null, "a.cs@sies.edu.in");
		coInstructor = createAccount("PROFESSOR", 1004L, null, "d.cs@sies.edu.in");
		headCs = createAccount("PROFESSOR", 1003L, null, "c.head@sies.edu.in");
		cr = createAccount("STUDENT", null, 2001L, "r.rao@sies.edu.in");
		long admin = createAccount("STAFF", null, null, "admin@sies.edu.in");

		grantRole(admin, "ADMIN", null, null, admin);
		grantRole(headCs, "HOD", "COMPUTER_SCIENCE", 2025, admin);

		postgres.asSuperuser("INSERT INTO attendance_delegate (student_id, course_id, "
				+ "academic_year, granted_by, granted_as) VALUES (2001, 3001, 2025, "
				+ profCs + ", 'INSTRUCTOR')");
	}

	// ── R22 ──────────────────────────────────────────────────────────────

	@Test
	void r22_aRepresentativeCannotRewriteAMarkTheProfessorGaveSomebodyElse() throws Exception {
		assertEquals(0, attempt(cr, null, 2001L,
						"UPDATE attendance SET present = true WHERE lecture_id = 4001 "
								+ "AND student_id = 2003"),
				"the representative turned a classmate's absence, recorded by the professor, "
						+ "into a presence");
	}

	// ── R23 ──────────────────────────────────────────────────────────────

	@Test
	void r23_aCoInstructorCannotMakeThemselvesTheCoordinator() throws Exception {
		assertEquals(0, attempt(coInstructor, 1004L, null,
						"UPDATE courses SET prof_id = 1004 WHERE course_id = 3001"),
				"an instructor who is not the coordinator took the course over");
	}

	@Test
	void r23_aCoInstructorCannotDeleteTheCourse() throws Exception {
		// A course with no lectures, so only the rule stands in the way.
		assertEquals(0, attempt(coInstructor, 1004L, null,
						"DELETE FROM course_batch_map WHERE course_id = 3003; "
								+ "DELETE FROM course_instructor WHERE course_id = 3003; "
								+ "DELETE FROM courses WHERE course_id = 3003"),
				"an instructor who is not the coordinator deleted the course");
		assertEquals(1, count("SELECT count(*) FROM courses WHERE course_id = 3003"));
	}

	@Test
	void r23_aHeadCannotPutTheirStudentInAnotherDepartmentsBatch() throws Exception {
		assertEquals(0, attempt(headCs, 1003L, null,
						"INSERT INTO batch_student_map (batch_id, student_id, academic_year) "
								+ "VALUES (5902, 2001, 2025)"),
				"the head of computer science added their student to chemistry's batch");
	}

	// ── R24 ──────────────────────────────────────────────────────────────

	@Test
	void r24_anAccountCannotUndoTheRevocationOfItsOwnDevice() throws Exception {

		postgres.asSuperuser("INSERT INTO device_registration (install_id, label, "
				+ "registered_by, revoked_at, revoked_by, reason) VALUES "
				+ "('11111111-2222-3333-4444-555555555555', 'lost phone', " + profCs
				+ ", now(), " + profCs + ", 'lost')");

		attempt(profCs, 1001L, null, "UPDATE device_registration SET revoked_at = NULL, "
				+ "revoked_by = NULL, reason = NULL WHERE registered_by = " + profCs);

		assertEquals(1, count("SELECT count(*) FROM device_registration "
						+ "WHERE revoked_at IS NOT NULL"),
				"the account cleared the revocation of its own device with a plain UPDATE");
	}

	// ── R15, R27 ─────────────────────────────────────────────────────────

	@Test
	void r15_twoLecturesOfOneClassCannotStartTogether() throws Exception {
		assertEquals(0, attempt(profCs, 1001L, null,
						"INSERT INTO lectures (lecture_id, course_id, batch_id, start_at, "
								+ "start_zone, end_at, end_zone) VALUES (4099, 3001, 5901, "
								+ "'2025-07-14 09:00:00+05:30', 'Asia/Kolkata', "
								+ "'2025-07-14 10:30:00+05:30', 'Asia/Kolkata')"),
				"a second lecture for the same course and batch, starting at the same moment "
						+ "and ending half an hour later, was accepted");
	}

	@Test
	void r27_aLectureCannotBeFiledAgainstABatchTheCourseDoesNotTake() throws Exception {
		assertEquals(0, attempt(profCs, 1001L, null,
						"INSERT INTO lectures (lecture_id, course_id, batch_id, start_at, "
								+ "start_zone, end_at, end_zone) VALUES (4098, 3001, 5902, "
								+ "'2025-07-15 09:00:00+05:30', 'Asia/Kolkata', "
								+ "'2025-07-15 10:00:00+05:30', 'Asia/Kolkata')"),
				"a CS-101 lecture was filed against FY CHEM, a batch not linked to CS-101");
	}

	// ── R28 ──────────────────────────────────────────────────────────────

	@Test
	void r28_deletingAStudentEitherRemovesThemOrLeavesTheirMarks() throws Exception {

		// What a phone sends when the head deletes 2003, a CS student with a chemistry
		// minor: their marks, memberships, enrolments and record, each row on its own, each
		// applied or refused on its own. The chemistry mark is not the head's to delete.
		String[] steps = {
				"DELETE FROM attendance WHERE lecture_id = 4001 AND student_id = 2003",
				"DELETE FROM attendance WHERE lecture_id = 4002 AND student_id = 2003",
				"DELETE FROM batch_student_map WHERE student_id = 2003 AND batch_id = 5901",
				"DELETE FROM batch_student_map WHERE student_id = 2003 AND batch_id = 5902",
				"DELETE FROM student_year WHERE student_id = 2003",
				"DELETE FROM students WHERE student_id = 2003"};

		StringBuilder trace = new StringBuilder();

		try (Connection as = postgres.actingAs(headCs, 1003L, null)) {
			for (String step : steps) {
				Savepoint before = as.setSavepoint();
				try (Statement statement = as.createStatement()) {
					trace.append(statement.executeUpdate(step)).append(' ');
				} catch (SQLException refused) {
					as.rollback(before);
					trace.append("X ");
				}
			}
			as.commit();
		}

		long student = count("SELECT count(*) FROM students WHERE student_id = 2003");
		long csMark = count("SELECT count(*) FROM attendance WHERE lecture_id = 4001 "
				+ "AND student_id = 2003");

		assertTrue(student == 0 || csMark == 1,
				"the student is still on the server and their computer science mark is gone "
						+ "(rows changed per step, X = refused: " + trace.toString().trim()
						+ ")");
	}

	// ── Helpers ──────────────────────────────────────────────────────────

	/** Rows changed by the statements, or 0 when the database refuses them. Committed. */
	private int attempt(long account, Long prof, Long student, String sql) throws SQLException {

		try (Connection as = postgres.actingAs(account, prof, student);
		     Statement statement = as.createStatement()) {
			int changed = 0;
			try {
				for (String one : sql.split(";")) {
					changed += statement.executeUpdate(one);
				}
				as.commit();
			} catch (SQLException refused) {
				as.rollback();
				return 0;
			}
			return changed;
		}
	}

	private long count(String sql) throws SQLException {
		try (Connection connection = postgres.connection();
		     Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(sql)) {
			if (!results.next()) {
				fail("no row from: " + sql);
			}
			return results.getLong(1);
		}
	}

	private long createAccount(String kind, Long prof, Long student, String email)
			throws SQLException {
		try (Connection connection = postgres.connection();
		     PreparedStatement statement = connection.prepareStatement(
				     "INSERT INTO app_account (kind, prof_id, student_id, display_name, "
						     + "login_email, password_hash) "
						     + "VALUES (?::account_kind, ?, ?, ?, ?, 'not-a-real-hash') "
						     + "RETURNING account_id")) {
			statement.setString(1, kind);
			statement.setObject(2, prof, java.sql.Types.BIGINT);
			statement.setObject(3, student, java.sql.Types.BIGINT);
			statement.setString(4, email);
			statement.setString(5, email);
			try (ResultSet results = statement.executeQuery()) {
				results.next();
				return results.getLong(1);
			}
		}
	}

	private void grantRole(long account, String role, String department, Integer session,
	                       long grantedBy) throws SQLException {
		try (Connection connection = postgres.connection();
		     PreparedStatement statement = connection.prepareStatement(
				     "INSERT INTO user_role (account_id, role, department, academic_year, "
						     + "granted_by) VALUES (?, ?::app_role, ?::department, ?, ?)")) {
			statement.setLong(1, account);
			statement.setString(2, role);
			statement.setString(3, department);
			statement.setObject(4, session, java.sql.Types.INTEGER);
			statement.setLong(5, grantedBy);
			statement.execute();
		}
	}
}
