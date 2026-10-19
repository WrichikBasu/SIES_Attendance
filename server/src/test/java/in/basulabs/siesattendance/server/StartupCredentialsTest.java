package in.basulabs.siesattendance.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * The check {@code main} runs before Spring starts.
 * <p>
 * Written after a run of the importer failed with <em>FATAL: password authentication failed for
 * user "sies_owner"</em> against a database whose owner password was exactly what it had always
 * been. Nothing was wrong with the password: the shell had never been given
 * {@code DB_OWNER_PASSWORD}, Spring passed the unresolved placeholder through as the password,
 * and PostgreSQL reported what it was sent. These cases are the ones that failure walked
 * through.
 */
class StartupCredentialsTest {

	private static final String[] NO_ARGUMENTS = {};

	private static List<String> missing(Map<String, String> environment, String... arguments) {
		return SiesAttendanceServer.missingCredentials(arguments, environment::get);
	}

	@Test
	void anEmptyEnvironmentIsBothPasswords() {
		assertEquals(List.of("DB_APP_PASSWORD", "DB_OWNER_PASSWORD"), missing(Map.of()));
	}

	@Test
	void theDevelopmentEnvironmentIsEnough() {
		assertEquals(List.of(), missing(Map.of(
				"DB_APP_PASSWORD", "devonly-app",
				"DB_OWNER_PASSWORD", "devonly-owner")));
	}

	@Test
	void theImporterNeedsTheOwnerToo() {
		// The profile does not change the database it connects to, and Flyway still runs under
		// it: importing into an unmigrated database would fail on the first insert.
		assertEquals(List.of("DB_OWNER_PASSWORD"),
				missing(Map.of("DB_APP_PASSWORD", "devonly-app"),
						"--spring.profiles.active=import",
						"--import.file=/tmp/a.siesbak"));
	}

	@Test
	void theBootstrapProfileDoesNotWantTheAppRole() {
		// application-bootstrap.yml points the datasource at the owner, because granting the
		// first ADMIN is the one thing the service's own role cannot do.
		assertEquals(List.of(),
				missing(Map.of("DB_OWNER_PASSWORD", "devonly-owner"),
						"--spring.profiles.active=bootstrap",
						"--bootstrap.email=admin@sies.edu.in"));
	}

	@Test
	void flywayTurnedOffDoesNotNeedTheOwner() {
		// What the tests do: the schema is already applied by psql, in production order, so
		// Flyway is off and no owner connection is ever opened.
		assertEquals(List.of(),
				missing(Map.of("DB_APP_PASSWORD", "whatever-the-container-got"),
						"--spring.flyway.enabled=false"));
	}

	@Test
	void aPasswordGivenDirectlyCounts() {
		assertEquals(List.of(),
				missing(Map.of(),
						"--spring.datasource.password=from-the-container",
						"--spring.flyway.password=from-the-container"));
	}

	@Test
	void theEnvironmentsSpellingOfAPropertyCounts() {
		assertEquals(List.of(),
				missing(Map.of(
						"SPRING_DATASOURCE_PASSWORD", "from-the-platform",
						"SPRING_FLYWAY_ENABLED", "false"), NO_ARGUMENTS));
	}

	@Test
	void anEmptyVariableIsNotAPassword() {
		// export DB_OWNER_PASSWORD= is how a typo in the eval shows up, and an empty password
		// fails at PostgreSQL exactly as an unresolved placeholder does.
		assertEquals(List.of("DB_OWNER_PASSWORD"), missing(Map.of(
				"DB_APP_PASSWORD", "devonly-app",
				"DB_OWNER_PASSWORD", "")));
	}

	@Test
	void theComplaintNamesTheVariableAndTheShell() {
		String complaint = SiesAttendanceServer.complaint(List.of("DB_OWNER_PASSWORD"));

		assertTrue(complaint.contains("DB_OWNER_PASSWORD"), complaint);
		assertTrue(complaint.contains("dev-database.sh --env"), complaint);
		// The whole point is to pre-empt the message PostgreSQL would have given instead.
		assertTrue(complaint.contains("password authentication failed"), complaint);
	}
}
