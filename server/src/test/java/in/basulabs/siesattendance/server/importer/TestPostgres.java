package in.basulabs.siesattendance.server.importer;

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
final class TestPostgres implements AutoCloseable {

	/** Overridable so the same test runs against the version the differ uses. */
	private static final String IMAGE = System.getenv().getOrDefault("PG_IMAGE", "postgres:18");

	/** Well outside the usual range, so a developer's own PostgreSQL is untouched. */
	private static final int PORT = 55433;

	private static final String CONTAINER = "sies-pg-importer-test";

	private static final String DATABASE = "sies";

	private final String url;

	private TestPostgres(String url) {
		this.url = url;
	}

	/** Whether Docker can be reached at all. */
	static boolean isAvailable() {

		try {
			return run(5, "docker", "info").exitCode == 0;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Starts a container, creates the database and applies every committed migration.
	 */
	static TestPostgres start() throws Exception {

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
		return postgres;
	}

	/** A data source for the importer, which is all it wants. */
	DataSource dataSource() {

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
	Connection connection() throws SQLException {
		return DriverManager.getConnection(url);
	}

	/**
	 * Empties every table, so each test starts from nothing without paying for a new container.
	 * <p>
	 * {@code TRUNCATE} rather than {@code DELETE} because it resets the {@code import_run}
	 * sequence as well, which keeps the run numbers in a failure message meaningful.
	 */
	void reset() throws SQLException {

		try (Connection connection = connection();
		     Statement statement = connection.createStatement()) {

			statement.execute("TRUNCATE attendance, lectures, course_instructor, "
					+ "course_batch_map, batch_student_map, student_year, batch_desc, "
					+ "courses, professors, students, academic_years, id_remap, import_run "
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
