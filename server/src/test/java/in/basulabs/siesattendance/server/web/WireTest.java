package in.basulabs.siesattendance.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
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

	private static final long LECTURE_CS = 4001;

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

		// The phone reads this field and keeps it: a student account is not offered the
		// screens that change students, professors or courses. See the app's Permissions.
		// Renaming it here would quietly give a class representative the roster back.
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
	void theApaarIdFindsTheSamePersonInTheNextSession() throws Exception {

		String office = tokenFor("office@sies.edu.in", "STAFF");

		assertEquals(200, postCsv("/roster?year=" + SESSION, """
				rollNo,name,pronouns,email,year,stream,majorDept,semester,apaarId
				CS-FY-020,Meera Iyer,she/her,,FY,BSc_unaided,COMPUTER_SCIENCE,1,555000111222
				""", office).status());

		// A new session and a new roll number: only the APAAR ID says who this is.
		Reply next = postCsv("/roster?year=" + (SESSION + 1), """
				rollNo,name,pronouns,email,year,stream,majorDept,semester,apaarId
				CS-SY-020,Meera Iyer,she/her,,SY,BSc_unaided,COMPUTER_SCIENCE,3,555000111222
				""", office);

		assertEquals(200, next.status(), next::toString);
		assertEquals(1, next.body().get("updated").intValue(),
				"the same person, not a second record of them");

		assertEquals(1, count("SELECT count(*) FROM students WHERE apaar_id = 555000111222"));
		assertEquals(2, count("SELECT count(*) FROM student_year y JOIN students s "
				+ "USING (student_id) WHERE s.apaar_id = 555000111222"));

		// And a later roster with the cell blank keeps the ID rather than erasing it.
		postCsv("/roster?year=" + (SESSION + 1), """
				rollNo,name,pronouns,email,year,stream,majorDept,semester
				CS-SY-020,Meera Iyer,she/her,,SY,BSc_unaided,COMPUTER_SCIENCE,3
				""", office);

		assertEquals(1, count("SELECT count(*) FROM students WHERE apaar_id = 555000111222"));
	}

	@Test
	void aStudentWhoHasLeftIsOffTheRoll() throws Exception {

		String roll = "SELECT count(*) FROM roll_for_course(" + COURSE_CS + ", " + SESSION + ")";
		long before = count(roll);

		postCsv("/roster?year=" + SESSION, """
				rollNo,name,pronouns,email,year,stream,majorDept,semester,leaveDate
				CS-FY-001,R RAO,she/her,r.rao@sies.edu.in,FY,BSc_unaided,COMPUTER_SCIENCE,1,2025-10-01
				""", tokenFor("office@sies.edu.in", "STAFF"));

		assertEquals(before - 1, count(roll),
				"a student with a leave date comes off the roll a CR is appointed from");
	}

	@Test
	void aProfessorLoadsTheirOwnDepartmentsStudentsAndNoOthers() throws Exception {

		String professor = tokenFor("a.cs@sies.edu.in", "STAFF");

		var imports = get("/me", professor, null).body().get("imports");
		assertTrue(imports.get("students").booleanValue());
		assertFalse(imports.get("anyDepartment").booleanValue());
		assertEquals("COMPUTER_SCIENCE", imports.get("departments").get(0).stringValue());
		assertTrue(imports.get("attendance").booleanValue());

		assertTrue(get("/me", tokenFor("office@sies.edu.in", "STAFF"), null).body()
				.get("imports").get("anyDepartment").booleanValue(), "the office, anywhere");

		Reply reply = postCsv("/roster?year=" + SESSION, """
				rollNo,name,pronouns,email,year,stream,majorDept,semester
				CS-FY-030,New CS Student,they/them,,FY,BSc_unaided,COMPUTER_SCIENCE,1
				CH-FY-030,New Chemistry Student,they/them,,FY,BSc,CHEMISTRY,1
				""", professor);

		assertEquals(200, reply.status(), reply::toString);
		assertEquals(1, reply.body().get("created").intValue(), reply::toString);
		assertEquals(1, reply.body().get("failed").intValue());
		assertTrue(reply.raw().contains("Line 3: ") && reply.raw().contains("not a department"),
				"the refusal says which line and why: " + reply.raw());
	}

	@Test
	void anAttendanceSheetIsPreviewedAndThenImported() throws Exception {

		// A batch for the class, which every imported lecture is filed against.
		postgres.asSuperuser("""
				INSERT INTO batch_desc (batch_id, batch_name, academic_year)
				    VALUES (5101, 'CS-101 class', 2025);
				INSERT INTO batch_student_map (batch_id, student_id, academic_year)
				    VALUES (5101, 2001, 2025);
				""");

		String sheet = """
				Course code,CS-101
				Date,21/07/2025
				Start time,09:00
				End time,10:00
				CS-FY-001,P
				""";

		String professor = tokenFor("a.cs@sies.edu.in", "STAFF");
		long lectures = count("SELECT count(*) FROM lectures");

		Reply preview = postCsv("/attendance/import?dryRun=true", sheet, professor);

		assertEquals(200, preview.status(), preview::toString);
		assertTrue(preview.body().get("canImport").booleanValue(), preview::toString);
		assertEquals("CS-101 class",
				preview.body().get("lectures").get(0).get("batchName").stringValue());
		assertEquals(lectures, count("SELECT count(*) FROM lectures"), "a preview writes nothing");

		Reply imported = postCsv("/attendance/import?dryRun=false", sheet, professor);

		assertEquals(200, imported.status(), imported::toString);
		assertEquals(1, imported.body().get("written").get("created").intValue());
		assertEquals(lectures + 1, count("SELECT count(*) FROM lectures"));
		assertEquals(1, count("SELECT count(*) FROM attendance a JOIN lectures l "
				+ "USING (lecture_id) WHERE a.student_id = 2001 AND a.present "
				+ "AND (l.start_at AT TIME ZONE 'Asia/Kolkata')::date = '2025-07-21'"));

		// The same sheet from somebody in another department, who cannot see the course at
		// all -- so the preview says so, rather than confirming the course exists.
		Reply refused = postCsv("/attendance/import?dryRun=true", sheet,
				tokenFor("b.chem@sies.edu.in", "STAFF"));

		assertEquals(200, refused.status(), refused::toString);
		assertFalse(refused.body().get("canImport").booleanValue());
		assertTrue(refused.raw().contains("No course you can see has the code CS-101"),
				refused.raw());

		assertEquals(400, postCsv("/attendance/import?dryRun=false", sheet,
				tokenFor("b.chem@sies.edu.in", "STAFF")).status());
	}

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
	void aProfessorMayNotLoadAnotherDepartmentsRoster() throws Exception {

		// V27: a professor's own department, and no other. A Chemistry professor loading a
		// Computer Science roster has every row refused, with the reason.
		Reply refused = postCsv("/roster?year=" + SESSION, """
				rollNo,name,year,stream,majorDept
				CS-FY-010,New Student,FY,BSc_unaided,COMPUTER_SCIENCE
				""", tokenFor("b.chem@sies.edu.in", "STAFF"));

		assertEquals(200, refused.status(), refused::toString);
		assertEquals(0, refused.body().get("created").intValue());
		assertEquals(1, refused.body().get("failed").intValue());

		assertEquals(0, count("SELECT count(*) FROM student_year "
				+ "WHERE roll_no = 'CS-FY-010'"), "and nothing was written");

		// A student account never may, CR or not.
		Reply student = postCsv("/roster?year=" + SESSION, """
				rollNo,name,year,stream,majorDept
				CS-FY-011,Another,FY,BSc_unaided,COMPUTER_SCIENCE
				""", tokenFor("r.rao@sies.edu.in", "STUDENT"));

		assertEquals(403, student.status(), student::toString);
		assertEquals("not_allowed", student.body().get("code").stringValue());
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
	void aBackupExportedHereImportsBackUnchanged() throws Exception {

		String admin = tokenFor("admin@sies.edu.in", "STAFF");

		HttpResponse<byte[]> exported = exportBackup("{\"password\": \"pässwörd\"}", admin);

		assertEquals(200, exported.statusCode(), () -> new String(exported.body(),
				StandardCharsets.UTF_8));
		assertEquals("SIESBAK1", new String(exported.body(), 0, 8, StandardCharsets.US_ASCII));
		assertTrue(exported.headers().firstValue("Content-Disposition").orElse("")
				.contains(".siesbak"));
		assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'EXPORT' "
				+ "AND table_name = 'backup' AND (detail->>'encrypted')::boolean"));

		long attendance = count("SELECT count(*) FROM attendance");
		long students = count("SELECT count(*) FROM students");

		assertTrue(attendance > 0 && students > 0, "a round trip of nothing proves nothing");
		assertTrue(count("SELECT (detail->>'rows')::bigint FROM audit_log WHERE action = 'EXPORT' "
				+ "AND table_name = 'backup'") >= attendance + students);

		Reply wrong = importBackup(exported.body(), "wrong", false, admin);

		assertEquals(400, wrong.status(), wrong::toString);
		assertEquals("That is not the password for this backup.",
				wrong.body().get("message").stringValue());

		Reply preview = importBackup(exported.body(), "pässwörd", true, admin);

		assertEquals(200, preview.status(), preview::toString);
		assertTrue(preview.body().get("succeeded").booleanValue(), preview::toString);
		assertEquals(0, preview.body().get("inserted").intValue(),
				"everything in it came from here: " + preview);

		Reply imported = importBackup(exported.body(), "pässwörd", false, admin);

		assertEquals(200, imported.status(), imported::toString);
		assertTrue(imported.body().get("succeeded").booleanValue(), imported::toString);
		assertEquals(attendance, count("SELECT count(*) FROM attendance"));
		assertEquals(students, count("SELECT count(*) FROM students"));

		String professor = tokenFor("a.cs@sies.edu.in", "STAFF");

		assertEquals(403, exportBackup("{}", professor).statusCode(),
				"backups are an administrator's");
		assertEquals(403, importBackup(exported.body(), "pässwörd", true, professor).status());
	}

	@Test
	void aBackupBringsBackBatchesAProfessorMade() throws Exception {

		// Only professors write batches and their links, but an import runs as an
		// administrator. These two are what an import of a real phone first tripped over.
		postgres.asSuperuser("""
				INSERT INTO batch_desc (batch_id, batch_name, academic_year)
				    VALUES (5201, 'FY CS Full class', 2025);
				INSERT INTO course_batch_map (course_id, batch_id) VALUES (3001, 5201);
				""");

		String admin = tokenFor("admin@sies.edu.in", "STAFF");
		HttpResponse<byte[]> exported = exportBackup("{}", admin);

		assertEquals(200, exported.statusCode());

		postgres.asSuperuser("""
				DELETE FROM course_batch_map WHERE batch_id = 5201;
				DELETE FROM batch_desc WHERE batch_id = 5201;
				""");

		Reply imported = importBackup(exported.body(), "", false, admin);

		assertEquals(200, imported.status(), imported::toString);
		assertTrue(imported.body().get("succeeded").booleanValue(), imported::toString);
		// By name: 5201 reads as an old phone's key, so the batch comes back under a new one.
		assertEquals(1, count("SELECT count(*) FROM batch_desc "
				+ "WHERE batch_name = 'FY CS Full class' AND academic_year = 2025"));
		assertEquals(1, count("SELECT count(*) FROM course_batch_map m "
				+ "JOIN batch_desc b USING (batch_id) "
				+ "WHERE b.batch_name = 'FY CS Full class' AND m.course_id = 3001"));

		// And outside an import, an administrator still cannot make one.
		try (Connection connection = postgres.actingAs(accountAdmin, null, null)) {
			try (Statement statement = connection.createStatement()) {
				statement.execute("INSERT INTO batch_desc (batch_id, batch_name, academic_year) "
						+ "VALUES (5202, 'Not an import', 2025)");
				fail("an administrator wrote a batch outside an import");
			} catch (SQLException refused) {
				assertTrue(refused.getMessage().contains("row-level security"),
						refused.getMessage());
			} finally {
				connection.rollback();
			}
		}
	}

	private HttpResponse<byte[]> exportBackup(String body, String token) throws Exception {

		return HTTP.send(HttpRequest
						.newBuilder(URI.create("http://localhost:" + port + Api.V1
								+ "/backup/export"))
						.header("Content-Type", "application/json")
						.header("Authorization", "Bearer " + token)
						.POST(HttpRequest.BodyPublishers.ofString(body))
						.build(),
				HttpResponse.BodyHandlers.ofByteArray());
	}

	private Reply importBackup(byte[] file, String password, boolean dryRun, String token)
			throws Exception {

		HttpRequest.Builder request = HttpRequest
				.newBuilder(URI.create("http://localhost:" + port + Api.V1
						+ "/backup/import?label=round%20trip&node=7&dryRun=" + dryRun))
				.header("Content-Type", "application/octet-stream")
				.header(BackupController.PASSWORD_HEADER, java.util.Base64.getEncoder()
						.encodeToString(password.getBytes(StandardCharsets.UTF_8)))
				.POST(HttpRequest.BodyPublishers.ofByteArray(file));

		return send(request, token, null);
	}

	@Test
	void anAdministratorEndsSomebodysSessionsEverywhere() throws Exception {

		String admin = tokenFor("admin@sies.edu.in", "STAFF");
		String phone = tokenFor("b.chem@sies.edu.in", "STAFF");
		String laptop = tokenFor("b.chem@sies.edu.in", "STAFF");

		assertEquals(200, get("/me", phone, null).status());

		assertEquals(403, post("/accounts/" + accountProfChem + "/sessions/end", "{}",
						tokenFor("a.cs@sies.edu.in", "STAFF"), null).status(),
				"ending somebody's sessions is an administrator's");
		assertEquals(200, get("/me", phone, null).status());

		Reply ended = post("/accounts/" + accountProfChem + "/sessions/end", "{}", admin, null);

		assertEquals(200, ended.status(), ended::toString);
		assertEquals(2, ended.body().get("ended").intValue());

		assertEquals(401, get("/me", phone, null).status(), "at once, on the next request");
		assertEquals(401, get("/me", laptop, null).status());

		assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'SESSIONS_END' "
				+ "AND (detail->>'account_id')::bigint = " + accountProfChem
				+ " AND (detail->>'ended')::int = 2"));

		// The account is untouched: its owner signs straight back in.
		assertEquals(200, get("/me", tokenFor("b.chem@sies.edu.in", "STAFF"), null).status());

		// Their own sessions, from this page, leave the page signed in.
		post("/accounts/" + accountAdmin + "/sessions/end", "{}", admin, null);
		assertEquals(200, get("/me", admin, null).status());

		assertEquals(404, post("/accounts/999999/sessions/end", "{}", admin, null).status());
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
	void aNewAccountIsToldApartFromOneWhoseRolesWereRevoked() throws Exception {

		String token = tokenFor("admin@sies.edu.in", "STAFF");

		Reply created = post("/accounts", json(
				"kind", "STAFF",
				"displayName", "G NEWCOMER",
				"loginEmail", "newcomer@sies.edu.in",
				"password", "a long enough opening password"), token, null);

		assertEquals(200, created.status(), created::toString);
		assertFalse(created.body().get("everHeld").booleanValue());

		long newcomer = created.body().get("accountId").longValue();

		long grantId = Long.parseLong(row("SELECT user_role_id FROM user_role WHERE account_id = "
				+ accountOffice + " AND role = 'OFFICE'").get("user_role_id"));
		assertEquals(200, post("/accounts/" + accountOffice + "/roles/" + grantId + "/revoke",
				json("reason", "left"), token, null).status());

		Map<Long, JsonNode> listed = new LinkedHashMap<>();
		for (JsonNode account : get("/accounts", token, null).body()) {
			listed.put(account.get("accountId").longValue(), account);
		}

		// Nothing held either way; the console lists the first and not the second.
		assertEquals(0, listed.get(newcomer).get("roles").size());
		assertFalse(listed.get(newcomer).get("everHeld").booleanValue());

		assertEquals(0, listed.get(accountOffice).get("roles").size());
		assertTrue(listed.get(accountOffice).get("everHeld").booleanValue());

		assertTrue(listed.get(accountAdmin).get("everHeld").booleanValue());
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
				options.body().get("departments").get(0).get("value").stringValue());
		assertEquals(Department.values()[0].toString(),
				options.body().get("departments").get(0).get("label").stringValue(),
				"stored name and label, because a form needs both");

		assertEquals(2, options.body().get("sessions").size(), "the fixture has two");
		assertEquals(4, options.body().get("roles").size());
	}

	@Test
	void thePeoplePickerOffersProfessorsAndStudentsAndNotStaff() throws Exception {

		String token = tokenFor("admin@sies.edu.in", "STAFF");

		Reply professors = get("/accounts/people?kind=PROFESSOR", token, null);

		assertEquals(200, professors.status(), professors::toString);
		assertEquals(3, professors.body().size(), professors::toString);

		// The department a person reads, not the one the column holds.
		assertTrue(professors.raw().contains("CS - Assistant Professor"), professors.raw());
		assertFalse(professors.raw().contains("COMPUTER_SCIENCE"), professors.raw());

		Reply students = get("/accounts/people?kind=STUDENT", token, null);

		assertEquals(200, students.status(), students::toString);
		assertEquals(2, students.body().size(), students::toString);
		assertTrue(students.raw().contains("CS-FY-001"),
				"the roll number is how a person is told from another of the same name");

		assertEquals(400, get("/accounts/people?kind=STAFF", token, null).status(),
				"a staff account is attached to nobody, so there is no list");
	}

	@Test
	void theConsoleIsServedWithoutAToken() throws Exception {

		// The filter guards /api/* and nothing else, which is deliberate: the page is a
		// static file and holds no data. Everything it shows, it fetches with a token.
		Reply page = send(HttpRequest.newBuilder(
				URI.create("http://localhost:" + port + "/console.html")).GET(),
				null, null);

		assertEquals(200, page.status());
		assertTrue(page.raw().contains("SIES Attendance"), "the page itself");

		// Keeping a session is now a choice somebody makes, so the old assertion -- no
		// storage write anywhere -- would be wrong. The contract instead: exactly ONE place
		// writes a token, it is behind that checkbox, and it is never a cookie. A cookie
		// would be attached to every request by the browser whether this page meant it or
		// not, which is a different and worse property than a value it has to go and fetch.
		assertEquals(1, page.raw().split("localStorage.setItem", -1).length - 1,
				"one place persists the token, and it is keepToken");
		assertTrue(page.raw().contains("Keep me signed in"),
				"and the person is asked before it does");
		assertFalse(page.raw().contains("document.cookie"), "never a cookie");
		assertFalse(page.raw().contains("sessionStorage.setItem"),
				"one storage, one choice: two would be two lifetimes to reason about");

		// Signing out has to take the kept token with it, or "sign out" means "until the
		// next reload".
		assertTrue(page.raw().contains("keepToken(null)"), page.raw().length() + " bytes");
	}

	@Test
	void theConsoleIsRevalidatedRatherThanRemembered() throws Exception {

		// Spring sends Last-Modified and, by default, no Cache-Control for a classpath
		// resource -- and a browser given a Last-Modified with no freshness information
		// applies a heuristic and may reuse the page without asking. For a console that
		// changes while somebody is using it that is the difference between "the option is
		// not there" and "the option is not there yet", with nothing to say which.
		HttpResponse<String> response = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(
						URI.create("http://localhost:" + port + "/console.html")).GET().build(),
				HttpResponse.BodyHandlers.ofString());

		assertEquals(200, response.statusCode());
		assertEquals("no-cache",
				response.headers().firstValue("Cache-Control").orElse("absent"),
				"the page has to be revalidated on every load");
	}

	@Test
	void theOldTwoPagesPointAtTheConsole() throws Exception {

		// Both URLs are in the README, in bookmarks and in somebody's notes.
		for (String was : List.of("/accounts.html", "/data.html")) {

			Reply page = send(HttpRequest.newBuilder(
					URI.create("http://localhost:" + port + was)).GET(), null, null);

			assertEquals(200, page.status(), was);
			assertTrue(page.raw().contains("/console.html"), was + " has to lead there");
		}
	}

	@Test
	void aHeadMayBeAppointedWithNoSessionAtAll() throws Exception {

		// V18, from the caller's side: the session is optional and its absence means "until
		// revoked". The page's dropdown now has an empty option for it, which is also the
		// default -- a head standing in for one year is the exception, not the rule.
		String admin = tokenFor("admin@sies.edu.in", "STAFF");

		Reply open = post("/accounts/" + accountProfChem + "/roles", json(
				"role", "HOD",
				"department", "CHEMISTRY",
				"reason", "head of chemistry"), admin, null);

		assertEquals(200, open.status(), open::toString);
		assertTrue(open.body().get("academicYear").isNull(),
				"no session, which is the open-ended appointment");

		// And it answers for a session nobody named it for, which is the whole point.
		assertEquals(1, count("SELECT count(*) FROM user_role WHERE account_id = "
				+ accountProfChem + " AND role = 'HOD' AND academic_year IS NULL"));

		// The department is still required: a head of nothing is not a role.
		Reply vague = post("/accounts/" + accountProfCs + "/roles", json(
				"role", "HOD"), admin, null);

		assertEquals(400, vague.status(), vague::toString);
		assertEquals("incomplete_grant", vague.body().get("code").stringValue());
		assertTrue(vague.body().get("message").stringValue().contains("optional"),
				"and it says what leaving the session out means: " + vague.raw());
	}

	@Test
	void aRevokedRoleCanBeGrantedAgain() throws Exception {

		// V5's unique index was not partial, so a revoked grant kept its slot and
		// re-appointing last year's head came back as "duplicate key value violates unique
		// constraint user_role_scope_uq" -- with no way round it from the page at all. The
		// index's own comment said "a role is held once per scope"; what it enforced was "a
		// role is granted once per scope, for ever". V15 makes it partial.
		String admin = tokenFor("admin@sies.edu.in", "STAFF");

		long first = 0;

		for (var account : get("/accounts", admin, null).body()) {
			if (account.get("accountId").longValue() == accountHeadCs) {
				first = account.get("roles").get(0).get("userRoleId").longValue();
			}
		}

		assertEquals(200, post("/accounts/" + accountHeadCs + "/roles/" + first + "/revoke",
				json("reason", "end of term"), admin, null).status());

		Reply again = post("/accounts/" + accountHeadCs + "/roles", json(
				"role", "HOD",
				"department", "COMPUTER_SCIENCE",
				"academicYear", SESSION,
				"reason", "appointed again"), admin, null);

		assertEquals(200, again.status(), again::toString);

		// A new grant, with its own id: the revoke endpoint names one by id, so two
		// appointments have to be two rows. "Head from June, then again from January" is two
		// appointments, and one row with an overwritten granted_at would answer "who was
		// allowed to do this in July" with the wrong date.
		long second = again.body().get("userRoleId").longValue();

		assertTrue(second != first, "a re-grant is a new grant, not the old one revived");

		assertEquals(2, count("SELECT count(*) FROM user_role WHERE account_id = "
				+ accountHeadCs + " AND role = 'HOD'"));
		assertEquals(1, count("SELECT count(*) FROM user_role WHERE account_id = "
				+ accountHeadCs + " AND role = 'HOD' AND revoked_at IS NULL"));

		// And the rule that was wanted all along still holds: one LIVE grant per scope.
		Reply twice = post("/accounts/" + accountHeadCs + "/roles", json(
				"role", "HOD",
				"department", "COMPUTER_SCIENCE",
				"academicYear", SESSION), admin, null);

		assertEquals(409, twice.status(), twice::toString);
		assertEquals("rejected_by_database", twice.body().get("code").stringValue(),
				"granting a role somebody already holds is their mistake, not the server's");
	}

	// ── What the console is told to offer ───────────────────────────────

	@Test
	void anAdministratorIsOfferedTheAccountsAndAProfessorIsNot() throws Exception {

		Reply admin = get("/me", tokenFor("admin@sies.edu.in", "STAFF"), null);

		assertEquals(200, admin.status(), admin::toString);
		assertTrue(admin.body().get("mayManageAccounts").booleanValue());
		assertEquals("ADMIN", admin.body().get("roles").get(0).get("role").stringValue());

		// The whole point of the endpoint: the page asks once instead of showing a professor
		// an accounts list, a create form and a grant form that would all refuse them.
		Reply professor = get("/me", tokenFor("a.cs@sies.edu.in", "STAFF"), null);

		assertEquals(200, professor.status(), professor::toString);
		assertFalse(professor.body().get("mayManageAccounts").booleanValue(),
				"a professor is not offered the accounts panel at all");
		assertEquals("PROFESSOR", professor.body().get("kind").stringValue());
		assertEquals(0, professor.body().get("roles").size(), "and holds no role");

		// A head of department holds one, and still may not manage accounts.
		Reply head = get("/me", tokenFor("c.head@sies.edu.in", "STAFF"), null);

		assertFalse(head.body().get("mayManageAccounts").booleanValue());
		assertEquals("HOD", head.body().get("roles").get(0).get("role").stringValue());
		assertEquals("COMPUTER_SCIENCE",
				head.body().get("roles").get(0).get("department").stringValue());
	}

	@Test
	void aClassRepresentativeIsToldWhichCoursesTheyRepresent() throws Exception {

		Reply reply = get("/me", tokenFor("r.rao@sies.edu.in", "STUDENT"), null);

		assertEquals(200, reply.status(), reply::toString);
		assertEquals("STUDENT", reply.body().get("kind").stringValue());
		assertEquals("CS-101",
				reply.body().get("represents").get(0).get("courseCode").stringValue());
		assertFalse(reply.body().get("mayManageAccounts").booleanValue());
	}

	@Test
	void aProfessorReadsTheTrailOfTheirOwnTeachingAndNoMore() throws Exception {

		// V16 and V17 together: a professor asking for the trail used to get a 403, which
		// answered "not for you" to a question that is partly theirs -- who changed my
		// register. The entries they may read are the ones about their own lectures, marks
		// and courses, whoever made them, plus their own actions.
		String professor = tokenFor("a.cs@sies.edu.in", "STAFF");

		Reply mine = get("/audit?limit=200", professor, null);

		assertEquals(200, mine.status(), mine::toString);
		assertTrue(mine.body().get("entries").size() > 0,
				"the fixture's CS lecture and its marks are this professor's");

		// Not the other department's. CH-101's lecture belongs to b.chem, and its marks are
		// the one row the chemistry student attended.
		assertFalse(mine.raw().contains("\"CH-101\""), mine.raw());

		for (var entry : mine.body().get("entries")) {

			String table = entry.get("tableName").isNull()
					? "" : entry.get("tableName").stringValue();

			// Every row entry is about this professor's teaching; anything else here would
			// be an entry they were given because they asked nicely.
			if (table.equals("attendance") || table.equals("lectures")) {
				assertTrue(entry.get("rowKey").stringValue().contains(
								Long.toString(LECTURE_CS)),
						"an entry about somebody else's lecture: " + entry.toString());
			}
		}

		// And the administrative half of the trail is still not theirs: the fixture's role
		// grants were made by the admin, on accounts that are not this professor's.
		assertFalse(mine.raw().contains("ROLE_GRANT"),
				"a professor does not read the college's grants");
	}

	@Test
	void everyEntryInTheTrailIsSaidInWords() throws Exception {

		// The console shows these instead of an action, a table and a key. The names come
		// from lookups run as this professor, so the fixture's mark on their own lecture reads
		// with the student and the course named.
		Reply mine = get("/audit?limit=200", tokenFor("a.cs@sies.edu.in", "STAFF"), null);

		assertEquals(200, mine.status(), mine::toString);

		boolean sawTheMark = false;

		for (var entry : mine.body().get("entries")) {

			String summary = entry.get("summary").stringValue();
			assertFalse(summary.isBlank(), "an entry with no sentence: " + entry);

			sawTheMark |= summary.startsWith("Marked R RAO ") && summary.contains(" in CS-101 on ");
		}

		assertTrue(sawTheMark, "the fixture's mark, by name: " + mine.raw());
	}

	@Test
	void theDepartmentsHeadReadsTheDepartmentsTrail() throws Exception {

		String head = tokenFor("c.head@sies.edu.in", "STAFF");

		Reply theirs = get("/audit?limit=200", head, null);

		assertEquals(200, theirs.status(), theirs::toString);

		// C HEAD teaches nothing in the fixture, so everything they can read here they can
		// read as the head of Computer Science: the CS lecture and its marks.
		assertTrue(theirs.body().get("entries").size() > 0, theirs::toString);
		assertFalse(theirs.raw().contains("\"CH-101\""),
				"the head of Computer Science does not read Chemistry's");
	}

	// ── The audit trail, read ───────────────────────────────────────────

	@Test
	void anAdministratorReadsTheWholeTrailAndAProfessorASliceOfIt() throws Exception {

		// Something to read: a grant, which the trigger on user_role records.
		String admin = tokenFor("admin@sies.edu.in", "STAFF");

		// The office account, which has rotated its password. Not accountFresh: that one
		// exists to be a new account, so it is under must_rotate and may do exactly two
		// things -- which is a different test, and a correct refusal here.
		grantRole(accountOffice, "AUDITOR", null, null, accountAdmin);

		Reply page = get("/audit?limit=5", admin, null);

		assertEquals(200, page.status(), page::toString);
		assertTrue(page.body().get("entries").size() > 0, "the trail is not empty");
		assertTrue(Long.parseLong(page.body().get("total").stringValue()) > 0,
				"and it says how much there is, which a page of rows cannot");

		// Ids as text, like every key that a page can see.
		assertTrue(page.raw().contains("\"auditId\":\""), page.raw().substring(0, 200));

		// The one role this is really for.
		Reply auditor = get("/audit?limit=5", tokenFor("office@sies.edu.in", "STAFF"), null);

		assertEquals(200, auditor.status(), auditor::toString);

		// A professor is not refused any more -- V14 refused them, and V16 replaced that
		// with three widths, because "who changed my register" is partly their question. What
		// they get is their own slice, which is strictly smaller than the whole.
		Reply professor = get("/audit?limit=500", tokenFor("a.cs@sies.edu.in", "STAFF"), null);

		assertEquals(200, professor.status(), professor::toString);

		long whole = Long.parseLong(get("/audit?limit=1", admin, null)
				.body().get("total").stringValue());
		long slice = Long.parseLong(professor.body().get("total").stringValue());

		assertTrue(slice > 0, "a professor's own teaching is in there");
		assertTrue(slice < whole,
				"and the rest of the college is not: " + slice + " of " + whole);

		// The count is the count of what the asker may see, not of the table: a page saying
		// "3 of 42468" to somebody who can reach three would be worse than no count at all.
		assertEquals(professor.body().get("entries").size() <= slice, true);
	}

	@Test
	void theTrailPagesBackwardsWithoutRepeatingItself() throws Exception {

		String admin = tokenFor("admin@sies.edu.in", "STAFF");

		Reply first = get("/audit?limit=3", admin, null);

		assertEquals(3, first.body().get("entries").size(), first::toString);

		String at = first.body().get("nextAt").stringValue();
		String id = first.body().get("nextId").stringValue();

		Reply second = get("/audit?limit=3&beforeAt=" + at + "&beforeId=" + id, admin, null);

		assertEquals(200, second.status(), second::toString);

		// The cursor is (occurred_at, audit_id) compared row-wise, because
		// clock_timestamp() can repeat inside one statement -- an import writes thousands of
		// rows in one transaction. A cursor on the timestamp alone would skip or repeat
		// exactly those.
		for (var entry : second.body().get("entries")) {
			assertFalse(first.raw().contains("\"auditId\":\""
							+ entry.get("auditId").stringValue() + "\""),
					"the second page must not repeat the first");
		}
	}

	@Test
	void aBadCursorIsTheCallersMistakeAndSaysSo() throws Exception {

		Reply refused = get("/audit?beforeAt=yesterday&beforeId=1",
				tokenFor("admin@sies.edu.in", "STAFF"), null);

		assertEquals(400, refused.status(), refused::toString);
		assertEquals("bad_cursor", refused.body().get("code").stringValue());
	}

	@Test
	void aRevokedGrantLeavesTheListAndStaysInTheTrail() throws Exception {

		String admin = tokenFor("admin@sies.edu.in", "STAFF");

		// The fixture's head of Computer Science.
		Reply before = get("/accounts", admin, null);
		assertTrue(before.raw().contains("HOD"), "the grant is on the list to begin with");

		long grantId = 0;

		for (var account : before.body()) {
			if (account.get("accountId").longValue() == accountHeadCs) {
				grantId = account.get("roles").get(0).get("userRoleId").longValue();
			}
		}

		assertEquals(200, post("/accounts/" + accountHeadCs + "/roles/" + grantId + "/revoke",
				json("reason", "no longer head"), admin, null).status());

		Reply after = get("/accounts", admin, null);

		for (var account : after.body()) {
			if (account.get("accountId").longValue() == accountHeadCs) {
				assertEquals(0, account.get("roles").size(),
						"a revoked grant is not shown at all: what happened to it is the "
								+ "trail's to say, and a crossed-out tag said less");
			}
		}

		// Still on record, with both halves: the grant and its end.
		assertEquals(1, count("SELECT count(*) FROM user_role WHERE user_role_id = " + grantId
				+ " AND revoked_at IS NOT NULL"));

		assertTrue(get("/audit?limit=20", admin, null).raw().contains("user_role"),
				"and the revocation is in the trail");
	}

	// ── Class representatives ───────────────────────────────────────────

	/**
	 * The gap this closed: a student account with no delegation sees nothing of any course, so
	 * a first sync brought down the student's own enrolment and stopped. Nothing was wrong with
	 * the sync; a student's reach <em>is</em> its delegations, and nothing in the service could
	 * create one. This is that, end to end: the account cannot see the course, somebody
	 * appoints them, it can.
	 */
	@Test
	void appointingARepresentativeIsWhatMakesAStudentAccountUseful() throws Exception {

		// The gap this closed: a student account with no appointment sees nothing of any
		// course, so a first sync brought down its own enrolment and stopped. Nothing was
		// wrong with the sync; a student's reach IS its appointments.
		String admin = tokenFor("admin@sies.edu.in", "STAFF");

		Reply made = post("/accounts", json(
				"kind", "STUDENT",
				"studentId", Long.toString(STUDENT_OTHER),
				"displayName", "S SHAH",
				"loginEmail", "s.shah@sies.edu.in",
				"password", "an opening password here"), admin, null);

		assertEquals(200, made.status(), made::toString);

		String student = rotated("s.shah@sies.edu.in", "an opening password here");

		assertEquals(0, get("/data/courses", student, null).body().get("rows").size(),
				"a student with no appointment sees no course at all, which is the whole of "
						+ "the complaint this feature answers");

		Reply appointed = post("/courses/" + COURSE_CHEM + "/representatives", json(
				"studentId", Long.toString(STUDENT_OTHER),
				"academicYear", SESSION,
				"reason", "class representative"), admin, null);

		assertEquals(200, appointed.status(), appointed::toString);
		assertEquals("CH-101", appointed.body().get("courseCode").stringValue());
		assertEquals("ADMIN", appointed.body().get("grantedAs").stringValue(),
				"stamped with the capacity it was made in, not recomputed later");

		assertEquals(1, get("/data/courses", student, null).body().get("rows").size(),
				"the appointment is what the policies read");

		Reply ended = post("/courses/" + COURSE_CHEM + "/representatives/revoke", json(
				"studentId", Long.toString(STUDENT_OTHER),
				"academicYear", SESSION), admin, null);

		assertEquals(200, ended.status(), ended::toString);
		assertFalse(ended.body().get("live").booleanValue());

		assertEquals(0, get("/data/courses", student, null).body().get("rows").size(),
				"and the reach goes with it");
	}

	@Test
	void anAppointmentEndedElsewhereLeavesTheAccountsList() throws Exception {

		// Ended through the Representatives tab, which goes by the course rather than the
		// account. The Accounts tab lists the same appointment under the student, and the two
		// must not disagree about whether it is still in force.
		String admin = tokenFor("admin@sies.edu.in", "STAFF");

		assertEquals(1, appointmentsOf(admin, accountCr), "the fixture's CR, before");

		// One that ran out rather than being ended reaches nothing, so it is not listed either.
		postgres.asSuperuser("UPDATE attendance_delegate "
				+ "SET granted_at = now() - interval '2 days', "
				+ "    expires_at = now() - interval '1 day' "
				+ "WHERE student_id = " + STUDENT_CR);

		assertEquals(0, appointmentsOf(admin, accountCr),
				"an expired appointment is not listed as one in force");

		postgres.asSuperuser("UPDATE attendance_delegate SET expires_at = NULL "
				+ "WHERE student_id = " + STUDENT_CR);

		assertEquals(1, appointmentsOf(admin, accountCr), "and back in force");

		assertEquals(200, post("/courses/" + COURSE_CS + "/representatives/revoke", json(
				"studentId", Long.toString(STUDENT_CR),
				"academicYear", SESSION), admin, null).status());

		assertEquals(0, appointmentsOf(admin, accountCr),
				"an ended appointment is gone from under the account");
	}

	/** How many appointments the Accounts tab shows under an account. */
	private int appointmentsOf(String admin, long accountId) throws Exception {

		for (var account : get("/accounts", admin, null).body()) {
			if (account.get("accountId").longValue() == accountId) {
				return account.get("delegations").size();
			}
		}
		throw new AssertionError("account " + accountId + " is not on the list");
	}

	@Test
	void theCoursesOwnProfessorAppointsItsRepresentative() throws Exception {

		// Why this moved off the account: a professor could reach the old endpoint with curl
		// and never from the page, because the form needed a list of student accounts and
		// only an administrator may read those. The roll is the list, and the roll is theirs.
		String instructor = tokenFor("a.cs@sies.edu.in", "STAFF");

		Reply roll = get("/courses/" + COURSE_CS + "/roll?academicYear=" + SESSION,
				instructor, null);

		assertEquals(200, roll.status(), roll::toString);
		assertEquals(1, roll.body().size(), "the one Computer Science student");
		assertEquals("CS-FY-001", roll.body().get(0).get("rollNo").stringValue());
		assertTrue(roll.body().get(0).get("hasAccount").booleanValue(),
				"and the form says whether an appointment would be usable");
		assertTrue(roll.body().get(0).get("represents").booleanValue(),
				"the fixture already appointed this one");

		assertTrue(roll.raw().contains("\"studentId\":\"" + STUDENT_CR + "\""), roll.raw());

		assertEquals(200, post("/courses/" + COURSE_CS + "/representatives/revoke", json(
				"studentId", Long.toString(STUDENT_CR),
				"academicYear", SESSION), instructor, null).status());

		Reply again = post("/courses/" + COURSE_CS + "/representatives", json(
				"studentId", Long.toString(STUDENT_CR),
				"academicYear", SESSION), instructor, null);

		assertEquals(200, again.status(), again::toString);
		assertEquals("INSTRUCTOR", again.body().get("grantedAs").stringValue());

		// Another department's professor is told the rule rather than that the server broke.
		Reply refused = get("/courses/" + COURSE_CS + "/roll?academicYear=" + SESSION,
				tokenFor("b.chem@sies.edu.in", "STAFF"), null);

		assertEquals(403, refused.status(), refused::toString);
		assertEquals("not_your_course", refused.body().get("code").stringValue());
	}

	@Test
	void theHeadOfTheDepartmentMayAppointForItsCourses() throws Exception {

		String head = tokenFor("c.head@sies.edu.in", "STAFF");

		post("/courses/" + COURSE_CS + "/representatives/revoke", json(
				"studentId", Long.toString(STUDENT_CR),
				"academicYear", SESSION), head, null);

		Reply appointed = post("/courses/" + COURSE_CS + "/representatives", json(
				"studentId", Long.toString(STUDENT_CR),
				"academicYear", SESSION), head, null);

		assertEquals(200, appointed.status(), appointed::toString);
		assertEquals("HOD", appointed.body().get("grantedAs").stringValue(),
				"the capacity is the one actually held, and a head who is not the instructor "
						+ "holds this one");
	}

	@Test
	void anOpenEndedHeadCanAppointARepresentative() throws Exception {

		// The bug V19 fixes, end to end. V18 made the session optional on a HOD grant and
		// taught actor_is_hod_of() that a grant naming none answers for every session. It did
		// not teach attendance_delegate_authorised(), which asked the same question in its
		// own words -- "ur.academic_year = NEW.academic_year", and NULL = 2026 is unknown, so
		// the trigger refused. A head appointing a representative in their own department was
		// told, in effect, that they were not the head.
		String admin = tokenFor("admin@sies.edu.in", "STAFF");

		// The fixture's head holds a 2025-scoped grant; revoke it and appoint them properly.
		long scoped = 0;

		for (var account : get("/accounts", admin, null).body()) {
			if (account.get("accountId").longValue() == accountHeadCs) {
				scoped = account.get("roles").get(0).get("userRoleId").longValue();
			}
		}

		assertEquals(200, post("/accounts/" + accountHeadCs + "/roles/" + scoped + "/revoke",
				json("reason", "made open-ended"), admin, null).status());

		Reply open = post("/accounts/" + accountHeadCs + "/roles", json(
				"role", "HOD",
				"department", "COMPUTER_SCIENCE"), admin, null);

		assertEquals(200, open.status(), open::toString);
		assertTrue(open.body().get("academicYear").isNull(), "open-ended");

		// And now the appointment the trigger used to refuse.
		String head = tokenFor("c.head@sies.edu.in", "STAFF");

		post("/courses/" + COURSE_CS + "/representatives/revoke", json(
				"studentId", Long.toString(STUDENT_CR),
				"academicYear", SESSION), head, null);

		Reply appointed = post("/courses/" + COURSE_CS + "/representatives", json(
				"studentId", Long.toString(STUDENT_CR),
				"academicYear", SESSION), head, null);

		assertEquals(200, appointed.status(), appointed::toString);
		assertEquals("HOD", appointed.body().get("grantedAs").stringValue());
	}

	@Test
	void aHeadAppointedForOneYearCannotReachAnother() throws Exception {

		// The half of the old rule that still holds, and which the fix must not have widened:
		// a head appointed for 2025-26 alone may not designate for 2026-27.
		String admin = tokenFor("admin@sies.edu.in", "STAFF");

		// An enrolment in the other session, so the roll check cannot refuse first and mask
		// the authority check. In the course's own year, not the next one: roll_for_course
		// matches the enrolment's year against the course's, so a second-year enrolment is
		// not on a first-year course's roll and the test would have been asking the wrong
		// question with a right-looking answer.
		postgres.asSuperuser("""
				INSERT INTO student_year
				    (student_id, academic_year, roll_no, year, stream, major_dept, semester)
				VALUES (2001, 2026, 'CS-FY-002', 'FY', 'BSc_unaided', 'COMPUTER_SCIENCE', 1);
				""");

		Reply refused = post("/courses/" + COURSE_CS + "/representatives", json(
				"studentId", Long.toString(STUDENT_CR),
				"academicYear", SESSION + 1), tokenFor("c.head@sies.edu.in", "STAFF"), null);

		assertEquals(403, refused.status(), refused::toString);
		assertEquals("not_yours_to_grant", refused.body().get("code").stringValue());

		// The administrator may, because ADMIN is not scoped to a session at all.
		assertEquals(200, post("/courses/" + COURSE_CS + "/representatives", json(
				"studentId", Long.toString(STUDENT_CR),
				"academicYear", SESSION + 1), admin, null).status());
	}

	@Test
	void aRepresentativeHasToBeOnTheCoursesRoll() throws Exception {

		String admin = tokenFor("admin@sies.edu.in", "STAFF");

		Reply refused = post("/courses/" + COURSE_CHEM + "/representatives", json(
				"studentId", Long.toString(STUDENT_CR),
				"academicYear", SESSION), admin, null);

		assertEquals(409, refused.status(), refused::toString);
		assertEquals("not_on_the_roll", refused.body().get("code").stringValue());
		assertTrue(refused.body().get("message").stringValue().contains("enrolment"),
				"and it says where a roll comes from: " + refused.raw());
	}

	@Test
	void thePickerOffersTheCoursesAndSessionsTheCallerCanUse() throws Exception {

		Reply all = get("/accounts/courses", tokenFor("admin@sies.edu.in", "STAFF"), null);

		assertEquals(200, all.status(), all::toString);
		assertEquals(2, all.body().size(), "both courses, for an administrator");
		assertTrue(all.raw().contains("CS-101"), all.raw());
		assertTrue(all.raw().contains("\"value\":\"" + COURSE_CS + "\""), all.raw());

		// A professor sees the one they teach, because this reads under their own actor.
		Reply mine = get("/accounts/courses", tokenFor("b.chem@sies.edu.in", "STAFF"), null);

		assertEquals(1, mine.body().size(), mine::toString);
		assertTrue(mine.raw().contains("CH-101"), mine.raw());

		// And the sessions, which the representatives panel cannot work without and which
		// are nobody's secret.
		Reply sessions = get("/accounts/sessions",
				tokenFor("b.chem@sies.edu.in", "STAFF"), null);

		assertEquals(200, sessions.status(), sessions::toString);
		assertEquals(2, sessions.body().size(), "the fixture has two");
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

	/**
	 * A usable token for an account that was just created, which starts under
	 * {@code must_rotate} and may therefore do exactly two things.
	 * <p>
	 * The same token is returned rather than a second login's: {@code Sessions} reads
	 * {@code app_account.must_rotate} live on every lookup, so the token in hand starts
	 * working the moment the password changes. A test that signed in again would pass without
	 * proving that.
	 */
	private String rotated(String email, String opening) throws Exception {

		Reply signedIn = login(email, "STUDENT", opening, null);

		assertEquals(200, signedIn.status(), signedIn::toString);
		assertTrue(signedIn.body().get("mustRotate").booleanValue(),
				"a new account's password was typed by whoever created it");

		String token = signedIn.body().get("token").stringValue();

		Reply changed = post("/auth/password", json(
				"currentPassword", opening,
				"newPassword", "a password of their own choosing"), token, null);

		assertEquals(200, changed.status(), changed::toString);
		return token;
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
