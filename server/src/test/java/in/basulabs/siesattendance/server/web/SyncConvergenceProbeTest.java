package in.basulabs.siesattendance.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.net.URLEncoder;
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
import in.basulabs.siesattendance.server.SiesAttendanceServer;
import in.basulabs.siesattendance.server.security.AccountDirectory;
import in.basulabs.siesattendance.server.testing.TestPostgres;

/**
 * White-box probes of whether two phones and a server end up holding the same thing.
 * <p>
 * Written from a reading of {@code SyncReader}, {@code SyncWriter}, {@code V10__sync.sql} and the
 * phone's {@code OutboxDrain}. The other suites prove that a row is sent and applied; these ask
 * what happens when two writers overlap, which is where a change feed and a retry queue lose
 * things without anybody being told.
 * <p>
 * Every test asserts the behaviour the system should have. The ones that fail today are marked
 * BUG in their notes, and each note says what a fix has to make true.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncConvergenceProbeTest {

	private static final String PASSWORD = "correct horse battery staple";

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	private static TestPostgres postgres;

	private static ConfigurableApplicationContext service;

	private static int port;

	@BeforeAll
	void startEverything() throws Exception {

		assumeTrue(TestPostgres.isAvailable(), "Docker is not available");

		postgres = TestPostgres.start();

		service = new SpringApplication(SiesAttendanceServer.class).run(
				"--spring.datasource.url=" + postgres.appJdbcUrl(),
				"--spring.datasource.username=" + TestPostgres.appUsername(),
				"--spring.datasource.password=" + TestPostgres.appPassword(),
				"--spring.flyway.enabled=false",
				"--server.port=0",
				"--spring.main.banner-mode=off",
				"--sies.login.attempts-per-window=100000");

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

		postgres.asSuperuser("""
				INSERT INTO academic_years (academic_year, start_date, end_date) VALUES
				    (2025, '2025-06-01', '2026-05-31');

				INSERT INTO professors (prof_id, name, email, department, designation) VALUES
				    (1001, 'A CS', 'a.cs@sies.edu.in', 'COMPUTER_SCIENCE', 'Assistant Professor');

				INSERT INTO students (student_id, name, pronouns, email) VALUES
				    (2001, 'R RAO', 'she/her', 'r.rao@sies.edu.in');

				INSERT INTO student_year
				    (student_id, academic_year, roll_no, year, stream, major_dept, semester) VALUES
				    (2001, 2025, 'CSFY001', 'FY', 'BSc_unaided', 'COMPUTER_SCIENCE', 1);

				INSERT INTO courses
				    (course_id, prof_id, course_code, course_topic, stream,
				     offering_department, year, semester, course_type) VALUES
				    (3001, 1001, 'CS101', 'Programming', 'BSc_unaided',
				     'COMPUTER_SCIENCE', 'FY', 1, 'MAJOR');

				INSERT INTO course_instructor (course_id, prof_id) VALUES (3001, 1001)
				    ON CONFLICT DO NOTHING;

				INSERT INTO lectures
				    (lecture_id, course_id, batch_id, start_at, start_zone, end_at, end_zone) VALUES
				    (4001, 3001, NULL, '2025-07-14 09:00:00+05:30', 'Asia/Kolkata',
				                       '2025-07-14 10:00:00+05:30', 'Asia/Kolkata');
				""");

		account(AccountKind.PROFESSOR, 1001L, "a.cs@sies.edu.in");
	}

	// ── The feed: a commit that lands behind the watermark ───────────────

	/**
	 * BUG: the change feed's cursor is {@code (occurred_at, audit_id)}, both of which are fixed
	 * when the audit trigger fires, inside the writing transaction. The entry becomes visible
	 * only when that transaction commits. A pull that runs in between sees a later entry from
	 * some other, shorter transaction, and moves its watermark past the place the uncommitted
	 * one will appear at. Nothing ever looks behind a watermark, so that change is not sent to
	 * this phone until the weekly snapshot (or a change of access) replaces everything.
	 * <p>
	 * The long transactions are real: an attendance import, a roster import and a backup import
	 * each run as one, and a push of a thousand intents is one too. Any phone that pulls while
	 * one of them is open, and after anybody else has committed anything, loses the lot.
	 * <p>
	 * A fix has to make a pull never hand out a watermark newer than the oldest transaction
	 * still in flight. The usual shapes are a cursor on {@code pg_current_snapshot()}'s xmin (an
	 * {@code xid8} column on the audit row, read with {@code < pg_snapshot_xmin(...)}), or
	 * holding the watermark back by a margin and letting the phone's idempotent apply absorb the
	 * overlap.
	 */
	@Test
	void aChangeCommittedBehindTheWatermarkStillReachesThePhone() throws Exception {

		Phone phone = phone();
		String watermark = snapshot(phone);

		List<String> arrived = new ArrayList<>();

		try (Connection slow = postgres.connection()) {

			// A writer that is still at it: an import half way down its file.
			slow.setAutoCommit(false);

			try (Statement statement = slow.createStatement()) {
				statement.executeUpdate("UPDATE lectures SET notes = 'written by the slow one' "
						+ "WHERE lecture_id = 4001");
			}

			// Somebody else, meanwhile, in and out.
			postgres.asSuperuser("""
					INSERT INTO lectures
					    (lecture_id, course_id, batch_id, start_at, start_zone, end_at, end_zone)
					VALUES (4002, 3001, NULL, '2025-07-15 09:00:00+05:30', 'Asia/Kolkata',
					                          '2025-07-15 10:00:00+05:30', 'Asia/Kolkata');
					""");

			Pulled first = pull(phone, watermark);
			arrived.addAll(first.described());
			watermark = first.watermark();

			slow.commit();
		}

		// Any number of pulls later, with nothing else written.
		for (int again = 0; again < 3; again++) {
			Pulled later = pull(phone, watermark);
			arrived.addAll(later.described());
			watermark = later.watermark();
		}

		assertTrue(arrived.contains("lectures[4002]"), "the quick one arrived: " + arrived);
		assertTrue(arrived.contains("lectures[4001]"),
				"the change committed behind the watermark never reached the phone; it holds "
						+ "the lecture as it was before, and will until it starts over. Arrived: "
						+ arrived);
	}

	/**
	 * The same race against a first sign-in: the snapshot reads committed rows and then hands
	 * out the head of the feed, so a transaction open across the snapshot is in neither.
	 */
	@Test
	void aChangeCommittedAcrossASnapshotStillReachesThePhone() throws Exception {

		Phone phone = phone();
		List<String> arrived = new ArrayList<>();
		String watermark;

		try (Connection slow = postgres.connection()) {

			slow.setAutoCommit(false);

			try (Statement statement = slow.createStatement()) {
				statement.executeUpdate("""
						INSERT INTO lectures
						    (lecture_id, course_id, batch_id, start_at, start_zone, end_at, end_zone)
						VALUES (4003, 3001, NULL, '2025-07-16 09:00:00+05:30', 'Asia/Kolkata',
						                          '2025-07-16 10:00:00+05:30', 'Asia/Kolkata')
						""");
			}

			// Something committed after the slow one's entry was stamped, so the head of the
			// feed is already past it.
			postgres.asSuperuser("UPDATE lectures SET notes = 'touched' WHERE lecture_id = 4001");

			Pulled page = pull(phone, "");
			arrived.addAll(page.described());

			while (page.more()) {
				page = pull(phone, page.watermark());
				arrived.addAll(page.described());
			}

			watermark = page.watermark();
			slow.commit();
		}

		for (int again = 0; again < 3; again++) {
			Pulled later = pull(phone, watermark);
			arrived.addAll(later.described());
			watermark = later.watermark();
		}

		assertTrue(arrived.contains("lectures[4003]"),
				"a lecture committed while the first download was running is in neither the "
						+ "snapshot nor the feed after it. Arrived: " + arrived);
	}

	// ── The queue: two phones, one slot ──────────────────────────────────

	/**
	 * BUG: a lecture is unique on its slot (course, batch, start, end) as well as on its id,
	 * and two phones that each record the same class offline give it two ids. The first push
	 * wins. The second is refused with {@code 23505}, which {@code SyncWriter.SETTLED_STATES}
	 * deliberately leaves out on the grounds that "one of them is about to win" -- but nothing
	 * ever makes the loser stop: its lecture has a different id, so it will be refused the same
	 * way on every push for as long as the phone is signed in, and every mark under it is
	 * refused with it (their lecture is "not yet" on the server, for ever).
	 * <p>
	 * The phone shows those marks as saved. A professor and a class representative both taking
	 * the same class is the ordinary way to get here.
	 * <p>
	 * A fix has to give the second phone something final: either the refusal is permanent and
	 * says which lecture already holds the slot, or -- better -- the server folds the push into
	 * the lecture that is already there and tells the phone its id.
	 */
	@Test
	void theSecondPhoneToRecordASlotIsToldSomethingFinal() throws Exception {

		Phone first = phone();
		Phone second = phone();

		long lectureOnFirst = StructuredId.compose(StructuredId.MIN_COUNTER + 11, first.node());
		long lectureOnSecond = StructuredId.compose(StructuredId.MIN_COUNTER + 12, second.node());

		Reply won = push(first, List.of(lecture(lectureOnFirst, 20), mark(lectureOnFirst, true)));
		assertEquals(200, won.status(), won::toString);
		assertEquals(2, won.body().get("accepted").intValue(), won::toString);

		Reply lost = push(second,
				List.of(lecture(lectureOnSecond, 20), mark(lectureOnSecond, false)));
		assertEquals(200, lost.status(), lost::toString);

		System.out.println("[probe] the second phone's push -> " + lost);

		List<String> stillQueued = new ArrayList<>();

		for (JsonNode rejection : lost.body().get("rejected")) {
			if (!rejection.get("permanent").booleanValue()) {
				stillQueued.add(rejection.get("table").stringValue() + ": "
						+ rejection.get("reason").stringValue());
			}
		}

		// Sent again, as the phone will, an hour later and every hour after that.
		Reply again = push(second,
				List.of(lecture(lectureOnSecond, 20), mark(lectureOnSecond, false)));

		assertEquals(lost.body().get("rejected").size(), again.body().get("rejected").size(),
				"nothing about the second attempt differs from the first");

		assertEquals(List.of(), stillQueued,
				"the second phone keeps these in its queue and sends them again on every sync, "
						+ "to the same answer; the screen shows the attendance as recorded");
	}

	// ── Values the schema cannot hold ────────────────────────────────────

	/**
	 * A text value PostgreSQL cannot store -- a NUL, which a pasted or imported name can carry
	 * -- is a row that will be refused tomorrow as well, so the phone should be told to stop
	 * sending it rather than left retrying.
	 */
	@Test
	void aTextValueTheDatabaseCannotStoreIsRefusedForGood() throws Exception {

		Phone phone = phone();
		long mine = StructuredId.compose(StructuredId.MIN_COUNTER + 21, phone.node());

		Map<String, Object> intent = lecture(mine, 21);
		@SuppressWarnings("unchecked")
		Map<String, Object> row = (Map<String, Object>) intent.get("row");
		row.put("notes", "a note with a NUL \u0000 in it");

		Reply reply = push(phone, List.of(intent));
		System.out.println("[probe] a NUL in a text column -> " + reply);

		assertEquals(200, reply.status(), "one bad row is that row's refusal, not the whole "
				+ "push's failure: " + reply);

		if (reply.body().get("accepted").intValue() == 1) {
			return;
		}

		assertTrue(reply.body().get("rejected").get(0).get("permanent").booleanValue(),
				"the row will never be storable as it stands, so keeping it queued sends it "
						+ "for ever: " + reply);
	}

	/**
	 * BUG candidate: {@code DeviceValue.convert} turns an EPOCH_DAY into a date with
	 * {@code LocalDate.ofEpochDay}, which throws {@code DateTimeException} outside ±999999999
	 * years. That is neither {@code Unconvertible} nor {@code SQLException}, so it is not a
	 * per-row refusal: the whole push is a 500 and rolls back, and the phone sends the same
	 * batch again.
	 */
	@Test
	void aDateOutsideTheCalendarIsOneRowsRefusalNotA500() throws Exception {

		Phone phone = phone();

		Map<String, Object> row = new LinkedHashMap<>();
		row.put("studentID", 2001L);
		row.put("academicYear", 2025L);
		row.put("rollNo", "CSFY001");
		row.put("year", "FY");
		row.put("stream", "BSc_unaided");
		row.put("majorDept", "COMPUTER_SCIENCE");
		row.put("minorDept", null);
		row.put("oeDept", null);
		row.put("semester", 1L);
		row.put("division", null);
		row.put("joinDate", 9_000_000_000_000_000L);
		row.put("leaveDate", null);

		long good = StructuredId.compose(StructuredId.MIN_COUNTER + 31, phone.node());

		Reply reply = push(phone, List.of(
				intent("student_year", List.of(2001L, 2025L), "UPSERT", row),
				lecture(good, 22)));

		System.out.println("[probe] an epoch day outside the calendar -> " + reply);

		assertEquals(200, reply.status(),
				"one unconvertible value failed the whole push, good rows included: " + reply);
		assertEquals(1, count("SELECT count(*) FROM lectures WHERE lecture_id = " + good),
				"the good row in the same push was not written");
	}

	// ── A watermark the server issued must be one it accepts ─────────────

	/**
	 * On a server whose audit log holds nothing for a synced table, {@code changes_head()}
	 * answers {@code -infinity}. Whatever that becomes as a watermark, the next pull has to
	 * accept it: the phone treats a 4xx here as "this version cannot sync" and a 5xx as "try
	 * again", and neither ever clears.
	 */
	@Test
	void theWatermarkOfAnEmptyFeedIsAcceptedBack() throws Exception {

		postgres.reset();

		try (Connection connection = postgres.connection()) {

			long id = AccountDirectory.create(connection, AccountKind.STAFF, null, null,
					"office", "office@sies.edu.in", PASSWORD.toCharArray(), null);

			try (PreparedStatement statement = connection.prepareStatement(
					"UPDATE app_account SET must_rotate = false WHERE account_id = ?")) {
				statement.setLong(1, id);
				statement.executeUpdate();
			}
		}

		String token = login("office@sies.edu.in").body().get("token").stringValue();
		UUID install = UUID.randomUUID();

		Reply registered = post("/devices", json("installId", install.toString(),
				"provisionalNode", null, "label", "probe"), token, null);
		assertEquals(200, registered.status(), registered::toString);

		Phone phone = new Phone(token, install, registered.body().get("node").intValue());

		Reply first = get("/sync/pull?limit=500", phone);
		assertEquals(200, first.status(), first::toString);

		String watermark = first.body().get("watermark").stringValue();
		System.out.println("[probe] the watermark of an empty feed: " + watermark);

		Reply second = get("/sync/pull?limit=500&since="
				+ URLEncoder.encode(watermark, StandardCharsets.UTF_8), phone);

		assertEquals(200, second.status(),
				"the server refused the watermark it had just issued (" + watermark + "): "
						+ second);
	}

	// ── What a phone gets back from its own push ─────────────────────────

	/**
	 * Not a defect on its own: pins that a phone's own push comes back to it on the next pull,
	 * because the feed does not know who wrote an entry. That echo is what
	 * {@code OutboxDrain.apply} writes over a row the user has edited again in the meantime;
	 * see {@code PullCollisionProbeTest.anEchoDoesNotUndoAnEditMadeSinceThePush}.
	 */
	@Test
	void aPhonesOwnPushComesBackToItOnTheNextPull() throws Exception {

		Phone phone = phone();
		String watermark = snapshot(phone);

		long mine = StructuredId.compose(StructuredId.MIN_COUNTER + 41, phone.node());
		Reply pushed = push(phone, List.of(lecture(mine, 23), mark(mine, true)));
		assertEquals(2, pushed.body().get("accepted").intValue(), pushed::toString);

		Pulled echo = pull(phone, watermark);

		assertTrue(echo.described().contains("lectures[" + mine + "]"), echo.described()::toString);
		assertTrue(echo.described().contains("attendance[" + mine + ", 2001]"),
				echo.described()::toString);
		assertNotEquals(watermark, echo.watermark());
	}

	// ── Plumbing ─────────────────────────────────────────────────────────

	private record Phone(String token, UUID install, int node) {
	}

	private record Pulled(String watermark, boolean more, List<String> described) {
	}

	private Phone phone() throws Exception {

		String token = login("a.cs@sies.edu.in").body().get("token").stringValue();
		UUID install = UUID.randomUUID();

		Reply registered = post("/devices", json("installId", install.toString(),
				"provisionalNode", null, "label", "probe"), token, null);
		assertEquals(200, registered.status(), registered::toString);

		return new Phone(token, install, registered.body().get("node").intValue());
	}

	/** Everything the account may read, and the watermark to resume from. */
	private String snapshot(Phone phone) throws Exception {

		Pulled page = pull(phone, "");

		while (page.more()) {
			page = pull(phone, page.watermark());
		}
		return page.watermark();
	}

	private Pulled pull(Phone phone, String watermark) throws Exception {

		Reply reply = get("/sync/pull?limit=500" + (watermark.isEmpty() ? ""
				: "&since=" + URLEncoder.encode(watermark, StandardCharsets.UTF_8)), phone);

		assertEquals(200, reply.status(), reply::toString);

		List<String> described = new ArrayList<>();

		for (JsonNode change : reply.body().get("changes")) {

			List<Long> key = new ArrayList<>();
			for (JsonNode part : change.get("key")) {
				key.add(part.longValue());
			}
			described.add(change.get("table").stringValue() + key);
		}

		return new Pulled(reply.body().get("watermark").stringValue(),
				reply.body().get("more").booleanValue(), described);
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

	private static String at(int day, int hour) {
		return StoredTimes.format(java.time.ZonedDateTime.of(2025, 7, day, hour, 0, 0, 0,
				java.time.ZoneId.of("Asia/Kolkata")));
	}

	private static Map<String, Object> lecture(long key, int day) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("lectureID", key);
		row.put("courseID", 3001L);
		row.put("batchID", null);
		row.put("startDateTime", at(day, 9));
		row.put("endDateTime", at(day, 10));
		row.put("notes", null);
		return intent("lectures", List.of(key), "UPSERT", row);
	}

	private static Map<String, Object> mark(long lecture, boolean present) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("lectureID", lecture);
		row.put("studentID", 2001L);
		row.put("present", present ? 1L : 0L);
		return intent("attendance", List.of(lecture, 2001L), "UPSERT", row);
	}

	private Reply push(Phone phone, List<Map<String, Object>> intents) throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("intents", intents);
		return post("/sync/push", JSON.writeValueAsString(body), phone.token(), phone.install());
	}

	private long account(AccountKind kind, Long prof, String email) throws SQLException {

		try (Connection connection = postgres.connection()) {

			long id = AccountDirectory.create(connection, kind, prof, null,
					email.substring(0, email.indexOf('@')), email, PASSWORD.toCharArray(), null);

			try (PreparedStatement statement = connection.prepareStatement(
					"UPDATE app_account SET must_rotate = false WHERE account_id = ?")) {
				statement.setLong(1, id);
				statement.executeUpdate();
			}
			return id;
		}
	}

	private record Reply(int status, JsonNode body, String raw) {
		@Override
		public String toString() {
			return status + " " + (raw == null || raw.length() < 600 ? raw
					: raw.substring(0, 600) + "…");
		}
	}

	private Reply login(String email) throws Exception {
		return post("/auth/login", json("email", email, "door", "STAFF", "password", PASSWORD),
				null, null);
	}

	private Reply get(String path, Phone phone) throws Exception {
		return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + Api.V1 + path))
				.GET(), phone.token(), phone.install());
	}

	private Reply post(String path, String body, String token, UUID install) throws Exception {
		return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + Api.V1 + path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)),
				token, install);
	}

	private static Reply send(HttpRequest.Builder request, String token, UUID install)
			throws IOException, InterruptedException {

		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		if (install != null) {
			request.header(Api.DEVICE_HEADER, install.toString());
		}

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

	private static String json(Object... pairs) {
		Map<String, Object> body = new LinkedHashMap<>();
		for (int at = 0; at < pairs.length; at += 2) {
			body.put(String.valueOf(pairs[at]), pairs[at + 1]);
		}
		return JSON.writeValueAsString(body);
	}

	private long count(String sql) throws SQLException {
		try (Connection connection = postgres.connection();
		     Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(sql)) {
			assertTrue(results.next());
			return results.getLong(1);
		}
	}
}
