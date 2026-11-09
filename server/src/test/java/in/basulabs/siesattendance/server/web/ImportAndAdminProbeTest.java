package in.basulabs.siesattendance.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import in.basulabs.siesattendance.backend.AccountKind;
import in.basulabs.siesattendance.server.SiesAttendanceServer;
import in.basulabs.siesattendance.server.security.AccountDirectory;
import in.basulabs.siesattendance.server.testing.TestPostgres;

/**
 * White-box probes, second round, of the paths {@code WireTest} reaches only on their happy
 * side: the roster and attendance importers' matching, the password re-check, and the V43
 * guard that there is always an administrator.
 * <p>
 * The whole service over real HTTP against a real PostgreSQL as {@code sies_app}, built the way
 * {@code WireTest} builds it, so that every policy applies. Skipped without Docker.
 * <p>
 * The tests marked BUG fail as of this commit.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImportAndAdminProbeTest {

	private static final int SESSION = 2025;

	private static final long PROF_CS = 1001;
	private static final long PROF_CHEM = 1002;

	private static final String PASSWORD = "correct horse battery staple";

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	private static TestPostgres postgres;

	private static ConfigurableApplicationContext service;

	private static int port;

	private long accountAdmin;
	private long accountSecondAdmin;

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
				    (1002, 'B CHEM', 'b.chem@sies.edu.in', 'CHEMISTRY',        'Assistant Professor');

				INSERT INTO students (student_id, name, pronouns, email, apaar_id) VALUES
				    (2001, 'R RAO',  'she/her',   'r.rao@sies.edu.in',  999000111222),
				    (2002, 'S SHAH', 'they/them', 's.shah@sies.edu.in', NULL);

				-- R Rao reads Computer Science with a Chemistry minor, which V30 lets the
				-- Chemistry professor see.
				INSERT INTO student_year
				    (student_id, academic_year, roll_no, year, stream, major_dept, minor_dept,
				     semester) VALUES
				    (2001, 2025, 'CS-FY-001', 'FY', 'BSc_unaided', 'COMPUTER_SCIENCE', 'CHEMISTRY', 1),
				    (2002, 2025, 'CS-FY-002', 'FY', 'BSc_unaided', 'COMPUTER_SCIENCE', NULL,        1);

				INSERT INTO courses
				    (course_id, prof_id, course_code, course_topic, stream,
				     offering_department, year, semester, course_type) VALUES
				    (3001, 1001, 'CS-101', 'Programming', 'BSc_unaided',
				     'COMPUTER_SCIENCE', 'FY', 1, 'MAJOR');

				INSERT INTO course_instructor (course_id, prof_id) VALUES (3001, 1001)
				    ON CONFLICT DO NOTHING;

				INSERT INTO batch_desc (batch_id, batch_name, academic_year, department) VALUES
				    (5901, 'FY CS', 2025, 'COMPUTER_SCIENCE');

				INSERT INTO batch_student_map (batch_id, student_id, academic_year) VALUES
				    (5901, 2001, 2025), (5901, 2002, 2025);

				INSERT INTO course_batch_map (course_id, batch_id) VALUES (3001, 5901);
				""");

		account(AccountKind.PROFESSOR, PROF_CS, "a.cs@sies.edu.in");
		account(AccountKind.PROFESSOR, PROF_CHEM, "b.chem@sies.edu.in");
		long office = account(AccountKind.STAFF, null, "office@sies.edu.in");
		accountAdmin = account(AccountKind.STAFF, null, "admin@sies.edu.in");
		accountSecondAdmin = account(AccountKind.STAFF, null, "admin2@sies.edu.in");

		grantRole(accountAdmin, "ADMIN", accountAdmin);
		grantRole(accountSecondAdmin, "ADMIN", accountAdmin);
		grantRole(office, "OFFICE", accountAdmin);
	}

	// ── Roster: roll numbers ─────────────────────────────────────────────

	/**
	 * Was a bug: the roster matched a roll number exactly, and {@code student_year_roll_no_
	 * academic_year_uq} is case-sensitive, so the same roll number typed in lower case was a
	 * second student in the same session. Fixed by storing every roll number canonically
	 * ({@code CanonicalCode}, V57): {@code cs-fy-002} is {@code CSFY002}, the student on file.
	 */
	@Test
	void aRollNumberInAnotherCaseIsTheSameStudent() throws Exception {

		Reply reply = postCsv("/roster?year=" + SESSION, """
				rollNo,name,pronouns,year,stream,majorDept,semester
				cs-fy-002,S Shah,they/them,FY,BSc_unaided,COMPUTER_SCIENCE,1
				""", tokenFor("office@sies.edu.in"));

		assertEquals(200, reply.status(), reply::toString);
		assertEquals(1, reply.body().get("updated").intValue(), reply::toString);
		assertEquals(1, count("SELECT count(*) FROM student_year WHERE academic_year = "
						+ SESSION + " AND roll_no = 'CSFY002'"),
				"one student per roll number per session, whatever its case: " + reply);
	}

	// ── Roster: what "updated" means ─────────────────────────────────────

	/**
	 * BUG: the Chemistry professor can see R Rao (a CS major with a Chemistry minor, V30), so
	 * the APAAR ID finds her. {@code UPDATE students} is then filtered to nothing by
	 * {@code students_professor_update}, without an error, and the reply still counts the row
	 * as updated, though her name did not change.
	 * <p>
	 * The enrolment half does go through: she is now on Chemistry's 2026 roll as a major,
	 * filed by a professor of a department she does not major in.
	 */
	@Test
	void aRowCountedAsUpdatedHasChangedTheStudent() throws Exception {

		Reply reply = postCsv("/roster?year=" + (SESSION + 1), """
				rollNo,name,pronouns,year,stream,majorDept,semester,apaarId
				CH-SY-050,Renamed By Chemistry,she/her,SY,BSc,CHEMISTRY,3,999000111222
				""", tokenFor("b.chem@sies.edu.in"));

		assertEquals(200, reply.status(), reply::toString);

		int updated = reply.body().get("updated").intValue();
		String name = text("SELECT name FROM students WHERE student_id = 2001");

		assertTrue(updated == 0 || name.equals("Renamed By Chemistry"),
				"the reply counted " + updated + " update(s), but the name is still '" + name
						+ "'; enrolments now: " + list("SELECT academic_year || ' '"
						+ " || major_dept FROM student_year WHERE student_id = 2001"
						+ " ORDER BY academic_year"));
	}

	/**
	 * The same professor, the same student, this session: the roll number finds her, the
	 * enrolment is not theirs to change, and the row is refused rather than half-done.
	 */
	@Test
	void anotherDepartmentsEnrolmentIsRefusedNotRewritten() throws Exception {

		Reply reply = postCsv("/roster?year=" + SESSION, """
				rollNo,name,pronouns,year,stream,majorDept,semester
				CS-FY-001,Renamed By Chemistry,she/her,FY,BSc,CHEMISTRY,1
				""", tokenFor("b.chem@sies.edu.in"));

		assertEquals(200, reply.status(), reply::toString);
		assertEquals(1, reply.body().get("failed").intValue(), reply::toString);
		assertEquals("COMPUTER_SCIENCE", text("SELECT major_dept FROM student_year "
				+ "WHERE student_id = 2001 AND academic_year = " + SESSION));
		assertEquals("R RAO", text("SELECT name FROM students WHERE student_id = 2001"));

		// And the reason is said in a sentence, not as a policy name.
		String error = reply.body().get("errors").get(0).stringValue();
		assertFalse(error.contains("row-level security"),
				"a policy name is not a reason a professor can act on: " + error);
	}

	// ── Attendance import: one student under two spellings ───────────────

	/**
	 * Was a bug: CS-FY-002 and CS/FY/002 passed the parser's duplicate check (it kept slashes)
	 * and were one student to the importer (it stripped them), so the preview offered three
	 * marks for two students and the later line silently won. Both now compare
	 * {@code CanonicalCode} spellings, so the sheet is refused for naming one student twice.
	 */
	@Test
	void oneStudentUnderTwoSpellingsIsNotImportedQuietly() throws Exception {

		Reply preview = postCsv("/attendance/import?dryRun=true", """
				Course code,CS-101
				Date,21/07/2025
				Start time,09:00
				End time,10:00
				CS-FY-001,P
				CS-FY-002,P
				CS/FY/002,AB
				""", tokenFor("a.cs@sies.edu.in"));

		assertEquals(200, preview.status(), preview::toString);
		assertFalse(preview.body().get("canImport").booleanValue(), preview::toString);
		assertTrue(preview.raw().contains("CSFY002 appears on lines 6 and 7"),
				preview::toString);
	}

	// ── The password re-check ────────────────────────────────────────────

	/**
	 * BUG: {@code /auth/login} has a per-address limiter, a lockout after five failures, and a
	 * LOGIN_FAILED entry. {@code /auth/password} checks the current password with none of the
	 * three, so a session token is an unlimited, unlogged oracle for the account's password,
	 * and stays one while the account is locked.
	 */
	@Test
	void guessingTheCurrentPasswordIsLimitedOrRecorded() throws Exception {

		String token = tokenFor("a.cs@sies.edu.in");
		List<Integer> statuses = new ArrayList<>();

		for (int attempt = 0; attempt < 8; attempt++) {
			statuses.add(post("/auth/password", json(
					"currentPassword", "guess number " + attempt,
					"newPassword", "a brand new passphrase " + attempt), token).status());
		}

		boolean limited = statuses.contains(429);
		boolean locked = count("SELECT count(*) FROM app_account WHERE login_email = "
				+ "'a.cs@sies.edu.in' AND (locked_until IS NOT NULL OR failed_logins > 0)") > 0;
		boolean recorded = count("SELECT count(*) FROM audit_log WHERE action IN "
				+ "('LOGIN_FAILED', 'ACCOUNT_LOCKED')") > 0;

		assertTrue(limited || locked || recorded,
				"eight wrong current passwords went unlimited, uncounted and unlogged: "
						+ statuses);
	}

	// ── V43: there is always an administrator ────────────────────────────

	/** The control: one administrator may not disable the other when that leaves none. */
	@Test
	void theLastAdministratorCannotBeDisabled() throws Exception {

		postgres.asSuperuser("UPDATE app_account SET disabled_at = now() WHERE account_id = "
				+ accountSecondAdmin);

		SQLException refused = null;

		try {
			postgres.asSuperuser("UPDATE app_account SET disabled_at = now() "
					+ "WHERE account_id = " + accountAdmin);
		} catch (SQLException e) {
			refused = e;
		}

		assertTrue(refused != null && refused.getMessage().contains("no administrator"),
				"the trigger has to refuse the last one: " + refused);
	}

	/**
	 * BUG: V43's trigger checks with a plain {@code NOT EXISTS} after the row is written, under
	 * READ COMMITTED, and takes no lock. Two transactions disabling the two administrators each
	 * see the other's administrator still live, both pass, both commit, and there are none.
	 */
	@Test
	void twoAdministratorsCannotBeDisabledAtOnce() throws Exception {

		try (Connection first = postgres.connection();
		     Connection second = postgres.connection()) {

			first.setAutoCommit(false);
			second.setAutoCommit(false);

			try (Statement one = first.createStatement();
			     Statement two = second.createStatement()) {

				one.executeUpdate("UPDATE app_account SET disabled_at = now() "
						+ "WHERE account_id = " + accountAdmin);
				two.executeUpdate("UPDATE app_account SET disabled_at = now() "
						+ "WHERE account_id = " + accountSecondAdmin);
			} catch (SQLException refusedEarly) {
				// A trigger that serialises the two may refuse the second here; that is a
				// pass, and the commits below then decide nothing.
				first.rollback();
				second.rollback();
				return;
			}

			first.commit();

			try {
				second.commit();
			} catch (SQLException refusedAtCommit) {
				// Equally a pass.
			}
		}

		assertTrue(count("SELECT count(*) FROM user_role r JOIN app_account a USING "
						+ "(account_id) WHERE r.role = 'ADMIN' AND r.revoked_at IS NULL "
						+ "AND a.disabled_at IS NULL") >= 1,
				"two concurrent disables left no live administrator");
	}

	// ── Fixture helpers ──────────────────────────────────────────────────

	private long account(AccountKind kind, Long prof, String email) throws SQLException {

		try (Connection connection = postgres.connection()) {

			long id = AccountDirectory.create(connection, kind, prof, null,
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

	private void grantRole(long account, String role, long grantedBy) throws SQLException {

		try (Connection connection = postgres.connection();
		     PreparedStatement statement = connection.prepareStatement(
				     "INSERT INTO user_role (account_id, role, granted_by) "
						     + "VALUES (?, ?::app_role, ?)")) {

			statement.setLong(1, account);
			statement.setString(2, role);
			statement.setLong(3, grantedBy);
			statement.execute();
		}
	}

	// ── HTTP ─────────────────────────────────────────────────────────────

	private record Reply(int status, JsonNode body, String raw) {

		@Override
		public String toString() {
			return status + " " + raw;
		}
	}

	private String tokenFor(String email) throws Exception {

		Reply reply = post("/auth/login", json("email", email, "door", "STAFF",
				"password", PASSWORD), null);

		assertEquals(200, reply.status(), reply::toString);
		return reply.body().get("token").stringValue();
	}

	private Reply post(String path, String body, String token) throws Exception {

		return send(HttpRequest
				.newBuilder(URI.create("http://localhost:" + port + Api.V1 + path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)), token);
	}

	private Reply postCsv(String path, String csv, String token) throws Exception {

		return send(HttpRequest
				.newBuilder(URI.create("http://localhost:" + port + Api.V1 + path))
				.header("Content-Type", "text/csv")
				.POST(HttpRequest.BodyPublishers.ofString(csv, StandardCharsets.UTF_8)), token);
	}

	private static Reply send(HttpRequest.Builder request, String token)
			throws IOException, InterruptedException {

		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}

		request.header(Api.VERSION_HEADER, "probe-harness/1.0");

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

	private String text(String sql) throws SQLException {

		try (Connection connection = postgres.connection();
		     Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(sql)) {

			assertTrue(results.next(), () -> "no row from: " + sql);
			return results.getString(1);
		}
	}

	private List<String> list(String sql) throws SQLException {

		List<String> out = new ArrayList<>();

		try (Connection connection = postgres.connection();
		     Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(sql)) {

			while (results.next()) {
				out.add(results.getString(1));
			}
		}
		return out;
	}
}
