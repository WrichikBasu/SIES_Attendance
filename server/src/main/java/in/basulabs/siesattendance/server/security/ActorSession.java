package in.basulabs.siesattendance.server.security;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Tells the database who is acting, for the length of one transaction.
 * <p>
 * Two things read these settings. The policies in {@code V6__row_level_security.sql} read the
 * first three through {@code current_account_id()}, {@code current_prof_id()} and
 * {@code current_student_id()}; the audit triggers and {@code audit_event()} in
 * {@code V7__audit_log.sql} read all of them. Nothing else establishes identity, so a query
 * that forgets to call this sees no attendance and no lectures — a missing actor loses rows
 * rather than leaking them, which is the failure to want.
 *
 * <h3>Why all three identity settings, always</h3>
 * Exactly one of {@code prof} and {@code student} is a number and the other is empty, and both
 * are written every time. A policy must never have to ask which of them is populated, because
 * the version of that question that gets written by accident is
 * {@code current_setting('app.prof_id')} with no second argument — which <em>raises</em> when
 * the setting is absent, so a student's transaction would error out of a professor's policy
 * instead of failing to match it.
 *
 * <h3>Why transaction-scoped</h3>
 * {@code set_config(..., true)} is undone when the transaction ends, which is what makes a
 * pooled connection safe: there is no path by which one person's identity survives into the
 * next request that borrows the same connection. The cost is that it has to be set again every
 * transaction, and that an auto-commit connection cannot hold it at all — the
 * {@code SELECT set_config(...)} would be its own transaction and be over before the next
 * statement ran. {@link #announce} refuses that case rather than appearing to work.
 */
public final class ActorSession {

	/**
	 * Who is acting, and the context an audit entry should carry.
	 * <p>
	 * Everything after {@code label} is optional and exists for the log rather than for the
	 * policies. Build one with {@link #of} and add what is known:
	 *
	 * <pre>
	 * ActorSession.announce(connection,
	 *         Actor.of(principal).fromAddress(request.getRemoteAddr()).inSession(sessionId));
	 * </pre>
	 *
	 * @param accountId      the {@code app_account} acting
	 * @param profId         their {@code prof_id}, or null
	 * @param studentId      their {@code student_id}, or null
	 * @param label          who, in words, for when the account is later gone. Never empty
	 * @param sessionId      the login session, so a run of entries can be read as one sitting
	 * @param deviceNode     which phone, from {@code StructuredId}
	 * @param clientIp       where from
	 * @param appVersion     which build
	 * @param delegateCourse the course whose CR grant authorised this, when that is what did.
	 *                       This is the column that answers "what allowed this" without having
	 *                       to guess from whatever roles the person holds by the time somebody
	 *                       asks
	 */
	public record Actor(long accountId, @Nullable Long profId, @Nullable Long studentId,
	                    @NonNull String label, @Nullable UUID sessionId,
	                    @Nullable Integer deviceNode, @Nullable String clientIp,
	                    @Nullable String appVersion, @Nullable Long delegateCourse) {

		public Actor {
			if (label.isBlank()) {
				throw new IllegalArgumentException(
						"An audit entry with no actor label and a null account would say "
								+ "nothing about who did it. Pass a name, or 'system'.");
			}
		}

		/** The ordinary case: a signed-in principal, with nothing else known yet. */
		@NonNull
		public static Actor of(@NonNull AccountDirectory.Principal principal) {
			return new Actor(principal.accountId(), principal.profId(), principal.studentId(),
					principal.displayName(), null, null, null, null, null);
		}

		/**
		 * An account acting without being a professor or a student — an admin, or the
		 * importer. The label is what the log will show.
		 */
		@NonNull
		public static Actor ofAccount(long accountId, @NonNull String label) {
			return new Actor(accountId, null, null, label, null, null, null, null, null);
		}

		@NonNull
		public Actor withLabel(@NonNull String newLabel) {
			return new Actor(accountId, profId, studentId, newLabel, sessionId, deviceNode,
					clientIp, appVersion, delegateCourse);
		}

		@NonNull
		public Actor inSession(@Nullable UUID id) {
			return new Actor(accountId, profId, studentId, label, id, deviceNode, clientIp,
					appVersion, delegateCourse);
		}

		@NonNull
		public Actor fromAddress(@Nullable String ip) {
			return new Actor(accountId, profId, studentId, label, sessionId, deviceNode, ip,
					appVersion, delegateCourse);
		}

		@NonNull
		public Actor fromDevice(@Nullable Integer node, @Nullable String version) {
			return new Actor(accountId, profId, studentId, label, sessionId, node, clientIp,
					version, delegateCourse);
		}

		/**
		 * Records that the authority for what follows was a CR grant on this course.
		 * <p>
		 * Set it when the request is being served under a delegation and not when the actor
		 * merely happens to hold one: the column means "this is what allowed it", and a CR who
		 * is also marking their own course as a student of it would otherwise be recorded as
		 * acting on a grant they were not using.
		 */
		@NonNull
		public Actor onDelegatedCourse(@Nullable Long course) {
			return new Actor(accountId, profId, studentId, label, sessionId, deviceNode,
					clientIp, appVersion, course);
		}
	}

	private ActorSession() {
	}

	/**
	 * Announces an actor on this connection's current transaction.
	 *
	 * @throws IllegalStateException if the connection is in auto-commit mode, where a
	 *                               transaction-scoped setting cannot outlive the statement
	 *                               that sets it
	 */
	public static void announce(@NonNull Connection connection, @NonNull Actor actor)
			throws SQLException {

		requireTransaction(connection);

		// One statement, because nine round trips to set nine strings on every request is nine
		// times the latency for no benefit.
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT set_config('app.account_id',      ?, true),"
						+ "       set_config('app.prof_id',         ?, true),"
						+ "       set_config('app.student_id',      ?, true),"
						+ "       set_config('app.actor_label',     ?, true),"
						+ "       set_config('app.session_id',      ?, true),"
						+ "       set_config('app.device_node',     ?, true),"
						+ "       set_config('app.client_ip',       ?, true),"
						+ "       set_config('app.app_version',     ?, true),"
						+ "       set_config('app.delegate_course', ?, true)")) {

			statement.setString(1, Long.toString(actor.accountId()));
			statement.setString(2, text(actor.profId()));
			statement.setString(3, text(actor.studentId()));
			statement.setString(4, actor.label());
			statement.setString(5, actor.sessionId() == null
					? "" : actor.sessionId().toString());
			statement.setString(6, text(actor.deviceNode()));
			statement.setString(7, actor.clientIp() == null ? "" : actor.clientIp());
			statement.setString(8, actor.appVersion() == null ? "" : actor.appVersion());
			statement.setString(9, text(actor.delegateCourse()));
			statement.execute();
		}
	}

	/**
	 * Announces an account with no name to show for it.
	 * <p>
	 * The label becomes {@code "account <id>"}, which is worse than a real name and better than
	 * "system" — the latter would claim nobody did it. For a caller that has a principal in
	 * hand, {@link Actor#of} carries the display name across instead.
	 */
	public static void announce(@NonNull Connection connection, long accountId,
	                            @Nullable Long profId, @Nullable Long studentId)
			throws SQLException {
		announce(connection, new Actor(accountId, profId, studentId, "account " + accountId,
				null, null, null, null, null));
	}

	/**
	 * Announces an actor whose identity is not an account at all, so that the audit entries a
	 * transaction writes still say something.
	 * <p>
	 * There is one caller: the importer, loading history whose author the device schema never
	 * recorded. {@code actor_account} stays null, which is honest, and the label says which
	 * kind of null it is. Nothing can be read or written under this: the policies key on the
	 * account, so an unattributed actor reaches no row at all.
	 */
	public static void announceUnattributed(@NonNull Connection connection,
	                                        @NonNull String label) throws SQLException {

		requireTransaction(connection);

		if (label.isBlank()) {
			throw new IllegalArgumentException("An unattributed actor needs a label, since "
					+ "there is no account to fall back on.");
		}

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT set_config('app.account_id',  '', true),"
						+ "       set_config('app.prof_id',     '', true),"
						+ "       set_config('app.student_id',  '', true),"
						+ "       set_config('app.actor_label',  ?, true)")) {

			statement.setString(1, label);
			statement.execute();
		}
	}

	private static void requireTransaction(@NonNull Connection connection) throws SQLException {

		if (connection.getAutoCommit()) {
			throw new IllegalStateException(
					"The actor is transaction-scoped, so it cannot be set on an auto-commit "
							+ "connection: the setting would be discarded before the next "
							+ "statement ran, and every policy would then match nothing. "
							+ "Call setAutoCommit(false) first.");
		}
	}

	@NonNull
	private static String text(@Nullable Number value) {
		return value == null ? "" : value.toString();
	}
}
