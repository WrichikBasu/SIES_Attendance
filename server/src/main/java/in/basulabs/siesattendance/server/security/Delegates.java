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

import in.basulabs.siesattendance.backend.GrantAuthority;

/**
 * Class representatives: the grants that let a student mark a course's register.
 * <p>
 * The counterpart of {@link Roles} for the other kind of authority in this schema. A role is
 * held over the college — an administrator, an auditor, the head of a department — and lives in
 * {@code user_role}. A delegation is held over one course in one session, and lives in
 * {@code attendance_delegate}. A student account with no delegation can sign in and see its own
 * enrolment and nothing else, which is correct and also why such an account is not much use:
 * <em>a student's reach is its delegations</em>.
 *
 * <h3>Who may appoint one, and why this does not ask</h3>
 * Three kinds of person: the course's instructor, the head of the department that offers it for
 * that session, and an administrator. That is written in SQL twice over — once as the policies on
 * the table and once in {@code attendance_delegate_authorised()}, a BEFORE trigger which checks
 * that the designator actually held the authority they claimed and that the student is on the
 * course's roll. So this class does not decide anything: it asks the database which capacity the
 * caller has ({@link #capacityFor}), records it, and lets the trigger refuse if it is wrong.
 * <p>
 * The capacity is recorded rather than recomputed later, which is {@code GrantAuthority}'s own
 * argument: a head of department who stops being one in 2027 must not retroactively change the
 * authority a 2026 grant was made under.
 *
 * <h3>Reviving a revoked grant</h3>
 * The primary key is {@code (student_id, course_id, academic_year)}, so a student who is made the
 * representative of the same course twice has one row, not two. {@link #grant} therefore clears
 * the revocation and stamps the new authority onto that row — and the history is not lost,
 * because the audit trigger records the UPDATE with its before and after images. Two rows would
 * have been the alternative, and the key says there is one grant per student per course per
 * session.
 */
public final class Delegates {

	/**
	 * One delegation, as the administration page shows it.
	 * <p>
	 * The two keys are text. They are {@code StructuredId}s and this is read by a page, where
	 * every number is a double — see {@code AccountsController.Person} for what that costs.
	 */
	public record Delegation(@NonNull String studentId, @NonNull String courseId,
	                         @NonNull String courseCode, @NonNull String courseTopic,
	                         int academicYear, @NonNull String grantedAs, boolean live,
	                         @NonNull String grantedAt, @Nullable String reason) {
	}

	private Delegates() {
	}

	/**
	 * The capacity in which this actor may appoint a representative for that course, or null
	 * when they may not.
	 * <p>
	 * Asked of the database's own helpers rather than reassembled here, so there is one answer
	 * to "who may do this" and it is the one the policies use. The order is the order the
	 * authority is really held in: a professor who teaches the course is its instructor even if
	 * they also happen to be the head of the department.
	 */
	@Nullable
	public static GrantAuthority capacityFor(@NonNull Connection connection, long courseId,
	                                         int academicYear) throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT actor_instructs(?) AS instructs,"
						+ "       EXISTS (SELECT 1 FROM courses c WHERE c.course_id = ?"
						+ "               AND actor_is_hod_of(c.offering_department, ?)) AS heads,"
						+ "       actor_has_role('ADMIN') AS administers")) {

			statement.setLong(1, courseId);
			statement.setLong(2, courseId);
			statement.setInt(3, academicYear);

			try (ResultSet results = statement.executeQuery()) {

				if (!results.next()) {
					return null;
				}

				if (results.getBoolean("instructs")) {
					return GrantAuthority.INSTRUCTOR;
				}

				if (results.getBoolean("heads")) {
					return GrantAuthority.HOD;
				}

				return results.getBoolean("administers") ? GrantAuthority.ADMIN : null;
			}
		}
	}

	/**
	 * Whether that student is on that course's roll for that session.
	 * <p>
	 * The trigger checks this as well and refuses with {@code check_violation}; this is asked
	 * first so the refusal can name the student and the course instead of naming a constraint.
	 * {@code roll_for_course} is the one definition of who takes a course — the same function
	 * the register screen and the export use.
	 */
	public static boolean onTheRoll(@NonNull Connection connection, long studentId,
	                                long courseId, int academicYear) throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT 1 FROM roll_for_course(?, ?) r WHERE r.student_id = ?")) {

			statement.setLong(1, courseId);
			statement.setInt(2, academicYear);
			statement.setLong(3, studentId);

			try (ResultSet results = statement.executeQuery()) {
				return results.next();
			}
		}
	}

	/** Appoints a representative, or revives a grant that was revoked. */
	public static void grant(@NonNull Connection connection, long studentId, long courseId,
	                         int academicYear, @NonNull GrantAuthority as, long grantedBy,
	                         @Nullable String reason) throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO attendance_delegate (student_id, course_id, academic_year, "
						+ "granted_by, granted_as, reason) "
						+ "VALUES (?, ?, ?, ?, ?::grant_authority, ?) "
						+ "ON CONFLICT (student_id, course_id, academic_year) DO UPDATE SET "
						+ "    granted_by = EXCLUDED.granted_by, "
						+ "    granted_as = EXCLUDED.granted_as, "
						+ "    granted_at = now(), "
						+ "    reason     = EXCLUDED.reason, "
						+ "    revoked_at = NULL, "
						+ "    revoked_by = NULL")) {

			statement.setLong(1, studentId);
			statement.setLong(2, courseId);
			statement.setInt(3, academicYear);
			statement.setLong(4, grantedBy);
			statement.setString(5, as.name());
			statement.setString(6, reason);
			statement.executeUpdate();
		}
	}

	/**
	 * Ends a delegation, keeping the row.
	 * <p>
	 * Never a DELETE, for the reason every grant in this schema is kept: the register that CR
	 * marked last term was marked under this authority, and a deleted row makes that history
	 * unexplainable.
	 *
	 * @return whether a live grant was there to revoke
	 */
	public static boolean revoke(@NonNull Connection connection, long studentId, long courseId,
	                            int academicYear, long revokedBy, @Nullable String reason)
			throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				"UPDATE attendance_delegate SET revoked_at = now(), revoked_by = ?, "
						+ "reason = COALESCE(?, reason) "
						+ "WHERE student_id = ? AND course_id = ? AND academic_year = ? "
						+ "AND revoked_at IS NULL")) {

			statement.setLong(1, revokedBy);
			statement.setString(2, reason);
			statement.setLong(3, studentId);
			statement.setLong(4, courseId);
			statement.setInt(5, academicYear);

			return statement.executeUpdate() > 0;
		}
	}

	/**
	 * Every delegation a student holds, live or ended, newest first.
	 * <p>
	 * Joined to the course so the page can say "SIUCSMN211 · SE" rather than a nineteen-digit
	 * key. Read under the caller's own actor, so what comes back is what the policies allow
	 * them to see.
	 */
	@NonNull
	public static List<Delegation> forStudent(@NonNull Connection connection, long studentId)
			throws SQLException {

		List<Delegation> delegations = new ArrayList<>();

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT d.student_id, d.course_id, c.course_code, c.course_topic, "
						+ "       d.academic_year, d.granted_as::text AS granted_as, "
						+ "       d.granted_at, d.reason, "
						// Ended or run out, the same as actor_represents() decides it: an
						// expired appointment reaches nothing, so it is not one to list.
						+ "       (d.revoked_at IS NULL "
						+ "        AND (d.expires_at IS NULL OR d.expires_at > now())) AS live "
						+ "FROM attendance_delegate d "
						+ "JOIN courses c ON c.course_id = d.course_id "
						+ "WHERE d.student_id = ? "
						+ "ORDER BY d.granted_at DESC")) {

			statement.setLong(1, studentId);

			try (ResultSet results = statement.executeQuery()) {

				while (results.next()) {

					Timestamp granted = results.getTimestamp("granted_at");

					delegations.add(new Delegation(
							Long.toString(results.getLong("student_id")),
							Long.toString(results.getLong("course_id")),
							results.getString("course_code"),
							results.getString("course_topic"),
							results.getInt("academic_year"),
							results.getString("granted_as"),
							results.getBoolean("live"),
							granted.toInstant().toString(),
							results.getString("reason")));
				}
			}
		}

		return delegations;
	}
}
