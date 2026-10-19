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
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.stream.Stream;

import in.basulabs.siesattendance.backend.AccountKind;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.StructuredId;
import in.basulabs.siesattendance.server.SiesAttendanceServer;
import in.basulabs.siesattendance.server.security.AccountDirectory;
import in.basulabs.siesattendance.server.testing.TestPostgres;

/**
 * Phase 5.5's gate: that the endpoints exist, that a token is the only way to reach them, and
 * that what a caller gets back is what their authority allows and nothing else.
 * <p>
 * The whole service, over real HTTP, against a real PostgreSQL. Not {@code MockMvc}: what is
 * being tested is the chain — filter, token, actor, transaction, policies — and a mocked servlet
 * layer would skip the filter registration, which is one of the things that can be wrong. The
 * service connects as {@code sies_app}, so every policy written in V6 and every function in V8
 * applies to it exactly as in production.
 * <p>
 * Flyway is switched off here because {@link TestPostgres} has already applied the committed
 * migrations with {@code psql}, in the order and as the role {@code tools/roles.sql} prescribes.
 * The connection details arrive as command-line arguments rather than default properties,
 * because {@code application.yml} would otherwise win and go looking for the production
 * environment variables.
 *
 * <h3>What is not tested here</h3>
 * The rate limiter's refusal. It counts attempts per address, every request in this class comes
 * from {@code 127.0.0.1}, and a limit low enough to trip would trip during the fixtures — so the
 * allowance is raised for this context and the limiting itself is tested in
 * {@link LoginRateLimiterTest}, where no clock and no container are needed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WireTest {

	/** 2025-26, the session everything below is in. */
	private static final int SESSION = 2025;

	private static final long PROF_CS = 1001;
	private static final long PROF_CHEM = 1002;
	private static final long PROF_HEAD_CS = 1003;

	private static final long STUDENT_CR = 2001;
	private static final long STUDENT_OTHER = 2002;

	private static final long COURSE_CS = 3001;
	private static final long COURSE_CHEM = 3002;

	/** Long enough for {@code Passwords.tooWeak}, and the same for every fixture account. */
	private static final String PASSWORD = "correct horse battery staple";

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	private static TestPostgres postgres;

	private static ConfigurableApplicationContext service;

	private static int port;

	private long accountProfCs;
	private long accountProfChem;
	private long accountHeadCs;
	private long accountCr;
	private long accountOffice;
	private long accountAdmin;
	private long accountFresh;

	@BeforeAll
	void startEverything() throws Exception {

		assumeTrue(TestPostgres.isAvailable(),
				"Docker is not available, so the endpoints cannot be tested against a real "
						+ "PostgreSQL. tools/verify-wire.sh fails rather than skips.");

		postgres = TestPostgres.start();

		SpringApplication application = new SpringApplication(SiesAttendanceServer.class);

		service = application.run(
				"--spring.datasource.url=" + postgres.appJdbcUrl(),
				"--spring.datasource.username=" + TestPostgres.appUsername(),
				"--spring.datasource.password=" + TestPostgres.appPassword(),
				// Already applied, by psql, in production order. Flyway would find a
				// non-empty schema with no history table and refuse, which is correct of it.
				"--spring.flyway.enabled=false",
				"--server.port=0",
				"--spring.main.banner-mode=off",
				// See the class notes.
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

				INSERT INTO lectures
				    (lecture_id, course_id, batch_id, start_at, start_zone, end_at, end_zone) VALUES
				    (4001, 3001, NULL, '2025-07-14 09:00:00+05:30', 'Asia/Kolkata',
				                       '2025-07-14 10:00:00+05:30', 'Asia/Kolkata'),
				    (4002, 3002, NULL, '2025-07-14 11:00:00+05:30', 'Asia/Kolkata',
				                       '2025-07-14 12:00:00+05:30', 'Asia/Kolkata');

				INSERT INTO attendance (lecture_id, student_id, present) VALUES
				    (4001, 2001, true),
				    (4002, 2002, true);
				""");

		accountProfCs = account(AccountKind.PROFESSOR, PROF_CS, null, "a.cs@sies.edu.in");
		accountProfChem = account(AccountKind.PROFESSOR, PROF_CHEM, null, "b.chem@sies.edu.in");
		accountHeadCs = account(AccountKind.PROFESSOR, PROF_HEAD_CS, null,
				"c.head@sies.edu.in");
		accountCr = account(AccountKind.STUDENT, null, STUDENT_CR, "r.rao@sies.edu.in");
		accountOffice = account(AccountKind.STAFF, null, null, "office@sies.edu.in");
		accountAdmin = account(AccountKind.STAFF, null, null, "admin@sies.edu.in");

		// The one account left as created: must_rotate true, which is what every new account
		// starts as and what the bootstrap's administrator is.
		try (Connection connection = postgres.connection()) {
			accountFresh = AccountDirectory.create(connection, AccountKind.STAFF, null, null,
					"Fresh Clerk", "fresh@sies.edu.in", PASSWORD.toCharArray(), null);
		}

		grantRole(accountAdmin, "ADMIN", null, null, accountAdmin);
		grantRole(accountOffice, "OFFICE", null, null, accountAdmin);
		grantRole(accountHeadCs, "HOD", "COMPUTER_SCIENCE", SESSION, accountAdmin);

		postgres.asSuperuser(
				"INSERT INTO attendance_delegate (student_id, course_id, academic_year, "
						+ "granted_by, granted_as) VALUES ("
						+ STUDENT_CR + ", " + COURSE_CS + ", " + SESSION + ", "
						+ accountProfCs + ", 'INSTRUCTOR')");
	}

	// ── Signing in ───────────────────────────────────────────────────────

	@Test
	void aGoodPasswordAtTheRightDoorReturnsAToken() throws Exception {

		Reply reply = login("a.cs@sies.edu.in", "STAFF", PASSWORD, null);

		assertEquals(200, reply.status(), reply::toString);
		assertFalse(reply.body().get("token").stringValue().isBlank(), "no token came back");
		assertFalse(reply.body().get("mustRotate").booleanValue(),
				"this fixture account has already rotated");
		assertEquals(accountProfCs, reply.body().get("account").get("accountId").longValue());
		assertEquals(PROF_CS, reply.body().get("account").get("profId").longValue());
		assertEquals("PROFESSOR", reply.body().get("account").get("kind").stringValue());
		assertTrue(reply.body().get("deviceNode").isNull(),
				"a login without an install id is not on a device");
	}

	@Test
	void everyRefusalLooksTheSameFromOutside() throws Exception {

		List<Reply> refusals = List.of(
				login("a.cs@sies.edu.in", "STAFF", "the wrong password entirely", null),
				login("nobody@sies.edu.in", "STAFF", PASSWORD, null),
				// A real account, a real password, the wrong door: app_account is unique per
				// door, so the staff door simply does not have this person behind it.
				login("r.rao@sies.edu.in", "STAFF", PASSWORD, null),
				login("a.cs@sies.edu.in", "STAFF", "", null));

		for (Reply refusal : refusals) {
			assertEquals(401, refusal.status(), refusal::toString);
			assertEquals("sign_in_refused", refusal.body().get("code").stringValue());
			assertEquals("Those sign-in details were not accepted.",
					refusal.body().get("message").stringValue(),
					"every refusal has to read identically, or the login box answers questions");
			assertNull(refusal.body().get("token"), "a refusal must not carry a token");
		}

		// And the log knows the difference, which is the whole point of collapsing the
		// response. Three entries, not four: the attempt with an empty password is refused
		// without a database lookup at all, deliberately, because hashing a blank password
		// against Argon2id on every request is a way to make the server do expensive work for
		// nothing. Nothing reached an account, so there is nothing to attribute.
		assertEquals(3, count("SELECT count(*) FROM audit_log WHERE action = 'LOGIN_FAILED'"));
		// Exactly one of the three names an account: the wrong password against a real
		// account. The unknown address has no account to name, and so does a real student at
		// the staff door -- app_account is unique per door, so that address is genuinely
		// nobody on that side of it.
		assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'LOGIN_FAILED' "
				+ "AND actor_account = " + accountProfCs),
				"a wrong password against a real account has to be attributed to it");
		assertEquals(2, count("SELECT count(*) FROM audit_log WHERE action = 'LOGIN_FAILED' "
				+ "AND actor_account IS NULL AND actor_label LIKE 'an unknown address%'"));
	}

	@Test
	void aLoginEntryCarriesTheAddressItCameFrom() throws Exception {

		assertEquals(200, login("a.cs@sies.edu.in", "STAFF", PASSWORD, null).status());

		assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'LOGIN' "
				+ "AND client_ip IS NOT NULL"),
				"one refusal says somebody mistyped; a run of them from one address says "
						+ "something else, and that is only visible if the address is recorded");
	}

	@Test
	void aStudentSignsInAtTheStudentDoor() throws Exception {

		Reply reply = login("r.rao@sies.edu.in", "STUDENT", PASSWORD, null);

		assertEquals(200, reply.status(), reply::toString);
		assertEquals(STUDENT_CR, reply.body().get("account").get("studentId").longValue());
		assertEquals("STUDENT", reply.body().get("account").get("kind").stringValue());
	}

	// ── The token ────────────────────────────────────────────────────────

	@Test
	void aRequestWithNoUsableTokenIsRefused() throws Exception {

		assertEquals(401, get("/export?year=" + SESSION, null, null).status(),
				"no Authorization header at all");

		assertEquals(401, get("/export?year=" + SESSION, "not-a-token-at-all", null).status(),
				"a token nobody issued");

		// And nothing was read: the refusal is the filter's, before any transaction.
		assertEquals(0, count("SELECT count(*) FROM audit_log WHERE action = 'EXPORT'"));
	}

	@Test
	void aTokenIsRefusedWhenReplayedFromAnotherDevice() throws Exception {

		String token = tokenFor("a.cs@sies.edu.in", "STAFF");
		UUID install = UUID.randomUUID();

		assertEquals(200, register(token, null, install, 40000, "Rao's phone").status());

		// The session is bound to that install now, so the token alone is no longer enough.
		assertEquals(401, get("/export?year=" + SESSION, token, null).status(),
				"a device-bound session must name its device");

		assertEquals(401, get("/export?year=" + SESSION, token, UUID.randomUUID()).status(),
				"a token lifted onto another phone must not work");

		assertEquals(200, get("/export?year=" + SESSION, token, install).status(),
				"and the phone it was issued to must still work");
	}

	@Test
	void anExpiredTokenIsRefused() throws Exception {

		String token = tokenFor("a.cs@sies.edu.in", "STAFF");

		// issued_at moves with it: app_session_expires_after_issue would refuse an expiry
		// before the issue, which is the constraint doing its job.
		postgres.asSuperuser("UPDATE app_session SET issued_at = now() - interval '30 days', "
				+ "expires_at = now() - interval '1 day'");

		assertEquals(401, get("/export?year=" + SESSION, token, null).status());
	}

	@Test
	void aRevokedTokenIsRefusedFromTheNextRequest() throws Exception {

		String token = tokenFor("a.cs@sies.edu.in", "STAFF");

		assertEquals(200, get("/export?year=" + SESSION, token, null).status());
		assertEquals(200, post("/auth/logout", "{}", token, null).status());

		assertEquals(401, get("/export?year=" + SESSION, token, null).status(),
				"revoking has to take effect on the next request, not the next login");
	}

	@Test
	void aDisabledAccountStopsWorkingOnItsNextRequest() throws Exception {

		String token = tokenFor("a.cs@sies.edu.in", "STAFF");
		assertEquals(200, get("/export?year=" + SESSION, token, null).status());

		postgres.asSuperuser("UPDATE app_account SET disabled_at = now() "
				+ "WHERE account_id = " + accountProfCs);

		assertEquals(401, get("/export?year=" + SESSION, token, null).status(),
				"disabling an account is not something to wait for a token to expire for");
	}

	// ── Rotation ─────────────────────────────────────────────────────────

	@Test
	void anAccountThatMustRotateCanDoNothingElse() throws Exception {

		Reply login = login("fresh@sies.edu.in", "STAFF", PASSWORD, null);

		assertEquals(200, login.status(), login::toString);
		assertTrue(login.body().get("mustRotate").booleanValue(),
				"a new account's password was typed by whoever created it");

		String token = login.body().get("token").stringValue();

		Reply refused = get("/export?year=" + SESSION, token, null);
		assertEquals(403, refused.status());
		assertEquals("password_rotation_required", refused.body().get("code").stringValue());

		Reply changed = post("/auth/password", json("currentPassword", PASSWORD,
				"newPassword", "a quite different long password"), token, null);
		assertEquals(200, changed.status(), changed::toString);

		// The session that rotated keeps working, and now for everything.
		assertEquals(200, get("/export?year=" + SESSION, token, null).status());

		assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'PASSWORD_ROTATE' "
				+ "AND actor_account = " + accountFresh));
	}

	@Test
	void changingThePasswordEndsEveryOtherSession() throws Exception {

		String first = tokenFor("a.cs@sies.edu.in", "STAFF");
		String second = tokenFor("a.cs@sies.edu.in", "STAFF");

		Reply changed = post("/auth/password", json("currentPassword", PASSWORD,
				"newPassword", "another perfectly good password"), second, null);

		assertEquals(200, changed.status(), changed::toString);
		assertEquals(1, changed.body().get("otherSessionsRevoked").intValue());

		assertEquals(401, get("/export?year=" + SESSION, first, null).status(),
				"somebody changing their password because somebody else has it is not "
						+ "finished until the other sign-ins are gone");
		assertEquals(200, get("/export?year=" + SESSION, second, null).status(),
				"and not signed out of the device in their hand");

		// The new password works and the old one does not.
		assertEquals(401, login("a.cs@sies.edu.in", "STAFF", PASSWORD, null).status());
		assertEquals(200, login("a.cs@sies.edu.in", "STAFF",
				"another perfectly good password", null).status());
	}

	@Test
	void aPasswordChangeIsAttributedOnItsRowEntryToo() throws Exception {

		String token = tokenFor("a.cs@sies.edu.in", "STAFF");

		// Emptied first, so what is counted below is this request's doing and not the
		// fixture's: the accounts are created as the superuser with no actor, which is
		// legitimately "system" and is exactly what must not be confused with a request.
		postgres.asSuperuser("TRUNCATE audit_log");

		assertEquals(200, post("/auth/password", json("currentPassword", PASSWORD,
				"newPassword", "another perfectly good password"), token, null).status());

		// Two entries for one change: the PASSWORD_ROTATE event and the UPDATE the trigger
		// saw. Both have to name the person. The first draft announced the actor after the
		// writes, so the event named them and the row entry beside it said "system" -- found
		// by reading the log of a by-hand run rather than by a test, which is why there is now
		// a test.
		assertEquals(1, count("SELECT count(*) FROM audit_log "
				+ "WHERE action = 'PASSWORD_ROTATE' AND actor_account = " + accountProfCs));

		assertEquals(0, count("SELECT count(*) FROM audit_log "
				+ "WHERE action = 'UPDATE' AND table_name = 'app_account' "
				+ "AND actor_account IS NULL"),
				"a row change made inside a request must not be recorded as the system's");

		// And it carries the request's context, not a bare account id: the change runs on the
		// request's own transaction, so the session, the device and the address are the ones
		// everything else in this phase records.
		assertEquals(2, count("SELECT count(*) FROM audit_log "
				+ "WHERE session_id IS NOT NULL AND client_ip IS NOT NULL"),
				"both entries for the change should name the sign-in it was made on");
	}

	@Test
	void aWrongCurrentPasswordChangesNothing() throws Exception {

		String token = tokenFor("a.cs@sies.edu.in", "STAFF");

		Reply refused = post("/auth/password", json("currentPassword", "not it",
				"newPassword", "a perfectly good new password"), token, null);

		assertEquals(403, refused.status());
		assertEquals("current_password_wrong", refused.body().get("code").stringValue());

		assertEquals(200, login("a.cs@sies.edu.in", "STAFF", PASSWORD, null).status(),
				"the old password must still be the password");
		assertEquals(0, count("SELECT count(*) FROM audit_log "
				+ "WHERE action = 'PASSWORD_ROTATE'"));
	}

	@Test
	void aShortNewPasswordIsRefused() throws Exception {

		String token = tokenFor("a.cs@sies.edu.in", "STAFF");

		Reply refused = post("/auth/password",
				json("currentPassword", PASSWORD, "newPassword", "short"), token, null);

		assertEquals(400, refused.status());
		assertEquals("weak_password", refused.body().get("code").stringValue());
	}

	// ── Devices ──────────────────────────────────────────────────────────

	@Test
	void registeringADeviceIssuesANodeFromTheAssignableHalf() throws Exception {

		String token = tokenFor("a.cs@sies.edu.in", "STAFF");
		UUID install = UUID.randomUUID();

		Reply reply = register(token, null, install, 41234, "Rao's phone");

		assertEquals(200, reply.status(), reply::toString);

		int node = reply.body().get("node").intValue();

		assertTrue(node >= StructuredId.FIRST_ASSIGNABLE_NODE_ID
						&& node <= StructuredId.MAX_ASSIGNED_NODE_ID,
				"a node has to come from the half the server hands out, not the half a device "
						+ "picks for itself: got " + node);
		assertFalse(reply.body().get("alreadyRegistered").booleanValue());
		assertEquals(0, reply.body().get("provisionalNodeClashes").longValue());

		// And the session this was called on is now bound to it, so one login was enough.
		assertEquals(1, count("SELECT count(*) FROM app_session WHERE device_id IS NOT NULL"));
	}

	@Test
	void registeringTheSameInstallAgainReturnsTheSameNode() throws Exception {

		String token = tokenFor("a.cs@sies.edu.in", "STAFF");
		UUID install = UUID.randomUUID();

		int first = register(token, null, install, 41234, "Rao's phone")
				.body().get("node").intValue();

		Reply again = register(token, install, install, 41234, "Rao's phone");

		assertEquals(200, again.status(), again::toString);
		assertEquals(first, again.body().get("node").intValue(),
				"a retry after a lost response must not burn a second node");
		assertTrue(again.body().get("alreadyRegistered").booleanValue());

		assertEquals(1, count("SELECT count(*) FROM device_registration"));
		assertEquals(1, count("SELECT count(*) FROM audit_log "
				+ "WHERE action = 'DEVICE_REGISTER'"),
				"and must not record a second registration that did not happen");
	}

	@Test
	void aProvisionalNodeTwoInstallsClaimIsReportedRatherThanRefused() throws Exception {

		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();

		String one = tokenFor("a.cs@sies.edu.in", "STAFF");
		assertEquals(0, register(one, null, first, 40000, "Rao's phone")
				.body().get("provisionalNodeClashes").longValue());

		String two = tokenFor("b.chem@sies.edu.in", "STAFF");
		Reply clashing = register(two, null, second, 40000, "Shah's phone");

		assertEquals(200, clashing.status(),
				"the clash already happened in the field; refusing the registration would "
						+ "leave the second phone unregistered and the collision unrecorded");
		assertEquals(1, clashing.body().get("provisionalNodeClashes").longValue());

		// On the entry as well as in the response, because this is the fact that stops being
		// discoverable once the rows arrive carrying nothing but the number.
		assertEquals(1, count("SELECT count(*) FROM audit_log "
				+ "WHERE action = 'DEVICE_REGISTER' "
				+ "AND (detail ->> 'provisional_node_clashes')::bigint = 1"));
	}

	@Test
	void aNodeFromTheWrongHalfIsRefused() throws Exception {

		String token = tokenFor("a.cs@sies.edu.in", "STAFF");

		Reply refused = register(token, null, UUID.randomUUID(), 17, "Rao's phone");

		assertEquals(400, refused.status());
		assertEquals("bad_provisional_node", refused.body().get("code").stringValue());
		assertEquals(0, count("SELECT count(*) FROM device_registration"));
	}

	// ── The export ───────────────────────────────────────────────────────

	@Test
	void aProfessorExportsExactlyTheirOwnCourses() throws Exception {

		Reply reply = get("/export?year=" + SESSION,
				tokenFor("a.cs@sies.edu.in", "STAFF"), null);

		assertEquals(200, reply.status(), reply::toString);
		assertEquals(1, reply.body().get("courseCount").intValue(),
				"a professor who teaches one course exports one course");

		for (JsonNode row : reply.body().get("rows")) {
			assertEquals(COURSE_CS, row.get("courseID").longValue(),
					"no other course may appear, however empty");
		}

		// The roster of CS-101 is the one Computer Science student, who attended its one
		// lecture. The counts come from attendance and lectures, which are under FORCE ROW
		// LEVEL SECURITY, so the policies decided them rather than this endpoint.
		assertEquals(1, reply.body().get("rowCount").intValue());
		assertEquals(STUDENT_CR, reply.body().get("rows").get(0).get("studentID").longValue());
		assertEquals(1, reply.body().get("rows").get(0).get("held").longValue());
		assertEquals(1, reply.body().get("rows").get(0).get("attended").longValue());
	}

	@Test
	void aCourseThatIsNotYoursIsRefusedRatherThanQuietlyDropped() throws Exception {

		Reply refused = get("/export?year=" + SESSION + "&courses=" + COURSE_CHEM,
				tokenFor("a.cs@sies.edu.in", "STAFF"), null);

		assertEquals(403, refused.status());
		assertEquals("not_yours_to_export", refused.body().get("code").stringValue());

		// Silently returning fewer columns than were asked for is how somebody comes to trust
		// a report that is quietly missing a course.
		assertEquals(0, count("SELECT count(*) FROM audit_log WHERE action = 'EXPORT'"),
				"a refused export is not an export");
	}

	@Test
	void theHeadOfDepartmentExportsTheDepartment() throws Exception {

		Reply reply = get("/export?year=" + SESSION,
				tokenFor("c.head@sies.edu.in", "STAFF"), null);

		assertEquals(200, reply.status(), reply::toString);
		assertEquals(1, reply.body().get("courseCount").intValue(),
				"Computer Science offers one course in this fixture");

		for (JsonNode row : reply.body().get("rows")) {
			assertEquals(COURSE_CS, row.get("courseID").longValue(),
					"a head of Computer Science may not export Chemistry");
		}
	}

	@Test
	void aClassRepresentativeExportsTheCourseTheyHoldAGrantFor() throws Exception {

		Reply reply = get("/export?year=" + SESSION,
				tokenFor("r.rao@sies.edu.in", "STUDENT"), null);

		assertEquals(200, reply.status(), reply::toString);
		assertEquals(1, reply.body().get("courseCount").intValue());
		assertEquals(COURSE_CS, reply.body().get("rows").get(0).get("courseID").longValue());
	}

	@Test
	void theOfficeExportsEverything() throws Exception {

		Reply reply = get("/export?year=" + SESSION,
				tokenFor("office@sies.edu.in", "STAFF"), null);

		assertEquals(200, reply.status(), reply::toString);
		assertEquals(2, reply.body().get("courseCount").intValue());
		assertEquals(2, reply.body().get("rowCount").intValue(),
				"both students, each on their own department's course");
	}

	@Test
	void aProfessorWithNoCoursesExportsNothingRatherThanEverything() throws Exception {

		// A clerk with no role at all: no courses to instruct, no department, no grant.
		postgres.asSuperuser("UPDATE app_account SET must_rotate = false "
				+ "WHERE account_id = " + accountFresh);

		Reply reply = get("/export?year=" + SESSION,
				tokenFor("fresh@sies.edu.in", "STAFF"), null);

		assertEquals(200, reply.status(), reply::toString);
		assertEquals(0, reply.body().get("courseCount").intValue());
		assertEquals(0, reply.body().get("rowCount").intValue(),
				"an empty allowance must mean nothing, not an unrestricted query");
	}

	@Test
	void anExportLeavesAnEntryNamingTheFilterAndTheCounts() throws Exception {

		String token = tokenFor("office@sies.edu.in", "STAFF");
		UUID install = UUID.randomUUID();
		assertEquals(200, register(token, null, install, 40321, "Office desk").status());

		Reply reply = get("/export?year=" + SESSION + "&years=FY&from=2025-07-01"
				+ "&to=2025-07-31", token, install);

		assertEquals(200, reply.status(), reply::toString);

		Map<String, String> entry = row("SELECT detail ->> 'filter' AS filter, "
				+ "detail ->> 'rows' AS rows, detail ->> 'courses' AS courses, "
				+ "actor_account::text AS actor, actor_label AS label, "
				// host(), not client_ip::text: an inet renders as 127.0.0.1/32, which is the
				// same address and not the same string, and is the sort of thing to find out
				// in a test rather than in a report.
				+ "device_node::text AS node, host(client_ip) AS ip, "
				+ "session_id::text AS session, table_name "
				+ "FROM audit_log WHERE action = 'EXPORT'");

		assertNotNull(entry, "an export has to leave an entry");
		assertTrue(entry.get("filter").contains("Dates: 2025-07-01 to 2025-07-31"),
				"the filter has to be in the entry, not just the fact that something was "
						+ "exported: got " + entry.get("filter"));
		assertTrue(entry.get("filter").contains("FY"), entry.get("filter"));
		assertEquals(Integer.toString(reply.body().get("rowCount").intValue()),
				entry.get("rows"));
		assertEquals(Long.toString(accountOffice), entry.get("actor"));

		// The context the session carries, which is what makes an entry answer "who, from
		// where, on what" rather than only "what".
		assertNotNull(entry.get("session"), "the entry should name the sign-in it happened on");
		assertNotNull(entry.get("node"), "and the device it came from");
		assertEquals("127.0.0.1", entry.get("ip"));
		assertFalse(entry.get("label").isBlank());
	}

	@Test
	void anExportCarriesNoRowsIntoTheLog() throws Exception {

		assertEquals(200, get("/export?year=" + SESSION,
				tokenFor("office@sies.edu.in", "STAFF"), null).status());

		assertEquals(0, count("SELECT count(*) FROM audit_log WHERE action = 'EXPORT' "
				+ "AND detail::text LIKE '%CS-FY-001%'"),
				"an audit log holding a copy of every export would be the most valuable thing "
						+ "in the database to steal");
	}

	@Test
	void aMalformedFilterIsRefusedBeforeAnythingIsRead() throws Exception {

		String token = tokenFor("office@sies.edu.in", "STAFF");

		assertEquals(400, get("/export?year=" + SESSION + "&years=NONSENSE", token, null)
				.status());
		assertEquals(400, get("/export?year=" + SESSION + "&from=last-tuesday", token, null)
				.status());
		assertEquals(400, get("/export?year=" + SESSION + "&courses=abc", token, null)
				.status());
		assertEquals(400, get("/export", token, null).status(),
				"the session is not optional: an export with no year is not a question");

		assertEquals(0, count("SELECT count(*) FROM audit_log WHERE action = 'EXPORT'"));
	}

	// ── The roster ───────────────────────────────────────────────────────

	@Test
	void theOfficeLoadsARoster() throws Exception {

		Reply reply = postCsv("/roster?year=" + SESSION, """
				rollNo,name,pronouns,email,year,stream,majorDept,semester
				CS-FY-010,New Student,they/them,new@sies.edu.in,FY,BSc_unaided,COMPUTER_SCIENCE,1
				CS-FY-001,R RAO RENAMED,she/her,r.rao@sies.edu.in,FY,BSc_unaided,COMPUTER_SCIENCE,1
				""", tokenFor("office@sies.edu.in", "STAFF"));

		assertEquals(200, reply.status(), reply::toString);
		assertEquals(1, reply.body().get("created").intValue());
		assertEquals(1, reply.body().get("updated").intValue(),
				"a row whose roll number is already on file for the session is an edit");
		assertEquals(0, reply.body().get("failed").intValue(), reply::toString);

		assertEquals(1, count("SELECT count(*) FROM student_year "
				+ "WHERE roll_no = 'CS-FY-010' AND academic_year = " + SESSION));
		assertEquals(1, count("SELECT count(*) FROM students WHERE name = 'R RAO RENAMED'"));

		// A new student gets a key under the server's node, so a student the office typed in
		// is distinguishable for ever from one a phone created.
		assertEquals(StructuredId.SERVER_NODE, count("SELECT s.student_id & "
				+ StructuredId.MAX_NODE_ID + " FROM students s "
				+ "JOIN student_year y ON y.student_id = s.student_id "
				+ "WHERE y.roll_no = 'CS-FY-010'"));

		assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'CSV_IMPORT' "
				+ "AND (detail ->> 'created')::int = 1 AND (detail ->> 'updated')::int = 1 "
				+ "AND actor_account = " + accountOffice));
	}

	@Test
	void aProfessorMayNotLoadARoster() throws Exception {

		Reply refused = postCsv("/roster?year=" + SESSION, """
				rollNo,name,year,stream,majorDept
				CS-FY-010,New Student,FY,BSc_unaided,COMPUTER_SCIENCE
				""", tokenFor("a.cs@sies.edu.in", "STAFF"));

		assertEquals(403, refused.status(), refused::toString);
		assertEquals("not_allowed", refused.body().get("code").stringValue());

		assertEquals(0, count("SELECT count(*) FROM student_year "
				+ "WHERE roll_no = 'CS-FY-010'"), "and nothing was written");
		assertEquals(0, count("SELECT count(*) FROM audit_log WHERE action = 'CSV_IMPORT'"));
	}

	@Test
	void aBadRowCostsOnlyThatRow() throws Exception {

		Reply reply = postCsv("/roster?year=" + SESSION, """
				rollNo,name,year,stream,majorDept,minorDept
				CS-FY-010,Good Row,FY,BSc_unaided,COMPUTER_SCIENCE,
				CS-FY-011,Unknown Department,FY,BSc_unaided,NOT_A_DEPARTMENT,
				CS-FY-012,Minor Equals Major,FY,BSc_unaided,COMPUTER_SCIENCE,COMPUTER_SCIENCE
				""", tokenFor("office@sies.edu.in", "STAFF"));

		assertEquals(200, reply.status(), reply::toString);
		assertEquals(1, reply.body().get("created").intValue());
		assertEquals(2, reply.body().get("failed").intValue(), reply::toString);
		assertEquals(2, reply.body().get("errors").size());

		// Each failure says which line, because the point of the list is to be able to go and
		// correct the file.
		assertTrue(reply.body().get("errors").get(0).stringValue().startsWith("Line 3:"),
				reply.body().get("errors").toString());
		assertTrue(reply.body().get("errors").get(1).stringValue().contains("minor department"),
				reply.body().get("errors").toString());

		assertEquals(1, count("SELECT count(*) FROM student_year "
				+ "WHERE roll_no = 'CS-FY-010' AND academic_year = " + SESSION),
				"the good row still landed");
	}

	@Test
	void aRowTheDatabaseRefusesIsRolledBackOnItsOwn() throws Exception {

		// The second row claims an address another student already has, which
		// students_email_uq refuses. Without a savepoint per row the refusal would abort the
		// transaction and take the first row with it.
		Reply reply = postCsv("/roster?year=" + SESSION, """
				rollNo,name,email,year,stream,majorDept
				CS-FY-010,Good Row,new@sies.edu.in,FY,BSc_unaided,COMPUTER_SCIENCE
				CH-FY-001,Claims Another Address,r.rao@sies.edu.in,FY,BSc,CHEMISTRY
				""", tokenFor("office@sies.edu.in", "STAFF"));

		assertEquals(200, reply.status(), reply::toString);
		assertEquals(1, reply.body().get("created").intValue());
		assertEquals(1, reply.body().get("failed").intValue(), reply::toString);

		assertEquals(1, count("SELECT count(*) FROM student_year "
				+ "WHERE roll_no = 'CS-FY-010' AND academic_year = " + SESSION));
		assertEquals(1, count("SELECT count(*) FROM students "
				+ "WHERE email = 'r.rao@sies.edu.in' AND name = 'R RAO'"),
				"the refused row must have changed nothing at all");
	}

	@Test
	void aRosterForASessionThatDoesNotExistFailsEveryRowAndWritesNothing() throws Exception {

		Reply reply = postCsv("/roster?year=2099", """
				rollNo,name,year,stream,majorDept
				CS-FY-010,Good Row,FY,BSc_unaided,COMPUTER_SCIENCE
				""", tokenFor("office@sies.edu.in", "STAFF"));

		assertEquals(200, reply.status(), reply::toString);
		assertEquals(0, reply.body().get("created").intValue());
		assertEquals(1, reply.body().get("failed").intValue());

		// The students row is inserted before the student_year row that fails, so this is the
		// savepoint being rolled back rather than the row never having been attempted.
		assertEquals(0, count("SELECT count(*) FROM students WHERE name = 'Good Row'"));
	}

	@Test
	void aRosterWithoutItsRequiredColumnsIsRejectedWhole() throws Exception {

		Reply reply = postCsv("/roster?year=" + SESSION, """
				name,email
				Somebody,somebody@sies.edu.in
				""", tokenFor("office@sies.edu.in", "STAFF"));

		assertEquals(200, reply.status(), reply::toString);
		assertEquals(0, reply.body().get("created").intValue());
		assertEquals(1, reply.body().get("failed").intValue());
		assertTrue(reply.body().get("errors").get(0).stringValue()
						.contains("Missing required column"),
				reply.body().get("errors").toString());
	}

	@Test
	void aDeviceThatLearnsItsOldNodeIsListenedTo() throws Exception {

		// The case a rebuilt database creates: the phone registered once without reporting the
		// node it had picked for itself -- older builds only reported it while it was still
		// current -- and now reports it. The node it was assigned stands, because taking a
		// second one would attribute half its rows to a device that is really the same one.
		// The provisional number is filled in, because it is the only thing that explains the
		// keys written before registration and without it they cannot be pushed.
		String token = tokenFor("a.cs@sies.edu.in", "STAFF");
		UUID install = UUID.randomUUID();

		Reply first = register(token, null, install, null, "Pixel 7a");

		assertEquals(200, first.status(), first::toString);
		int node = first.body().get("node").intValue();

		assertEquals(1, count("SELECT count(*) FROM device_registration "
				+ "WHERE install_id = '" + install + "' AND provisional_node IS NULL"));

		// The install header, because the first registration bound this session to the device:
		// from then on every request has to name it. That binding is the point of V8's
		// device_session_binding, and a test that forgot the header got a 401 rather than the
		// answer it was looking for.
		Reply again = register(token, install, install, 37498, "Pixel 7a");

		assertEquals(200, again.status(), again::toString);
		assertEquals(node, again.body().get("node").intValue(),
				"the assigned node stands: a second one would split this phone in two");
		assertTrue(again.body().get("alreadyRegistered").booleanValue());

		assertEquals(1, count("SELECT count(*) FROM device_registration "
						+ "WHERE install_id = '" + install + "' AND provisional_node = 37498"),
				"and the number it used before is now on record");
	}

	@Test
	void aRecordedProvisionalNodeIsNotRewritten() throws Exception {

		String token = tokenFor("a.cs@sies.edu.in", "STAFF");
		UUID install = UUID.randomUUID();

		assertEquals(200, register(token, null, install, 41000, "Pixel 7a").status());
		assertEquals(200, register(token, install, install, 59999, "Pixel 7a").status(),
				"the install header, because registering bound the session to this device");

		assertEquals(1, count("SELECT count(*) FROM device_registration "
						+ "WHERE install_id = '" + install + "' AND provisional_node = 41000"),
				"the first number was reported while it was still current, which is the "
						+ "better evidence; a later one must not overwrite it");
	}

	// ── Accounts and roles ───────────────────────────────────────────────

	/*
	 * The administration endpoints, and what they refuse. The refusals are the point: an
	 * account is an authority rather than a record about somebody, so every one of these is
	 * about who may hand one out.
	 */

	@Test
	void anAdminCreatesAnAccountAndGrantsItARoleInOneRequest() throws Exception {

		String token = tokenFor("admin@sies.edu.in", "STAFF");

		Reply created = post("/accounts", json(
				"kind", "STAFF",
				"displayName", "D AUDIT",
				"loginEmail", "audit@sies.edu.in",
				"password", "a long enough opening password",
				"role", "AUDITOR"), token, null);

		assertEquals(200, created.status(), created::toString);

		long accountId = created.body().get("accountId").longValue();

		assertTrue(created.body().get("mustRotate").booleanValue(),
				"whoever typed the opening password must not be the person who ends up "
						+ "knowing it");
		assertEquals("AUDITOR",
				created.body().get("roles").get(0).get("role").stringValue());

		assertEquals(1, count("SELECT count(*) FROM app_account WHERE account_id = "
				+ accountId));
		assertEquals(1, count("SELECT count(*) FROM user_role WHERE account_id = " + accountId
						+ " AND role = 'AUDITOR' AND revoked_at IS NULL AND granted_by = "
						+ accountAdmin),
				"the grant names who made it, which is what makes it answerable");

		// Both rows are in the audit log against the admin, written by the triggers rather
		// than by the endpoint.
		assertEquals(2, count("SELECT count(*) FROM audit_log WHERE action = 'INSERT' "
						+ "AND table_name IN ('app_account', 'user_role') "
						+ "AND actor_account = " + accountAdmin),
				"an account and its role are two rows and two entries");

		// And it can sign in, and is told to change the password before anything else.
		Reply signedIn = login("audit@sies.edu.in", "STAFF",
				"a long enough opening password", null);

		assertEquals(200, signedIn.status(), signedIn::toString);
		assertTrue(signedIn.body().get("mustRotate").booleanValue());
	}

	@Test
	void anAccountAndItsRoleArriveTogetherOrNotAtAll() throws Exception {

		String token = tokenFor("admin@sies.edu.in", "STAFF");

		// A head of department with no department: the grant cannot be written, and the
		// account must not be either.
		Reply refused = post("/accounts", json(
				"kind", "STAFF",
				"displayName", "E HEAD",
				"loginEmail", "ehead@sies.edu.in",
				"password", "a long enough opening password",
				"role", "HOD"), token, null);

		assertEquals(400, refused.status(), refused::toString);
		assertEquals("incomplete_grant", refused.body().get("code").stringValue());

		assertEquals(0, count("SELECT count(*) FROM app_account "
						+ "WHERE login_email = 'ehead@sies.edu.in'"),
				"half of \"create an auditor\" is a login that can do nothing and nobody "
						+ "knows why");
	}

	@Test
	void aProfessorMayNotCreateAccountsOrReadTheList() throws Exception {

		String token = tokenFor("a.cs@sies.edu.in", "STAFF");

		Reply created = post("/accounts", json(
				"kind", "STAFF",
				"displayName", "F NOBODY",
				"loginEmail", "nobody@sies.edu.in",
				"password", "a long enough opening password"), token, null);

		assertEquals(403, created.status(), created::toString);
		assertEquals("not_allowed", created.body().get("code").stringValue());

		assertEquals(403, get("/accounts", token, null).status(),
				"the list is a list of who can do what, which is not a teaching matter");
		assertEquals(403, get("/accounts/options", token, null).status());

		assertEquals(0, count("SELECT count(*) FROM app_account "
				+ "WHERE login_email = 'nobody@sies.edu.in'"));
	}

	@Test
	void theOfficeMayNotCreateAccountsEither() throws Exception {

		// Stated as a test because it is the line most likely to move: onboarding is office
		// work in most colleges, and V12 is one line if that is what is wanted. Until then
		// the behaviour is what the file says it is.
		String token = tokenFor("office@sies.edu.in", "STAFF");

		Reply created = post("/accounts", json(
				"kind", "STAFF",
				"displayName", "G OFFICE MADE",
				"loginEmail", "officemade@sies.edu.in",
				"password", "a long enough opening password"), token, null);

		assertEquals(403, created.status(), created::toString);
	}

	@Test
	void oneAddressMayHoldAStaffAccountAndAStudentAccountButNotTwoOfEither()
			throws Exception {

		String token = tokenFor("admin@sies.edu.in", "STAFF");

		Reply duplicate = post("/accounts", json(
				"kind", "STAFF",
				"displayName", "H CLASH",
				"loginEmail", "admin@sies.edu.in",
				"password", "a long enough opening password"), token, null);

		assertEquals(409, duplicate.status(), duplicate::toString);
		assertEquals("email_taken", duplicate.body().get("code").stringValue());

		// The same address on the other door is a different account, which is what the
		// unique index says and what the two login screens are for.
		Reply student = post("/accounts", json(
				"kind", "STUDENT",
				"studentId", Long.toString(STUDENT_OTHER),
				"displayName", "S SHAH",
				"loginEmail", "admin@sies.edu.in",
				"password", "a long enough opening password"), token, null);

		assertEquals(200, student.status(), student::toString);
	}

	@Test
	void anAccountHasToNameThePersonItIsForAndStaffNameNobody() throws Exception {

		String token = tokenFor("admin@sies.edu.in", "STAFF");

		Reply noProfessor = post("/accounts", json(
				"kind", "PROFESSOR",
				"displayName", "I NOBODY",
				"loginEmail", "iprof@sies.edu.in",
				"password", "a long enough opening password"), token, null);

		assertEquals(400, noProfessor.status(), noProfessor::toString);
		assertEquals("needs_person", noProfessor.body().get("code").stringValue());

		Reply staffWithPerson = post("/accounts", json(
				"kind", "STAFF",
				"profId", PROF_CS,
				"displayName", "J STAFF",
				"loginEmail", "jstaff@sies.edu.in",
				"password", "a long enough opening password"), token, null);

		assertEquals(400, staffWithPerson.status(), staffWithPerson::toString);
		assertEquals("staff_has_nobody", staffWithPerson.body().get("code").stringValue());
	}

	@Test
	void anOpeningPasswordIsHeldToTheSameLengthAsAnyOther() throws Exception {

		String token = tokenFor("admin@sies.edu.in", "STAFF");

		Reply weak = post("/accounts", json(
				"kind", "STAFF",
				"displayName", "K WEAK",
				"loginEmail", "kweak@sies.edu.in",
				"password", "short"), token, null);

		assertEquals(400, weak.status(), weak::toString);
		assertEquals("weak_password", weak.body().get("code").stringValue());
	}

	@Test
	void aCollegeWideRoleTakesNoDepartmentAndAHeadTakesBoth() throws Exception {

		String token = tokenFor("admin@sies.edu.in", "STAFF");

		Reply scoped = post("/accounts/" + accountOffice + "/roles",
				json("role", "AUDITOR", "department", "CHEMISTRY"), token, null);

		assertEquals(400, scoped.status(), scoped::toString);
		assertEquals("scoped_grant", scoped.body().get("code").stringValue());

		Reply head = post("/accounts/" + accountProfChem + "/roles",
				json("role", "HOD", "department", "CHEMISTRY", "academicYear", SESSION),
				token, null);

		assertEquals(200, head.status(), head::toString);
		assertEquals(1, count("SELECT count(*) FROM user_role WHERE account_id = "
				+ accountProfChem + " AND role = 'HOD' AND department = 'CHEMISTRY' "
				+ "AND academic_year = " + SESSION));
	}

	@Test
	void revokingARoleKeepsTheGrantAndStopsThePower() throws Exception {

		String token = tokenFor("admin@sies.edu.in", "STAFF");

		long grantId = row("SELECT user_role_id FROM user_role WHERE account_id = "
				+ accountOffice + " AND role = 'OFFICE'").get("user_role_id") == null
						? 0L
						: Long.parseLong(row("SELECT user_role_id FROM user_role "
								+ "WHERE account_id = " + accountOffice
								+ " AND role = 'OFFICE'").get("user_role_id"));

		Reply revoked = post("/accounts/" + accountOffice + "/roles/" + grantId + "/revoke",
				json("reason", "left the registry"), token, null);

		assertEquals(200, revoked.status(), revoked::toString);
		assertFalse(revoked.body().get("live").booleanValue());

		assertEquals(1, count("SELECT count(*) FROM user_role WHERE user_role_id = " + grantId
						+ " AND revoked_at IS NOT NULL AND revoked_by = " + accountAdmin),
				"a grant is revoked and never deleted: who held what and when has to stay "
						+ "answerable");

		// And it takes effect at once, on the next request rather than the next sign-in.
		assertEquals(403, postCsv("/roster?year=" + SESSION, "rollNo,name\n",
						tokenFor("office@sies.edu.in", "STAFF")).status(),
				"the roster was theirs a moment ago");

		// Revoking it twice is not an error the second time round, it is a 404: there is no
		// live grant to revoke.
		assertEquals(404, post("/accounts/" + accountOffice + "/roles/" + grantId + "/revoke",
				json("reason", "again"), token, null).status());
	}

	@Test
	void theListSaysWhoHoldsWhatAndNeverAHash() throws Exception {

		String token = tokenFor("admin@sies.edu.in", "STAFF");
		Reply listed = get("/accounts", token, null);

		assertEquals(200, listed.status(), listed::toString);
		assertTrue(listed.body().size() >= 6, listed::toString);

		assertFalse(listed.raw().contains("argon2"),
				"a password hash has no business leaving the database, and the encoding "
						+ "names itself");
		assertFalse(listed.raw().contains("passwordHash"), listed::toString);

		boolean foundTheAdmin = false;

		for (JsonNode account : listed.body()) {

			if (account.get("accountId").longValue() == accountAdmin) {
				foundTheAdmin = true;
				assertEquals("ADMIN",
						account.get("roles").get(0).get("role").stringValue());
			}
		}

		assertTrue(foundTheAdmin, listed::toString);
	}

	@Test
	void theFormsAreOfferedTheDatabasesOwnDepartmentsAndSessions() throws Exception {

		String token = tokenFor("admin@sies.edu.in", "STAFF");
		Reply options = get("/accounts/options", token, null);

		assertEquals(200, options.status(), options::toString);

		// From the department enum itself, so a department added in a migration appears
		// without anybody editing a list in a web page.
		assertEquals(Department.values().length, options.body().get("departments").size(),
				options::toString);
		assertEquals(Department.values()[0].name(),
				options.body().get("departments").get(0).stringValue());

		assertEquals(2, options.body().get("sessions").size(), "the fixture has two");
		assertEquals(4, options.body().get("roles").size());
	}

	@Test
	void thePeoplePickerOffersProfessorsAndStudentsAndNotStaff() throws Exception {

		String token = tokenFor("admin@sies.edu.in", "STAFF");

		Reply professors = get("/accounts/people?kind=PROFESSOR", token, null);

		assertEquals(200, professors.status(), professors::toString);
		assertEquals(3, professors.body().size(), professors::toString);

		Reply students = get("/accounts/people?kind=STUDENT", token, null);

		assertEquals(200, students.status(), students::toString);
		assertEquals(2, students.body().size(), students::toString);
		assertTrue(students.raw().contains("CS-FY-001"),
				"the roll number is how a person is told from another of the same name");

		assertEquals(400, get("/accounts/people?kind=STAFF", token, null).status(),
				"a staff account is attached to nobody, so there is no list");
	}

	@Test
	void theAdministrationPageIsServedWithoutAToken() throws Exception {

		// The filter guards /api/* and nothing else, which is deliberate: the page is a
		// static file and holds no data. Everything it shows, it fetches with a token.
		Reply page = send(HttpRequest.newBuilder(
				URI.create("http://localhost:" + port + "/accounts.html")).GET(),
				null, null);

		assertEquals(200, page.status());
		assertTrue(page.raw().contains("SIES Attendance"), "the page itself");

		// Mentioning it is fine -- the page says in a comment why it does not use it. What
		// must not appear is a write: a token in storage outlives the person walking away
		// from the machine, and survives the tab that was supposed to end the session.
		assertFalse(page.raw().contains("localStorage.setItem")
						|| page.raw().contains("sessionStorage.setItem")
						|| page.raw().contains("document.cookie"),
				"the token lives in a variable and is meant to die with the tab");
	}

	// ── Keys on the wire ────────────────────────────────────────────────

	/**
	 * A real student key, and what a browser makes of it.
	 * <p>
	 * {@code 370666965485748224} is a {@code StructuredId} the import minted, taken from the
	 * dev database. A double holds it exactly — and then prints itself as the shortest decimal
	 * that identifies it, which is the second number. That is what a page sends after
	 * {@code Number()} has touched it, and what the server then cannot find.
	 */
	private static final long REAL_KEY = 370666965485748224L;

	private static final long AS_A_BROWSER_PRINTS_IT = 370666965485748200L;

	@Test
	void aStudentsKeyIsCarriedAsTextInBothDirections() throws Exception {

		postgres.asSuperuser("""
				INSERT INTO students (student_id, name, pronouns, email) VALUES
				    (370666965485748224, 'W BASU', 'he/him', 'w.basu@sies.edu.in');
				INSERT INTO student_year
				    (student_id, academic_year, roll_no, year, stream, major_dept, semester)
				VALUES (370666965485748224, 2025, 'CS-TY-042', 'TY', 'BSc_unaided',
				        'COMPUTER_SCIENCE', 5);
				""");

		String token = tokenFor("admin@sies.edu.in", "STAFF");

		Reply students = get("/accounts/people?kind=STUDENT", token, null);

		// Quoted, digit for digit. A JSON number here would be read by the only client this
		// endpoint has -- a page -- into a double, and printed back out as a different key.
		assertTrue(students.raw().contains("\"id\":\"" + REAL_KEY + "\""),
				"the picker has to send keys as text: " + students.raw());

		Reply created = post("/accounts", json(
				"kind", "STUDENT",
				"studentId", Long.toString(REAL_KEY),
				"displayName", "W BASU",
				"loginEmail", "w.basu@sies.edu.in",
				"password", "a long enough opening password"), token, null);

		assertEquals(200, created.status(), created::toString);
		assertEquals(Long.toString(REAL_KEY), created.body().get("studentId").stringValue(),
				"and it comes back as the key that went in");

		assertEquals(1, count("SELECT count(*) FROM app_account WHERE student_id = "
				+ REAL_KEY));
	}

	@Test
	void aKeyThatIsNotOnFileIsNotAServerFault() throws Exception {

		// Exactly what a browser sent before the page stopped putting keys through Number():
		// a student id one rounding away from a real one. The answer used to be 500
		// "Something went wrong at the server", which is the least useful true statement
		// available -- nothing had gone wrong at the server, and the person had chosen a real
		// name off a list.
		String token = tokenFor("admin@sies.edu.in", "STAFF");

		Reply refused = post("/accounts", json(
				"kind", "STUDENT",
				"studentId", Long.toString(AS_A_BROWSER_PRINTS_IT),
				"displayName", "W BASU",
				"loginEmail", "w.basu@sies.edu.in",
				"password", "a long enough opening password"), token, null);

		assertEquals(404, refused.status(), refused::toString);
		assertEquals("no_such_person", refused.body().get("code").stringValue());
		assertTrue(refused.body().get("message").stringValue().contains("roster CSV"),
				"and it says where students come from: " + refused.raw());

		// Nothing half-made, which is the other half of the old behaviour being wrong: the
		// insert had already taken an account id before the foreign key refused it.
		assertEquals(0, count("SELECT count(*) FROM app_account "
				+ "WHERE login_email = 'w.basu@sies.edu.in'"));
	}

	@Test
	void aProfessorWhoIsNotOnFileIsNamedAsTheProblem() throws Exception {

		String token = tokenFor("admin@sies.edu.in", "STAFF");

		Reply refused = post("/accounts", json(
				"kind", "PROFESSOR",
				"profId", "1999",
				"displayName", "X GHOST",
				"loginEmail", "x.ghost@sies.edu.in",
				"password", "a long enough opening password"), token, null);

		assertEquals(404, refused.status(), refused::toString);
		assertEquals("no_such_person", refused.body().get("code").stringValue());
	}

	@Test
	void somethingThatIsNotAKeyAtAllIsRefusedBeforeAnyDatabaseWork() throws Exception {

		String token = tokenFor("admin@sies.edu.in", "STAFF");

		Reply refused = post("/accounts", json(
				"kind", "STUDENT",
				"studentId", "undefined",
				"displayName", "Y NOBODY",
				"loginEmail", "y.nobody@sies.edu.in",
				"password", "a long enough opening password"), token, null);

		assertEquals(400, refused.status(), refused::toString);
		assertEquals("not_a_key", refused.body().get("code").stringValue());
	}

	// ── Creating the person with the account ─────────────────────────────

	@Test
	void aProfessorWhoIsNotOnTheServerYetIsCreatedWithTheirAccount() throws Exception {

		// The gap a fresh server has: nothing in this service could write a professors row, so
		// there were no professors, so there could be no professor accounts. The page could
		// only say "nobody on file yet".
		String token = tokenFor("admin@sies.edu.in", "STAFF");

		Reply created = post("/accounts", json(
				"kind", "PROFESSOR",
				"displayName", "N NEW",
				"loginEmail", "nnew@sies.edu.in",
				"password", "a long enough opening password",
				"person", Map.of(
						"name", "N NEW",
						"email", "nnew@sies.edu.in",
						"department", "PHYSICS",
						"designation", "Assistant Professor")), token, null);

		assertEquals(200, created.status(), created::toString);

		// Text on the wire, as every structured key is here: see AccountsController.Person.
		long profId = Long.parseLong(created.body().get("profId").stringValue());

		assertTrue(StructuredId.isStructured(profId),
				"a row the server invented has to be keyed the way the server keys rows");
		assertEquals(0, StructuredId.nodeOf(profId),
				"under node 0, which is what tells it apart from a phone's row for ever");

		assertEquals(1, count("SELECT count(*) FROM professors WHERE prof_id = " + profId
				+ " AND department = 'PHYSICS'"));
		assertEquals(1, count("SELECT count(*) FROM app_account WHERE prof_id = " + profId));

		// Both rows are in the audit log against the admin who made them.
		assertEquals(2, count("SELECT count(*) FROM audit_log WHERE action = 'INSERT' "
				+ "AND table_name IN ('professors', 'app_account') "
				+ "AND actor_account = " + accountAdmin));
	}

	@Test
	void aProfessorsRowIsNotLeftBehindByAnAccountThatFails() throws Exception {

		String token = tokenFor("admin@sies.edu.in", "STAFF");

		// The email is already a staff account's, so the account insert is refused after the
		// professors row has been written. One transaction, so neither survives.
		Reply refused = post("/accounts", json(
				"kind", "PROFESSOR",
				"displayName", "O CLASH",
				"loginEmail", "admin@sies.edu.in",
				"password", "a long enough opening password",
				"person", Map.of(
						"name", "O CLASH",
						"email", "oclash@sies.edu.in",
						"department", "PHYSICS",
						"designation", "Professor")), token, null);

		assertEquals(409, refused.status(), refused::toString);
		assertEquals(0, count("SELECT count(*) FROM professors "
						+ "WHERE email = 'oclash@sies.edu.in'"),
				"a name in the staff list that nobody asked for and nothing points at");
	}

	@Test
	void aStudentIsNotCreatableThisWayAndTheAnswerSaysWhere() throws Exception {

		String token = tokenFor("admin@sies.edu.in", "STAFF");

		Reply refused = post("/accounts", json(
				"kind", "STUDENT",
				"displayName", "P PUPIL",
				"loginEmail", "ppupil@sies.edu.in",
				"password", "a long enough opening password",
				"person", Map.of(
						"name", "P PUPIL",
						"email", "ppupil@sies.edu.in",
						"department", "PHYSICS",
						"designation", "n/a")), token, null);

		assertEquals(400, refused.status(), refused::toString);
		assertEquals("only_professors", refused.body().get("code").stringValue());

		Reply noStudent = post("/accounts", json(
				"kind", "STUDENT",
				"displayName", "P PUPIL",
				"loginEmail", "ppupil@sies.edu.in",
				"password", "a long enough opening password"), token, null);

		assertEquals(400, noStudent.status(), noStudent::toString);
		assertTrue(noStudent.body().get("message").stringValue().contains("roster CSV"),
				"an error that does not say where to go instead is half an error: "
						+ noStudent.body().get("message"));
	}

	// ── Reading the data ─────────────────────────────────────────────────

	/*
	 * The browser endpoint has no authority rules of its own, which is the whole design: every
	 * row comes back from a query under the caller's own actor, so these tests are really about
	 * whether the policies reach a generic reader the same way they reach a purpose-built one.
	 */

	@Test
	void theTableListCountsOnlyWhatTheCallerMaySee() throws Exception {

		Reply asAdmin = get("/data", tokenFor("admin@sies.edu.in", "STAFF"), null);

		assertEquals(200, asAdmin.status(), asAdmin::toString);
		assertEquals(11, asAdmin.body().size(), "every table a device holds");

		Map<String, Long> admin = new LinkedHashMap<>();

		for (JsonNode table : asAdmin.body()) {
			admin.put(table.get("table").stringValue(), table.get("rows").longValue());
		}

		assertEquals(2L, admin.get("students"));
		assertEquals(2L, admin.get("lectures"));

		// The same question, asked by a professor, is a different answer and both are right.
		Reply asProfessor = get("/data", tokenFor("a.cs@sies.edu.in", "STAFF"), null);
		Map<String, Long> professor = new LinkedHashMap<>();

		for (JsonNode table : asProfessor.body()) {
			professor.put(table.get("table").stringValue(), table.get("rows").longValue());
		}

		assertEquals(1L, professor.get("students"), "the one student on their own roll");
		assertEquals(1L, professor.get("lectures"), "their own lecture");
		assertEquals(1L, professor.get("courses"));
	}

	@Test
	void aProfessorBrowsingStudentsSeesTheirOwnRollAndNoOther() throws Exception {

		Reply rows = get("/data/students", tokenFor("a.cs@sies.edu.in", "STAFF"), null);

		assertEquals(200, rows.status(), rows::toString);
		assertEquals(1, rows.body().get("rows").size(), rows::toString);

		assertTrue(rows.raw().contains("R RAO"));
		assertFalse(rows.raw().contains("S SHAH"),
				"a Chemistry major is on nobody's Computer Science roll");

		// Rendered as text, which is what a reader wants: the columns are the table's own, in
		// the table's own order, provenance included.
		assertTrue(rows.body().get("columns").toString().contains("created_at"),
				rows.body().get("columns").toString());
	}

	@Test
	void aTableWithNoPoliciesIsNotBrowsable() throws Exception {

		String token = tokenFor("admin@sies.edu.in", "STAFF");

		// app_account has no policies -- V6 says why -- so a generic reader over it would be a
		// data leak with a user interface. It is not on the list, so it is not reachable.
		Reply refused = get("/data/app_account", token, null);

		assertEquals(404, refused.status(), refused::toString);
		assertEquals("no_such_table", refused.body().get("code").stringValue());

		assertEquals(404, get("/data/audit_log", token, null).status(),
				"and the log the service cannot even read");
		assertEquals(404, get("/data/pg_user", token, null).status());
	}

	@Test
	void pagingWalksEveryRowOnceAndThenStops() throws Exception {

		String token = tokenFor("admin@sies.edu.in", "STAFF");

		// One row at a time, which is the setting most likely to expose an off-by-one in the
		// cursor: the first version of this endpoint took the cursor from the row it did not
		// send, which would have skipped one row per page.
		List<String> seen = new ArrayList<>();
		String after = null;

		for (int page = 0; page < 10; page++) {

			Reply rows = get("/data/students?limit=1"
					+ (after == null ? "" : "&after="
							+ java.net.URLEncoder.encode(after, StandardCharsets.UTF_8)),
					token, null);

			assertEquals(200, rows.status(), rows::toString);

			for (JsonNode row : rows.body().get("rows")) {
				seen.add(row.get(0).stringValue());
			}

			if (rows.body().get("next").isNull()) {
				break;
			}

			after = rows.body().get("next").stringValue();
		}

		assertEquals(2, seen.size(), "both students, once each: " + seen);
		assertEquals(seen.size(), new java.util.LinkedHashSet<>(seen).size(), seen.toString());
	}

	@Test
	void aCursorThisEndpointDidNotIssueIsRefused() throws Exception {

		String token = tokenFor("admin@sies.edu.in", "STAFF");

		assertEquals(400, get("/data/students?after=yesterday", token, null).status());
		assertEquals(400, get("/data/attendance?after=1", token, null).status(),
				"attendance is keyed on two columns, so a one-part cursor is not one of ours");
	}

	@Test
	void thereIsNoBrowsingWithoutASession() throws Exception {

		Reply refused = get("/data", null, null);

		assertEquals(401, refused.status(), refused::toString);
		assertEquals("unauthenticated", refused.body().get("code").stringValue());
	}

	@Test
	void theDataPageIsServedWithoutAToken() throws Exception {

		Reply page = send(HttpRequest.newBuilder(
				URI.create("http://localhost:" + port + "/data.html")).GET(), null, null);

		assertEquals(200, page.status());
		assertTrue(page.raw().contains("SIES Attendance"));
		assertFalse(page.raw().contains("localStorage.setItem")
				|| page.raw().contains("document.cookie"));
	}

	// ── The shape of the thing ───────────────────────────────────────────

	@Test
	void theHealthProbeNeedsNoToken() throws Exception {

		HttpResponse<String> response = HTTP.send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port
						+ "/actuator/health")).GET().build(),
				HttpResponse.BodyHandlers.ofString());

		assertEquals(200, response.statusCode(),
				"a health probe has to answer before anybody has signed in");
		assertTrue(response.body().contains("UP"), response.body());
	}

	@Test
	void everyEndpointLivesUnderTheVersionedPath() throws Exception {

		// An old build meeting a new enum is supposed to fail loudly rather than store a value
		// it cannot read back, and that only works if the server can tell which client it is
		// talking to. Unversioned paths are the ones that end up installed on every phone.
		assertEquals(404, get("/export?year=" + SESSION, tokenFor("office@sies.edu.in",
				"STAFF"), null, "/api/export").status());

		assertTrue(Api.V1.startsWith("/api/v"), Api.V1);
	}

	@Test
	void nothingButActorSessionSetsTheActor() throws Exception {

		// The gate's wording, as a test. An endpoint that announced an actor of its own making
		// -- or a service that set one from anything but a verified token -- would be the one
		// way round everything V6 and V7 enforce, and it would not look wrong in review.
		List<String> offenders = new ArrayList<>();

		try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {

			for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {

				// Comments stripped first. Three files explain why the actor is
				// transaction-scoped and what set_config does, and a test that counted
				// explanations would be a test that discouraged writing them.
				String source = withoutComments(
						Files.readString(file, StandardCharsets.UTF_8));

				if (source.contains("set_config")
						&& !file.getFileName().toString().equals("ActorSession.java")) {
					offenders.add(file.toString());
				}
			}
		}

		assertEquals(List.of(), offenders,
				"only ActorSession may set the actor settings; everything else goes through it");
	}

	/**
	 * Java source with its comments removed, crudely but adequately: block comments first, then
	 * line comments. It does not understand a {@code //} inside a string literal, which is
	 * acceptable because the only thing it is used to look for is a SQL function name.
	 */
	private static String withoutComments(String source) {
		return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
	}

	// ── Fixture helpers ──────────────────────────────────────────────────

	private long account(AccountKind kind, Long prof, Long student, String email)
			throws SQLException {

		try (Connection connection = postgres.connection()) {

			long id = AccountDirectory.create(connection, kind, prof, student,
					email.substring(0, email.indexOf('@')), email, PASSWORD.toCharArray(),
					null);

			// Every account is created needing a rotation, which is right and would make every
			// test below a two-step. One account is left as created, for the test that is
			// about exactly that.
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

	// ── HTTP ─────────────────────────────────────────────────────────────

	/** A response, with its body already parsed, because every endpoint here answers in JSON. */
	private record Reply(int status, JsonNode body, String raw) {

		@Override
		public String toString() {
			return status + " " + raw;
		}
	}

	private Reply login(String email, String door, String password, UUID install)
			throws Exception {

		String body = install == null
				? json("email", email, "door", door, "password", password)
				: json("email", email, "door", door, "password", password,
						"installId", install.toString());

		return post("/auth/login", body, null, null);
	}

	/** Signs in and returns the token, for the tests that are about what happens next. */
	private String tokenFor(String email, String door) throws Exception {

		Reply reply = login(email, door, PASSWORD, null);

		assertEquals(200, reply.status(), reply::toString);
		return reply.body().get("token").stringValue();
	}

	private Reply register(String token, UUID header, UUID install, Integer provisional,
	                       String label) throws Exception {

		return post("/devices", json("installId", install.toString(),
				"provisionalNode", provisional, "label", label), token, header);
	}

	private Reply get(String path, String token, UUID install) throws Exception {
		return get(path, token, install, Api.V1);
	}

	private Reply get(String path, String token, UUID install, String prefix) throws Exception {

		HttpRequest.Builder request = HttpRequest
				.newBuilder(URI.create("http://localhost:" + port + prefix + path))
				.GET();

		return send(request, token, install);
	}

	private Reply post(String path, String body, String token, UUID install) throws Exception {

		HttpRequest.Builder request = HttpRequest
				.newBuilder(URI.create("http://localhost:" + port + Api.V1 + path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

		return send(request, token, install);
	}

	private Reply postCsv(String path, String csv, String token) throws Exception {

		HttpRequest.Builder request = HttpRequest
				.newBuilder(URI.create("http://localhost:" + port + Api.V1 + path))
				.header("Content-Type", "text/csv")
				.POST(HttpRequest.BodyPublishers.ofString(csv, StandardCharsets.UTF_8));

		return send(request, token, null);
	}

	private static Reply send(HttpRequest.Builder request, String token, UUID install)
			throws IOException, InterruptedException {

		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}

		if (install != null) {
			request.header(Api.DEVICE_HEADER, install.toString());
		}

		// Sent on every request, because a real client would, and because it is one of the
		// things an audit entry is supposed to carry.
		request.header(Api.VERSION_HEADER, "test-harness/1.0");

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

	// ── Reading the database back ────────────────────────────────────────

	private long count(String sql) throws SQLException {

		try (Connection connection = postgres.connection();
		     Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(sql)) {

			assertTrue(results.next(), () -> "no row from: " + sql);
			return results.getLong(1);
		}
	}

	/** One row as a map of text, which is enough for every assertion about an audit entry. */
	private Map<String, String> row(String sql) throws SQLException {

		try (Connection connection = postgres.connection();
		     Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(sql)) {

			if (!results.next()) {
				return null;
			}

			Map<String, String> row = new LinkedHashMap<>();

			for (int column = 1; column <= results.getMetaData().getColumnCount(); column++) {
				row.put(results.getMetaData().getColumnLabel(column), results.getString(column));
			}
			return row;
		}
	}
}
