package in.basulabs.siesattendance.server;

import androidx.annotation.NonNull;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Says what the migrations did, under the {@code migrate} profile.
 * <p>
 * The work is already over by the time this runs: Spring Boot's {@code flywayInitializer}
 * migrates while the context is still building, so a failure never reaches here — it comes out
 * as a failed startup with Flyway's own message, and the process exits non-zero, which is what a
 * deployment script needs. What is left is to report, because "it exited 0" is not the same
 * sentence as "it applied V14 and the schema is at 14".
 *
 * <h3>Why a profile and not a flag</h3>
 * {@code application-migrate.yml} makes this a command: no web server, so it cannot take the
 * port the service wants, and the datasource is the owner, so it needs one password and the
 * service needs the other. The service itself then runs with {@code SPRING_FLYWAY_ENABLED=false}
 * and never holds a credential that can run DDL.
 *
 * <pre>
 * DB_OWNER_PASSWORD=... java -jar sies-attendance.jar --spring.profiles.active=migrate
 * </pre>
 */
@Component
@Profile("migrate")
public class MigrateCommand implements ApplicationRunner {

	@NonNull
	private final Flyway flyway;

	public MigrateCommand(@NonNull Flyway flyway) {
		this.flyway = flyway;
	}

	@Override
	public void run(@NonNull ApplicationArguments args) {

		MigrationInfo current = flyway.info().current();

		List<String> applied = new ArrayList<>();

		for (MigrationInfo migration : flyway.info().applied()) {
			// Only this run's. Flyway stamps every row it writes with the moment it wrote it,
			// and the ones from previous deployments are not news.
			if (migration.getInstalledOn() != null
					&& migration.getInstalledOn().toInstant()
							.isAfter(java.time.Instant.now().minusSeconds(600))) {
				applied.add(migration.getVersion() + " " + migration.getDescription());
			}
		}

		System.out.println();

		if (applied.isEmpty()) {
			System.out.println("Nothing to apply. The schema is at "
					+ (current == null ? "nothing yet" : current.getVersion()) + ".");

		} else {
			System.out.println("Applied " + applied.size() + ":");

			for (String migration : applied) {
				System.out.println("  " + migration);
			}

			System.out.println();
			System.out.println("The schema is at "
					+ (current == null ? "nothing yet" : current.getVersion()) + ".");
		}

		// Said every time, because the one thing a deployment can get wrong here is to leave
		// the service migrating on its own startup as well.
		System.out.println();
		System.out.println("The service itself should run with SPRING_FLYWAY_ENABLED=false, so"
				+ " that it holds the app role's password and not the owner's.");
	}
}
