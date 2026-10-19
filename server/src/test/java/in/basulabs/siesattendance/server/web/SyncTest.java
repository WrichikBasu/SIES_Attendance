package in.basulabs.siesattendance.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
				    (1002, 'B CHEM', 'b.chem@sies.edu.in', 'CHEMISTRY',        'Assistant Professor');

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
						.contains("not by this device"),
				pushed.body().get("rejected").toString());

		assertEquals(0, count("SELECT count(*) FROM lectures"),
				"a phone must not be able to write rows claiming to be another phone's");
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
	void aTableThePoliciesDoNotCoverIsNotAccepted() throws Exception {

		Phone phone = signInWithAPhone("a.cs@sies.edu.in");

		Map<String, Object> student = new LinkedHashMap<>();
		student.put("studentID", 2003L);
		student.put("name", "NEW PERSON");
		student.put("pronouns", "they/them");
		student.put("email", null);

		Reply pushed = push(phone, List.of(intent("students", List.of(2003L), "UPSERT",
				student)));

		assertEquals(0, pushed.body().get("accepted").intValue());
		assertTrue(pushed.body().get("rejected").get(0).get("reason").stringValue()
						.contains("does not accept students"),
				"students has no read policy, so nothing here could decide who may rewrite "
						+ "the roll -- and deciding it in Java is the thing this design keeps out");

		assertEquals(2, count("SELECT count(*) FROM students"));
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

	// ── The pull ─────────────────────────────────────────────────────────

	@Test
	void aFirstPullIsASnapshotAndYieldsAWatermark() throws Exception {

		Phone phone = signInWithAPhone("a.cs@sies.edu.in");
		long lectureID = phone.key(1000);

		push(phone, List.of(lectureIntent(lectureID, COURSE_CS, 9),
				attendanceIntent(lectureID, STUDENT_CS, true)));

		Reply pulled = get(phone, "/sync/pull");

		assertEquals(200, pulled.status(), pulled::toString);
		assertEquals(2, pulled.body().get("changes").size(), pulled::toString);
		assertFalse(pulled.body().get("watermark").stringValue().isBlank(),
				"a snapshot has to come with somewhere to resume from");

		// In dependency order, so a device can apply the page as it arrives.
		assertEquals("lectures", pulled.body().get("changes").get(0).get("table").stringValue());
		assertEquals("attendance",
				pulled.body().get("changes").get(1).get("table").stringValue());
	}

	@Test
	void theRowsComeBackInTheDevicesOwnRepresentation() throws Exception {

		Phone phone = signInWithAPhone("a.cs@sies.edu.in");
		long lectureID = phone.key(1100);

		push(phone, List.of(lectureIntent(lectureID, COURSE_CS, 9),
				attendanceIntent(lectureID, STUDENT_CS, false)));

		JsonNode changes = get(phone, "/sync/pull").body().get("changes");

		JsonNode lecture = changes.get(0).get("row");
		JsonNode marks = changes.get(1).get("row");

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
		assertEquals(0, pulled.body().get("changes").size(),
				"the feed hands out keys; the policies are what make it the caller's keys");
	}

	@Test
	void theOfficeSeesEverybodysRows() throws Exception {

		Phone cs = signInWithAPhone("a.cs@sies.edu.in");
		push(cs, List.of(lectureIntent(cs.key(1500), COURSE_CS, 9)));

		Phone office = signInWithAPhone("office@sies.edu.in");

		assertEquals(1, get(office, "/sync/pull").body().get("changes").size());
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

		assertEquals(2, page.changes().size());
		assertFalse(page.watermark().isBlank());

		SyncIntent lecture = page.changes().get(0);

		assertEquals("lectures", lecture.table());
		assertEquals(lectureID, (long) lecture.key().get(0));
		assertEquals(OutboxOp.UPSERT, lecture.op());
		assertEquals(COURSE_CS, lecture.row().get("courseID"),
				"a key read as a double would come back rounded, and be a different course");

		assertEquals(0L, page.changes().get(1).row().get("present"));
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
	private record Phone(String token, UUID install, int node) {

		/** A key this device would issue, distinct per {@code n}. */
		long key(int n) {
			return StructuredId.compose(StructuredId.MIN_COUNTER + n, node);
		}
	}

	private Phone signInWithAPhone(String email) throws Exception {

		String token = tokenFor(email);
		UUID install = UUID.randomUUID();

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("installId", install.toString());
		body.put("provisionalNode", 40000 + (int) (Math.random() * 20000));
		body.put("label", email + "'s phone");

		Reply registered = send(HttpRequest.newBuilder(
						URI.create("http://localhost:" + port + Api.V1 + "/devices"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body),
						StandardCharsets.UTF_8)), token, null);

		assertEquals(200, registered.status(), registered::toString);

		return new Phone(token, install, registered.body().get("node").intValue());
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
