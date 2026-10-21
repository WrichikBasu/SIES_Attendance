package in.basulabs.siesattendance.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import java.sql.Statement;

import in.basulabs.siesattendance.server.testing.TestPostgres;

/**
 * Phase 4's gate: that the policies in {@code V6__row_level_security.sql} actually stop a read
 * the grants would have allowed.
 * <p>
 * Every test here connects as {@code sies_app_test} — not a superuser, not the owner of any
 * table, no {@code BYPASSRLS} — because those are the three ways to be exempt from row-level
 * security and a test that was any of them would pass against a database with no policies in it
 * at all. {@link TestPostgres#actingAs} sets the three actor settings exactly as the service
 * will, and the fixture is built as the superuser, which is the only thing here that sidesteps
 * the policies.
 * <p>
 * The queries are deliberately unfiltered. {@code SELECT count(*) FROM attendance} with no WHERE
 * clause is the shape of the mistake this phase exists to survive: a report written in a hurry,
 * an admin script at 11pm, a join somebody forgot to constrain. If the answer is still only the
 * caller's own rows, the policy is doing the work and not the query.
 *
 * @see TestPostgres for how the container and the application role are made
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RowLevelSecurityTest {

	/** 2025-26, the session almost everything below is in. */
	private static final int SESSION = 2025;

	/** The following session, for the test that a grant does not outlive its own. */
	private static final int NEXT_SESSION = 2026;

	// Fixture ids, fixed so a failure message names something recognisable.
	private static final long PROF_CS = 1001;
	private static final long PROF_CHEM = 1002;
	private static final long PROF_HEAD_CS = 1003;

	private static final long STUDENT_CR = 2001;
	private static final long STUDENT_OTHER = 2002;

	private static final long COURSE_CS = 3001;
	private static final long COURSE_CHEM = 3002;

	private static final long LECTURE_CS = 4001;
	private static final long LECTURE_CHEM = 4002;
	private static final long LECTURE_CS_NEXT = 4003;

	private static TestPostgres postgres;

	/** Account ids, assigned by the sequence, so read back after the fixture is built. */
	private long accountProfCs;
	private long accountProfChem;
	private long accountHeadCs;
	private long accountCr;
	private long accountOffice;
	private long accountAuditor;
	private long accountAdmin;

	@BeforeAll
	void startDatabase() throws Exception {

		assumeTrue(TestPostgres.isAvailable(),
				"Docker is not available, so the policies cannot be tested against a real "
						+ "PostgreSQL. tools/verify-rls.sh fails rather than skips.");

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

		// Two departments, two professors who each teach one course, a head of Computer
		// Science, and a student who is the CR of the Computer Science course only.
		postgres.asSuperuser("""
				INSERT INTO academic_years (academic_year, start_date, end_date) VALUES
				    (2025, '2025-06-01', '2026-05-31'),
				    (2026, '2026-06-01', '2027-05-31');

				INSERT INTO professors (prof_id, name, email, department, designation) VALUES
				    (1001, 'A CS',   'a.cs@sies.edu.in',   'COMPUTER_SCIENCE', 'Assistant Professor'),
				    (1002, 'B CHEM', 'b.chem@sies.edu.in', 'CHEMISTRY',        'Assistant Professor'),
				    (1003, 'C HEAD', 'c.head@sies.edu.in', 'COMPUTER_SCIENCE', 'Professor');

				INSERT INTO students (student_id, name, pronouns, email) VALUES
				    (2001, 'R RAO',  'she/her',   'r.rao@sies.edu.in'),
				    (2002, 'S SHAH', 'they/them', 's.shah@sies.edu.in');

				INSERT INTO student_year
				    (student_id, academic_year, roll_no, year, stream, major_dept, semester) VALUES
				    (2001, 2025, 'CS-FY-001', 'FY', 'BSc_unaided', 'COMPUTER_SCIENCE', 1),
				    (2002, 2025, 'CH-FY-001', 'FY', 'BSc',         'CHEMISTRY',        1),
				    (2001, 2026, 'CS-SY-001', 'SY', 'BSc_unaided', 'COMPUTER_SCIENCE', 3);

				INSERT INTO courses
				    (course_id, prof_id, course_code, course_topic, stream,
				     offering_department, year, semester, course_type) VALUES
				    (3001, 1001, 'CS-101', 'Programming', 'BSc_unaided',
				     'COMPUTER_SCIENCE', 'FY', 1, 'MAJOR'),
				    (3002, 1002, 'CH-101', 'Inorganic',   'BSc',
				     'CHEMISTRY',        'FY', 1, 'MAJOR');

				INSERT INTO course_instructor (course_id, prof_id) VALUES
				    (3001, 1001), (3002, 1002);

				INSERT INTO lectures
				    (lecture_id, course_id, batch_id, start_at, start_zone, end_at, end_zone) VALUES
				    (4001, 3001, NULL, '2025-07-14 09:00:00+05:30', 'Asia/Kolkata',
				                       '2025-07-14 10:00:00+05:30', 'Asia/Kolkata'),
				    (4002, 3002, NULL, '2025-07-14 11:00:00+05:30', 'Asia/Kolkata',
				                       '2025-07-14 12:00:00+05:30', 'Asia/Kolkata'),
				    (4003, 3001, NULL, '2026-07-14 09:00:00+05:30', 'Asia/Kolkata',
				                       '2026-07-14 10:00:00+05:30', 'Asia/Kolkata');

				INSERT INTO attendance (lecture_id, student_id, present) VALUES
				    (4001, 2001, true),
				    (4002, 2002, true),
				    (4003, 2001, false);
				""");

		accountProfCs = createAccount("PROFESSOR", PROF_CS, null, "a.cs@sies.edu.in");
		accountProfChem = createAccount("PROFESSOR", PROF_CHEM, null, "b.chem@sies.edu.in");
		accountHeadCs = createAccount("PROFESSOR", PROF_HEAD_CS, null, "c.head@sies.edu.in");
		accountCr = createAccount("STUDENT", null, STUDENT_CR, "r.rao@sies.edu.in");
		accountOffice = createAccount("STAFF", null, null, "office@sies.edu.in");
		accountAuditor = createAccount("STAFF", null, null, "auditor@sies.edu.in");
		accountAdmin = createAccount("STAFF", null, null, "admin@sies.edu.in");

		// The admin grants itself, which is how the first one has to come into being.
		grantRole(accountAdmin, "ADMIN", null, null, accountAdmin);
		grantRole(accountOffice, "OFFICE", null, null, accountAdmin);
		grantRole(accountAuditor, "AUDITOR", null, null, accountAdmin);
		grantRole(accountHeadCs, "HOD", "COMPUTER_SCIENCE", SESSION, accountAdmin);

		// The CR of CS-101 for 2025-26, and nothing else.
		postgres.asSuperuser(
				"INSERT INTO attendance_delegate (student_id, course_id, academic_year, "
						+ "granted_by, granted_as) VALUES ("
						+ STUDENT_CR + ", " + COURSE_CS + ", " + SESSION + ", "
						+ accountProfCs + ", 'INSTRUCTOR')");
	}

	// ── The gate ─────────────────────────────────────────────────────────

	@Test
	void aProfessorCannotReadAnotherDepartmentsAttendance() throws Exception {

		try (Connection as = postgres.actingAs(accountProfCs, PROF_CS, null)) {

			// Unfiltered on purpose: this is the query somebody writes by accident.
			// Two rows, not one: both are CS-101's, in two different sessions.
			// course_instructor names no session, so a professor keeps reach over their
			// own course's history -- which is what they need to answer a query about last
			// year's register. The boundary this test is about is the department.
			assertEquals(2, count(as, "SELECT count(*) FROM attendance"),
					"a professor should see their own course's attendance and no other");

			assertEquals(0, count(as,
					"SELECT count(*) FROM attendance WHERE lecture_id = " + LECTURE_CHEM),
					"Chemistry's attendance must be invisible to a Computer Science professor");

			// And through a join, which is the other way a report leaks.
			assertEquals(2, count(as,
					"SELECT count(*) FROM attendance a JOIN lectures l "
							+ "ON l.lecture_id = a.lecture_id"),
					"joining through lectures must not widen what is visible");
		}
	}

	@Test
	void aProfessorCannotWriteAnotherDepartmentsAttendance() throws Exception {

		try (Connection as = postgres.actingAs(accountProfCs, PROF_CS, null)) {

			// An UPDATE that matches no visible row changes nothing. It does not error,
			// which is the point worth knowing: RLS filters, it does not complain.
			assertEquals(0, update(as,
					"UPDATE attendance SET present = false WHERE lecture_id = " + LECTURE_CHEM),
					"a professor must not be able to edit another department's register");

			// Inserting into it is refused outright, because WITH CHECK is not a filter.
			assertThrows(SQLException.class, () -> execute(as,
					"INSERT INTO attendance (lecture_id, student_id, present) VALUES ("
							+ LECTURE_CHEM + ", " + STUDENT_CR + ", true)"),
					"a row-level security violation on INSERT must be an error, not a no-op");
		}
	}

	@Test
	void aCrCannotTouchACourseTheyHoldNoGrantFor() throws Exception {

		try (Connection as = postgres.actingAs(accountCr, null, STUDENT_CR)) {

			assertEquals(1, count(as, "SELECT count(*) FROM attendance"),
					"the CR of CS-101 should see exactly its one 2025-26 attendance row");

			assertEquals(0, count(as,
					"SELECT count(*) FROM lectures WHERE course_id = " + COURSE_CHEM),
					"a CR has no reach into a course they were not designated for");

			assertEquals(0, update(as,
					"UPDATE attendance SET present = false WHERE lecture_id = " + LECTURE_CHEM),
					"a CR must not be able to edit a course they hold no grant for");
		}
	}

	@Test
	void aCrsGrantDoesNotReachTheFollowingSession() throws Exception {

		// The same course, a lecture in 2026-27, and a grant that names 2025-26. Nobody has
		// revoked anything; the grant simply does not describe that register.
		try (Connection as = postgres.actingAs(accountCr, null, STUDENT_CR)) {

			assertEquals(0, count(as,
					"SELECT count(*) FROM lectures WHERE lecture_id = " + LECTURE_CS_NEXT),
					"a 2025-26 grant must not reach a 2026-27 lecture of the same course");

			assertEquals(0, update(as,
					"UPDATE attendance SET present = true WHERE lecture_id = "
							+ LECTURE_CS_NEXT),
					"last year's CR must not be able to amend this year's register");

			// And the grant still works for its own session, so the test above is about the
			// session and not about the grant being broken.
			assertEquals(1, update(as,
					"UPDATE attendance SET present = false WHERE lecture_id = " + LECTURE_CS),
					"the grant must still work for the session it names");
		}
	}

	// ── The rest of the policy surface ───────────────────────────────────

	@Test
	void anUnauthenticatedConnectionSeesNothing() throws Exception {

		// No actor set at all: current_account_id() is NULL, which matches no policy.
		try (Connection as = postgres.appConnection()) {
			assertEquals(0, count(as, "SELECT count(*) FROM attendance"));
			assertEquals(0, count(as, "SELECT count(*) FROM lectures"));
		}
	}

	@Test
	void aRevokedGrantStopsWorkingAtOnce() throws Exception {

		postgres.asSuperuser(
				"UPDATE attendance_delegate SET revoked_at = now(), revoked_by = "
						+ accountAdmin + " WHERE student_id = " + STUDENT_CR);

		try (Connection as = postgres.actingAs(accountCr, null, STUDENT_CR)) {
			assertEquals(0, count(as, "SELECT count(*) FROM attendance"),
					"revoking takes effect on the next statement, not the next login");
		}
	}

	@Test
	void anExpiredGrantStopsWorking() throws Exception {

		// granted_at moves back too. attendance_delegate_expiry_after_grant refuses a
		// grant that expired before it was issued, so a spent grant is a backdated one and
		// not merely one with a past expiry.
		postgres.asSuperuser(
				"UPDATE attendance_delegate SET granted_at = now() - interval '2 days', "
						+ "expires_at = now() - interval '1 day' "
						+ "WHERE student_id = " + STUDENT_CR);

		try (Connection as = postgres.actingAs(accountCr, null, STUDENT_CR)) {
			assertEquals(0, count(as, "SELECT count(*) FROM attendance"));
		}
	}

	@Test
	void aCrMayMarkTheirOwnAttendance() throws Exception {

		// Asserted rather than left implicit: this was decided with the alternative in hand,
		// and the audit log is the only control over it. A policy change that quietly closed
		// it would break this test, which is the point of writing it down.
		try (Connection as = postgres.actingAs(accountCr, null, STUDENT_CR)) {
			assertEquals(1, update(as,
					"UPDATE attendance SET present = true WHERE lecture_id = " + LECTURE_CS
							+ " AND student_id = " + STUDENT_CR),
					"a CR may mark themselves present; the audit row is the only trace");
		}
	}

	@Test
	void aCrCannotDeleteALectureOrAnAttendanceRow() throws Exception {

		// Deleting a lecture destroys its attendance, which is not what "take attendance on
		// behalf of the professor" means. There is no DELETE policy for a delegate, and an
		// absent policy denies.
		try (Connection as = postgres.actingAs(accountCr, null, STUDENT_CR)) {

			assertEquals(0, update(as,
					"DELETE FROM attendance WHERE lecture_id = " + LECTURE_CS));
			assertEquals(0, update(as,
					"DELETE FROM lectures WHERE lecture_id = " + LECTURE_CS));
		}

		// Still there.
		try (Connection as = postgres.actingAs(accountProfCs, PROF_CS, null)) {
			assertEquals(1, count(as,
					"SELECT count(*) FROM lectures WHERE lecture_id = " + LECTURE_CS));
		}
	}

	@Test
	void aHeadOfDepartmentSeesTheirOwnDepartmentAndNoOther() throws Exception {

		try (Connection as = postgres.actingAs(accountHeadCs, PROF_HEAD_CS, null)) {

			// Both Computer Science lectures: the 2025-26 one through the HOD grant, and the
			// 2026-27 one not at all, because the headship names a session too.
			assertEquals(1, count(as,
					"SELECT count(*) FROM lectures WHERE course_id = " + COURSE_CS
							+ " AND lecture_id = " + LECTURE_CS));
			assertEquals(0, count(as,
					"SELECT count(*) FROM lectures WHERE lecture_id = " + LECTURE_CS_NEXT),
					"a headship for 2025-26 does not extend into 2026-27");

			assertEquals(0, count(as,
					"SELECT count(*) FROM attendance WHERE lecture_id = " + LECTURE_CHEM),
					"the head of Computer Science is nobody in Chemistry");
		}
	}

	@Test
	void anAuditorReadsEverythingAndChangesNothing() throws Exception {

		try (Connection as = postgres.actingAs(accountAuditor, null, null)) {

			assertEquals(3, count(as, "SELECT count(*) FROM attendance"),
					"an auditor reads across every department");

			assertEquals(0, update(as, "UPDATE attendance SET present = false"),
					"an auditor has a FOR SELECT policy and nothing else");
			assertEquals(0, update(as, "DELETE FROM attendance"));
		}
	}

	@Test
	void theOfficeReadsAttendanceAndDoesNotEditIt() throws Exception {

		try (Connection as = postgres.actingAs(accountOffice, null, null)) {
			assertEquals(3, count(as, "SELECT count(*) FROM attendance"),
					"the office exports attendance, so it reads all of it");
			assertEquals(0, update(as, "UPDATE attendance SET present = false"),
					"the permission matrix gives the office no attendance edit");
		}
	}

	@Test
	void anAdminReachesEverything() throws Exception {

		try (Connection as = postgres.actingAs(accountAdmin, null, null)) {
			assertEquals(3, count(as, "SELECT count(*) FROM attendance"));
			assertEquals(3, update(as, "UPDATE attendance SET present = true"));
		}
	}

	// ── Designating a CR ─────────────────────────────────────────────────

	@Test
	void aProfessorCannotDesignateACrForSomebodyElsesCourse() throws Exception {

		// The Chemistry professor, reaching at the Computer Science course. The policy denies
		// the attempt before the trigger is ever consulted.
		try (Connection as = postgres.actingAs(accountProfChem, PROF_CHEM, null)) {
			assertThrows(SQLException.class, () -> execute(as,
					"INSERT INTO attendance_delegate (student_id, course_id, academic_year, "
							+ "granted_by, granted_as) VALUES (" + STUDENT_OTHER + ", "
							+ COURSE_CS + ", " + SESSION + ", " + accountProfChem
							+ ", 'INSTRUCTOR')"));
		}
	}

	@Test
	void claimingAnAuthorityYouDoNotHoldIsRefused() throws Exception {

		// The admin may designate, so the policy lets this through — and the trigger then
		// catches that the claimed capacity is INSTRUCTOR, which the admin is not.
		try (Connection as = postgres.actingAs(accountAdmin, null, null)) {

			SQLException refused = assertThrows(SQLException.class, () -> execute(as,
					"INSERT INTO attendance_delegate (student_id, course_id, academic_year, "
							+ "granted_by, granted_as) VALUES (" + STUDENT_CR + ", "
							+ COURSE_CHEM + ", " + SESSION + ", " + accountAdmin
							+ ", 'INSTRUCTOR')"));

			assertTrue(refused.getMessage().contains("cannot designate"),
					"the trigger should say what was wrong, not just fail: "
							+ refused.getMessage());
		}
	}

	@Test
	void aCrWhoIsNotOnTheRollIsRefused() throws Exception {

		// Student 2002 is a Chemistry major, so they are not on CS-101's roll at all.
		try (Connection as = postgres.actingAs(accountProfCs, PROF_CS, null)) {

			SQLException refused = assertThrows(SQLException.class, () -> execute(as,
					"INSERT INTO attendance_delegate (student_id, course_id, academic_year, "
							+ "granted_by, granted_as) VALUES (" + STUDENT_OTHER + ", "
							+ COURSE_CS + ", " + SESSION + ", " + accountProfCs
							+ ", 'INSTRUCTOR')"));

			assertTrue(refused.getMessage().contains("not on the roll"),
					"the roster check should name the problem: " + refused.getMessage());
		}
	}

	@Test
	void aGrantIsNeverDeleted() throws Exception {

		// Not even by an admin. Revoking is an UPDATE; there is no DELETE policy on the table
		// for anybody, and an absent policy denies.
		try (Connection as = postgres.actingAs(accountAdmin, null, null)) {
			assertEquals(0, update(as, "DELETE FROM attendance_delegate"));
			assertEquals(1, update(as,
					"UPDATE attendance_delegate SET revoked_at = now(), revoked_by = "
							+ accountAdmin));
		}
	}

	@Test
	void aCrSeesTheirOwnGrantsAndNobodyElsesRoles() throws Exception {

		try (Connection as = postgres.actingAs(accountCr, null, STUDENT_CR)) {

			assertEquals(1, count(as, "SELECT count(*) FROM attendance_delegate"),
					"a CR may see what they have been given");

			// Four roles exist; a student holds none and may see none of them.
			assertEquals(0, count(as, "SELECT count(*) FROM user_role"),
					"a student has no business reading the college's role grants");
		}
	}

	// ── roll_for_course ──────────────────────────────────────────────────

	@Test
	void theRosterFunctionPutsACrossDepartmentMinorOnTheRoll() throws Exception {

		// V4's case, now load-bearing for the grant trigger: a Mathematics minor read to a
		// BSc_unaided class. If roll_for_course got this wrong, no CR could be designated for
		// a minor at all.
		postgres.asSuperuser("""
				INSERT INTO professors (prof_id, name, email, department, designation)
				    VALUES (1004, 'D MATH', 'd.math@sies.edu.in', 'MATHEMATICS', 'Professor');

				UPDATE student_year SET minor_dept = 'MATHEMATICS'
				    WHERE student_id = 2001 AND academic_year = 2025;

				INSERT INTO courses
				    (course_id, prof_id, course_code, course_topic, stream,
				     offering_department, year, semester, course_type)
				    VALUES (3003, 1004, 'MATH-MIN-1', 'Discrete', 'BSc_unaided',
				            'MATHEMATICS', 'FY', 1, 'MINOR');
				""");

		try (Connection as = postgres.connection()) {
			assertEquals(1, count(as,
					"SELECT count(*) FROM roll_for_course(3003, 2025) WHERE student_id = 2001"),
					"a BSc_unaided student minoring in Mathematics is on that minor's roll");
			assertEquals(0, count(as,
					"SELECT count(*) FROM roll_for_course(3003, 2025) WHERE student_id = 2002"),
					"a Chemistry major is not");
		}
	}

	// ── The roster, since V11 ────────────────────────────────────────────

	/*
	 * Before V11 these nine tables had no policies at all, so every one of these tests would
	 * have passed trivially by reading everything. They are the gate on the reachability rule:
	 * "which students may this professor see" is not a department comparison, and the way to be
	 * sure of that is to ask it of a roll that crosses departments.
	 */

	@Test
	void aProfessorSeesTheStudentsOnTheirOwnRollAndNoOthers() throws Exception {

		try (Connection as = postgres.actingAs(accountProfCs, PROF_CS, null)) {

			assertEquals(1, count(as, "SELECT count(*) FROM students"),
					"the one student on CS-101's roll");
			assertEquals(1, count(as, "SELECT count(*) FROM students WHERE student_id = "
					+ STUDENT_CR));
			assertEquals(0, count(as, "SELECT count(*) FROM students WHERE student_id = 2002"),
					"a Chemistry major is on nobody's CS roll");

			assertEquals(0, count(as, "SELECT count(*) FROM student_year "
							+ "WHERE student_id = 2002"),
					"and neither is their enrolment, which is where the roll number lives");
		}
	}

	@Test
	void aMinorFromAnotherDepartmentIsOnTheRollAndSoIsItsStudent() throws Exception {

		// The case V6 would not guess at, and the reason this took until V11: a department
		// comparison would hide this student from the professor who actually teaches them.
		postgres.asSuperuser("""
				INSERT INTO professors (prof_id, name, email, department, designation)
				    VALUES (1004, 'D MATH', 'd.math@sies.edu.in', 'MATHEMATICS', 'Professor');

				UPDATE student_year SET minor_dept = 'MATHEMATICS'
				    WHERE student_id = 2001 AND academic_year = 2025;

				INSERT INTO courses
				    (course_id, prof_id, course_code, course_topic, stream,
				     offering_department, year, semester, course_type)
				    VALUES (3003, 1004, 'MATH-MIN-1', 'Discrete', 'BSc_unaided',
				            'MATHEMATICS', 'FY', 1, 'MINOR');

				INSERT INTO course_instructor (course_id, prof_id) VALUES (3003, 1004);
				""");

		long accountMath = createAccount("PROFESSOR", 1004L, null, "d.math@sies.edu.in");

		try (Connection as = postgres.actingAs(accountMath, 1004L, null)) {

			assertEquals(1, count(as, "SELECT count(*) FROM students WHERE student_id = 2001"),
					"a Computer Science major taking Mathematics as a minor is on the "
							+ "Mathematics professor's roll, and a department comparison "
							+ "would have hidden them");
			assertEquals(0, count(as, "SELECT count(*) FROM students WHERE student_id = 2002"),
					"and a Chemistry major who takes no Mathematics is not");
		}
	}

	@Test
	void aCrReadsTheRollOfTheCourseTheyRepresentAndNoOther() throws Exception {

		// A CR has to see the class list: in this app, taking attendance is marking a roll.
		// So the reachability predicate admits a CR exactly as it admits an instructor -- and
		// the grant's session is part of it, which is what keeps last year's CR out.
		postgres.asSuperuser("""
				INSERT INTO students (student_id, name, pronouns, email) VALUES
				    (2003, 'T THOMAS', 'he/him', 't.thomas@sies.edu.in');

				INSERT INTO student_year
				    (student_id, academic_year, roll_no, year, stream, major_dept, semester)
				    VALUES (2003, 2025, 'CS-FY-002', 'FY', 'BSc_unaided',
				            'COMPUTER_SCIENCE', 1);
				""");

		try (Connection as = postgres.actingAs(accountCr, null, STUDENT_CR)) {

			assertEquals(2, count(as, "SELECT count(*) FROM students"),
					"both students on CS-101's roll, which is what a register needs");
			assertEquals(1, count(as, "SELECT count(*) FROM students WHERE student_id = 2003"));
			assertEquals(0, count(as, "SELECT count(*) FROM students WHERE student_id = 2002"),
					"and not the Chemistry major, who is on nobody's CS roll");

			// Their own enrolments come back through the own-row policy whatever else is
			// true -- including the 2026 one, which their CR grant does not cover.
			assertEquals(1, count(as, "SELECT count(*) FROM student_year "
							+ "WHERE student_id = " + STUDENT_CR + " AND academic_year = 2026"),
					"a student always reads their own record");

			assertEquals(0, update(as, "UPDATE students SET name = 'CHANGED' "
							+ "WHERE student_id = 2003"),
					"reading a roll is not editing one");
		}
	}

	@Test
	void aHeadReadsTheirDepartmentsRollAndNotAnothers() throws Exception {

		try (Connection as = postgres.actingAs(accountHeadCs, PROF_HEAD_CS, null)) {

			assertEquals(1, count(as, "SELECT count(*) FROM students WHERE student_id = 2001"),
					"a head is responsible for their department's students whether or not "
							+ "they teach them");
			assertEquals(0, count(as, "SELECT count(*) FROM students WHERE student_id = 2002"));

			assertEquals(1, count(as, "SELECT count(*) FROM courses"),
					"and for their department's courses");
		}
	}

	@Test
	void aProfessorMayNotRewriteTheRoll() throws Exception {

		try (Connection as = postgres.actingAs(accountProfCs, PROF_CS, null)) {

			// No exception: a row no policy's USING clause admits is a row the UPDATE does
			// not see, so it matches nothing. That is the quiet form of a refusal, and it is
			// why every one of these tests checks the read side as well.
			assertEquals(0, update(as,
							"UPDATE students SET name = 'CHANGED' WHERE student_id = 2001"),
					"a roll number is what every other session's records are matched by, so "
							+ "the roll is the office's");

			assertEquals(0, update(as, "UPDATE student_year SET roll_no = 'CS-FY-999' "
					+ "WHERE student_id = 2001 AND academic_year = 2025"));

			// The loud form, for completeness: an INSERT has nothing to filter, so the WITH
			// CHECK refuses it outright.
			assertThrows(SQLException.class, () -> execute(as,
							"INSERT INTO students (student_id, name, pronouns) "
									+ "VALUES (2009, 'NEW PERSON', 'they/them')"),
					"and a professor may not add a student either");
		}

		try (Connection as = postgres.actingAs(accountOffice, null, null)) {
			assertEquals(1, update(as,
					"UPDATE students SET name = 'R RAO JR' WHERE student_id = 2001"),
					"and the office may");
		}
	}

	@Test
	void aProfessorMayRunTheirOwnCourseAndNotSomebodyElses() throws Exception {

		try (Connection as = postgres.actingAs(accountProfCs, PROF_CS, null)) {

			assertEquals(1, update(as, "UPDATE courses SET remarks = 'moved to lab 2' "
					+ "WHERE course_id = " + COURSE_CS));

			assertEquals(0, update(as, "UPDATE courses SET remarks = 'not mine' "
							+ "WHERE course_id = " + COURSE_CHEM),
					"another department's course is not visible, so the UPDATE matches "
							+ "nothing -- which is the quiet form of a refusal and why the "
							+ "read side is tested separately");

			// A new course of their own, which is what the app lets a professor do. The
			// coordinator column is what says it is theirs at the moment of the insert:
			// course_instructor cannot exist yet.
			execute(as, "INSERT INTO courses (course_id, prof_id, course_code, course_topic, "
					+ "stream, offering_department, year, semester, course_type) VALUES "
					+ "(3009, " + PROF_CS + ", 'CS-109', 'Practicals', 'BSc_unaided', "
					+ "'COMPUTER_SCIENCE', 'FY', 1, 'MAJOR')");

			assertThrows(SQLException.class, () -> execute(as,
							"INSERT INTO courses (course_id, prof_id, course_code, "
									+ "course_topic, stream, offering_department, year, "
									+ "semester, course_type) VALUES "
									+ "(3010, " + PROF_CHEM + ", 'CH-109', 'Practicals', "
									+ "'BSc', 'CHEMISTRY', 'FY', 1, 'MAJOR')"),
					"and not one under another professor in another department");
		}
	}

	@Test
	void theCalendarAndTheStaffListAreReadableByEverybodySignedIn() throws Exception {

		// Both are needed by every device -- a lecture's session is resolved against the
		// calendar -- and neither says anything about a student.
		try (Connection as = postgres.actingAs(accountCr, null, STUDENT_CR)) {

			assertEquals(2, count(as, "SELECT count(*) FROM academic_years"));
			assertEquals(3, count(as, "SELECT count(*) FROM professors"));

			assertEquals(0, update(as, "UPDATE academic_years SET end_date = '2026-06-30' "
							+ "WHERE academic_year = 2025"),
					"readable is not writable: the read policy admits the row for SELECT and "
							+ "no policy admits it for UPDATE, so the statement matches none");

			assertThrows(SQLException.class, () -> execute(as,
					"INSERT INTO academic_years (academic_year, start_date, end_date) "
							+ "VALUES (2027, '2027-06-01', '2028-05-31')"));
		}
	}

	@Test
	void anUnauthenticatedConnectionSeesNoRosterEither() throws Exception {

		try (Connection as = postgres.appConnection()) {

			assertEquals(0, count(as, "SELECT count(*) FROM students"));
			assertEquals(0, count(as, "SELECT count(*) FROM student_year"));
			assertEquals(0, count(as, "SELECT count(*) FROM courses"));
			assertEquals(0, count(as, "SELECT count(*) FROM academic_years"));
			assertEquals(0, count(as, "SELECT count(*) FROM professors"));
		}
	}

	/*
	 * A device stores a row only when the rows it points at are there, so whatever an actor can
	 * read has to arrive with everything it points at. Until V21 a mark could arrive without its
	 * student: the register said who was in the room, the roll said who should have been, and
	 * the policies on attendance and students asked those two different questions.
	 */

	@Test
	void everyoneWhoReadsAMarkReadsTheStudentItIsFor() throws Exception {

		// A Chemistry major marked on a Computer Science lecture: on the register, not on the
		// roll. A student dropped from the roll after being marked looks exactly like this.
		postgres.asSuperuser("INSERT INTO attendance (lecture_id, student_id, present) VALUES ("
				+ LECTURE_CS + ", 2002, true)");

		String marked = "SELECT count(*) FROM students WHERE student_id = 2002";

		try (Connection as = postgres.actingAs(accountCr, null, STUDENT_CR)) {
			assertEquals(1, count(as, marked), "the CR reads the register, so the student on it");
		}

		try (Connection as = postgres.actingAs(accountProfCs, PROF_CS, null)) {
			assertEquals(1, count(as, marked), "and so does the professor who teaches it");
		}

		try (Connection as = postgres.actingAs(accountHeadCs, PROF_HEAD_CS, null)) {
			assertEquals(1, count(as, marked), "and the head of the department offering it");
		}

		try (Connection as = postgres.actingAs(accountProfChem, PROF_CHEM, null)) {
			assertEquals(0, count(as, "SELECT count(*) FROM students WHERE student_id = "
							+ STUDENT_CR),
					"a mark is no way into somebody else's register: CS-101's marks do not "
							+ "show the Chemistry professor its students");
		}
	}

	@Test
	void nobodyReadsARowWithoutTheRowsItPointsAt() throws Exception {

		postgres.asSuperuser("INSERT INTO attendance (lecture_id, student_id, present) VALUES ("
				+ LECTURE_CS + ", 2002, true)");

		// Every foreign key a device has, as DeviceTables lists them: child, column, parent.
		String[][] references = {
				{"student_year", "student_id", "students", "student_id"},
				{"courses", "prof_id", "professors", "prof_id"},
				{"batch_student_map", "batch_id", "batch_desc", "batch_id"},
				{"batch_student_map", "student_id", "students", "student_id"},
				{"course_batch_map", "course_id", "courses", "course_id"},
				{"course_batch_map", "batch_id", "batch_desc", "batch_id"},
				{"course_instructor", "course_id", "courses", "course_id"},
				{"course_instructor", "prof_id", "professors", "prof_id"},
				{"lectures", "course_id", "courses", "course_id"},
				{"lectures", "batch_id", "batch_desc", "batch_id"},
				{"attendance", "lecture_id", "lectures", "lecture_id"},
				{"attendance", "student_id", "students", "student_id"},
		};

		// A batch the CR may not read, which every row pointing at it has to respect.
		postgres.asSuperuser("""
				INSERT INTO batch_desc (batch_id, batch_name, academic_year)
				    VALUES (5009, 'Hidden from the CR', 2025);
				INSERT INTO batch_student_map (batch_id, student_id, academic_year)
				    VALUES (5009, 2001, 2025);
				INSERT INTO course_batch_map (course_id, batch_id) VALUES (3002, 5009);
				""");

		Object[][] actors = {
				{"the CS professor", accountProfCs, PROF_CS, null},
				{"the Chemistry professor", accountProfChem, PROF_CHEM, null},
				{"the CS head", accountHeadCs, PROF_HEAD_CS, null},
				{"the CR", accountCr, null, STUDENT_CR},
				{"the office", accountOffice, null, null},
				{"the auditor", accountAuditor, null, null},
				{"the admin", accountAdmin, null, null},
		};

		for (Object[] actor : actors) {
			try (Connection as = postgres.actingAs((Long) actor[1], (Long) actor[2],
					(Long) actor[3])) {

				for (String[] reference : references) {
					assertEquals(0, count(as, "SELECT count(*) FROM " + reference[0] + " c "
									+ "WHERE c." + reference[1] + " IS NOT NULL AND NOT EXISTS ("
									+ "SELECT 1 FROM " + reference[2] + " p WHERE p." + reference[3]
									+ " = c." + reference[1] + ")"),
							actor[0] + " reads " + reference[0] + " rows whose " + reference[1]
									+ " they cannot read, and a device cannot store those");
				}
			}
		}
	}

	@Test
	void batchesAndTheirCoursesAreAnyProfessorsAndNobodyElses() throws Exception {

		String batch = "INSERT INTO batch_desc (batch_id, batch_name, academic_year) VALUES ";

		// A Chemistry professor makes a batch and links it to a Computer Science course they
		// do not teach: an open elective's batch is often put together by somebody else.
		try (Connection as = postgres.actingAs(accountProfChem, PROF_CHEM, null)) {
			execute(as, batch + "(5001, 'OE takers', " + SESSION + ")");
			execute(as, "INSERT INTO course_batch_map (course_id, batch_id) VALUES ("
					+ COURSE_CS + ", 5001)");

			// actingAs is one transaction; the next actor has to see these rows.
			as.commit();
		}

		// And the CS professor may undo it, on a batch they did not make.
		try (Connection as = postgres.actingAs(accountProfCs, PROF_CS, null)) {
			assertEquals(1, update(as, "DELETE FROM course_batch_map WHERE batch_id = 5001"));
			assertEquals(1, update(as, "UPDATE batch_desc SET batch_name = 'renamed' "
					+ "WHERE batch_id = 5001"));
		}

		// Each refusal on a connection of its own: a refused statement aborts the transaction,
		// and the next one would then fail for that reason rather than for its own.
		assertRefused(accountOffice, null, null, batch + "(5002, 'by the office', " + SESSION
				+ ")", "the office keeps the roll and not the batches");

		try (Connection as = postgres.actingAs(accountOffice, null, null)) {
			assertEquals(0, update(as, "UPDATE batch_desc SET batch_name = 'no' "
					+ "WHERE batch_id = 5001"), "nor renames one");
		}

		assertRefused(accountAdmin, null, null, "INSERT INTO course_batch_map (course_id, "
				+ "batch_id) VALUES (" + COURSE_CS + ", 5001)", "nor does an administrator");

		assertRefused(accountCr, null, STUDENT_CR, batch + "(5003, 'by a CR', " + SESSION + ")",
				"and a class representative makes none");

		assertRefused(accountCr, null, STUDENT_CR, "INSERT INTO course_batch_map (course_id, "
				+ "batch_id) VALUES (" + COURSE_CS + ", 5001)", "nor links one");
	}

	/*
	 * V23: where a course has a batch linked for the session, its roll is the batch. The
	 * fixture's CS-101 is a first-year major course, so by the rules its roll is R RAO, the one
	 * Computer Science major; S SHAH is a Chemistry major and on nobody's CS roll.
	 */

	@Test
	void aCourseWithABatchTakesItsRollFromTheBatch() throws Exception {

		String roll = "SELECT string_agg(student_id::text, ',' ORDER BY student_id) "
				+ "FROM roll_for_course(" + COURSE_CS + ", " + SESSION + ")";

		assertEquals("2001", text(roll), "by the rules, before any batch");

		// The batch holds the Chemistry major alone -- somebody from another department and
		// another stream, which is exactly what no rule could have put on this roll.
		postgres.asSuperuser("""
				INSERT INTO batch_desc (batch_id, batch_name, academic_year)
				    VALUES (5001, 'Chosen it', 2025);
				INSERT INTO batch_student_map (batch_id, student_id, academic_year)
				    VALUES (5001, 2002, 2025);
				INSERT INTO course_batch_map (course_id, batch_id) VALUES (3001, 5001);
				""");

		assertEquals("2002", text(roll), "the batch is the roll now, and only the batch");

		try (Connection as = postgres.actingAs(accountProfCs, PROF_CS, null)) {
			assertEquals(1, count(as, "SELECT count(*) FROM student_year "
							+ "WHERE student_id = 2002"),
					"the professor teaching it reaches the student the batch put on it");
		}

		try (Connection as = postgres.actingAs(accountCr, null, STUDENT_CR)) {
			assertEquals(1, count(as, "SELECT count(*) FROM students WHERE student_id = 2002"),
					"and so does the course's representative");
		}

		// A batch from another session says nothing about this one's roll: next session has
		// no batch for the course, so the rules decide it -- and by them a first-year course
		// has nobody, because R RAO is enrolled in second year by then. Had the 2025 batch
		// leaked across, S SHAH would be here.
		assertEquals("none", text("SELECT coalesce(string_agg(student_id::text, ','), 'none') "
						+ "FROM roll_for_course(" + COURSE_CS + ", " + NEXT_SESSION + ")"),
				"next session has no batch for the course, so the rules decide it");
	}

	@Test
	void aCrSeesTheBatchesOfTheirCoursesAndNoOthers() throws Exception {

		// 5001 is linked to CS-101, which the CR represents. 5002 is linked to nothing, and the
		// CR is in it. 5003 is linked to nothing either, but a CS-101 lecture is held for it.
		postgres.asSuperuser("""
				INSERT INTO batch_desc (batch_id, batch_name, academic_year) VALUES
				    (5001, 'CS-101 practicals', 2025),
				    (5002, 'Some club',         2025),
				    (5003, 'Held for, unlinked', 2025);
				INSERT INTO course_batch_map (course_id, batch_id) VALUES (3001, 5001);
				INSERT INTO batch_student_map (batch_id, student_id, academic_year) VALUES
				    (5001, 2001, 2025),
				    (5002, 2001, 2025);
				UPDATE lectures SET batch_id = 5003 WHERE lecture_id = 4001;
				""");

		try (Connection as = postgres.actingAs(accountCr, null, STUDENT_CR)) {

			assertEquals(1, count(as, "SELECT count(*) FROM batch_desc WHERE batch_id = 5001"),
					"the batch linked to the course they represent");
			assertEquals(0, count(as, "SELECT count(*) FROM batch_desc WHERE batch_id = 5002"),
					"not one linked to nothing, even with the CR in it");
			assertEquals(1, count(as, "SELECT count(*) FROM batch_desc WHERE batch_id = 5003"),
					"the batch a lecture they read was held for, or the lecture arrives on "
							+ "the phone pointing at nothing");

			assertEquals(0, count(as, "SELECT count(*) FROM batch_student_map "
							+ "WHERE batch_id = 5002"),
					"and not even their own membership of a batch they cannot see");
			assertEquals(1, count(as, "SELECT count(*) FROM batch_student_map "
					+ "WHERE batch_id = 5001"));
		}

		try (Connection as = postgres.actingAs(accountProfChem, PROF_CHEM, null)) {
			assertEquals(3, count(as, "SELECT count(*) FROM batch_desc"),
					"a professor still reads every batch");
		}
	}

	@Test
	void onlyAnAdministratorOpensASession() throws Exception {

		String open = "INSERT INTO academic_years (academic_year, start_date, end_date) VALUES ";

		try (Connection as = postgres.actingAs(accountAdmin, null, null)) {
			execute(as, open + "(2027, '2027-06-01', '2028-05-31')");
			as.commit();
		}

		assertRefused(accountOffice, null, null, open + "(2028, '2028-06-01', '2029-05-31')",
				"the office keeps the roll, not the calendar");

		assertRefused(accountProfCs, PROF_CS, null, open + "(2028, '2028-06-01', '2029-05-31')",
				"nor does a professor");

		try (Connection as = postgres.actingAs(accountOffice, null, null)) {
			assertEquals(1, update(as, "UPDATE academic_years SET end_date = '2028-05-30' "
							+ "WHERE academic_year = 2027"),
					"correcting a session's dates is still the office's");
		}
	}

	// ── Fixture helpers ──────────────────────────────────────────────────

	/** Creates an account as the superuser and returns the id the sequence gave it. */
	private long createAccount(String kind, Long prof, Long student, String email)
			throws SQLException {

		try (Connection connection = postgres.connection();
		     PreparedStatement statement = connection.prepareStatement(
				     "INSERT INTO app_account (kind, prof_id, student_id, display_name, "
						     + "login_email, password_hash) "
						     + "VALUES (?::account_kind, ?, ?, ?, ?, 'not-a-real-hash') "
						     + "RETURNING account_id")) {

			statement.setString(1, kind);
			if (prof == null) {
				statement.setNull(2, java.sql.Types.BIGINT);
			} else {
				statement.setLong(2, prof);
			}
			if (student == null) {
				statement.setNull(3, java.sql.Types.BIGINT);
			} else {
				statement.setLong(3, student);
			}
			statement.setString(4, email);
			statement.setString(5, email);

			try (ResultSet results = statement.executeQuery()) {
				assertTrue(results.next(), "no account id came back");
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
			if (session == null) {
				statement.setNull(4, java.sql.Types.INTEGER);
			} else {
				statement.setInt(4, session);
			}
			statement.setLong(5, grantedBy);
			statement.execute();
		}
	}

	// ── Query helpers ────────────────────────────────────────────────────

	/** That statement, as that actor, is refused outright. */
	private void assertRefused(long account, Long prof, Long student, String sql,
	                           String why) throws SQLException {

		try (Connection as = postgres.actingAs(account, prof, student)) {
			assertThrows(SQLException.class, () -> execute(as, sql), why);
		}
	}

	private static long count(Connection connection, String sql) throws SQLException {

		try (Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(sql)) {

			assertTrue(results.next(), () -> "no row from: " + sql);
			return results.getLong(1);
		}
	}

	/** One text value, read as the superuser. */
	private String text(String sql) throws SQLException {

		try (Connection connection = postgres.connection();
		     Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(sql)) {

			assertTrue(results.next(), () -> "no row from: " + sql);
			return results.getString(1);
		}
	}

	/** The number of rows a write touched, which is what a policy filter reduces to zero. */
	private static int update(Connection connection, String sql) throws SQLException {

		try (Statement statement = connection.createStatement()) {
			return statement.executeUpdate(sql);
		}
	}

	private static void execute(Connection connection, String sql) throws SQLException {

		try (Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	/** Kept so an unused-import warning cannot hide a test that stopped asserting. */
	@Test
	void theFixtureItselfIsVisibleToTheSuperuser() throws Exception {
		try (Connection as = postgres.connection()) {
			assertEquals(3, count(as, "SELECT count(*) FROM attendance"));
			assertFalse(count(as, "SELECT count(*) FROM app_account") < 7,
					"seven accounts should have been created");
		}
	}
}
