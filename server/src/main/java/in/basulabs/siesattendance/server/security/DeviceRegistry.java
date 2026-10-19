package in.basulabs.siesattendance.server.security;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Types;
import java.util.UUID;

import javax.sql.DataSource;

import in.basulabs.siesattendance.backend.StructuredId;

/**
 * Which installation is which, and what node number each writes its keys under.
 * <p>
 * A node is the bottom sixteen bits of every primary key the app issues, and the half of that
 * space from {@link StructuredId#FIRST_ASSIGNABLE_NODE_ID} to
 * {@link StructuredId#MAX_ASSIGNED_NODE_ID} is handed out from here, one number per install,
 * never reused. The other half is what an install picks for itself before it has ever spoken to
 * a server, which every install does and some never leave.
 *
 * <h3>Why registration is the last chance to fix a clash</h3>
 * Two installs choosing their own node at random may choose the same one, and each may already
 * have written rows under it. Nothing in those rows says which phone wrote them — that is what
 * the node field was supposed to say. Registration is the one moment both facts are in the same
 * place: the install's own identifier, and the number it had been using. Recording the pair is
 * what lets the keys be told apart afterwards; once the rows arrive with nothing but the
 * number, they cannot be.
 * <p>
 * So a clash is reported, not refused. It has already happened, out in the field, and refusing
 * the registration would leave the second phone unregistered and the collision unrecorded —
 * which is the one outcome with no way back.
 */
public final class DeviceRegistry {

	/**
	 * What an install has, or has just been given.
	 *
	 * @param alreadyRegistered true when this install was registered before, which makes a
	 *                          retried request after a lost response safe: the node comes back
	 *                          rather than a second one being issued
	 * @param provisionalClashes how many <em>other</em> installs claim the same provisional node.
	 *                           Non-zero means keys written before either registered cannot be
	 *                           attributed by their node alone
	 */
	public record Registered(long deviceId, int node, boolean alreadyRegistered,
	                         long provisionalClashes) {
	}

	@NonNull
	private final DataSource dataSource;

	public DeviceRegistry(@NonNull DataSource dataSource) {
		this.dataSource = dataSource;
	}

	/**
	 * The device an install already has, or null.
	 * <p>
	 * Through {@code device_for_install} and {@code node_for_install}, which are
	 * {@code SECURITY DEFINER}: the policies on {@code device_registration} let an account see
	 * only the devices it registered itself, and a departmental tablet that one professor
	 * registered and another signs in on has to resolve for both. The functions answer one
	 * question narrowly — this install's node — rather than opening the table.
	 */
	@Nullable
	public Registered forInstall(@NonNull UUID install) throws SQLException {

		try (Connection connection = dataSource.getConnection()) {
			return resolve(connection, install);
		}
	}

	/**
	 * Records the node an install used before it registered, if nothing is recorded yet.
	 * <p>
	 * {@code WHERE provisional_node IS NULL}, so this cannot rewrite history: a number already
	 * there was reported when it was still current and is the better evidence. The clash count
	 * is deliberately not recalculated — it was counted at registration, against the devices
	 * that existed then, and a number computed later would mean something different while
	 * looking the same.
	 */
	private static void rememberProvisionalNode(@NonNull Connection connection,
	                                            @NonNull UUID install, int provisionalNode)
			throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				"UPDATE device_registration SET provisional_node = ? "
						+ "WHERE install_id = ? AND provisional_node IS NULL "
						+ "AND revoked_at IS NULL")) {

			statement.setInt(1, provisionalNode);
			statement.setObject(2, install);
			statement.executeUpdate();
		}
	}

	/**
	 * Registers an install, or returns the node it already had.
	 * <p>
	 * On the caller's transaction, which must already have had an actor announced on it: the
	 * insert is checked by {@code device_registration_register}, which requires the row to name
	 * the acting account as the one that registered it.
	 *
	 * @param provisionalNode the node this install had picked for itself, or null if it had none
	 */
	@NonNull
	public Registered register(@NonNull Connection connection, @NonNull UUID install,
	                           @Nullable Integer provisionalNode, @NonNull String label,
	                           @Nullable String appVersion, long account) throws SQLException {

		Registered existing = resolve(connection, install);

		if (existing != null) {

			// Already registered, so the node stands -- taking a second one would attribute
			// half this phone's rows to a device that is really the same one. But a device
			// that can now say what node it used before registering, and whose row does not
			// record one, is worth listening to: that number is the only thing that explains
			// the keys it wrote back then, and without it they cannot be pushed. Filled when
			// blank and never overwritten, so a recorded clash stays recorded.
			if (provisionalNode != null) {
				rememberProvisionalNode(connection, install, provisionalNode);
			}

			return existing;
		}

		// A savepoint, because two registrations of the same install arriving together is a
		// race the unique index resolves by refusing one of them -- and the refusal has to cost
		// only this statement, not the caller's whole transaction. The loser then reads the
		// winner's row, which is the same answer it would have got a moment later.
		Savepoint savepoint = connection.setSavepoint();

		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO device_registration "
						+ "(install_id, provisional_node, label, app_version, registered_by) "
						+ "VALUES (?, ?, ?, ?, ?) RETURNING device_id, node")) {

			statement.setObject(1, install);

			if (provisionalNode == null) {
				statement.setNull(2, Types.INTEGER);
			} else {
				statement.setInt(2, provisionalNode);
			}

			statement.setString(3, label);
			statement.setString(4, appVersion);
			statement.setLong(5, account);

			try (ResultSet results = statement.executeQuery()) {

				if (!results.next()) {
					throw new SQLException("no device came back from the registration");
				}

				long deviceId = results.getLong("device_id");
				int node = results.getInt("node");

				connection.releaseSavepoint(savepoint);

				return new Registered(deviceId, node, false, clashes(connection, install));
			}

		} catch (SQLException e) {

			if (!"23505".equals(e.getSQLState())) {
				throw e;
			}

			connection.rollback(savepoint);

			Registered winner = resolve(connection, install);

			if (winner == null) {
				// The unique violation was not install_id's, then -- node's, which would mean
				// the sequence has handed out a number twice. Nothing here can make that right.
				throw e;
			}
			return winner;
		}
	}

	/**
	 * One round trip, three definer functions: the device, its node, and how many other installs
	 * claim the provisional node it arrived with. Everything a caller may know about an install
	 * it cannot read the row of.
	 */
	@Nullable
	private static Registered resolve(@NonNull Connection connection, @NonNull UUID install)
			throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT device_for_install(?) AS device_id, node_for_install(?) AS node, "
						+ "provisional_node_clashes(?) AS clashes")) {

			statement.setObject(1, install);
			statement.setObject(2, install);
			statement.setObject(3, install);

			try (ResultSet results = statement.executeQuery()) {

				if (!results.next()) {
					return null;
				}

				long deviceId = results.getLong("device_id");

				if (results.wasNull()) {
					return null;
				}

				return new Registered(deviceId, results.getInt("node"), true,
						results.getLong("clashes"));
			}
		}
	}

	private static long clashes(@NonNull Connection connection, @NonNull UUID install)
			throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT provisional_node_clashes(?)")) {

			statement.setObject(1, install);

			try (ResultSet results = statement.executeQuery()) {
				return results.next() ? results.getLong(1) : 0;
			}
		}
	}
}
