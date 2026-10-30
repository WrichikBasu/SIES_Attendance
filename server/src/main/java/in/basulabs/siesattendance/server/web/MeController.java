package in.basulabs.siesattendance.server.web;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.server.security.Delegates;
import in.basulabs.siesattendance.server.security.Roles;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Who the caller is, and what the database will let them do.
 * <p>
 * The console used to find this out by being refused: it showed every panel, and the ones that
 * were not yours answered 403 with the server's own sentence. That is honest and it is a poor
 * way to meet a page — a professor signing in was shown an accounts list, a form for creating
 * accounts and a form for granting roles, all three of which would refuse them. So the page asks
 * once, here, and offers what there is.
 *
 * <h3>It is not an authority, and cannot be</h3>
 * Everything this returns is already enforced somewhere that matters: {@code user_role}'s
 * policies refuse a grant, {@code actor_may_manage_accounts()} refuses the accounts list,
 * {@code audit_entry_visible()} decides the trail entry by entry. A caller who lies to themselves
 * about this answer gains nothing. It exists so the page can be <em>civil</em>, not so it can be
 * safe: the safety is in SQL and stays there.
 *
 * <h3>Why the roles are asked for under the caller's own actor</h3>
 * {@code user_role} has a policy — {@code user_role_own} — admitting an account to its own
 * grants, so this needs no privilege of its own. Everything here is a fact about the caller,
 * which is the only kind of fact an endpoint called {@code /me} should be able to tell.
 */
@RestController
@RequestMapping(Api.V1 + "/me")
public final class MeController {

	/**
	 * @param mayManageAccounts whether the accounts list and the two forms beside it are worth
	 *                          showing: {@code actor_may_manage_accounts()}, which today means
	 *                          holding ADMIN
	 * @param mayKeepARoll      whether to offer the roster screens: adding a student,
	 *                          correcting one, removing one. True for the office, an
	 *                          administrator, and the head of a department — and for the head
	 *                          it is still decided row by row, because another department's
	 *                          student is not theirs. The phone asks this at sign-in for the
	 *                          same reason the console does: a screen that leads to a refusal
	 *                          is a worse screen than no screen
	 * @param auditEntries      how many audit entries this caller may read. Zero is a real
	 *                          answer and the page says so rather than offering an empty table
	 * @param appoints          the courses this caller may appoint a class representative
	 *                          for, so the phone offers the roll on those and no others
	 */
	public record Who(long accountId, @NonNull String kind, @NonNull String displayName,
	                  boolean mustRotate, boolean mayManageAccounts, boolean mayKeepARoll,
	                  @NonNull String auditEntries, @NonNull List<Held> roles,
	                  @NonNull List<Delegates.Delegation> represents,
	                  @NonNull Imports imports, @NonNull Batches batches,
	                  @NonNull List<Appointable> appoints) {
	}

	/**
	 * One course this account may appoint a representative for, asked the way
	 * {@code Delegates.capacityFor} asks it: one they teach, one their department offers if they
	 * head it, or any for an administrator. Discontinued courses are left out, as the console's
	 * picker leaves them out.
	 *
	 * @param courseId a key, as digits in a string like every key on this wire
	 */
	public record Appointable(@NonNull String courseId) {
	}

	/**
	 * Whose batches this account keeps (V33), so the phone offers a batch's editor only to
	 * somebody who may save it, and files a new one under the right department.
	 *
	 * @param anyDepartment true for an administrator, who keeps every department's batches
	 * @param departments   otherwise, a professor's own department alone: heading another
	 *                      does not make its batches theirs. Empty for everybody else, who
	 *                      reads batches and keeps none
	 */
	public record Batches(boolean anyDepartment, @NonNull List<String> departments) {
	}

	/**
	 * What this account may import, so a client offers the right screens and can refuse a
	 * row before sending it.
	 *
	 * @param students        whether to offer the student import at all (V27)
	 * @param anyDepartment   true for the office and an administrator, who may add students
	 *                        to any department
	 * @param departments     otherwise, the departments they may: a professor's own and any
	 *                        they head
	 * @param attendance      whether to offer the attendance import: a professor or an
	 *                        administrator. Which lectures they may write is still the
	 *                        policies' decision
	 */
	public record Imports(boolean students, boolean anyDepartment,
	                      @NonNull List<String> departments, boolean attendance) {
	}

	/**
	 * One role this account holds, with the department said twice.
	 * <p>
	 * {@code department} is what is stored and what every policy compares against;
	 * {@code departmentLabel} is {@code Department.toString()}, which is what a person reads.
	 * Both, because the page needs the second and cannot work it out: the labels reach the
	 * console through {@code /accounts/options}, which is an administrator's endpoint, so a
	 * professor's own strip used to read <em>HOD COMPUTER_SCIENCE</em> — a column's contents,
	 * in the one place on the page that is about them.
	 */
	public record Held(long userRoleId, @NonNull String role, @Nullable String department,
	                   @Nullable String departmentLabel, @Nullable Integer academicYear,
	                   @NonNull String grantedAt) {
	}

	@NonNull
	private final ActorDatabase database;

	public MeController(@NonNull ActorDatabase database) {
		this.database = database;
	}

	@GetMapping
	@NonNull
	public Who me(@NonNull HttpServletRequest http) throws SQLException {

		RequestActor actor = RequestActor.of(http);
		long accountId = actor.session().accountId();

		return database.inTransaction(actor, connection -> {

			List<Held> roles = new ArrayList<>();

			for (Roles.Grant grant : Roles.of(connection, accountId)) {

				if (!grant.live()) {
					continue;
				}

				Department known = grant.department() == null
						? null : Department.fromAny(grant.department());

				roles.add(new Held(grant.userRoleId(), grant.role(), grant.department(),
						known == null ? grant.department() : known.toString(),
						grant.academicYear(), grant.grantedAt().toInstant().toString()));
			}

			List<Delegates.Delegation> represents = new ArrayList<>();

			if (actor.session().studentId() != null) {
				for (Delegates.Delegation delegation
						: Delegates.forStudent(connection, actor.session().studentId())) {
					if (delegation.live()) {
						represents.add(delegation);
					}
				}
			}

			Imports imports = imports(connection, roles);

			return new Who(accountId,
					// name(), not toString(): AccountKind's toString is the human label
					// ("Professor"), and what crosses a wire in this system is always the
					// stored constant. The phone would have parsed either; the console shows
					// it, and a page that displayed PROFESSOR where the enum says Professor
					// is a page showing a column name.
					actor.session().kind().name(),
					actor.session().displayName(),
					actor.session().mustRotate(),
					asked(connection, "SELECT actor_may_manage_accounts() AS yes"),
					asked(connection, "SELECT actor_may_keep_a_roll() AS yes"),
					// A count rather than a boolean: V16 made the trail readable by anybody
					// signed in and narrowed what each of them sees, so "may you read it" is
					// no longer the useful question. "Is there anything in it for you" is.
					Long.toString(counted(connection, "SELECT audit_size() AS total")),
					roles, represents, imports,
					new Batches(asked(connection, "SELECT actor_has_role('ADMIN')"),
							ownDepartment(connection)),
					appoints(connection));
		});
	}

	/** See {@link Appointable}. Empty for a student, who teaches and heads nothing. */
	@NonNull
	private static List<Appointable> appoints(@NonNull java.sql.Connection connection)
			throws SQLException {

		List<Appointable> courses = new ArrayList<>();

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT course_id FROM courses WHERE date_discontinued IS NULL "
						+ "  AND (actor_instructs(course_id) "
						+ "       OR actor_heads(offering_department) "
						+ "       OR actor_has_role('ADMIN')) "
						+ "ORDER BY course_id");
		     ResultSet results = statement.executeQuery()) {

			while (results.next()) {
				courses.add(new Appointable(Long.toString(results.getLong(1))));
			}
		}
		return courses;
	}

	/** A professor's own department, as a list of one; empty for anybody who is not one. */
	@NonNull
	private static List<String> ownDepartment(@NonNull java.sql.Connection connection)
			throws SQLException {

		List<String> departments = new ArrayList<>();

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT actor_prof_department()::text");
		     ResultSet results = statement.executeQuery()) {

			if (results.next() && results.getString(1) != null) {
				departments.add(results.getString(1));
			}
		}
		return departments;
	}

	@NonNull
	private static Imports imports(@NonNull java.sql.Connection connection,
	                               @NonNull List<Held> roles) throws SQLException {

		// A professor's own department and the ones they head.
		List<String> departments = ownDepartment(connection);

		for (Held role : roles) {
			if ("HOD".equals(role.role()) && role.department() != null
					&& !departments.contains(role.department())) {
				departments.add(role.department());
			}
		}

		return new Imports(
				asked(connection, "SELECT actor_may_import_roster()"),
				asked(connection, "SELECT actor_may_manage_roster()"),
				departments,
				asked(connection, "SELECT current_prof_id() IS NOT NULL "
						+ "OR actor_has_role('ADMIN')"));
	}

	private static boolean asked(@NonNull java.sql.Connection connection, @NonNull String sql)
			throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(sql);
		     ResultSet results = statement.executeQuery()) {

			return results.next() && results.getBoolean(1);
		}
	}

	private static long counted(@NonNull java.sql.Connection connection, @NonNull String sql)
			throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(sql);
		     ResultSet results = statement.executeQuery()) {

			return results.next() ? results.getLong(1) : 0L;
		}
	}
}
