package in.basulabs.siesattendance.server.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

/**
 * A throwaway PostgreSQL, started for one test class and removed afterwards.
 * <p>
 * The same approach as {@code tools/verify-schema.sh}: a {@code docker run}, a poll until it
 * answers, the committed migrations applied with {@code psql}, and {@code docker rm -f} at the
 * end. No test-container library, because the project already does this with Docker and a
 * script, and the importer needs a real PostgreSQL rather than a convincing one — half of what
 * it is being tested for is whether V2's constraints reject a row.
 * <p>
 * {@link #isAvailable()} is false when Docker is not there, and the tests say loudly that they
 * were skipped rather than passing quietly. The script in {@code tools/verify-import.sh} is the
 * thing that insists.
 */
public final class TestPostgres implements AutoCloseable {

	/** Overridable so the same test runs against the version the differ uses. */
	private static final String IMAGE = System.getenv().getOrDefault("PG_IMAGE", "postgres:18");

	/** Well outside the usual range, so a developer's own PostgreSQL is untouched. */
	private static final int PORT = 55433;

	private static final String CONTAINER = "sies-pg-importer-test";

	private static final String DATABASE = "sies";

	/** The non-superuser, non-owner role the policies are written for. */
	private static final String APP_ROLE = "sies_app_test";

	private static final String APP_PASSWORD = "sies_app_test";

	private final String url;

	private TestPostgres(String url) {
		this.url = url;
	}

	/** Whether Docker can be reached at all. */
	public static boolean isAvailable() {

		try {
			return run(5, "docker", "info").exitCode == 0;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Starts a container, creates the database and applies every committed migration.
	 */
	public static TestPostgres start() throws Exception {

		run(60, "docker", "rm", "-f", CONTAINER);

		Result started = run(120, "docker", "run", "-d", "--name", CONTAINER,
				"-e", "POSTGRES_PASSWORD=postgres",
				"-e", "POSTGRES_DB=" + DATABASE,
				"-p", PORT + ":5432",
				// A throwaway database that never has to survive a restart, so there is no
				// reason to let it touch the disk.
				//
				// The mount is at /var/lib/postgresql, NOT at its data subdirectory.
				// postgres:18 moved PGDATA into a major-version-specific directory below
				// that path, and a mount at the old location makes the entrypoint refuse to
				// start with a long explanation about pg_upgrade. tools/dev-database.sh has
				// the same note; this is the second time it has bitten.
				"--tmpfs", "/var/lib/postgresql",
				IMAGE);

		if (started.exitCode != 0) {
			throw new IllegalStateException("could not start " + IMAGE + ": " + started.output);
		}

		waitUntilReady();

		TestPostgres postgres = new TestPostgres(
				"jdbc:postgresql://localhost:" + PORT + "/" + DATABASE
						+ "?user=postgres&password=postgres");

		postgres.applyMigrations();
		postgres.createApplicationRole();
		return postgres;
	}

	/**
	 * Creates the role the row-level security tests connect as.
	 * <p>
	 * This is not decoration. A superuser bypasses every policy even on a table under
	 * {@code FORCE ROW LEVEL SECURITY}, and a table's owner bypasses its own policies unless
	 * the table is forced — so a test that connected as {@code postgres}, which is both, would
	 * pass against a database with no policies at all. The role below is neither: it owns
	 * nothing, it is not a superuser, and it has no {@code BYPASSRLS}. It is what
	 * {@code tools/roles.sql} calls {@code sies_app}.
	 * <p>
	 * The privileges are deliberately wide — every table, every verb — because the point is to
	 * prove that the <em>policies</em> stop a read that the <em>grants</em> allow. A test that
	 * passed because of a missing GRANT would prove nothing about row-level security.
	 */
	private void createApplicationRole() throws Exception {

		psql("DROP ROLE IF EXISTS " + APP_ROLE + ";"
				+ " CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_PASSWORD + "'"
				+ " NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;"
				+ " GRANT USAGE ON SCHEMA public TO " + APP_ROLE + ";"
				+ " GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "
				+ APP_ROLE + ";"
				+ " GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO " + APP_ROLE + ";"
				+ " GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO " + APP_ROLE + ";");
	}

	/**
	 * A connection as the application role, which every policy applies to.
	 * <p>
	 * Use {@link #actingAs} rather than this directly unless the test is about what an
	 * unauthenticated connection can see — which, correctly, is nothing.
	 */
	public Connection appConnection() throws SQLException {
		return DriverManager.getConnection(
				"jdbc:postgresql://localhost:" + PORT + "/" + DATABASE
						+ "?user=" + APP_ROLE + "&password=" + APP_PASSWORD);
	}

	/**
	 * A connection as the application role with the actor set for the transaction, exactly as
	 * the service will set it before its first statement.
	 * <p>
	 * All three settings are written every time, one of {@code prof} and {@code student} as an
	 * empty string, because "which of these is populated" must never be a question a policy
	 * has to ask. Auto-commit is off: {@code set_config(..., true)} is transaction-scoped, and
	 * on an auto-commit connection the transaction would end before the next statement ran.
	 */
	public Connection actingAs(long account, Long prof, Long student) throws SQLException {

		Connection connection = appConnection();

		try {
			connection.setAutoCommit(false);

			try (java.sql.PreparedStatement statement = connection.prepareStatement(
					"SELECT set_config('app.account_id', ?, true),"
							+ "       set_config('app.prof_id',    ?, true),"
							+ "       set_config('app.student_id', ?, true)")) {

				statement.setString(1, Long.toString(account));
				statement.setString(2, prof == null ? "" : Long.toString(prof));
				statement.setString(3, student == null ? "" : Long.toString(student));
				statement.execute();
			}
			return connection;

		} catch (SQLException e) {
			connection.close();
			throw e;
		}
	}

	/** Runs SQL as the superuser, for a fixture that has to sidestep the policies. */
	public void asSuperuser(String sql) throws SQLException {

		try (Connection connection = connection();
		     Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	/** A data source for the importer, which is all it wants. */
	public DataSource dataSource() {

		return new DataSource() {

			@Override
			public Connection getConnection() throws SQLException {
				return DriverManager.getConnection(url);
			}

			@Override
			public Connection getConnection(String username, String password)
					throws SQLException {
				return getConnection();
			}

			@Override
			public java.io.PrintWriter getLogWriter() {
				return null;
			}

			@Override
			public void setLogWriter(java.io.PrintWriter out) {
			}

			@Override
			public void setLoginTimeout(int seconds) {
			}

			@Override
			public int getLoginTimeout() {
				return 0;
			}

			@Override
			public java.util.logging.Logger getParentLogger() {
				return java.util.logging.Logger.getGlobal();
			}

			@Override
			public <T> T unwrap(Class<T> type) throws SQLException {
				throw new SQLException("not a wrapper");
			}

			@Override
			public boolean isWrapperFor(Class<?> type) {
				return false;
			}
		};
	}

	/** A fresh connection, for a test that wants to look at the rows itself. */
	public Connection connection() throws SQLException {
		return DriverManager.getConnection(url);
	}

	/**
	 * Empties every table, so each test starts from nothing without paying for a new container.
	 * <p>
	 * {@code TRUNCATE} rather than {@code DELETE} because it resets the {@code import_run}
	 * sequence as well, which keeps the run numbers in a failure message meaningful.
	 */
	public void reset() throws SQLException {

		try (Connection connection = connection();
		     Statement statement = connection.createStatement()) {

			statement.execute("TRUNCATE attendance, lectures, course_instructor, "
					+ "course_batch_map, batch_student_map, student_year, batch_desc, "
					+ "courses, professors, students, academic_years, id_remap, import_run, "
					// V5's identity tables. Listed explicitly rather than left to CASCADE:
					// a grant or a role surviving into the next test would make a policy
					// test pass for the wrong reason.
					+ "app_account, user_role, attendance_delegate "
					+ "RESTART IDENTITY CASCADE");
		}
	}

	@Override
	public void close() {

		try {
			run(60, "docker", "rm", "-f", CONTAINER);
		} catch (Exception e) {
			// Declaring this as throwing would make every try-with-resources in the tests
			// handle an InterruptedException that cannot usefully be acted on. A container
			// left behind is visible in `docker ps` and the next run removes it by name.
			System.err.println("could not remove " + CONTAINER + ": " + e);
		}
	}

	// ── Internals ────────────────────────────────────────────────────────

	private static void waitUntilReady() throws Exception {

		for (int attempt = 0; attempt < 90; attempt++) {

			// pg_isready answers true while the server is still in its bootstrap restart, so
			// an actual query is what is polled.
			Result probe = run(10, "docker", "exec", CONTAINER,
					"psql", "-U", "postgres", "-d", DATABASE, "-tAc", "SELECT 1");

			if (probe.exitCode == 0 && probe.output.trim().startsWith("1")) {
				return;
			}
			TimeUnit.MILLISECONDS.sleep(500);
		}
		throw new IllegalStateException(IMAGE + " never became ready");
	}

	private void applyMigrations() throws Exception {

		Path directory = Paths.get("src/main/resources/db/migration").toAbsolutePath();

		List<Path> files = new ArrayList<>();

		try (var stream = Files.list(directory)) {
			stream.filter(path -> path.getFileName().toString().matches("V\\d+__.*\\.sql"))
					.sorted()
					.forEach(files::add);
		}

		if (files.isEmpty()) {
			throw new IllegalStateException("no migrations found in " + directory);
		}

		for (Path file : files) {

			Result copied = run(30, "docker", "cp", file.toString(),
					CONTAINER + ":/tmp/" + file.getFileName());

			if (copied.exitCode != 0) {
				throw new IllegalStateException("could not copy " + file + ": "
						+ copied.output);
			}

			Result applied = run(120, "docker", "exec", CONTAINER, "psql", "-U", "postgres",
					"-d", DATABASE, "-v", "ON_ERROR_STOP=1", "-q",
					"-f", "/tmp/" + file.getFileName());

			if (applied.exitCode != 0) {
				throw new IllegalStateException("applying " + file.getFileName() + " failed: "
						+ applied.output);
			}
		}
	}

	/** Runs one statement batch inside the container, as the superuser. */
	private void psql(String sql) throws Exception {

		Result result = run(60, "docker", "exec", CONTAINER, "psql", "-U", "postgres",
				"-d", DATABASE, "-v", "ON_ERROR_STOP=1", "-q", "-c", sql);

		if (result.exitCode != 0) {
			throw new IllegalStateException("psql failed: " + result.output
					+ "\nstatement was: " + sql);
		}
	}

	private record Result(int exitCode, String output) {
	}

	private static Result run(int timeoutSeconds, String... command) throws Exception {

		ProcessBuilder builder = new ProcessBuilder(command);
		builder.redirectErrorStream(true);

		Process process = builder.start();
		String output;

		try (var stream = process.getInputStream()) {
			output = new String(stream.readAllBytes());
		} catch (IOException e) {
			output = "";
		}

		if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			return new Result(-1, output + "\n(timed out)");
		}
		return new Result(process.exitValue(), output);
	}
}
