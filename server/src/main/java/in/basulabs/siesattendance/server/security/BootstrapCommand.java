package in.basulabs.siesattendance.server.security;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

import in.basulabs.siesattendance.backend.AccountKind;

/**
 * Creates the first administrator, which nothing else can.
 * <p>
 * There has to be one and it cannot come from a migration: a migration is a committed file, and
 * a committed file with a password in it is a password everybody with the repository has. Nor
 * can it come from the service, because granting the {@code ADMIN} role needs the {@code ADMIN}
 * role — the policy on {@code user_role} says so, and the first grant has nobody to make it.
 * <p>
 * So this: a command, under its own profile, run by somebody on the machine. It connects as the
 * migration role rather than as the service, which is the one legitimate use of that
 * connection and the reason it is a separate command rather than an endpoint.
 *
 * <pre>
 * SIES_ADMIN_PASSWORD=... ./gradlew :server:bootRun --args='--spring.profiles.active=bootstrap \
 *     --bootstrap.email=admin@sies.edu.in \
 *     --bootstrap.name="A Administrator"'
 * </pre>
 *
 * <h3>It refuses to run twice</h3>
 * A second administrator is granted by the first, through the ordinary path, where the grant is
 * attributable. A command that could keep minting them would be a way around that, so it checks
 * for an existing {@code ADMIN} and stops.
 *
 * <h3>The password is not an argument</h3>
 * Arguments are readable by every process on the machine through {@code /proc} and are kept in
 * shell history, so the password comes from the environment or from a terminal prompt. Same
 * rule as the importer's.
 */
@Component
@Profile("bootstrap")
public class BootstrapCommand implements ApplicationRunner {

	private static final String DEFAULT_PASSWORD_ENV = "SIES_ADMIN_PASSWORD";

	@NonNull
	private final DataSource dataSource;

	public BootstrapCommand(@NonNull DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	public void run(@NonNull ApplicationArguments arguments) throws Exception {

		String email = one(arguments, "bootstrap.email");
		String name = one(arguments, "bootstrap.name");

		if (email == null || name == null) {
			System.err.println("""
					Creates the first administrator account.

					  --bootstrap.email=admin@sies.edu.in   the login address (required)
					  --bootstrap.name="A Administrator"     the display name (required)
					  --bootstrap.password-env=NAME          where to read the password from,
					                                         default SIES_ADMIN_PASSWORD

					The password is never an argument. Set the environment variable, or run
					this on a terminal and it will prompt.""");
			throw new IllegalArgumentException("--bootstrap.email and --bootstrap.name "
					+ "are both required");
		}

		if (administratorExists()) {
			throw new IllegalStateException(
					"An administrator already exists. A second one is granted by the first, "
							+ "through the ordinary path, so that the grant has an author. "
							+ "This command only brings the first into being.");
		}

		char[] password = readPassword(
				one(arguments, "bootstrap.password-env") == null
						? DEFAULT_PASSWORD_ENV
						: one(arguments, "bootstrap.password-env"));

		long account = createAdministrator(name, email, password);

		System.out.printf("""
				Created administrator account %d for %s.

				It must change its password on first use: must_rotate is set, and nothing
				should let it past that until it has.
				%n""", account, email);
	}

	/**
	 * Creates the account and grants it {@code ADMIN}, in one transaction, labelled so the
	 * audit entries say what they are.
	 * <p>
	 * One transaction because a half-bootstrapped administrator — an account with no role — is
	 * an account that cannot grant itself one, and the only way out would be to run this again
	 * against a database where {@link #administratorExists()} is still false but the address is
	 * taken.
	 * <p>
	 * The actor is announced as unattributed with the label {@code "bootstrap"}. There is no
	 * account to attribute it to yet, which is the whole point, and {@code "bootstrap"} is a
	 * truer answer than the {@code "system"} the audit trigger would otherwise default to:
	 * both of these entries were caused by a person running a command, and the log should say
	 * which command.
	 */
	private long createAdministrator(@NonNull String name, @NonNull String email,
	                                 @NonNull char[] password) throws SQLException {

		try (Connection connection = dataSource.getConnection()) {

			connection.setAutoCommit(false);

			try {
				ActorSession.announceUnattributed(connection, "bootstrap");

				// STAFF: an administrator is not necessarily a professor, and giving them a
				// professors row to hang the account on would corrupt every query that counts
				// teaching staff.
				long account = AccountDirectory.create(connection, AccountKind.STAFF, null,
						null, name, email, password.clone(), null);

				// Self-granting is not a loophole; it is the only way the first one can exist.
				// Recording the account as its own author says exactly that, rather than
				// leaving the column null and losing the difference between "bootstrapped"
				// and "unknown".
				try (PreparedStatement statement = connection.prepareStatement(
						"INSERT INTO user_role (account_id, role, granted_by, reason) "
								+ "VALUES (?, 'ADMIN', ?, "
								+ "'bootstrapped by BootstrapCommand')")) {

					statement.setLong(1, account);
					statement.setLong(2, account);
					statement.executeUpdate();
				}

				connection.commit();
				return account;

			} catch (SQLException | RuntimeException e) {
				connection.rollback();
				throw e;
			} finally {
				connection.setAutoCommit(true);
				Arrays.fill(password, '\0');
			}
		}
	}

	private boolean administratorExists() throws SQLException {

		try (Connection connection = dataSource.getConnection();
		     PreparedStatement statement = connection.prepareStatement(
				     "SELECT count(*) FROM user_role "
						     + "WHERE role = 'ADMIN' AND revoked_at IS NULL");
		     var results = statement.executeQuery()) {

			return results.next() && results.getLong(1) > 0;
		}
	}

	@Nullable
	private static String one(@NonNull ApplicationArguments arguments, @NonNull String name) {

		List<String> values = arguments.getOptionValues(name);

		return values == null || values.isEmpty() || values.get(0).isBlank()
				? null
				: values.get(0);
	}

	/** From the environment, or from the terminal when there is one. Never from an argument. */
	@NonNull
	private static char[] readPassword(@NonNull String variable) {

		String fromEnvironment = System.getenv(variable);

		if (fromEnvironment != null && !fromEnvironment.isEmpty()) {
			return fromEnvironment.toCharArray();
		}

		if (System.console() != null) {
			char[] typed = System.console().readPassword(
					"Password for the new administrator: ");
			if (typed != null && typed.length > 0) {
				return typed;
			}
		}

		throw new IllegalStateException(variable + " is not set and there is no terminal to "
				+ "prompt on. The password is deliberately not an argument: arguments are "
				+ "visible to every process on this machine.");
	}
}
