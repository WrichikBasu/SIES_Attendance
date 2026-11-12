package in.basulabs.siesattendance.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import in.basulabs.siesattendance.backend.AccountKind;
import in.basulabs.siesattendance.server.SiesAttendanceServer;
import in.basulabs.siesattendance.server.security.AccountDirectory;
import in.basulabs.siesattendance.server.testing.TestPostgres;

/**
 * Probes for findings about the roster import and the request size that had only been read.
 * Each test states the behaviour that would be right, so a failure here is the finding
 * confirmed. Numbers are the review's.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImportReadingProbeTest {

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

	// ── R17 ──────────────────────────────────────────────────────────────

	@Test
	void r17_aNewRollNumberWithSomebodyElsesEmailDoesNotRewriteThatStudent() throws Exception {

		// A sibling who shares the family address, under a roll number nobody holds yet.
		Reply reply = postCsv("/roster?year=" + SESSION, """
				rollNo,name,pronouns,year,stream,majorDept,semester,email
				CS-FY-099,T Shah,she/her,FY,BSc_unaided,COMPUTER_SCIENCE,1,s.shah@sies.edu.in
				""", tokenFor("office@sies.edu.in"));

		assertEquals("S SHAH|CSFY002",
				text("SELECT s.name || '|' || y.roll_no FROM students s JOIN student_year y "
						+ "USING (student_id) WHERE s.student_id = 2002 "
						+ "AND y.academic_year = " + SESSION),
				"S Shah was renamed and given the new roll number: " + reply);
	}

	// ── R18 ──────────────────────────────────────────────────────────────

	@Test
	void r18_aFileWithNoEmailColumnDoesNotEraseTheEmailsOnRecord() throws Exception {

		Reply reply = postCsv("/roster?year=" + SESSION, """
				rollNo,name,pronouns,year,stream,majorDept,semester
				CS-FY-001,R Rao,she/her,FY,BSc_unaided,COMPUTER_SCIENCE,1
				""", tokenFor("office@sies.edu.in"));

		assertEquals(200, reply.status(), reply::toString);
		assertEquals("r.rao@sies.edu.in",
				text("SELECT coalesce(email, '(none)') FROM students WHERE student_id = 2001"),
				"the file has no email column, and the email on record is gone");
	}

	@Test
	void r18_aFileWithNoMinorColumnDoesNotEraseTheMinorOnRecord() throws Exception {

		Reply reply = postCsv("/roster?year=" + SESSION, """
				rollNo,name,pronouns,year,stream,majorDept,semester
				CS-FY-001,R Rao,she/her,FY,BSc_unaided,COMPUTER_SCIENCE,1
				""", tokenFor("office@sies.edu.in"));

		assertEquals(200, reply.status(), reply::toString);
		assertEquals("CHEMISTRY",
				text("SELECT coalesce(minor_dept::text, '(none)') FROM student_year "
						+ "WHERE student_id = 2001 AND academic_year = " + SESSION),
				"the file has no minor column, and the minor on record is gone");
	}

	// ── R2 ───────────────────────────────────────────────────────────────

	@Test
	void r2_aRosterOfAHundredMegabytesIsTurnedAwayBySize() throws Exception {

		// One enormous line: nothing a roster could be, and enough to matter to a small heap.
		String body = "rollNo,name\n" + "x".repeat(100 * 1024 * 1024);

		long before = System.nanoTime();
		Reply reply = postCsv("/roster?year=" + SESSION, body, tokenFor("office@sies.edu.in"));
		long millis = (System.nanoTime() - before) / 1_000_000;

		assertEquals(413, reply.status(), "100 MB was read in full (" + millis
				+ " ms) and answered as an ordinary request: "
				+ reply.raw().substring(0, Math.min(200, reply.raw().length())));
	}

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
