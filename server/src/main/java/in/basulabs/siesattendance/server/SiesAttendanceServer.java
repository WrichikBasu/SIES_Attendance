package in.basulabs.siesattendance.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * The service.
 * <p>
 * Sixteen endpoints under {@code /api/v1}, and the three commands that are not endpoints at all:
 * the importer, the bootstrap of the first administrator, and this. Every endpoint but signing in
 * needs a bearer token, and the token is the only way an actor is established — see
 * {@code BearerTokenFilter} and {@code ActorDatabase}, which together are the reason the policies
 * written in Phase 4 and the audit entries written in Phase 5 are reachable by something other
 * than a test.
 * <p>
 * The static pages under {@code resources/static} are deliberately outside that: the filter is
 * registered for {@code /api/*} alone, so a page is served before anybody has signed in and does
 * its own signing in from the browser.
 * <p>
 * The one thing {@code main} does before Spring starts is refuse to run without the database
 * credentials, because the failure otherwise is actively misleading. See
 * {@link #missingCredentials}.
 */
@SpringBootApplication
public class SiesAttendanceServer {

	public static void main(String[] args) {

		List<String> missing = missingCredentials(args, System::getenv);

		if (!missing.isEmpty()) {
			System.err.println(complaint(missing));
			System.exit(2);
		}

		SpringApplication.run(SiesAttendanceServer.class, args);
	}

	/**
	 * Which database passwords the environment does not have, in the order they are needed.
	 * <p>
	 * This check exists because of the failure it replaces. {@code application.yml} writes the
	 * passwords as {@code ${DB_APP_PASSWORD}} and {@code ${DB_OWNER_PASSWORD}} with no default,
	 * on purpose — the alternative is a development default that quietly connects to something.
	 * But Spring's binder passes a placeholder it cannot resolve through as a literal, so the
	 * password PostgreSQL is offered is the eight characters {@code ${DB_OWN…}} and the answer is
	 * <em>FATAL: password authentication failed for user "sies_owner"</em>. That reads as a wrong
	 * password — as a database whose roles were created with something else — and the thing
	 * actually wrong is a variable nobody exported. It cost an evening once.
	 * <p>
	 * The rules are the ones the configuration files already imply:
	 * <ul>
	 * <li>Flyway connects as the owner whenever it is enabled, which it is by default.</li>
	 * <li>Under the {@code bootstrap} profile the datasource <em>is</em> the owner — that is the
	 * one legitimate use of the migration role by something other than Flyway — so the app
	 * role's password is not wanted and is not asked for.</li>
	 * <li>A password handed over directly, as {@code --spring.datasource.password=…} or through
	 * {@code SPRING_DATASOURCE_PASSWORD}, satisfies the requirement. The tests start the context
	 * that way, against a container whose password they invented a moment earlier.</li>
	 * </ul>
	 *
	 * @param args        the command line, as Spring will see it
	 * @param environment a lookup of the environment, so this is testable without one
	 */
	static List<String> missingCredentials(String[] args,
	                                       Function<String, String> environment) {

		List<String> arguments = Arrays.asList(args);
		List<String> missing = new ArrayList<>();

		boolean bootstrap = value(arguments, environment, "spring.profiles.active", "")
				.contains("bootstrap");

		if (!bootstrap
				&& blank(environment.apply("DB_APP_PASSWORD"))
				&& blank(value(arguments, environment, "spring.datasource.password", null))) {
			missing.add("DB_APP_PASSWORD");
		}

		boolean flyway = !"false".equalsIgnoreCase(
				value(arguments, environment, "spring.flyway.enabled", "true"));

		if ((flyway || bootstrap)
				&& blank(environment.apply("DB_OWNER_PASSWORD"))
				&& blank(value(arguments, environment, "spring.flyway.password", null))) {
			missing.add("DB_OWNER_PASSWORD");
		}

		return missing;
	}

	/** What to say about it, in the terms of the shell the command was typed into. */
	static String complaint(List<String> missing) {
		return String.join("\n",
				"Not starting: " + String.join(" and ", missing)
						+ (missing.size() == 1 ? " is not set." : " are not set."),
				"",
				"Starting anyway would fail with PostgreSQL's \"password authentication failed\",",
				"because an unresolved ${...} is sent as the password itself -- which reads as a",
				"wrong password rather than a variable nobody exported.",
				"",
				"For the development database, in the same shell as ./gradlew:",
				"",
				"    eval \"$(server/tools/dev-database.sh --env)\"",
				"",
				"A variable exported in one shell does not reach the next one.");
	}

	/**
	 * A Spring property as the command line or the environment gives it, or {@code fallback}.
	 * <p>
	 * Only the two sources that are set before the context exists, and only the spellings Spring
	 * itself would accept for them: {@code --a.b.c=value} and {@code A_B_C}. Not the files —
	 * reading those here would mean a second implementation of the thing being checked.
	 */
	private static String value(List<String> arguments, Function<String, String> environment,
	                            String property, String fallback) {

		String prefix = "--" + property + "=";

		for (String argument : arguments) {
			if (argument.startsWith(prefix)) {
				return argument.substring(prefix.length());
			}
		}

		String fromEnvironment =
				environment.apply(property.toUpperCase().replace('.', '_'));

		return fromEnvironment == null ? fallback : fromEnvironment;
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}
}
