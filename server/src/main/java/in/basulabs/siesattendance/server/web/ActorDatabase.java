package in.basulabs.siesattendance.server.web;

import androidx.annotation.NonNull;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import in.basulabs.siesattendance.server.security.ActorSession;

/**
 * One transaction, with the actor announced on it before anything else runs.
 * <p>
 * Every endpoint that touches data goes through here, and that is the point: the actor is not
 * something a controller remembers to set. A controller that forgot would not leak — a missing
 * actor matches no policy, so it would read nothing and write nothing — but it would also write
 * audit entries labelled "system" for things a person did, and an unattributable entry is a
 * quieter failure than an empty result.
 *
 * <h3>Why not Spring's transaction management</h3>
 * {@code @Transactional} would open the transaction and hand the connection to the method, and
 * the actor has to be announced after the transaction begins and before the first statement.
 * Hooking that reliably means a {@code TransactionSynchronization} or a wrapped
 * {@link DataSource}, both of which end up being this class with more machinery and one more
 * place for the ordering to be wrong. There is no entity manager here and no service layer worth
 * weaving: the endpoints are a handful of statements each.
 */
public final class ActorDatabase {

	/** What a caller does with the connection once the actor is on it. */
	@FunctionalInterface
	public interface Work<T> {

		T run(@NonNull Connection connection) throws SQLException;
	}

	@NonNull
	private final DataSource dataSource;

	public ActorDatabase(@NonNull DataSource dataSource) {
		this.dataSource = dataSource;
	}

	/** Runs the work as the request's actor, committing if it returns and rolling back if not. */
	public <T> T inTransaction(@NonNull RequestActor actor, @NonNull Work<T> work)
			throws SQLException {
		return inTransaction(actor.actor(), work);
	}

	/**
	 * The same, for a caller that has built an actor itself — one acting under a CR grant, which
	 * carries the course that authorised it.
	 */
	public <T> T inTransaction(@NonNull ActorSession.Actor actor, @NonNull Work<T> work)
			throws SQLException {

		try (Connection connection = dataSource.getConnection()) {

			// Before announce(), which refuses an auto-commit connection outright: a
			// transaction-scoped setting on one would be discarded before the next statement
			// ran, and every policy would then match nothing.
			connection.setAutoCommit(false);

			try {
				ActorSession.announce(connection, actor);
				T result = work.run(connection);
				connection.commit();
				return result;

			} catch (SQLException | RuntimeException e) {
				connection.rollback();
				throw e;

			} finally {
				// The connection goes back to the pool, where the next borrower expects the
				// default. The actor itself needs no clearing: set_config(..., true) ends with
				// the transaction, which is why it is used.
				connection.setAutoCommit(true);
			}
		}
	}
}
