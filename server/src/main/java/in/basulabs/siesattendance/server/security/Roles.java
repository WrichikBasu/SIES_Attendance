package in.basulabs.siesattendance.server.security;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Granting and revoking a role, and reading what an account holds.
 * <p>
 * Thin on purpose. Who may do any of this is decided by {@code user_role}'s policies, which say
 * admin and nothing else, and the one thing this class must not do is form a second opinion about
 * that: every statement here runs on the caller's own transaction with the actor announced on it,
 * so a non-admin's grant is refused by the database and arrives as an error rather than as a
 * decision somebody could forget to take.
 *
 * <h3>A grant is never deleted</h3>
 * Revoking sets {@code revoked_at} and {@code revoked_by}. There is no DELETE policy on the table
 * and the service holds no DELETE privilege on it, which is deliberate twice: "who was head of
 * Chemistry in 2025-26" has to stay answerable, and an audit trail of authority with rows missing
 * from it is worth nothing in the dispute it exists for.
 *
 * <h3>What the constraints insist on</h3>
 * {@code HOD} carries a department and an academic year; every other role carries neither. That is
 * two CHECK constraints in V5 rather than a rule here, so the error for getting it wrong comes
 * from the schema and says which half is missing.
 */
public final class Roles {

	/** One grant, live or revoked. */
	public record Grant(long userRoleId, @NonNull String role, @Nullable String department,
	                    @Nullable Integer academicYear, long grantedBy,
	                    @NonNull Timestamp grantedAt, @Nullable Timestamp revokedAt) {

		public boolean live() {
			return revokedAt == null;
		}
	}

	private Roles() {
	}

	/**
	 * Grants a role and returns the id of the grant.
	 *
	 * @param department   the department, for {@code HOD}, and null for every other role
	 * @param academicYear the session, for {@code HOD}, and null for every other role
	 */
	public static long grant(@NonNull Connection connection, long accountId,
	                         @NonNull String role, @Nullable String department,
	                         @Nullable Integer academicYear, long grantedBy,
	                         @Nullable String reason) throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO user_role (account_id, role, department, academic_year, "
						+ "granted_by, reason) "
						+ "VALUES (?, ?::app_role, ?::department, ?, ?, ?) "
						+ "RETURNING user_role_id")) {

			statement.setLong(1, accountId);
			statement.setString(2, role);
			statement.setString(3, department);

			if (academicYear == null) {
				statement.setNull(4, java.sql.Types.INTEGER);
			} else {
				statement.setInt(4, academicYear);
			}

			statement.setLong(5, grantedBy);
			statement.setString(6, reason);

			try (ResultSet results = statement.executeQuery()) {

				if (!results.next()) {
					// RETURNING with no row means the WITH CHECK admitted nothing, which is
					// how a policy refusal reaches an INSERT … RETURNING in some paths.
					throw new SQLException("the grant was not written");
				}
				return results.getLong(1);
			}
		}
	}

	/**
	 * Revokes a live grant. Returns false when it was already revoked or is not the caller's to
	 * touch, which read the same way from here and should: a row the policies hide is a row that
	 * is not there.
	 */
	public static boolean revoke(@NonNull Connection connection, long userRoleId, long revokedBy,
	                             @Nullable String reason) throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				"UPDATE user_role SET revoked_at = now(), revoked_by = ?, "
						+ "reason = coalesce(?, reason) "
						+ "WHERE user_role_id = ? AND revoked_at IS NULL")) {

			statement.setLong(1, revokedBy);
			statement.setString(2, reason);
			statement.setLong(3, userRoleId);

			return statement.executeUpdate() == 1;
		}
	}

	/** Every grant on one account, newest first, revoked ones included. */
	@NonNull
	public static List<Grant> of(@NonNull Connection connection, long accountId)
			throws SQLException {

		List<Grant> grants = new ArrayList<>();

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT user_role_id, role::text, department::text, academic_year, "
						+ "granted_by, granted_at, revoked_at "
						+ "FROM user_role WHERE account_id = ? "
						+ "ORDER BY granted_at DESC, user_role_id DESC")) {

			statement.setLong(1, accountId);

			try (ResultSet results = statement.executeQuery()) {

				while (results.next()) {

					// getObject rather than getInt + wasNull(): wasNull() reports on the last
					// column read, so asking it after reading two more columns answers about
					// the wrong one. A null session would have come back as 1970 in disguise.
					Number year = (Number) results.getObject("academic_year");

					grants.add(new Grant(
							results.getLong("user_role_id"),
							results.getString("role"),
							results.getString("department"),
							year == null ? null : year.intValue(),
							results.getLong("granted_by"),
							results.getTimestamp("granted_at"),
							results.getTimestamp("revoked_at")));
				}
			}
		}
		return grants;
	}
}
