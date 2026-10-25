package in.basulabs.siesattendance.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import in.basulabs.siesattendance.backend.AccountKind;
import in.basulabs.siesattendance.backend.StoredTimes;
import in.basulabs.siesattendance.backend.StructuredId;
import in.basulabs.siesattendance.backend.sync.DeviceTables;
import in.basulabs.siesattendance.backend.sync.OutboxOp;
import in.basulabs.siesattendance.backend.sync.SyncIntent;
import in.basulabs.siesattendance.backend.sync.SyncJson;
import in.basulabs.siesattendance.server.SiesAttendanceServer;
import in.basulabs.siesattendance.server.security.AccountDirectory;
import in.basulabs.siesattendance.server.testing.TestPostgres;

/**
 * Phase 6's gate, as far as a server can carry it: marks taken on a phone reach the server
 * exactly once each, with the right authorship, and nothing else comes with them.
 * <p>
 * The airplane-mode half of that gate needs a phone, and this is not one. What is proved here is
 * everything on this side of the wire — that a batch applied twice changes nothing, that a device
 * cannot write under another device's node, that the policies refuse a lecture that is not the
 * caller's, and that the audit entries name the account, the session and the device. The device
 * half is proved separately: {@code OutboxTriggerTest} shows the queue filling against real
 * SQLite, and the two have never been joined on a handset.
 *
 * @see WireTest for how the service is booted; the arrangement is the same
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncTest {

	private static final int SESSION = 2025;

	private static final long PROF_CS = 1001;
	private static final long PROF_CHEM = 1002;

	private static final long STUDENT_CS = 2001;
	private static final long STUDENT_CHEM = 2002;

	private static final long COURSE_CS = 3001;
	private static final long COURSE_CHEM = 3002;

	private static final String PASSWORD = "correct horse battery staple";

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	private static TestPostgres postgres;

	private static ConfigurableApplicationContext service;

	private static int port;

	private long accountProfCs;
	private long accountProfChem;
	private long accountOffice;

	private long accountHeadCs;

	@BeforeAll
	void startEverything() throws Exception {

		assumeTrue(TestPostgres.isAvailable(),
				"Docker is not available, so sync cannot be tested against a real PostgreSQL. "
						+ "tools/verify-sync.sh fails rather than skips.");

		postgres = TestPostgres.start();

		service = new SpringApplication(SiesAttendanceServer.class).run(
				"--spring.datasource.url=" + postgres.appJdbcUrl(),
				"--spring.datasource.username=" + TestPostgres.appUsername(),
				"--spring.datasource.password=" + TestPostgres.appPassword(),
				"--spring.flyway.enabled=false",
				"--server.port=0",
				"--spring.main.banner-mode=off",
				"--sies.login.attempts-per-window=1000");

		port = ((WebServerApplicationContext) service).getWebServer().getPort();
	}

	@AfterAll
	void stopEverything() {

		if (service != null) {
			service.close();
		}

		if (postgres != null) {
			postgres.close();
		}
	}

	@BeforeEach
	void buildFixture() throws Exception {

		assumeTrue(postgres != null, "no database");

		postgres.reset();

		// No lectures and no attendance in the fixture: this class is about rows arriving from
		// a device, and a fixture that pre-loaded them would make a push look like it had
		// worked when it had not.
		postgres.asSuperuser("""
				INSERT INTO academic_years (academic_year, start_date, end_date) VALUES
				    (2025, '2025-06-01', '2026-05-31');

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
				    (2002, 2025, 'CH-FY-001', 'FY', 'BSc',         'CHEMISTRY',        1);

				INSERT INTO courses
				    (course_id, prof_id, course_code, course_topic, stream,
				     offering_department, year, semester, course_type) VALUES
				    (3001, 1001, 'CS-101', 'Programming', 'BSc_unaided',
				     'COMPUTER_SCIENCE', 'FY', 1, 'MAJOR'),
				    (3002, 1002, 'CH-101', 'Inorganic',   'BSc',
				     'CHEMISTRY',        'FY', 1, 'MAJOR');

				INSERT INTO course_instructor (course_id, prof_id) VALUES
				    (3001, 1001), (3002, 1002);
				""");

		accountProfCs = account(AccountKind.PROFESSOR, PROF_CS, null, "a.cs@sies.edu.in");
		accountProfChem = account(AccountKind.PROFESSOR, PROF_CHEM, null, "b.chem@sies.edu.in");
		accountOffice = account(AccountKind.STAFF, null, null, "office@sies.edu.in");

		grantRole(accountOffice, "OFFICE", null, null, accountOffice);

		// A head of Computer Science, for V20's half of the roll. Their own account rather
		// than a grant on one of the two professors': aPullSeesNothingOfAnotherDepartment
		// signs in as the chemistry professor precisely to show they reach nothing of
		// Computer Science, and making them its head would have made that test a lie.
		accountHeadCs = account(AccountKind.PROFESSOR, 1003L, null, "c.head@sies.edu.in");

		grantRole(accountHeadCs, "HOD", "COMPUTER_SCIENCE", null, accountOffice);
	}

	// ── The gate ─────────────────────────────────────────────────────────

	@Test
	void threeLecturesWorthOfMarksArriveOnceEachWithTheirAuthorship() throws Exception {

		Phone phone = signInWithAPhone("a.cs@sies.edu.in");

		// What a morning offline looks like: three lectures, each with its own sheet, all
		// queued and sent together when the phone finds a signal.
		List<Map<String, Object>> intents = new ArrayList<>();

		for (int lecture = 0; lecture < 3; lecture++) {

			long lectureID = phone.key(100 + lecture);

			intents.add(lectureIntent(lectureID, COURSE_CS, 9 + lecture));
			intents.add(attendanceIntent(lectureID, STUDENT_CS, lecture != 1));
		}

		Reply pushed = push(phone, intents);

		assertEquals(200, pushed.status(), pushed::toString);
		assertEquals(6, pushed.body().get("accepted").intValue(), pushed::toString);
		assertEquals(0, pushed.body().get("rejected").size(), pushed::toString);

		assertEquals(3, count("SELECT count(*) FROM lectures"));
		assertEquals(3, count("SELECT count(*) FROM attendance"));
		assertEquals(1, count("SELECT count(*) FROM attendance WHERE NOT present"),
				"the one lecture the student missed");

		// Correct authorship, which is the half of the gate a row count cannot show. The
		// triggers wrote these, carrying the actor the token established.
		assertEquals(6, count("SELECT count(*) FROM audit_log WHERE action = 'INSERT' "
						+ "AND table_name IN ('lectures', 'attendance') "
						+ "AND actor_account = " + accountProfCs
						+ " AND device_node = " + phone.node
						+ " AND session_id IS NOT NULL"),
				"every row has to name who sent it, from which phone, on which sign-in");
	}

	@Test
	void theSameBatchSentTwiceChangesNothing() throws Exception {

		Phone phone = signInWithAPhone("a.cs@sies.edu.in");
		long lectureID = phone.key(200);

		List<Map<String, Object>> intents = List.of(
				lectureIntent(lectureID, COURSE_CS, 11),
				attendanceIntent(lectureID, STUDENT_CS, true));

		assertEquals(2, push(phone, intents).body().get("accepted").intValue());

		// The response was lost, as far as the phone knows. It sends the batch again.
		Reply again = push(phone, intents);

		assertEquals(200, again.status(), again::toString);
		assertEquals(2, again.body().get("accepted").intValue(),
				"a replay is accepted, not refused: the device cannot tell whether the first "
						+ "one arrived, and it should not have to");

		assertEquals(1, count("SELECT count(*) FROM lectures"));
		assertEquals(1, count("SELECT count(*) FROM attendance"));

		// The second application is an UPDATE that changed nothing, which the audit trigger
		// drops: one INSERT entry per row, and no second entry claiming a change.
		assertEquals(2, count("SELECT count(*) FROM audit_log "
						+ "WHERE table_name IN ('lectures', 'attendance')"),
				"a replay must not fill the log with changes that did not happen");
	}

	@Test
	void aCorrectionOverwritesTheMarkAndIsRecorded() throws Exception {

		Phone phone = signInWithAPhone("a.cs@sies.edu.in");
		long lectureID = phone.key(300);

		push(phone, List.of(lectureIntent(lectureID, COURSE_CS, 9),
				attendanceIntent(lectureID, STUDENT_CS, true)));

		// The professor notices the student was absent after all.
		assertEquals(1, push(phone, List.of(attendanceIntent(lectureID, STUDENT_CS, false)))
				.body().get("accepted").intValue());

		assertEquals(1, count("SELECT count(*) FROM attendance WHERE NOT present"));
		assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'UPDATE' "
						+ "AND table_name = 'attendance' AND actor_account = " + accountProfCs),
				"the correction is a change, and a change is a line in the record");
	}

	// ── What a device may not do ─────────────────────────────────────────

	@Test
	void aKeyIssuedByAnotherDeviceIsRefused() throws Exception {

		Phone phone = signInWithAPhone("a.cs@sies.edu.in");

		// The same professor, the same token, a key claiming to be another phone's.
		long foreign = StructuredId.compose(StructuredId.MIN_COUNTER + 400, phone.node + 7);

		Reply pushed = push(phone, List.of(lectureIntent(foreign, COURSE_CS, 9)));

		assertEquals(200, pushed.status(), pushed::toString);
		assertEquals(0, pushed.body().get("accepted").intValue());
		assertTrue(pushed.body().get("rejected").get(0).get("reason").stringValue()
						.contains("no such row here"),
				pushed.body().get("rejected").toString());
		assertTrue(pushed.body().get("rejected").get(0).get("permanent").booleanValue(),
				"a key from the wrong node will be the wrong node tomorrow too");

		assertEquals(0, count("SELECT count(*) FROM lectures"),
				"a phone must not be able to CREATE rows claiming to be another phone's");
	}

	@Test
	void theRowsAPhoneMadeBeforeItRegisteredAreItsOwn() throws Exception {

		// The defect a handset found, and the worst shape one can have: a phone that had been
		// taking attendance for a term was pointed at a server, and every row it held was
		// refused because the keys carried the node it had picked for itself before
		// registering. V11 had made that refusal final, so the device dropped the intents and
		// reported a sync that moved nothing. Measured on the real thing: node 1 assigned,
		// provisional node 37498, and the only row that arrived was the one table whose key no
		// device issues.
		Phone phone = signInWithAPhone("a.cs@sies.edu.in");
		long lectureID = phone.keyFromBefore(700);

		assertTrue(StructuredId.nodeOf(lectureID) != phone.node,
				"the whole point: a key from before the node was assigned");

		Reply pushed = push(phone, List.of(lectureIntent(lectureID, COURSE_CS, 9),
				attendanceIntent(lectureID, STUDENT_CS, true)));

		assertEquals(200, pushed.status(), pushed::toString);
		assertEquals(2, pushed.body().get("accepted").intValue(), pushed::toString);

		assertEquals(1, count("SELECT count(*) FROM lectures WHERE lecture_id = " + lectureID));
		assertEquals(1, count("SELECT count(*) FROM attendance WHERE lecture_id = "
				+ lectureID));
	}

	@Test
	void aProvisionalNodeThatIsNotThisDevicesIsStillRefused() throws Exception {

		Phone phone = signInWithAPhone("a.cs@sies.edu.in");
		Phone other = signInWithAPhone("b.chem@sies.edu.in");

		// Another install's self-picked node. Accepting the device's own prior node must not
		// become accepting the whole self-picked half of the range.
		long foreign = StructuredId.compose(StructuredId.MIN_COUNTER + 710, other.provisional);

		Reply pushed = push(phone, List.of(lectureIntent(foreign, COURSE_CS, 9)));

		assertEquals(200, pushed.status(), pushed::toString);
		assertEquals(0, pushed.body().get("accepted").intValue(), pushed::toString);
		assertTrue(pushed.body().get("rejected").get(0).get("reason").stringValue()
						.contains("no such row here"),
				pushed.body().get("rejected").toString());

		assertEquals(0, count("SELECT count(*) FROM lectures"));
	}

	@Test
	void anotherDevicesRowMayBeAmendedWhenItIsAlreadyThere() throws Exception {

		// One professor, two phones -- a new handset, or a tablet for the laboratory. The
		// second one has no business creating rows under the first one's node, and every
		// business correcting a mark on a lecture the first one recorded.
		Phone first = signInWithAPhone("a.cs@sies.edu.in");
		long lectureID = first.key(500);

		assertEquals(2, push(first, List.of(lectureIntent(lectureID, COURSE_CS, 9),
				attendanceIntent(lectureID, STUDENT_CS, true))).body()
				.get("accepted").intValue());

		Phone second = signInWithAPhone("a.cs@sies.edu.in");

		assertTrue(second.node != first.node, "two installs, two nodes");

		Reply pushed = push(second, List.of(attendanceIntent(lectureID, STUDENT_CS, false),
				lectureIntent(lectureID, COURSE_CS, 9)));

		assertEquals(200, pushed.status(), pushed::toString);
		assertEquals(2, pushed.body().get("accepted").intValue(), pushed::toString);

		assertEquals(1, count("SELECT count(*) FROM attendance WHERE NOT present"),
				"the correction from the second phone is the one that stands");
		assertEquals(1, count("SELECT count(*) FROM lectures"),
				"and it amended the lecture rather than making a second one");
	}

	@Test
	void aKeyFromBeforeTheIdSchemeIsRefused() throws Exception {

		Phone phone = signInWithAPhone("a.cs@sies.edu.in");

		Reply pushed = push(phone, List.of(lectureIntent(42L, COURSE_CS, 9)));

		assertEquals(0, pushed.body().get("accepted").intValue());
		assertTrue(pushed.body().get("rejected").get(0).get("reason").stringValue()
						.contains("belongs in a backup import"),
				"a legacy key means nothing outside the file it came from, and the importer is "
						+ "the thing that can renumber it");
	}

	@Test
	void aLectureInAnotherDepartmentIsRefusedByThePolicies() throws Exception {

		Phone phone = signInWithAPhone("a.cs@sies.edu.in");

		// A Computer Science professor, a Chemistry course. The key is this device's, so the
		// node check passes and the refusal comes from where it should: the database.
		Reply pushed = push(phone, List.of(lectureIntent(phone.key(500), COURSE_CHEM, 9)));

		assertEquals(0, pushed.body().get("accepted").intValue(), pushed::toString);
		assertTrue(pushed.body().get("rejected").get(0).get("reason").stringValue()
						.contains("not yours to change"),
				pushed.body().get("rejected").toString());

		// Not final, and worth saying why: this professor cannot see that Chemistry course at
		// all, so the server cannot tell "not your course" from "that course has not reached
		// us yet". It keeps the intent rather than dropping it, which leaves a stuck row with
		// a sentence against it instead of a lost one. Keeping beats dropping whenever the
		// answer is unclear.
		assertFalse(pushed.body().get("rejected").get(0).get("permanent").booleanValue(),
				pushed.body().get("rejected").toString());

		assertEquals(0, count("SELECT count(*) FROM lectures"));
	}

	@Test
	void oneRefusedRowDoesNotCostTheRestOfTheBatch() throws Exception {

		Phone phone = signInWithAPhone("a.cs@sies.edu.in");
		long mine = phone.key(600);

		Reply pushed = push(phone, List.of(
				lectureIntent(mine, COURSE_CS, 9),
				lectureIntent(phone.key(601), COURSE_CHEM, 10),
				attendanceIntent(mine, STUDENT_CS, true)));

		assertEquals(200, pushed.status(), pushed::toString);
		assertEquals(2, pushed.body().get("accepted").intValue(), pushed::toString);
		assertEquals(1, pushed.body().get("rejected").size());

		assertEquals(1, count("SELECT count(*) FROM lectures"));
		assertEquals(1, count("SELECT count(*) FROM attendance"),
				"a phone that has been offline for a day must not have the whole day refused "
						+ "over one bad row");
	}

	@Test
	void aProfessorMayNotPushTheRoll() throws Exception {

		// V11 accepts students from a device. Who may write one is the policies' decision, and
		// their answer is the office: a roll number is what every other session's records are
		// matched by, so a professor rewriting their own class's roll would be rewriting
		// history nobody asked them to.
		Phone phone = signInWithAPhone("a.cs@sies.edu.in");

		Reply pushed = push(phone, List.of(studentIntent(phone.key(600), "NEW PERSON")));

		assertEquals(0, pushed.body().get("accepted").intValue(), pushed::toString);
		assertTrue(pushed.body().get("rejected").get(0).get("reason").stringValue()
						.contains("loaded by the office"),
				"the sentence a professor meets has to say what to do about it: "
						+ pushed.body().get("rejected"));
		assertTrue(pushed.body().get("rejected").get(0).get("permanent").booleanValue(),
				"a policy refusal is settled: a device that kept retrying it would never "
						+ "empty its queue, and the row behind it is real attendance");

		assertEquals(2, count("SELECT count(*) FROM students"));
	}

	@Test
	void aProfessorPushesABatchOfTheirDepartmentWithAnotherDepartmentsStudent() throws Exception {

		// R RAO, a CS major in BSc_unaided, takes Chemistry as an open elective. No Chemistry
		// course reaches her by the rules -- it is BSc -- so the Chemistry professor reads her
		// only as one of Chemistry's own open-elective students (V30).
		postgres.asSuperuser("UPDATE student_year SET oe_dept = 'CHEMISTRY' "
				+ "WHERE student_id = 2001");

		Phone phone = signInWithAPhone("b.chem@sies.edu.in");
		long batchID = phone.key(620);

		Map<String, Object> batch = new LinkedHashMap<>();
		batch.put("batchID", batchID);
		batch.put("batchName", "Chemistry OE");
		batch.put("batchDesc", null);
		batch.put("academicYear", 2025);
		batch.put("department", "CHEMISTRY");

		Map<String, Object> member = new LinkedHashMap<>();
		member.put("batchID", batchID);
		member.put("studentID", 2001L);
		member.put("academicYear", 2025);

		Map<String, Object> link = new LinkedHashMap<>();
		link.put("courseID", 3002L);
		link.put("batchID", batchID);

		// Every push is an upsert, which PostgreSQL also holds to the read policies: V34 is
		// what lets a batch, and its members, be read before anything points at them.
		Reply pushed = push(phone, List.of(
				intent("batch_desc", List.of(batchID), "UPSERT", batch),
				intent("batch_student_map", List.of(batchID, 2001L, 2025L), "UPSERT", member),
				intent("course_batch_map", List.of(3002L, batchID), "UPSERT", link)));

		assertEquals(200, pushed.status(), pushed::toString);
		assertEquals(3, pushed.body().get("accepted").intValue(), pushed::toString);
		assertEquals(1, count("SELECT count(*) FROM batch_student_map WHERE batch_id = "
				+ batchID));
	}

	@Test
	void theOfficeSettingHodOnAPhoneMakesTheProfessorHead() throws Exception {

		// A professor the office added on a phone, with an account made for them since.
		Phone office = signInWithAPhone("office@sies.edu.in");
		long profID = office.key(630);

		Map<String, Object> professor = new LinkedHashMap<>();
		professor.put("profID", profID);
		professor.put("name", "D DUTTA");
		professor.put("email", "d.dutta@sies.edu.in");
		professor.put("department", "CHEMISTRY");
		professor.put("designation", "Professor");

		Reply added = push(office, List.of(
				intent("professors", List.of(profID), "UPSERT", professor)));
		assertEquals(1, added.body().get("accepted").intValue(), added::toString);

		long accountID = account(AccountKind.PROFESSOR, profID, null, "d.dutta@sies.edu.in");

		String head = "SELECT count(*) FROM user_role WHERE account_id = " + accountID
				+ " AND role = 'HOD' AND department = 'CHEMISTRY' AND revoked_at IS NULL";

		assertEquals(0, count(head));

		// The office sets HOD on the phone.
		professor.put("designation", "HOD");
		Reply pushed = push(office, List.of(
				intent("professors", List.of(profID), "UPSERT", professor)));

		assertEquals(1, pushed.body().get("accepted").intValue(), pushed::toString);
		assertEquals(1, count(head),
				"head of their own department, as the console would make them");
		assertEquals(1, count("SELECT count(*) FROM user_role WHERE account_id = " + accountID
				+ " AND granted_by = " + accountOffice), "granted by whoever set the designation");

		// Sent again, nothing more: one appointment, not two.
		push(office, List.of(intent("professors", List.of(profID), "UPSERT", professor)));
		assertEquals(1, count(head));

		// And taken away again, the appointment stays until an administrator ends it.
		professor.put("designation", "Professor");
		push(office, List.of(intent("professors", List.of(profID), "UPSERT", professor)));
		assertEquals(1, count(head));
	}

	@Test
	void theOfficeMayPushTheRoll() throws Exception {

		Phone office = signInWithAPhone("office@sies.edu.in");
		long studentID = office.key(610);

		Reply pushed = push(office, List.of(studentIntent(studentID, "T THOMAS")));

		assertEquals(200, pushed.status(), pushed::toString);
		assertEquals(1, pushed.body().get("accepted").intValue(), pushed::toString);

		assertEquals(1, count("SELECT count(*) FROM students WHERE student_id = " + studentID));
		assertEquals(1, count("SELECT count(*) FROM audit_log WHERE table_name = 'students' "
						+ "AND action = 'INSERT' AND actor_account = " + accountOffice),
				"a roster row arriving from a device is still a change with an author");
	}

	@Test
	void aTableNoDeviceHoldsIsNotAccepted() throws Exception {

		Phone phone = signInWithAPhone("a.cs@sies.edu.in");

		Map<String, Object> row = new LinkedHashMap<>();
		row.put("accountID", 99L);

		Reply pushed = push(phone, List.of(intent("app_account", List.of(99L), "UPSERT", row)));

		assertEquals(0, pushed.body().get("accepted").intValue());
		assertTrue(pushed.body().get("rejected").get(0).get("reason").stringValue()
						.contains("does not accept app_account"),
				"synced_tables() is the list, and the server's own tables are not on it");
	}

	@Test
	void aRowPointingAtAnUnsyncableParentIsRefusedForGoodAndSaysWhy() throws Exception {

		// What a phone whose database predates the id scheme sends: an enrolment keyed on a
		// student numbered 1. That student cannot be pushed -- a key counted out from 1 by
		// SQLite means nothing off the device -- so the enrolment can never land, and what
		// arrived before this was a foreign key error that the device kept and retried on
		// every sync, a hundred rows at a time, for ever.
		Phone phone = signInWithAPhone("office@sies.edu.in");

		Map<String, Object> enrolment = new LinkedHashMap<>();
		enrolment.put("studentID", 1L);
		enrolment.put("academicYear", (long) SESSION);
		enrolment.put("rollNo", "CS-FY-900");
		enrolment.put("year", "FY");
		enrolment.put("stream", "BSc_unaided");
		enrolment.put("majorDept", "COMPUTER_SCIENCE");
		enrolment.put("minorDept", null);
		enrolment.put("oeDept", null);
		enrolment.put("semester", 1L);
		enrolment.put("division", null);

		Reply pushed = push(phone, List.of(intent("student_year",
				List.of(1L, (long) SESSION), "UPSERT", enrolment)));

		assertEquals(200, pushed.status(), pushed::toString);
		assertEquals(0, pushed.body().get("accepted").intValue(), pushed::toString);

		JsonNode refusal = pushed.body().get("rejected").get(0);

		assertTrue(refusal.get("permanent").booleanValue(),
				"waiting for a parent that can never be pushed is waiting for ever: "
						+ refusal);
		assertTrue(refusal.get("reason").stringValue().contains("backup import"),
				"and the sentence has to say what to do instead: " + refusal);
		assertTrue(refusal.get("reason").stringValue().contains("studentID 1"), refusal::toString);
	}

	@Test
	void aParentThatIsHereIsFineWhateverItsKeyLooksLike() throws Exception {

		// The other half of the rule. A server whose rows came from a backup import or a
		// roster CSV has them keyed under node 0, and this fixture inserts them by hand, so a
		// parent that is present must not be judged by the shape of its key -- a device
		// holding that key got it from a pull.
		Phone phone = signInWithAPhone("office@sies.edu.in");

		Map<String, Object> enrolment = new LinkedHashMap<>();
		enrolment.put("studentID", STUDENT_CS);
		enrolment.put("academicYear", (long) SESSION);
		enrolment.put("rollNo", "CS-FY-001");
		enrolment.put("year", "FY");
		enrolment.put("stream", "BSc_unaided");
		enrolment.put("majorDept", "COMPUTER_SCIENCE");
		enrolment.put("minorDept", null);
		enrolment.put("oeDept", null);
		enrolment.put("semester", 2L);
		enrolment.put("division", null);

		Reply pushed = push(phone, List.of(intent("student_year",
				List.of(STUDENT_CS, (long) SESSION), "UPSERT", enrolment)));

		assertEquals(200, pushed.status(), pushed::toString);
		assertEquals(1, pushed.body().get("accepted").intValue(), pushed::toString);
		assertEquals(2, count("SELECT semester FROM student_year WHERE student_id = "
				+ STUDENT_CS + " AND academic_year = " + SESSION));
	}

	@Test
	void aMarkWhoseLectureHasNotArrivedIsKeptRatherThanDropped() throws Exception {

		Phone phone = signInWithAPhone("a.cs@sies.edu.in");

		// A mark on its own, with no lecture. attendance's policies reach the lecture to decide
		// anything, so this comes back as "not yours to change" when the truth is "not yet" --
		// and the batch that sends the lecture would have had it accepted. Dropping it as a
		// settled refusal would be losing attendance.
		Reply pushed = push(phone, List.of(
				attendanceIntent(phone.key(620), STUDENT_CS, true)));

		assertEquals(0, pushed.body().get("accepted").intValue(), pushed::toString);
		assertFalse(pushed.body().get("rejected").get(0).get("permanent").booleanValue(),
				"a refusal that the next batch could resolve must not be final: "
						+ pushed.body().get("rejected"));

		// And it does resolve: the same mark, once its lecture is there.
		long lectureID = phone.key(621);

		assertEquals(2, push(phone, List.of(lectureIntent(lectureID, COURSE_CS, 9),
				attendanceIntent(lectureID, STUDENT_CS, true))).body()
				.get("accepted").intValue());
	}

	@Test
	void aSessionWithNoDeviceMayNotWriteKeyedRows() throws Exception {

		// Signed in, but never registered a phone: an office desk, or a phone that has not
		// called POST /devices yet.
		String token = tokenFor("a.cs@sies.edu.in");

		Reply pushed = push(token, null, List.of(lectureIntent(
				StructuredId.compose(StructuredId.MIN_COUNTER + 700, 5), COURSE_CS, 9)));

		assertEquals(0, pushed.body().get("accepted").intValue(), pushed::toString);
		assertTrue(pushed.body().get("rejected").get(0).get("reason").stringValue()
						.contains("not on a registered device"),
				pushed.body().get("rejected").toString());
	}

	// ── Ordering ─────────────────────────────────────────────────────────

	@Test
	void marksAreAppliedAfterTheirLectureWhateverOrderTheyArriveIn() throws Exception {

		Phone phone = signInWithAPhone("a.cs@sies.edu.in");
		long lectureID = phone.key(800);

		// The sheet first, which is the order a careless client would send and the order a
		// foreign key refuses.
		Reply pushed = push(phone, List.of(
				attendanceIntent(lectureID, STUDENT_CS, true),
				lectureIntent(lectureID, COURSE_CS, 9)));

		assertEquals(2, pushed.body().get("accepted").intValue(),
				"the server sorts a batch into dependency order rather than taking the "
						+ "sender's word for it");

		assertEquals(1, count("SELECT count(*) FROM attendance"));
	}

	/**
	 * A student with a key the server minted, enrolled where the Computer Science professor
	 * can see them.
	 * <p>
	 * Written as the superuser, because putting a student on the roll is the office's and this
	 * is a fixture rather than a thing under test.
	 */
	private long newStudentOnTheCsRoll(String name, String email) throws Exception {

		long student = StructuredId.compose(StructuredId.MIN_COUNTER + 500,
				StructuredId.SERVER_NODE);

		postgres.asSuperuser("""
				INSERT INTO students (student_id, name, pronouns, email) VALUES
				    (%d, '%s', 'they/them', '%s');
				INSERT INTO student_year
				    (student_id, academic_year, roll_no, year, stream, major_dept, semester)
				VALUES (%d, %d, 'CS-FY-%03d', 'FY', 'BSc_unaided', 'COMPUTER_SCIENCE', 1);
				""".formatted(student, name, email, student, SESSION,
						(int) (student % 900) + 10));

		return student;
	}

	@Test
	void aDeletionThePoliciesWillNotMakeIsReportedAndNotSwallowed() throws Exception {

		// Found on a handset: a student deleted in the app, synced, and still on the server --
		// with a successful sync and an empty queue to say otherwise.
		//
		// The cause is the rule this project wrote down about UPDATE and then leaned on for
		// DELETE: a statement no policy's USING clause admits matches NO ROWS rather than
		// raising. So the delete ran, changed nothing, reported zero, and the writer read
		// zero as "the row was not there anyway" -- which is the other thing zero means.
		// A student the server minted, because the fixture's 2001 predates the id scheme and
		// the node check refuses it before the delete path is reached -- which is itself
		// right, and would have hidden what this test is about.
		long student = newStudentOnTheCsRoll("D DELETABLE", "d.deletable@sies.edu.in");

		Phone phone = signInWithAPhone("a.cs@sies.edu.in");

		Reply pushed = push(phone, List.of(
				intent("students", List.of(student), "DELETE", null)));

		assertEquals(0, pushed.body().get("accepted").intValue(), pushed::toString);
		assertEquals(1, pushed.body().get("rejected").size(), pushed::toString);

		String reason = pushed.body().get("rejected").get(0).get("reason").stringValue();

		assertTrue(reason.contains("office"), reason);
		assertTrue(reason.contains("still on the server"),
				"and it says what is now true of the two copies: " + reason);
		assertTrue(pushed.body().get("rejected").get(0).get("permanent").booleanValue(),
				"sending it again cannot help, and a queue that retries for ever never "
						+ "empties");

		assertEquals(1, count("SELECT count(*) FROM students WHERE student_id = " + student),
				"the student is still there, which was always true -- the news is that the "
						+ "phone is told");
	}

	@Test
	void theHeadOfDepartmentMayAddAndRemoveTheirOwnStudents() throws Exception {

		// V20. The roll was the office's alone, so a head of department could read their
		// department's roll and change nothing on it: a student who had left, or one the
		// office had not entered yet, was a message to somebody else and a wait.
		Phone phone = signInWithAPhone("c.head@sies.edu.in");

		// phone.key, not a server-minted one: a device may only create rows under its own
		// node, and a students row carrying node 0 is one this phone is claiming to amend
		// rather than to add. The node check says so, and it is right to.
		long student = phone.key(700);

		Reply added = push(phone, List.of(
				intent("students", List.of(student), "UPSERT", Map.of(
						"studentID", student, "name", "H HEADS OWN",
						"pronouns", "they/them", "email", "h.own@sies.edu.in")),
				intent("student_year", List.of(student, (long) SESSION), "UPSERT", Map.of(
						"studentID", student, "academicYear", SESSION,
						"rollNo", "CS-FY-700", "year", "FY", "stream", "BSc_unaided",
						"majorDept", "COMPUTER_SCIENCE", "semester", 1))));

		assertEquals(2, added.body().get("accepted").intValue(), added::toString);

		// And out again, enrolment first, which is the order the foreign key wants.
		Reply removed = push(phone, List.of(
				intent("student_year", List.of(student, (long) SESSION), "DELETE", null),
				intent("students", List.of(student), "DELETE", null)));

		assertEquals(2, removed.body().get("accepted").intValue(), removed::toString);
		assertEquals(0, count("SELECT count(*) FROM students WHERE student_id = " + student));
	}

	@Test
	void aStudentWhoRepresentsACourseCannotBeRemovedAndTheRefusalSaysSo() throws Exception {

		// Found on a handset: a head of department removed a student who was a class
		// representative and got "violates RESTRICT setting of foreign key constraint
		// attendance_delegate_student_id_fkey", every sync, for ever. A constraint name where
		// a sentence belongs, and a refusal treated as transient because 23503 is not in
		// SETTLED_STATES -- which is right for an upsert waiting for its parent and backwards
		// for a delete held down by a child.
		long student = newStudentOnTheCsRoll("R REPRESENTS", "r.represents@sies.edu.in");

		// Designated by the head, in the capacity they actually hold: the BEFORE trigger on
		// attendance_delegate checks that, whoever runs the statement.
		postgres.asSuperuser("INSERT INTO attendance_delegate (student_id, course_id, "
				+ "academic_year, granted_by, granted_as) VALUES ("
				+ student + ", " + COURSE_CS + ", " + SESSION + ", "
				+ accountHeadCs + ", 'HOD')");

		Phone phone = signInWithAPhone("c.head@sies.edu.in");

		Reply refused = push(phone, List.of(
				intent("student_year", List.of(student, (long) SESSION), "DELETE", null),
				intent("students", List.of(student), "DELETE", null)));

		// The enrolment goes; the student is held down by the appointment.
		assertEquals(1, refused.body().get("accepted").intValue(), refused::toString);

		var rejection = refused.body().get("rejected").get(0);
		String reason = rejection.get("reason").stringValue();

		assertEquals("students", rejection.get("table").stringValue());
		assertTrue(reason.contains("class representative"),
				"the sentence names what is in the way, not a constraint: " + reason);
		assertFalse(reason.contains("fkey"), "and never a constraint name: " + reason);
		assertTrue(reason.contains("on the server first"),
				"and says where it has to be undone: " + reason);

		assertTrue(rejection.get("permanent").booleanValue(),
				"an appointment cannot be cleared from a phone, so retrying is a queue that "
						+ "never empties -- which is what it did");

		// And once the appointment is ended, the same deletion goes through.
		postgres.asSuperuser("UPDATE attendance_delegate SET revoked_at = now(), "
				+ "revoked_by = " + accountHeadCs + " WHERE student_id = " + student);

		assertEquals(0, push(phone, List.of(intent("students", List.of(student), "DELETE",
				null))).body().get("accepted").intValue(),
				"a revoked appointment keeps its row, which still points at the student -- "
						+ "nothing in this schema is deleted, and that is the trade");
	}

	@Test
	void aDeleteHeldDownByRowsTheDeviceIsAlsoSendingIsWorthWaitingFor() throws Exception {

		// The other half of the rule, and the reason 23503 is not simply settled: the
		// enrolment is the device's to delete too, so a batch that sent the parent first
		// should be told to try again rather than to give up.
		long student = newStudentOnTheCsRoll("W WAITABLE", "w.waitable@sies.edu.in");

		Phone phone = signInWithAPhone("c.head@sies.edu.in");

		Reply refused = push(phone, List.of(
				intent("students", List.of(student), "DELETE", null)));

		assertEquals(0, refused.body().get("accepted").intValue(), refused::toString);

		var rejection = refused.body().get("rejected").get(0);
		String reason = rejection.get("reason").stringValue();

		assertTrue(reason.contains("enrolment"),
				"it names the rows that are in the way: " + reason);
		assertFalse(rejection.get("permanent").booleanValue(),
				"an enrolment is a table this device holds, so the next batch may clear it: "
						+ reason);

		// Sent in the device's own dependency order, it goes through.
		Reply ordered = push(phone, List.of(
				intent("student_year", List.of(student, (long) SESSION), "DELETE", null),
				intent("students", List.of(student), "DELETE", null)));

		assertEquals(2, ordered.body().get("accepted").intValue(), ordered::toString);
		assertEquals(0, count("SELECT count(*) FROM students WHERE student_id = " + student));
	}

	@Test
	void theHeadOfOneDepartmentCannotTouchAnothers() throws Exception {

		// The careful half of V20: the authority is the department, and the enrolment is what
		// says whose student this is. The head of Computer Science may not enrol somebody in
		// Chemistry, and may not remove a student who is on Chemistry's roll -- that row is
		// Chemistry's as much as theirs.
		Phone phone = signInWithAPhone("c.head@sies.edu.in");

		long student = phone.key(701);

		Reply refused = push(phone, List.of(
				intent("students", List.of(student), "UPSERT", Map.of(
						"studentID", student, "name", "O OTHER DEPT",
						"pronouns", "they/them", "email", "o.other@sies.edu.in")),
				intent("student_year", List.of(student, (long) SESSION), "UPSERT", Map.of(
						"studentID", student, "academicYear", SESSION,
						"rollNo", "CH-FY-701", "year", "FY", "stream", "BSc",
						"majorDept", "CHEMISTRY", "semester", 1))));

		// The bare students row is admitted -- a person with no enrolment is on nobody's roll
		// -- and the enrolment that would put them on Chemistry's is not.
		assertEquals(1, refused.body().get("accepted").intValue(), refused::toString);
		assertEquals(1, refused.body().get("rejected").size(), refused::toString);
		assertEquals("student_year",
				refused.body().get("rejected").get(0).get("table").stringValue());

		// And a student on Chemistry's roll is not theirs to remove.
		Reply theirs = push(phone, List.of(
				intent("students", List.of(STUDENT_CHEM), "DELETE", null)));

		assertEquals(0, theirs.body().get("accepted").intValue(), theirs::toString);
		assertEquals(1, count("SELECT count(*) FROM students WHERE student_id = "
				+ STUDENT_CHEM));
	}

	@Test
	void aPlainProfessorStillOnlyReadsTheRoll() throws Exception {

		// Unchanged by V20, and deliberately: teaching a course is not keeping the roll. The
		// roll is what says who is on the course in the first place, and a professor
		// correcting it would be a professor deciding which of their students exist.
		long student = newStudentOnTheCsRoll("P PLAIN", "p.plain@sies.edu.in");

		Phone phone = signInWithAPhone("a.cs@sies.edu.in");

		Reply refused = push(phone, List.of(
				intent("students", List.of(student), "DELETE", null)));

		assertEquals(0, refused.body().get("accepted").intValue(), refused::toString);
		assertTrue(refused.body().get("rejected").get(0).get("reason").stringValue()
						.contains("office"),
				refused::toString);
	}

	@Test
	void theOfficesOwnDeletionLands() throws Exception {

		// The other half: the rule is not "deletions never sync", it is "the roll is the
		// office's". An office account's deletion goes through, which is what makes the
		// refusal above a statement about authority rather than about deletions.
		long student = newStudentOnTheCsRoll("E ERASABLE", "e.erasable@sies.edu.in");

		Phone phone = signInWithAPhone("office@sies.edu.in");

		// The enrolment goes first: student_year points at the student, and the roll is the
		// office's too, so both deletions are theirs to make and the order is the phone's own
		// dependency order.
		Reply pushed = push(phone, List.of(
				// (long) SESSION: a key is a list of longs, and List.of(long, int) infers
				// nothing both can be.
				intent("student_year", List.of(student, (long) SESSION), "DELETE", null),
				intent("students", List.of(student), "DELETE", null)));

		assertEquals(2, pushed.body().get("accepted").intValue(), pushed::toString);
		assertEquals(0, count("SELECT count(*) FROM students WHERE student_id = " + student));
	}

	@Test
	void aDeletionOfARowTheServerNeverHadIsStillAccepted() throws Exception {

		// The leniency that was there for a reason, and which the fix must keep: a device
		// cannot tell whether its batch arrived, so it sends it again. A delete of a row that
		// is already gone is the ordinary case of that, and it has to be accepted or the
		// queue never empties.
		Phone phone = signInWithAPhone("a.cs@sies.edu.in");
		long lectureID = phone.key(901);

		push(phone, List.of(lectureIntent(lectureID, COURSE_CS, 9)));
		push(phone, List.of(intent("lectures", List.of(lectureID), "DELETE", null)));

		Reply again = push(phone, List.of(
				intent("lectures", List.of(lectureID), "DELETE", null)));

		assertEquals(1, again.body().get("accepted").intValue(),
				"a replayed delete is not a refusal: " + again);
	}

	@Test
	void aLecturesMarksGoBeforeTheLectureDoes() throws Exception {

		Phone phone = signInWithAPhone("a.cs@sies.edu.in");
		long lectureID = phone.key(900);

		push(phone, List.of(lectureIntent(lectureID, COURSE_CS, 9),
				attendanceIntent(lectureID, STUDENT_CS, true)));

		// Deleting a lecture on the phone deletes its marks too, and both intents are queued.
		// Sent lecture-first, which is the order the foreign key refuses.
		Reply pushed = push(phone, List.of(
				intent("lectures", List.of(lectureID), "DELETE", null),
				intent("attendance", List.of(lectureID, STUDENT_CS), "DELETE", null)));

		assertEquals(2, pushed.body().get("accepted").intValue(), pushed::toString);
		assertEquals(0, count("SELECT count(*) FROM lectures"));
		assertEquals(0, count("SELECT count(*) FROM attendance"));
	}

	@Test
	void whatTheServerMayLeaveNullTheDeviceMayLeaveNullToo() throws Exception {

		// The other half of the lesson a handset taught: a column the server can send as null
		// and the device declares NOT NULL is a row that cannot be written, which is a pull
		// that cannot move its watermark, which is a sync that never finishes. PullApplyTest
		// checks the shape of a row the server sends; this checks the two descriptions of
		// every column agree about nullability in the direction that matters.
		List<String> wrong = new ArrayList<>();

		try (Connection connection = postgres.connection()) {

			for (String name : syncedTables(connection)) {

				DeviceTables.Table table = DeviceTables.of(name);

				for (DeviceTables.Column column : table.carriedColumns()) {

					boolean postgresAllowsNull = "YES".equals(nullability(connection, name,
							column.postgres()));

					if (postgresAllowsNull && !column.nullable()) {
						wrong.add(name + "." + column.postgres() + " is nullable in PostgreSQL "
								+ "and DeviceTables says it is not");
					}
				}
			}
		}

		assertTrue(wrong.isEmpty(), String.join("\n", wrong));
	}

	@NonNull
	private static List<String> syncedTables(@NonNull Connection connection)
			throws SQLException {

		List<String> tables = new ArrayList<>();

		try (Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(
				     "SELECT unnest(synced_tables())")) {

			while (results.next()) {
				tables.add(results.getString(1));
			}
		}
		return tables;
	}

	@Nullable
	private static String nullability(@NonNull Connection connection, @NonNull String table,
	                                  @NonNull String column) throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT is_nullable FROM information_schema.columns "
						+ "WHERE table_schema = current_schema() AND table_name = ? "
						+ "  AND column_name = ?")) {

			statement.setString(1, table);
			statement.setString(2, column);

			try (ResultSet results = statement.executeQuery()) {
				return results.next() ? results.getString(1) : null;
			}
		}
	}

	// ── The pull ─────────────────────────────────────────────────────────

	@Test
	void aFirstPullIsASnapshotAndYieldsAWatermark() throws Exception {

		Phone phone = signInWithAPhone("a.cs@sies.edu.in");
		long lectureID = phone.key(1000);

		push(phone, List.of(lectureIntent(lectureID, COURSE_CS, 9),
				attendanceIntent(lectureID, STUDENT_CS, true)));

		Reply pulled = get(phone, "/sync/pull");

		assertEquals(200, pulled.status(), pulled::toString);
		assertFalse(pulled.body().get("watermark").stringValue().isBlank(),
				"a snapshot has to come with somewhere to resume from");

		// Since V11 a snapshot is what a new install needs to be usable, not just the rows it
		// sent: the session, the staff list, the one student it may see, that student's
		// enrolment, its own course, and then the lecture and the mark.
		assertEquals(1, changesFor(pulled.body(), "academic_years").size());
		// Two: the professors of Computer Science, the head among them. Chemistry's professor
		// teaches nothing this professor reads, so does not come (V35).
		assertEquals(2, changesFor(pulled.body(), "professors").size());
		assertEquals(1, changesFor(pulled.body(), "courses").size(), pulled::toString);
		assertEquals(1, changesFor(pulled.body(), "students").size());
		assertEquals(1, changesFor(pulled.body(), "student_year").size());
		assertEquals(1, changesFor(pulled.body(), "lectures").size());
		assertEquals(1, changesFor(pulled.body(), "attendance").size());

		// In dependency order, so a device can apply the page as it arrives: nothing points at
		// a row that has not been sent yet.
		List<Integer> depths = new ArrayList<>();

		for (JsonNode change : pulled.body().get("changes")) {
			depths.add(DeviceTables.depthOf(change.get("table").stringValue()));
		}

		for (int at = 1; at < depths.size(); at++) {
			assertTrue(depths.get(at) >= depths.get(at - 1),
					"a snapshot out of dependency order cannot be applied as it arrives: "
							+ depths);
		}
	}

	@Test
	void aSnapshotTooBigForOnePageResumesWhereItStopped() throws Exception {

		// The failure this guards against was invisible while the feed was two tables of a
		// fixture: a truncated snapshot used to hand back the HEAD as its watermark, so every
		// row past the first page was never sent and no later pull would ever ask for it. A
		// college roster is thousands of rows and a page is hundreds.
		Phone phone = signInWithAPhone("a.cs@sies.edu.in");
		long lectureID = phone.key(1050);

		push(phone, List.of(lectureIntent(lectureID, COURSE_CS, 9),
				attendanceIntent(lectureID, STUDENT_CS, true)));

		List<String> seen = new ArrayList<>();
		String watermark = null;

		for (int page = 0; page < 20; page++) {

			Reply pulled = get(phone, "/sync/pull?limit=2"
					+ (watermark == null ? "" : "&since=" + urlEncode(watermark)));

			assertEquals(200, pulled.status(), pulled::toString);

			for (JsonNode change : pulled.body().get("changes")) {
				seen.add(change.get("table").stringValue() + change.get("key"));
			}

			watermark = pulled.body().get("watermark").stringValue();

			if (!pulled.body().get("more").booleanValue()) {
				break;
			}
		}

		assertEquals(9, seen.size(), "every row, across the pages: " + seen);
		assertEquals(seen.size(), new java.util.LinkedHashSet<>(seen).size(),
				"and each of them once: " + seen);

		// And the watermark the last page handed back is an ordinary one, so the next pull is
		// a feed read rather than a fourth attempt at the snapshot.
		Reply after = get(phone, "/sync/pull?since=" + urlEncode(watermark));

		assertEquals(200, after.status(), after::toString);
		assertEquals(0, after.body().get("changes").size(),
				"nothing has happened since the snapshot, so there is nothing to send");
	}

	@Test
	void theRowsComeBackInTheDevicesOwnRepresentation() throws Exception {

		Phone phone = signInWithAPhone("a.cs@sies.edu.in");
		long lectureID = phone.key(1100);

		push(phone, List.of(lectureIntent(lectureID, COURSE_CS, 9),
				attendanceIntent(lectureID, STUDENT_CS, false)));

		JsonNode pulled = get(phone, "/sync/pull").body();

		JsonNode lecture = changesFor(pulled, "lectures").get(0).get("row");
		JsonNode marks = changesFor(pulled, "attendance").get(0).get("row");

		// Room's column names, and Room's values: the phone applies these without converting
		// anything, which is why the server converts in both directions.
		assertEquals(COURSE_CS, lecture.get("courseID").longValue());
		assertTrue(lecture.get("batchID").isNull(), lecture.toString());

		String stored = lecture.get("startDateTime").stringValue();
		assertNotNull(StoredTimes.parse(stored), stored);
		assertTrue(stored.contains("|Asia/Kolkata"),
				"the zone the lecture was recorded in travels with the instant: " + stored);

		assertEquals(0, marks.get("present").intValue(),
				"SQLite has no boolean, so a mark is 0 or 1 and not false");
	}

	@Test
	void aPullWithAWatermarkBringsOnlyWhatChangedSince() throws Exception {

		Phone phone = signInWithAPhone("a.cs@sies.edu.in");

		push(phone, List.of(lectureIntent(phone.key(1200), COURSE_CS, 9)));

		String watermark = get(phone, "/sync/pull").body().get("watermark").stringValue();

		Reply quiet = get(phone, "/sync/pull?since=" + urlEncode(watermark));

		assertEquals(200, quiet.status(), quiet::toString);
		assertEquals(0, quiet.body().get("changes").size(),
				"nothing has happened, so nothing comes back");

		long second = phone.key(1201);
		push(phone, List.of(lectureIntent(second, COURSE_CS, 10)));

		Reply changed = get(phone, "/sync/pull?since=" + urlEncode(watermark));

		assertEquals(1, changed.body().get("changes").size(), changed::toString);
		assertEquals(second, changed.body().get("changes").get(0).get("key").get(0).longValue());
	}

	@Test
	void aDeletionReachesThePullAsADeletion() throws Exception {

		Phone phone = signInWithAPhone("a.cs@sies.edu.in");
		long lectureID = phone.key(1300);

		push(phone, List.of(lectureIntent(lectureID, COURSE_CS, 9)));

		String watermark = get(phone, "/sync/pull").body().get("watermark").stringValue();

		push(phone, List.of(intent("lectures", List.of(lectureID), "DELETE", null)));

		Reply pulled = get(phone, "/sync/pull?since=" + urlEncode(watermark));

		assertEquals(1, pulled.body().get("changes").size(), pulled::toString);
		assertEquals("DELETE", pulled.body().get("changes").get(0).get("op").stringValue(),
				"a device that never heard about a deleted lecture would show it for ever");
		assertTrue(pulled.body().get("changes").get(0).get("row").isNull());
	}

	@Test
	void aPullSeesNothingOfAnotherDepartment() throws Exception {

		Phone cs = signInWithAPhone("a.cs@sies.edu.in");
		push(cs, List.of(lectureIntent(cs.key(1400), COURSE_CS, 9),
				attendanceIntent(cs.key(1400), STUDENT_CS, true)));

		Phone chemistry = signInWithAPhone("b.chem@sies.edu.in");

		Reply pulled = get(chemistry, "/sync/pull");

		assertEquals(200, pulled.status(), pulled::toString);
		assertEquals(0, changesFor(pulled.body(), "lectures").size(),
				"the feed hands out keys; the policies are what make it the caller's keys");
		assertEquals(0, changesFor(pulled.body(), "attendance").size(), pulled::toString);

		// What a chemist does get is the shared scaffolding and their own roll, and the CS
		// student is not part of either. This is the reachability rule doing its job: a
		// department comparison would have been wrong, because a minor or an open elective
		// puts another department's student on the roll -- and nothing here does.
		assertEquals(1, changesFor(pulled.body(), "students").size(), pulled::toString);
		assertEquals(STUDENT_CHEM,
				changesFor(pulled.body(), "students").get(0).get("key").get(0).longValue());
		assertEquals(COURSE_CHEM,
				changesFor(pulled.body(), "courses").get(0).get("key").get(0).longValue());
	}

	@Test
	void theOfficeSeesEverybodysRows() throws Exception {

		Phone cs = signInWithAPhone("a.cs@sies.edu.in");
		push(cs, List.of(lectureIntent(cs.key(1500), COURSE_CS, 9)));

		Phone office = signInWithAPhone("office@sies.edu.in");

		JsonNode pulled = get(office, "/sync/pull").body();

		assertEquals(1, changesFor(pulled, "lectures").size());
		assertEquals(2, changesFor(pulled, "students").size(),
				"both departments' students, which is what an office account is for");
		assertEquals(2, changesFor(pulled, "courses").size(), pulled::toString);
	}

	@Test
	void aWatermarkThisServerDidNotIssueIsRefused() throws Exception {

		Phone phone = signInWithAPhone("a.cs@sies.edu.in");

		Reply pulled = get(phone, "/sync/pull?since=yesterday");

		assertEquals(400, pulled.status(), pulled::toString);
		assertEquals("bad_watermark", pulled.body().get("code").stringValue());
	}

	@Test
	void anEmptyPushIsRefusedAndAHugeOneIsRefusedTooSoon() throws Exception {

		Phone phone = signInWithAPhone("a.cs@sies.edu.in");

		assertEquals(400, push(phone, List.of()).status());

		List<Map<String, Object>> many = new ArrayList<>();

		for (int at = 0; at < 1001; at++) {
			many.add(lectureIntent(phone.key(2000 + at), COURSE_CS, 9));
		}

		Reply refused = push(phone, many);

		assertEquals(413, refused.status(), refused::toString);
		assertEquals(0, count("SELECT count(*) FROM lectures"),
				"refused before anything was written, not half way through");
	}

	@Test
	void syncNeedsATokenLikeEverythingElse() throws Exception {

		assertEquals(401, get(null, null, "/sync/pull").status());
		assertEquals(401, push(null, null, List.of()).status());
	}

	// ── The two sides agree about the format ─────────────────────────────

	@Test
	void theServerAcceptsWhatTheDevicesOwnWriterProduces() throws Exception {

		Phone phone = signInWithAPhone("a.cs@sies.edu.in");
		long lectureID = phone.key(1600);

		// Not a hand-built body: the bytes the phone would actually send, from the shared
		// writer in :shared. This and the test below are the only things that can find a
		// disagreement about the format without a handset in the room -- and a disagreement
		// about the format is a sync that fails in the field and nowhere else.
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("lectureID", lectureID);
		row.put("courseID", COURSE_CS);
		row.put("batchID", null);
		row.put("startDateTime", StoredTimes.format(
				java.time.ZonedDateTime.of(2025, 7, 14, 9, 0, 0, 0,
						java.time.ZoneId.of("Asia/Kolkata"))));
		row.put("endDateTime", StoredTimes.format(
				java.time.ZonedDateTime.of(2025, 7, 14, 9, 50, 0, 0,
						java.time.ZoneId.of("Asia/Kolkata"))));
		row.put("notes", "Rao said \"bring a calculator\"\nand a pen");

		String body = SyncJson.writePush(List.of(new SyncIntent("lectures",
				List.of(lectureID), OutboxOp.UPSERT, row)));

		Reply pushed = send(HttpRequest.newBuilder(
						URI.create("http://localhost:" + port + Api.V1 + "/sync/push"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)),
				phone.token(), phone.install());

		assertEquals(200, pushed.status(), pushed::toString);
		assertEquals(1, SyncJson.parsePushOutcome(pushed.raw()).accepted(),
				"the server has to accept the writer's own output, quotes and newlines and all");

		assertEquals(1, count("SELECT count(*) FROM lectures WHERE notes LIKE '%calculator%'"));
	}

	@Test
	void theDevicesOwnReaderUnderstandsThePullsAnswer() throws Exception {

		Phone phone = signInWithAPhone("a.cs@sies.edu.in");
		long lectureID = phone.key(1700);

		push(phone, List.of(lectureIntent(lectureID, COURSE_CS, 9),
				attendanceIntent(lectureID, STUDENT_CS, false)));

		Reply pulled = get(phone, "/sync/pull");

		// The server writes its response with Jackson and the phone reads it with the
		// hand-rolled reader in :shared. Nothing else checks that those two agree.
		SyncJson.PullPage page = SyncJson.parsePull(pulled.raw());

		assertFalse(page.watermark().isBlank());

		SyncIntent lecture = null;
		SyncIntent mark = null;

		for (SyncIntent change : page.changes()) {

			if ("lectures".equals(change.table())) {
				lecture = change;
			} else if ("attendance".equals(change.table())) {
				mark = change;
			}
		}

		assertNotNull(lecture, page.changes().toString());
		assertNotNull(mark, page.changes().toString());

		assertEquals(lectureID, (long) lecture.key().get(0));
		assertEquals(OutboxOp.UPSERT, lecture.op());
		assertEquals(COURSE_CS, lecture.row().get("courseID"),
				"a key read as a double would come back rounded, and be a different course");

		assertEquals(0L, mark.row().get("present"));

		// And the reader copes with every table the feed now carries, not only the two it was
		// written against: an enum, a date and a null all arrive through it.
		for (SyncIntent change : page.changes()) {
			assertFalse(change.table().isBlank());
			assertFalse(change.key().isEmpty());
		}
	}

	@Test
	void aDeletionReadBackByTheDeviceCarriesNoRow() throws Exception {

		Phone phone = signInWithAPhone("a.cs@sies.edu.in");
		long lectureID = phone.key(1800);

		push(phone, List.of(lectureIntent(lectureID, COURSE_CS, 9)));
		String watermark = get(phone, "/sync/pull").body().get("watermark").stringValue();
		push(phone, List.of(intent("lectures", List.of(lectureID), "DELETE", null)));

		SyncJson.PullPage page = SyncJson.parsePull(
				get(phone, "/sync/pull?since=" + urlEncode(watermark)).raw());

		assertEquals(1, page.changes().size());
		assertEquals(OutboxOp.DELETE, page.changes().get(0).op());
		assertNull(page.changes().get(0).row());
	}

	// ── Intents ──────────────────────────────────────────────────────────

	/** A lecture at nine, ten or eleven on the same morning, as the device holds it. */
	private static Map<String, Object> lectureIntent(long lectureID, long courseID, int hour) {

		Map<String, Object> row = new LinkedHashMap<>();
		row.put("lectureID", lectureID);
		row.put("courseID", courseID);
		row.put("batchID", null);
		row.put("startDateTime", StoredTimes.format(
				java.time.ZonedDateTime.of(2025, 7, 14, hour, 0, 0, 0,
						java.time.ZoneId.of("Asia/Kolkata"))));
		row.put("endDateTime", StoredTimes.format(
				java.time.ZonedDateTime.of(2025, 7, 14, hour, 50, 0, 0,
						java.time.ZoneId.of("Asia/Kolkata"))));
		row.put("notes", null);

		return intent("lectures", List.of(lectureID), "UPSERT", row);
	}

	private static Map<String, Object> attendanceIntent(long lectureID, long studentID,
	                                                    boolean present) {

		Map<String, Object> row = new LinkedHashMap<>();
		row.put("lectureID", lectureID);
		row.put("studentID", studentID);
		// 0 or 1, as SQLite holds it.
		row.put("present", present ? 1 : 0);

		return intent("attendance", List.of(lectureID, studentID), "UPSERT", row);
	}

	private static Map<String, Object> intent(String table, List<Long> key, String op,
	                                          Map<String, Object> row) {

		Map<String, Object> intent = new LinkedHashMap<>();
		intent.put("table", table);
		intent.put("key", key);
		intent.put("op", op);
		intent.put("row", row);
		return intent;
	}

	// ── A phone ──────────────────────────────────────────────────────────

	/** A signed-in, registered device: a token, an install and the node it was issued. */
	private record Phone(String token, UUID install, int node, int provisional) {

		/** A key this device would issue, distinct per {@code n}. */
		long key(int n) {
			return StructuredId.compose(StructuredId.MIN_COUNTER + n, node);
		}

		/**
		 * A key this device issued before it registered, under the node it had picked for
		 * itself. On a phone that has been in use for a term, every key looks like this.
		 */
		long keyFromBefore(int n) {
			return StructuredId.compose(StructuredId.MIN_COUNTER + n, provisional);
		}
	}

	private Phone signInWithAPhone(String email) throws Exception {

		String token = tokenFor(email);
		UUID install = UUID.randomUUID();

		// The node this phone picked for itself before it had ever spoken to a server, which
		// is what every row it created up to now carries. Kept, because that is the whole
		// point of sending it: registration is the only chance to record it.
		int provisional = 40000 + (int) (Math.random() * 20000);

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("installId", install.toString());
		body.put("provisionalNode", provisional);
		body.put("label", email + "'s phone");

		Reply registered = send(HttpRequest.newBuilder(
						URI.create("http://localhost:" + port + Api.V1 + "/devices"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body),
						StandardCharsets.UTF_8)), token, null);

		assertEquals(200, registered.status(), registered::toString);

		return new Phone(token, install, registered.body().get("node").intValue(),
				provisional);
	}

	private String tokenFor(String email) throws Exception {

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("email", email);
		body.put("door", "STAFF");
		body.put("password", PASSWORD);

		Reply reply = send(HttpRequest.newBuilder(
						URI.create("http://localhost:" + port + Api.V1 + "/auth/login"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body),
						StandardCharsets.UTF_8)), null, null);

		assertEquals(200, reply.status(), reply::toString);
		return reply.body().get("token").stringValue();
	}

	// ── HTTP ─────────────────────────────────────────────────────────────

	private record Reply(int status, JsonNode body, String raw) {

		@Override
		public String toString() {
			return status + " " + raw;
		}
	}

	/** A student row as a device would send one. */
	private Map<String, Object> studentIntent(long studentID, String name) {

		Map<String, Object> student = new LinkedHashMap<>();
		student.put("studentID", studentID);
		student.put("name", name);
		student.put("pronouns", "they/them");
		student.put("email", null);

		return intent("students", List.of(studentID), "UPSERT", student);
	}

	/** The changes in a page that are for one table, in the order they arrived. */
	private static List<JsonNode> changesFor(JsonNode body, String table) {

		List<JsonNode> matching = new ArrayList<>();

		for (JsonNode change : body.get("changes")) {
			if (table.equals(change.get("table").stringValue())) {
				matching.add(change);
			}
		}
		return matching;
	}

	private Reply push(Phone phone, List<Map<String, Object>> intents) throws Exception {
		return push(phone.token(), phone.install(), intents);
	}

	private Reply push(String token, UUID install, List<Map<String, Object>> intents)
			throws Exception {

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("intents", intents);

		return send(HttpRequest.newBuilder(
						URI.create("http://localhost:" + port + Api.V1 + "/sync/push"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body),
						StandardCharsets.UTF_8)), token, install);
	}

	private Reply get(Phone phone, String path) throws Exception {
		return get(phone.token(), phone.install(), path);
	}

	private Reply get(String token, UUID install, String path) throws Exception {

		return send(HttpRequest.newBuilder(
				URI.create("http://localhost:" + port + Api.V1 + path)).GET(), token, install);
	}

	private static Reply send(HttpRequest.Builder request, String token, UUID install)
			throws IOException, InterruptedException {

		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}

		if (install != null) {
			request.header(Api.DEVICE_HEADER, install.toString());
		}

		request.header(Api.VERSION_HEADER, "sync-test/1.0");

		HttpResponse<String> response = HTTP.send(request.build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

		String raw = response.body();
		JsonNode body;

		try {
			body = raw == null || raw.isBlank() ? JSON.nullNode() : JSON.readTree(raw);
		} catch (RuntimeException e) {
			body = JSON.nullNode();
		}

		return new Reply(response.statusCode(), body, raw);
	}

	private static String urlEncode(String value) {
		return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	// ── Fixture helpers ──────────────────────────────────────────────────

	private long account(AccountKind kind, Long prof, Long student, String email)
			throws SQLException {

		try (Connection connection = postgres.connection()) {

			long id = AccountDirectory.create(connection, kind, prof, student,
					email.substring(0, email.indexOf('@')), email, PASSWORD.toCharArray(),
					null);

			try (PreparedStatement statement = connection.prepareStatement(
					"UPDATE app_account SET must_rotate = false WHERE account_id = ?")) {

				statement.setLong(1, id);
				statement.executeUpdate();
			}
			return id;
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

	private long count(String sql) throws SQLException {

		try (Connection connection = postgres.connection();
		     Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(sql)) {

			assertTrue(results.next(), () -> "no row from: " + sql);
			return results.getLong(1);
		}
	}

	/** Kept so the unused-field warning cannot hide a fixture nobody checks. */
	@Test
	void theFixtureHasTwoDepartmentsAndThreeAccounts() throws Exception {
		assertEquals(2, count("SELECT count(*) FROM courses"));
		assertTrue(accountProfChem > 0 && accountOffice > 0 && accountProfCs > 0);
	}
}
