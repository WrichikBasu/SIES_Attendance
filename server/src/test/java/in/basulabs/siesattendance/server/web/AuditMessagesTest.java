package in.basulabs.siesattendance.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The sentences the audit trail shows, against entries shaped exactly as {@code audit_row()} and
 * {@code audit_event()} write them: the row images use the database's column names, and IDs
 * arrive as JSON numbers.
 */
class AuditMessagesTest {

	private static final long STUDENT = 370666965666103296L;
	private static final long OTHER_STUDENT = 370666964969848832L;
	private static final long PROF = 370666965712240640L;
	private static final long COURSE = 370666966312026112L;
	private static final long LECTURE = 370919062403809287L;
	private static final long BATCH = 370666966404300800L;

	private AuditMessages messages;

	@BeforeEach
	void names() {

		AuditNames names = new AuditNames();
		names.students.put(STUDENT, "Pugaonkar Sanskruti");
		names.professors.put(PROF, "Prof. Jesica D'Cruz");
		names.courses.put(COURSE, new AuditNames.Course("SIUCSMJ211", "Data Structures"));
		names.batches.put(BATCH, "SY CS Practical B2");
		names.accounts.put(10L, "Dr. Manoj Singh");

		// 04:30 UTC is 10:00 in the college's zone.
		names.lectures.put(LECTURE, new AuditNames.Lecture(COURSE,
				Instant.parse("2026-10-20T04:30:00Z"), "Asia/Kolkata", BATCH));

		messages = new AuditMessages(names);
	}

	// ── Attendance ───────────────────────────────────────────────────────

	@Test
	void aMarkSaysWhoWhereAndWhen() {

		Map<String, Object> mark = row("lecture_id", LECTURE, "student_id", STUDENT,
				"present", true);

		assertEquals("Marked Pugaonkar Sanskruti present in SIUCSMJ211 on Tue 20 Oct 2026, 10:00",
				messages.describe("INSERT", "attendance", null, null, mark, null));

		assertEquals("Changed Pugaonkar Sanskruti from present to absent in SIUCSMJ211 on "
						+ "Tue 20 Oct 2026, 10:00",
				messages.describe("UPDATE", "attendance", null, mark,
						with(mark, "present", false), null));

		assertEquals("Removed Pugaonkar Sanskruti's mark from SIUCSMJ211 on "
						+ "Tue 20 Oct 2026, 10:00",
				messages.describe("DELETE", "attendance", null, mark, null, null));
	}

	@Test
	void aNameNobodyCanReadIsNotInvented() {

		// A student this reader may not see, and a lecture since deleted.
		Map<String, Object> mark = row("lecture_id", 1L, "student_id", OTHER_STUDENT,
				"present", true);

		assertEquals("Removed the student's mark from a lecture",
				messages.describe("DELETE", "attendance", null, mark, null, null));
	}

	// ── Lectures ─────────────────────────────────────────────────────────

	@Test
	void aLectureIsDescribedInItsOwnZone() {

		Map<String, Object> lecture = row("lecture_id", LECTURE, "course_id", COURSE,
				"start_at", "2026-10-20T04:30:00+00:00", "start_zone", "Asia/Kolkata",
				"batch_id", BATCH, "notes", null);

		assertEquals("Added a lecture of SIUCSMJ211 on Tue 20 Oct 2026, 10:00 for batch "
						+ "SY CS Practical B2",
				messages.describe("INSERT", "lectures", null, null, lecture, null));

		assertEquals("Changed the lecture of SIUCSMJ211 on Tue 20 Oct 2026, 10:00: moved it to "
						+ "Wed 21 Oct 2026, 10:00, made it a whole-class lecture",
				messages.describe("UPDATE", "lectures", null, lecture, with(with(lecture,
						"start_at", "2026-10-21T04:30:00+00:00"), "batch_id", null), null));
	}

	// ── The roll ─────────────────────────────────────────────────────────

	@Test
	void studentsAndEnrolmentsSayWhatChanged() {

		Map<String, Object> student = row("student_id", STUDENT, "name", "WRICHIK BASU ARPITA",
				"pronouns", "they/them", "email", null, "updated_at", null);

		assertEquals("Renamed student WRICHIK BASU ARPITA to WRICHIK BASU",
				messages.describe("UPDATE", "students", null, student, with(with(student,
						"name", "WRICHIK BASU"), "updated_at", "2026-10-19T21:03:11Z"), null));

		Map<String, Object> enrolment = row("student_id", STUDENT, "academic_year", 2026L,
				"roll_no", "SCS2627098", "year", "SY", "stream", "BSc_unaided",
				"major_dept", "COMPUTER_SCIENCE", "semester", 3L);

		assertEquals("Changed Pugaonkar Sanskruti's enrolment for 2026–27: roll number from "
						+ "SCS2627098 to SCS2627099, semester from 3 to 4",
				messages.describe("UPDATE", "student_year", null, enrolment, with(with(enrolment,
						"roll_no", "SCS2627099"), "semester", 4L), null));

		assertEquals("Withdrew Pugaonkar Sanskruti (SCS2627098) from 2026–27",
				messages.describe("DELETE", "student_year", null, enrolment, null, null));
	}

	@Test
	void batchesAndTheirLinks() {

		assertEquals("Added Pugaonkar Sanskruti to batch SY CS Practical B2",
				messages.describe("INSERT", "batch_student_map", null, null,
						row("batch_id", BATCH, "student_id", STUDENT, "academic_year", 2026L),
						null));

		assertEquals("Linked batch SY CS Practical B2 to SIUCSMJ211",
				messages.describe("INSERT", "course_batch_map", null, null,
						row("batch_id", BATCH, "course_id", COURSE), null));

		assertEquals("Made Prof. Jesica D'Cruz an instructor of SIUCSMJ211",
				messages.describe("INSERT", "course_instructor", null, null,
						row("prof_id", PROF, "course_id", COURSE), null));
	}

	// ── Authority ────────────────────────────────────────────────────────

	@Test
	void rolesAndAppointments() {

		Map<String, Object> grant = row("user_role_id", 9L, "account_id", 10L, "role", "HOD",
				"department", "COMPUTER_SCIENCE", "academic_year", null, "revoked_at", null);

		assertEquals("Gave Dr. Manoj Singh the role of head of CS",
				messages.describe("INSERT", "user_role", null, null, grant, null));

		assertEquals("Revoked Dr. Manoj Singh's role of head of CS",
				messages.describe("UPDATE", "user_role", null, grant,
						with(grant, "revoked_at", "2026-10-20T21:34:05Z"), null));

		Map<String, Object> appointment = row("student_id", STUDENT, "course_id", COURSE,
				"academic_year", 2026L, "revoked_at", "2026-10-20T21:20:27Z");

		assertEquals("Re-appointed Pugaonkar Sanskruti class representative of SIUCSMJ211 "
						+ "for 2026–27",
				messages.describe("UPDATE", "attendance_delegate", null, appointment,
						with(appointment, "revoked_at", null), null));
	}

	@Test
	void signingInIsNotAnEditToTheAccount() {

		Map<String, Object> account = row("account_id", 10L, "display_name", "Dr. Manoj Singh",
				"failed_logins", 0L, "last_login_at", "2026-10-20T18:00:00Z");

		assertEquals("Recorded a sign-in by Dr. Manoj Singh",
				messages.describe("UPDATE", "app_account", null, account,
						with(account, "last_login_at", "2026-10-21T09:00:00Z"), null));

		assertEquals("Recorded a failed sign-in for Dr. Manoj Singh",
				messages.describe("UPDATE", "app_account", null, account,
						with(account, "failed_logins", 1L), null));
	}

	// ── Events ───────────────────────────────────────────────────────────

	@Test
	void eventsReadFromTheirDetail() {

		assertEquals("Signed in as a student",
				messages.describe("LOGIN", null, null, null, null, row("door", "STUDENT")));

		assertEquals("A sign-in was refused: the password did not match",
				messages.describe("LOGIN_FAILED", null, null, null, null,
						row("refused", "the password did not match")));

		assertEquals("A sign-in was refused: no account has that address",
				messages.describe("LOGIN_FAILED", null, null, null, null, null));

		assertEquals("Imported a phone backup from Wrichik phone: 14072 added, 0 updated, "
						+ "1 unchanged",
				messages.describe("BACKUP_IMPORT", "import_run", null, null, null,
						row("source", "Wrichik phone", "dry_run", false, "inserted", 14072L,
								"updated", 0L, "unchanged", 1L)));

		assertEquals("Registered a phone (samsung SM-M315F) as device 22",
				messages.describe("DEVICE_REGISTER", "device_registration", null, null, null,
						row("node", 22L, "label", "samsung SM-M315F",
								"provisional_node_clashes", 0L)));

		assertEquals("Loaded a roster CSV for 2026–27: 3 added, 1 updated, 0 refused",
				messages.describe("CSV_IMPORT", "students", null, null, null,
						row("academic_year", 2026L, "created", 3L, "updated", 1L, "failed", 0L)));
	}

	@Test
	void somethingThisBuildHasNeverSeenStillReadsAsASentence() {

		assertEquals("Added a row to some new table",
				messages.describe("INSERT", "some_new_table", null, null, row("x", 1L), null));

		assertEquals("Some new action",
				messages.describe("SOME_NEW_ACTION", null, null, null, null, null));

		// A malformed image: a value of a type nothing expects.
		assertEquals("Marked a student absent in a lecture",
				messages.describe("INSERT", "attendance", null, null,
						row("lecture_id", "not a number", "student_id", new Object()), null));
	}

	@Test
	void aSessionIsTheYearItStartsIn() {
		assertEquals("2026–27", AuditMessages.session(2026L));
		assertEquals("2099–00", AuditMessages.session(2099L));
	}

	// ── Helpers ──────────────────────────────────────────────────────────

	private static Map<String, Object> row(Object... pairs) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int at = 0; at + 1 < pairs.length; at += 2) {
			map.put((String) pairs[at], pairs[at + 1]);
		}
		return map;
	}

	private static Map<String, Object> with(Map<String, Object> row, String key, Object value) {
		Map<String, Object> copy = new HashMap<>(row);
		copy.put(key, value);
		return copy;
	}
}
