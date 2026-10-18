package in.basulabs.siesattendance.server.security;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Tells the database who is acting, for the length of one transaction.
 * <p>
 * Every policy in {@code V6__row_level_security.sql} reads these three settings through
 * {@code current_account_id()}, {@code current_prof_id()} and {@code current_student_id()}.
 * Nothing else establishes identity: a query that forgets to call this sees no attendance and
 * no lectures at all, which is the failure mode to want — a missing actor loses rows rather
 * than leaking them.
 *
 * <h3>Why all three, always</h3>
 * Exactly one of {@code prof} and {@code student} is a number and the other is an empty string,
 * and both are written every time. A policy must never have to ask which of them is populated,
 * because the version of that question that gets written by accident is
 * {@code current_setting('app.prof_id')} with no second argument — which <em>raises</em> when
 * the setting is absent, so a student's transaction would error out of a professor's policy
 * instead of failing to match it.
 *
 * <h3>Why transaction-scoped</h3>
 * {@code set_config(..., true)} is undone when the transaction ends, which is what makes a
 * pooled connection safe: there is no path by which one person's identity survives into the
 * next request that borrows the same connection. The cost is that it has to be set again on
 * every transaction, and that an auto-commit connection cannot hold it at all — the
 * {@code SELECT set_config(...)} would be its own transaction and be over before the next
 * statement ran. {@link #announce} refuses that case rather than appearing to work.
 */
public final class ActorSession {

	private ActorSession() {
	}

	/**
	 * Announces the signed-in principal on this connection's current transaction.
	 *
	 * @throws IllegalStateException if the connection is in auto-commit mode, where a
	 *                               transaction-scoped setting cannot outlive the statement
	 *                               that sets it
	 */
	public static void announce(@NonNull Connection connection,
	                            @NonNull AccountDirectory.Principal principal)
			throws SQLException {
		announce(connection, principal.accountId(), principal.profId(), principal.studentId());
	}

	/**
	 * The same, from the three values directly. Used by the importer, which acts as an account
	 * that is neither a professor nor a student.
	 */
	public static void announce(@NonNull Connection connection, long accountId,
	                            @Nullable Long profId, @Nullable Long studentId)
			throws SQLException {

		if (connection.getAutoCommit()) {
			throw new IllegalStateException(
					"The actor is transaction-scoped, so it cannot be set on an auto-commit "
							+ "connection: the setting would be discarded before the next "
							+ "statement ran, and every policy would then match nothing. "
							+ "Call setAutoCommit(false) first.");
		}

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT set_config('app.account_id', ?, true),"
						+ "       set_config('app.prof_id',    ?, true),"
						+ "       set_config('app.student_id', ?, true)")) {

			statement.setString(1, Long.toString(accountId));
			statement.setString(2, profId == null ? "" : Long.toString(profId));
			statement.setString(3, studentId == null ? "" : Long.toString(studentId));
			statement.execute();
		}
	}
}
