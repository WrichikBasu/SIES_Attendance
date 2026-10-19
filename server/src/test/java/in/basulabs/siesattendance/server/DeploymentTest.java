package in.basulabs.siesattendance.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The deployment files, held to what the code actually requires.
 * <p>
 * A unit file is the one part of a service nothing else checks: it is read by systemd on a
 * machine nobody is looking at, and a mistake in it surfaces as "the restart killed a request"
 * or "the service is migrating the schema again" months later. These are the four things that
 * can be checked here rather than there.
 */
class DeploymentTest {

	private static final Path DEPLOY = Paths.get("deploy");

	private static String read(String name) throws IOException {
		return Files.readString(DEPLOY.resolve(name), StandardCharsets.UTF_8);
	}

	/** The variables a file sets, as systemd and libpq read them: {@code NAME=value} lines. */
	private static Map<String, String> variables(String contents) {

		Map<String, String> values = new LinkedHashMap<>();

		for (String line : contents.split("\n")) {

			String trimmed = line.trim();

			if (trimmed.startsWith("#") || !trimmed.contains("=")) {
				continue;
			}

			// Environment=NAME=value in a unit, NAME=value in an environment file.
			String pair = trimmed.startsWith("Environment=")
					? trimmed.substring("Environment=".length()) : trimmed;

			int split = pair.indexOf('=');
			String name = pair.substring(0, split).trim();

			if (!name.equals(name.toUpperCase()) || name.contains(" ")) {
				// A unit directive such as TimeoutStopSec, not a variable.
				continue;
			}

			values.put(name, pair.substring(split + 1).trim());
		}

		return values;
	}

	@Test
	void theEnvironmentFileIsEnoughToStartTheServiceWith() throws Exception {

		Map<String, String> environment = variables(read("sies-attendance.env.example"));
		environment.putAll(variables(read("sies-attendance.service")));

		// The template leaves the password blank for somebody to fill in; everything else
		// has to be there. This is the guard in main() asked of the deployment file, which
		// is the only way the two cannot drift apart.
		environment.put("DB_APP_PASSWORD", "filled in on the machine");

		assertEquals(List.of(),
				SiesAttendanceServer.missingCredentials(new String[0], environment::get),
				"the unit and its environment file have to satisfy the startup check");

		// And the owner's password is deliberately NOT among them: the service runs with
		// Flyway off, so it holds a credential that cannot run DDL.
		assertEquals("false", environment.get("SPRING_FLYWAY_ENABLED"),
				"migrations are a separate step; see MigrateCommand");
		assertFalse(environment.containsKey("DB_OWNER_PASSWORD")
						&& !environment.get("DB_OWNER_PASSWORD").isEmpty(),
				"the long-running process must not hold a password that can run DDL");
	}

	@Test
	void theMigrationStepIsGivenTheOwnerAndTheServiceIsNot() throws Exception {

		Map<String, String> migration = variables(read("migrate-env.example"));

		migration.put("DB_OWNER_PASSWORD", "filled in on the machine");

		assertEquals(List.of(), SiesAttendanceServer.missingCredentials(
						new String[]{"--spring.profiles.active=migrate"}, migration::get),
				"the migrate profile needs the owner and nothing else");

		assertFalse(migration.containsKey("DB_APP_PASSWORD"),
				"and has no use for the app role's password");
	}

	@Test
	void systemdIsGivenLongerToStopThanTheGracefulShutdownTakes() throws Exception {

		String unit = read("sies-attendance.service");
		String application = Files.readString(
				Paths.get("src/main/resources/application.yml"), StandardCharsets.UTF_8);

		assertTrue(application.contains("shutdown: graceful"),
				"the unit's timeout only matters because the shutdown waits");

		int stopSeconds = Integer.parseInt(
				unit.replaceAll("(?s).*TimeoutStopSec=(\\d+).*", "$1"));

		// Spring's default when spring.lifecycle.timeout-per-shutdown-phase is unset. If the
		// unit's timeout were the shorter of the two, systemd would SIGKILL the process in
		// the middle of the requests the graceful shutdown was waiting for -- which is the
		// one failure a graceful shutdown exists to prevent.
		int gracePeriod = application.contains("timeout-per-shutdown-phase")
				? Integer.parseInt(application.replaceAll(
						"(?s).*timeout-per-shutdown-phase: *(\\d+).*", "$1"))
				: 30;

		assertTrue(stopSeconds > gracePeriod,
				"TimeoutStopSec=" + stopSeconds + " has to exceed the " + gracePeriod
						+ "s shutdown phase");

		assertTrue(unit.contains("KillSignal=SIGTERM"), "which is what graceful listens for");
	}

	@Test
	void theUnitStartsTheJarTheBuildProduces() throws Exception {

		String unit = read("sies-attendance.service");
		String build = Files.readString(Paths.get("build.gradle.kts"), StandardCharsets.UTF_8);

		assertTrue(build.contains("archiveFileName = \"sies-attendance.jar\""), build);
		assertTrue(unit.contains("/opt/sies-attendance/sies-attendance.jar"),
				"the unit names a path, so the jar's name is part of the contract");

		// The actuator override dev-serve.sh needs must not be here: /actuator/mappings is a
		// map of the API, and application.yml exposes health alone. Asked of the directives
		// and not of the file, because the file's own comments explain why it is absent --
		// the same trap as a test that searched a page for "localStorage" and found the
		// comment saying why the page does not use it.
		for (String line : unit.split("\n")) {
			if (!line.trim().startsWith("#")) {
				assertFalse(line.contains("mappings") || line.contains("exposure.include"),
						"the route table is for development, not for a public port: " + line);
			}
		}
	}
}
