package in.basulabs.siesattendance.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
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
import java.util.Arrays;
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
 * Adversarial white-box probes of the HTTP surface, written from a reading of
 * {@link BearerTokenFilter}, {@code AccountDirectory.authenticate}, {@code SyncWriter},
 * {@code SyncReader} and {@link DataController}.
 * <p>
 * Each test names the branch it aims at. The ones that currently fail are marked BUG in their
 * notes; the rest pin behaviour that no other suite exercises.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdversarialProbeTest {

	private static final int SESSION = 2025;

	private static final String PASSWORD = "correct horse battery staple";

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	private static TestPostgres postgres;

	private static ConfigurableApplicationContext service;

	private static int port;

	private long accountProfCs;
	private long accountProfChem;

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
				    (1001, 'A CS',   'a.cs@sies.edu.in',   'COMPUTER_SCIENCE', 'Assistant Professor'),
				    (1002, 'B CHEM', 'b.chem@sies.edu.in', 'CHEMISTRY',        'Assistant Professor'),
				    (1004, 'D LOCK', 'd.lock@sies.edu.in', 'CHEMISTRY',        'Assistant Professor'),
				    (1005, 'E OFF',  'e.off@sies.edu.in',  'CHEMISTRY',        'Assistant Professor');

				INSERT INTO students (student_id, name, pronouns, email) VALUES
				    (2001, 'R RAO',  'she/her',   'r.rao@sies.edu.in');

				INSERT INTO student_year
				    (student_id, academic_year, roll_no, year, stream, major_dept, semester) VALUES
				    (2001, 2025, 'CS-FY-001', 'FY', 'BSc_unaided', 'COMPUTER_SCIENCE', 1);

				INSERT INTO courses
				    (course_id, prof_id, course_code, course_topic, stream,
				     offering_department, year, semester, course_type) VALUES
				    (3001, 1001, 'CS-101', 'Programming', 'BSc_unaided',
				     'COMPUTER_SCIENCE', 'FY', 1, 'MAJOR');

				INSERT INTO course_instructor (course_id, prof_id) VALUES (3001, 1001)
				    ON CONFLICT DO NOTHING;

				INSERT INTO lectures
				    (lecture_id, course_id, batch_id, start_at, start_zone, end_at, end_zone) VALUES
				    (4001, 3001, NULL, '2025-07-14 09:00:00+05:30', 'Asia/Kolkata',
				                       '2025-07-14 10:00:00+05:30', 'Asia/Kolkata');
				""");

		accountProfCs = account(AccountKind.PROFESSOR, 1001L, "a.cs@sies.edu.in");
		accountProfChem = account(AccountKind.PROFESSOR, 1002L, "b.chem@sies.edu.in");
		account(AccountKind.PROFESSOR, 1004L, "d.lock@sies.edu.in");
		account(AccountKind.PROFESSOR, 1005L, "e.off@sies.edu.in");
	}

	// ── The filter's path test ───────────────────────────────────────────

	/**
	 * {@link BearerTokenFilter} decides whether to authenticate with
	 * {@code getRequestURI().startsWith("/api/v1")}, on the raw, undecoded URI, while Spring
	 * matches controllers on a normalised path with {@code ;params} removed. A spelling that
	 * fails the prefix test but still reaches a controller skips the filter entirely.
	 * <p>
	 * Defence in depth holds today only because every data controller calls
	 * {@link RequestActor#of}, which throws 401 when no actor was attached. This test makes
	 * that the asserted contract: no spelling of a protected path returns 200 without a token.
	 */
	@Test
	void noSpellingOfAProtectedPathIsServedWithoutAToken() throws Exception {

		List<String> spellings = List.of(
				"/api;x=1/v1/me",
				"/api/v1;x=1/me",
				"/api;/v1/me",
				"//api/v1/me",
				"/api//v1/me",
				"/api/./v1/me",
				"/api/v1/./me",
				"/api/x/../v1/me",
				"/api/%761/me",
				"/%61pi/v1/me",
				"/API/v1/me",
				"/api/v1/me/",
				"/api;x=1/v1/export?year=" + SESSION,
				"/api;x=1/v1/data/students",
				"/api;x=1/v1/sync/pull");

		List<String> served = new ArrayList<>();

		for (String spelling : spellings) {
			RawReply reply = raw("GET", spelling, null, null);
			if (reply.status() == 200) {
				served.add(spelling + " -> " + reply.body());
			}
		}

		assertEquals(List.of(), served, "a protected path was served without any token");
	}

	/** Which spellings skip the filter. Informational: logged, not asserted. */
	@Test
	void reportWhichSpellingsSkipTheFilter() throws Exception {

		for (String spelling : List.of("/api;x=1/v1/me", "/api/v1;x=1/me", "/api/%761/me",
				"//api/v1/me")) {
			RawReply reply = raw("GET", spelling, null, null);
			System.out.println("[probe] " + spelling + " -> " + reply.status() + " "
					+ reply.body());
		}
	}

	/**
	 * A must-rotate session may do two things. A path spelling that misses
	 * {@code ROTATION_ONLY}'s exact match must not open anything else, and must also not lock
	 * the account out of the two it is allowed.
	 */
	@Test
	void aMustRotateSessionCannotEscapeThroughAPathSpelling() throws Exception {

		long fresh;
		try (Connection connection = postgres.connection()) {
			fresh = AccountDirectory.create(connection, AccountKind.STAFF, null, null,
					"Fresh", "fresh@sies.edu.in", PASSWORD.toCharArray(), null);
		}
		assertTrue(fresh > 0);

		String token = login("fresh@sies.edu.in", "STAFF", PASSWORD, null).body()
				.get("token").stringValue();

		for (String spelling : List.of("/api/v1/me", "/api/v1/me;x=1", "/api/v1/me/",
				"/api/v1/auth/password/../../me", "/api/v1/auth/logout/../../me",
				"/api/v1/auth/password;x=/../../me")) {

			RawReply reply = raw("GET", spelling, token, null);
			assertNotEquals(200, reply.status(),
					"a must-rotate session read " + spelling + ": " + reply.body());
		}
	}

	// ── The login oracle ─────────────────────────────────────────────────

	/**
	 * BUG: {@code authenticate} spends an Argon2id hash on an unknown address precisely so
	 * that "no such account" and "wrong password" take the same time. The locked branch
	 * returns before any hash. Five wrong guesses lock a real account; an unknown address
	 * never locks. From then on a real account answers in a few milliseconds and an unknown
	 * one in tens, so the login box answers "does this person have an account" again.
	 */
	@Test
	void aLockedAccountTakesAsLongToRefuseAsAnUnknownOne() throws Exception {

		postgres.asSuperuser("UPDATE app_account SET locked_until = now() + interval '1 hour', "
				+ "failed_logins = 5 WHERE lower(login_email) = 'd.lock@sies.edu.in'");

		long locked = medianNanos(() -> login("d.lock@sies.edu.in", "STAFF", "guess one two", null));
		long unknown = medianNanos(() -> login("nobody.here@sies.edu.in", "STAFF", "guess one two",
				null));

		System.out.printf("[probe] locked median %.1f ms, unknown median %.1f ms%n",
				locked / 1e6, unknown / 1e6);

		assertTrue(locked * 2 >= unknown, String.format(
				"a locked account is refused in %.1f ms and an unknown one in %.1f ms: the "
						+ "difference tells an outsider which addresses have accounts",
				locked / 1e6, unknown / 1e6));
	}

	/** BUG: the same oracle through the disabled branch. */
	@Test
	void aDisabledAccountTakesAsLongToRefuseAsAnUnknownOne() throws Exception {

		postgres.asSuperuser("UPDATE app_account SET disabled_at = now() "
				+ "WHERE lower(login_email) = 'e.off@sies.edu.in'");

		long disabled = medianNanos(() -> login("e.off@sies.edu.in", "STAFF", "guess one two", null));
		long unknown = medianNanos(() -> login("nobody.here@sies.edu.in", "STAFF", "guess one two",
				null));

		System.out.printf("[probe] disabled median %.1f ms, unknown median %.1f ms%n",
				disabled / 1e6, unknown / 1e6);

		assertTrue(disabled * 2 >= unknown, String.format(
				"a disabled account is refused in %.1f ms and an unknown one in %.1f ms",
				disabled / 1e6, unknown / 1e6));
	}

	/** Whatever the timing, the bodies of every refusal stay identical. */
	@Test
	void lockedAndDisabledRefusalsReadLikeAnyOther() throws Exception {

		postgres.asSuperuser("UPDATE app_account SET locked_until = now() + interval '1 hour' "
				+ "WHERE lower(login_email) = 'd.lock@sies.edu.in'; "
				+ "UPDATE app_account SET disabled_at = now() "
				+ "WHERE lower(login_email) = 'e.off@sies.edu.in'");

		for (String email : List.of("d.lock@sies.edu.in", "e.off@sies.edu.in",
				"nobody.here@sies.edu.in")) {
			Reply reply = login(email, "STAFF", PASSWORD, null);
			assertEquals(401, reply.status(), reply::toString);
			assertEquals("sign_in_refused", reply.body().get("code").stringValue());
		}
	}

	// ── Device identity ──────────────────────────────────────────────────

	/**
	 * An install id is a bearer secret: any account that presents one at login is bound to that
	 * device and issues ids under its node. Characterisation, logged rather than asserted;
	 * see the report. This test asserts only the part that must hold whatever the design
	 * decision: the id does not leak to another account through the audit trail.
	 */
	@Test
	void anInstallIdDoesNotLeakToAnotherAccountThroughTheTrail() throws Exception {

		String csToken = login("a.cs@sies.edu.in", "STAFF", PASSWORD, null).body()
				.get("token").stringValue();
		UUID install = UUID.randomUUID();

		Reply registered = post("/devices", json("installId", install.toString(),
				"provisionalNode", null, "label", "CS phone"), csToken, null);
		assertEquals(200, registered.status(), registered::toString);
		int csNode = registered.body().get("node").intValue();

		String chemToken = login("b.chem@sies.edu.in", "STAFF", PASSWORD, null).body()
				.get("token").stringValue();

		Reply trail = get("/audit?limit=500", chemToken, null);
		assertFalse(trail.raw().contains(install.toString()),
				"another professor can read the CS phone's install id from the trail");

		Reply rows = get("/data", chemToken, null);
		assertFalse(rows.raw().contains("device_registration"),
				"the device table is browsable by another professor");

		// The characterisation: the chemistry professor, given the id, is bound to that phone.
		Reply hijack = login("b.chem@sies.edu.in", "STAFF", PASSWORD, install);
		System.out.println("[probe] login with another account's install id -> "
				+ hijack.status() + " deviceNode=" + hijack.body().get("deviceNode")
				+ " (CS phone's node " + csNode + ")");
	}

	// ── Push: hostile intents ────────────────────────────────────────────

	private record Phone(String token, UUID install, int node) {
	}

	private Phone csPhone() throws Exception {

		String token = login("a.cs@sies.edu.in", "STAFF", PASSWORD, null).body()
				.get("token").stringValue();
		UUID install = UUID.randomUUID();

		Reply registered = post("/devices", json("installId", install.toString(),
				"provisionalNode", null, "label", "probe"), token, null);
		assertEquals(200, registered.status(), registered::toString);

		return new Phone(token, install, registered.body().get("node").intValue());
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

	private Reply push(Phone phone, List<Map<String, Object>> intents) throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("intents", intents);
		return post("/sync/push", JSON.writeValueAsString(body), phone.token(), phone.install());
	}

	/** Tables the device does not hold, and the server's own bookkeeping, are refused. */
	@Test
	void aPushToATableOutsideTheSyncedSetIsRefusedPerRow() throws Exception {

		Phone phone = csPhone();
		long key = StructuredId.compose(StructuredId.MIN_COUNTER + 5, phone.node());

		for (String table : List.of("app_account", "user_role", "audit_log", "app_session",
				"device_registration", "pg_authid", "students; DROP TABLE students",
				"STUDENTS", "")) {

			Reply reply = push(phone, List.of(intent(table, List.of(key), "UPSERT",
					Map.of("x", 1))));

			if (table.isEmpty()) {
				assertTrue(reply.status() == 200 || reply.status() == 400, reply::toString);
				continue;
			}

			assertEquals(200, reply.status(), table + ": " + reply);
			assertEquals(0, reply.body().get("accepted").intValue(), table + ": " + reply);
			assertTrue(reply.body().get("rejected").get(0).get("permanent").booleanValue(),
					table + " should be refused for good: " + reply);
		}

		assertEquals(1, count("SELECT count(*) FROM students"),
				"the table named in an injected string is still there");
	}

	/** Shape errors are refused per row or per request, never with a 500. */
	@Test
	void malformedIntentsNeverReachTheEdgeAsAServerError() throws Exception {

		Phone phone = csPhone();
		long mine = StructuredId.compose(StructuredId.MIN_COUNTER + 9, phone.node());

		List<String> bodies = List.of(
				"{}",
				"{\"intents\":null}",
				"{\"intents\":[]}",
				"{\"intents\":[{}]}",
				"{\"intents\":[{\"table\":\"lectures\",\"key\":[],\"op\":\"UPSERT\"}]}",
				"{\"intents\":[{\"table\":\"lectures\",\"key\":[" + mine + "],\"op\":\"MERGE\"}]}",
				"{\"intents\":[{\"table\":\"lectures\",\"key\":[" + mine + "],\"op\":null}]}",
				"{\"intents\":[{\"table\":\"lectures\",\"key\":[" + mine
						+ "],\"op\":\"UPSERT\",\"row\":null}]}",
				"{\"intents\":[{\"table\":\"lectures\",\"key\":[" + mine + ",1],"
						+ "\"op\":\"UPSERT\",\"row\":{}}]}",
				"{\"intents\":[{\"table\":\"attendance\",\"key\":[4001],"
						+ "\"op\":\"UPSERT\",\"row\":{}}]}",
				"{\"intents\":[{\"table\":\"lectures\",\"key\":[\"abc\"],\"op\":\"UPSERT\"}]}",
				"{\"intents\":[{\"table\":\"lectures\",\"key\":[1e300],\"op\":\"UPSERT\"}]}",
				"{\"intents\":[{\"table\":\"lectures\",\"key\":[99999999999999999999999],"
						+ "\"op\":\"UPSERT\"}]}",
				"{\"intents\":[{\"table\":\"lectures\",\"key\":[null],\"op\":\"UPSERT\"}]}",
				"{\"intents\":[{\"table\":\"lectures\",\"key\":[" + mine + "],"
						+ "\"op\":\"UPSERT\",\"row\":{\"courseID\":\"not a number\","
						+ "\"startDateTime\":\"yesterday\",\"endDateTime\":[1,2]}}]}",
				"{\"intents\":[{\"table\":\"lectures\",\"key\":[" + mine + "],"
						+ "\"op\":\"UPSERT\",\"row\":{\"courseID\":3001,"
						+ "\"startDateTime\":\"2025-07-14T09:00:00+05:30[Asia/Kolkata]\","
						+ "\"endDateTime\":\"2025-07-14T08:00:00+05:30[Asia/Kolkata]\"}}]}",
				"not json at all",
				"[1,2,3]");

		List<String> fiveHundreds = new ArrayList<>();

		for (String body : bodies) {
			Reply reply = post("/sync/push", body, phone.token(), phone.install());
			System.out.println("[probe] push " + body + " -> " + reply);
			if (reply.status() >= 500) {
				fiveHundreds.add(body + " -> " + reply);
			}
		}

		assertEquals(List.of(), fiveHundreds, "a malformed push reached the edge as a 5xx");
	}

	/** More than the cap is refused whole, before any database work. */
	@Test
	void aPushOverTheCapIsRefusedWhole() throws Exception {

		Phone phone = csPhone();
		List<Map<String, Object>> intents = new ArrayList<>();

		for (int at = 0; at < 1001; at++) {
			intents.add(intent("lectures",
					List.of(StructuredId.compose(StructuredId.MIN_COUNTER + at, phone.node())),
					"DELETE", null));
		}

		Reply reply = push(phone, intents);
		assertEquals(413, reply.status(), reply::toString);
	}

	/**
	 * Columns the device does not carry are ignored, not written: a phone cannot stamp its own
	 * provenance or bump a row's version.
	 */
	@Test
	void aPushCannotWriteColumnsTheDeviceDoesNotCarry() throws Exception {

		Phone phone = csPhone();
		long key = StructuredId.compose(StructuredId.MIN_COUNTER + 77, phone.node());

		Map<String, Object> row = new LinkedHashMap<>();
		row.put("lectureID", key);
		row.put("courseID", 3001);
		row.put("batchID", null);
		row.put("startDateTime", at(15, 9));
		row.put("endDateTime", at(15, 10));
		row.put("notes", null);
		row.put("created_at", "1999-01-01T00:00:00Z");
		row.put("row_version", 999);
		row.put("lecture_id", 1);

		Reply reply = push(phone, List.of(intent("lectures", List.of(key), "UPSERT", row)));
		System.out.println("[probe] push with extra columns -> " + reply);

		if (reply.status() == 200 && reply.body().get("accepted").intValue() == 1) {
			assertEquals(0, count("SELECT count(*) FROM lectures WHERE lecture_id = " + key
					+ " AND (row_version = 999 OR created_at < '2000-01-01')"),
					"a device-sent provenance column was written");
			assertEquals(0, count("SELECT count(*) FROM lectures WHERE lecture_id = 1"),
					"a row key in the row overrode the intent's key");
		}
	}

	/** A key under somebody else's node, for a row that does not exist, is refused. */
	@Test
	void aPushCannotMintAKeyUnderAnotherNode() throws Exception {

		Phone phone = csPhone();
		int otherNode = phone.node() == 1 ? 2 : 1;
		long foreign = StructuredId.compose(StructuredId.MIN_COUNTER + 31, otherNode);

		Map<String, Object> row = new LinkedHashMap<>();
		row.put("lectureID", foreign);
		row.put("courseID", 3001);
		row.put("batchID", null);
		row.put("startDateTime", at(16, 9));
		row.put("endDateTime", at(16, 10));
		row.put("notes", null);

		Reply reply = push(phone, List.of(intent("lectures", List.of(foreign), "UPSERT", row)));

		assertEquals(200, reply.status(), reply::toString);
		assertEquals(0, reply.body().get("accepted").intValue(), reply::toString);
		assertTrue(reply.raw().contains("issued by node"),
				"refused, but not by the node check: " + reply);
		assertEquals(0, count("SELECT count(*) FROM lectures WHERE lecture_id = " + foreign));
	}

	private static String at(int day, int hour) {
		return StoredTimes.format(java.time.ZonedDateTime.of(2025, 7, day, hour, 0, 0, 0,
				java.time.ZoneId.of("Asia/Kolkata")));
	}

	private static Map<String, Object> lecture(long key, Object courseId, int day) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("lectureID", key);
		row.put("courseID", courseId);
		row.put("batchID", null);
		row.put("startDateTime", at(day, 9));
		row.put("endDateTime", at(day, 10));
		row.put("notes", null);
		return intent("lectures", List.of(key), "UPSERT", row);
	}

	/**
	 * BUG: {@code DeviceValue.convert} casts INTEGER, BOOLEAN and EPOCH_DAY values with
	 * {@code (Number) raw}. A string there throws ClassCastException, which is neither the
	 * {@code Unconvertible} nor the {@code SQLException} {@code SyncWriter.apply} turns into a
	 * per-row rejection. The whole push is a 500 and rolls back; the phone's drain records the
	 * failure against the whole batch and sends the same oldest batch next time, so a single
	 * such row blocks every change behind it. The importer uses the same converter.
	 */
	@Test
	void oneUnconvertibleRowCostsOnlyItself() throws Exception {

		Phone phone = csPhone();
		long good = StructuredId.compose(StructuredId.MIN_COUNTER + 101, phone.node());
		long bad = StructuredId.compose(StructuredId.MIN_COUNTER + 102, phone.node());

		Reply reply = push(phone, List.of(lecture(good, 3001, 17), lecture(bad, "3001x", 18)));

		assertEquals(200, reply.status(), "the push as a whole failed: " + reply);
		assertEquals(1, reply.body().get("accepted").intValue(), reply::toString);
		assertEquals(1, count("SELECT count(*) FROM lectures WHERE lecture_id = " + good),
				"the good row was lost along with the bad one");
	}

	/** The same through BOOLEAN: a mark whose present flag is text. */
	@Test
	void aMarkWithATextFlagIsRefusedNotAServerError() throws Exception {

		Phone phone = csPhone();
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("lectureID", 4001);
		row.put("studentID", 2001);
		row.put("present", "yes");

		Reply reply = push(phone, List.of(intent("attendance", List.of(4001L, 2001L),
				"UPSERT", row)));

		assertTrue(reply.status() < 500, reply::toString);
	}

	// ── Pull and keys: hostile parameters ────────────────────────────────

	@Test
	void hostileWatermarksAreTheCallersMistakeNotTheServers() throws Exception {

		Phone phone = csPhone();

		List<String> watermarks = List.of(
				"@", "x@1", "2025-01-01T00:00:00Z@", "2025-01-01T00:00:00Z@abc",
				"2025-01-01T00:00:00Z@99999999999999999999",
				"snap|", "snap||", "snap|||", "snap|students||", "snap|students|abc|x@1",
				"snap|app_account|1|2025-01-01T00:00:00Z@1",
				"snap|students|1,2|2025-01-01T00:00:00Z@1",
				"snap|students|1|garbage",
				"snap|attendance|1|2025-01-01T00:00:00Z@1",
				"+999999999-12-31T23:59:59Z@1",
				"-999999999-01-01T00:00:00Z@1");

		List<String> wrong = new ArrayList<>();

		for (String watermark : watermarks) {
			Reply reply = get("/sync/pull?since="
					+ java.net.URLEncoder.encode(watermark, StandardCharsets.UTF_8),
					phone.token(), phone.install());
			System.out.println("[probe] pull since=" + watermark + " -> " + reply.status()
					+ " " + abbreviate(reply.raw()));
			if (reply.status() >= 500) {
				wrong.add(watermark + " -> " + reply.status());
			}
		}

		assertEquals(List.of(), wrong, "a hostile watermark produced a server error");
	}

	/**
	 * A resume watermark naming a real device table that is not in the synced set skips the
	 * whole snapshot and hands back the head: the phone believes it has everything. Only a
	 * forged watermark can reach this, so it is characterised rather than asserted.
	 */
	@Test
	void aResumeAtAnUnreachableTableIsCharacterised() throws Exception {

		Phone phone = csPhone();
		Reply reply = get("/sync/pull?since=" + java.net.URLEncoder.encode(
				"snap|students|1|2025-01-01T00:00:00Z@1", StandardCharsets.UTF_8),
				phone.token(), phone.install());
		System.out.println("[probe] resume at students -> " + abbreviate(reply.raw()));
	}

	@Test
	void hostileLimitsAreClampedNotObeyed() throws Exception {

		Phone phone = csPhone();

		for (String limit : List.of("0", "-1", "-2147483648", "2147483647")) {
			Reply reply = get("/sync/pull?limit=" + limit, phone.token(), phone.install());
			assertEquals(200, reply.status(), limit + ": " + reply);
		}

		for (String limit : List.of("abc", "2147483648", "1e3")) {
			Reply reply = get("/sync/pull?limit=" + limit, phone.token(), phone.install());
			assertEquals(400, reply.status(), limit + ": " + reply);
		}
	}

	@Test
	void hostileKeyCursorsAreRefused() throws Exception {

		Phone phone = csPhone();

		assertEquals(400, get("/sync/keys?table=app_account", phone.token(), phone.install())
				.status());
		assertEquals(400, get("/sync/keys?table=" + java.net.URLEncoder.encode(
				"lectures; DROP TABLE lectures", StandardCharsets.UTF_8),
				phone.token(), phone.install()).status());
		assertEquals(400, get("/sync/keys?table=lectures&after=1,2", phone.token(),
				phone.install()).status());
		assertEquals(400, get("/sync/keys?table=attendance&after=1,,2", phone.token(),
				phone.install()).status());
		assertEquals(400, get("/sync/keys?table=lectures&after=99999999999999999999",
				phone.token(), phone.install()).status());
		assertEquals(200, get("/sync/keys?table=lectures&limit=-5", phone.token(),
				phone.install()).status());

		assertEquals(1, count("SELECT count(*) FROM lectures"));
	}

	@Test
	void theBrowserRefusesAnythingButASyncedTable() throws Exception {

		String token = login("b.chem@sies.edu.in", "STAFF", PASSWORD, null).body()
				.get("token").stringValue();

		for (String table : List.of("app_account", "app_session", "audit_log", "pg_shadow",
				"information_schema.tables", "students%20WHERE%201=1")) {
			Reply reply = get("/data/" + table, token, null);
			assertTrue(reply.status() == 404 || reply.status() == 400,
					table + " -> " + reply);
		}

		Reply badCursor = get("/data/students?after=1,2", token, null);
		assertEquals(400, badCursor.status(), badCursor::toString);
	}

	// ── Password change edges ────────────────────────────────────────────

	/**
	 * Twelve characters of which eleven are spaces passes {@code tooWeak}. Characterised: the
	 * rule is "length, and not only spaces", and this is what that rule permits.
	 */
	@Test
	void aPasswordOfMostlySpacesIsCharacterised() throws Exception {

		String token = login("a.cs@sies.edu.in", "STAFF", PASSWORD, null).body()
				.get("token").stringValue();

		Reply reply = post("/auth/password", json("currentPassword", PASSWORD,
				"newPassword", "           a"), token, null);
		System.out.println("[probe] eleven spaces and a letter -> " + reply);
	}

	/** A one-megabyte password is refused or hashed, but the server answers. */
	@Test
	void anEnormousPasswordDoesNotTakeTheServerDown() throws Exception {

		char[] huge = new char[1 << 20];
		Arrays.fill(huge, 'x');

		long started = System.nanoTime();
		Reply reply = login("a.cs@sies.edu.in", "STAFF", new String(huge), null);
		long took = System.nanoTime() - started;

		System.out.printf("[probe] 1 MiB password -> %d in %.0f ms%n", reply.status(),
				took / 1e6);
		assertTrue(reply.status() < 500, reply::toString);
	}

	// ── Plumbing ─────────────────────────────────────────────────────────

	private interface Call {
		Reply run() throws Exception;
	}

	private static long medianNanos(Call call) throws Exception {

		call.run();
		call.run();

		long[] samples = new long[9];
		for (int at = 0; at < samples.length; at++) {
			long started = System.nanoTime();
			call.run();
			samples[at] = System.nanoTime() - started;
		}
		Arrays.sort(samples);
		return samples[samples.length / 2];
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
			return status + " " + abbreviate(raw);
		}
	}

	private record RawReply(int status, String body) {
	}

	private static String abbreviate(String text) {
		return text == null || text.length() < 300 ? text : text.substring(0, 300) + "…";
	}

	private Reply login(String email, String door, String password, UUID install)
			throws Exception {
		return post("/auth/login", install == null
				? json("email", email, "door", door, "password", password)
				: json("email", email, "door", door, "password", password,
						"installId", install.toString()), null, null);
	}

	private Reply get(String path, String token, UUID install) throws Exception {
		return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + Api.V1 + path))
				.GET(), token, install);
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

	/**
	 * A request written byte for byte on a socket, because {@link HttpClient} normalises
	 * some of the path spellings the filter test is about.
	 */
	private static RawReply raw(String method, String target, String token, UUID install)
			throws IOException {

		try (Socket socket = new Socket("localhost", port)) {

			socket.setSoTimeout(10_000);

			StringBuilder request = new StringBuilder()
					.append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
					.append("Host: localhost:").append(port).append("\r\n")
					.append("Connection: close\r\n");

			if (token != null) {
				request.append("Authorization: Bearer ").append(token).append("\r\n");
			}
			if (install != null) {
				request.append(Api.DEVICE_HEADER).append(": ").append(install).append("\r\n");
			}
			request.append("\r\n");

			OutputStream out = socket.getOutputStream();
			out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
			out.flush();

			InputStream in = socket.getInputStream();
			String response = new String(in.readAllBytes(), StandardCharsets.UTF_8);

			String statusLine = response.substring(0, response.indexOf("\r\n"));
			int status = Integer.parseInt(statusLine.split(" ")[1]);
			int bodyAt = response.indexOf("\r\n\r\n");
			return new RawReply(status, bodyAt < 0 ? "" : abbreviate(response.substring(bodyAt + 4)));
		}
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
