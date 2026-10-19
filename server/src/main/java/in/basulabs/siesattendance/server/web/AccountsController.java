package in.basulabs.siesattendance.server.web;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import in.basulabs.siesattendance.backend.AccountKind;
import in.basulabs.siesattendance.server.importer.ServerIds;
import in.basulabs.siesattendance.server.security.AccountDirectory;
import in.basulabs.siesattendance.server.security.Passwords;
import in.basulabs.siesattendance.server.security.Roles;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Accounts and the roles on them.
 * <p>
 * The endpoints behind the administration page. Four of them, and between them they are the whole
 * of "give this person a login and say what they may do": list what exists, create an account,
 * grant a role, revoke one.
 *
 * <h3>Who may</h3>
 * An administrator, and the rule is in SQL twice over rather than here. Creating an account asks
 * {@code actor_may_manage_accounts()} from V12, because {@code app_account} has no policies and
 * V6 says why. Granting and revoking a role is refused by {@code user_role}'s own policies, which
 * admit nobody but an admin; this class asks the same function first anyway, so that a
 * professor's attempt comes back as a 403 that says what the matter is rather than as a database
 * error about insufficient privilege.
 *
 * <h3>What this deliberately cannot do</h3>
 * <ul>
 *     <li><b>Set a password on somebody else's account.</b> A new account gets an initial one and
 *     {@code must_rotate} is true from the insert, so the person who typed it is not the person
 *     who ends up knowing it. There is no endpoint for changing another account's password:
 *     {@code /auth/password} requires the current one, and an administrator who could skip that
 *     could sign in as a professor and mark a register in their name.</li>
 *     <li><b>Delete anything.</b> A role is revoked and never deleted — see {@link Roles} — and
 *     an account is not removable at all. Both are pointed at by the audit log, which is the
 *     record of what they did.</li>
 *     <li><b>Create the first administrator.</b> That still needs
 *     {@code BootstrapCommand} and a shell on the machine, because granting {@code ADMIN} needs
 *     {@code ADMIN}. This endpoint is how the second one comes into being.</li>
 * </ul>
 */
@RestController
@RequestMapping(Api.V1 + "/accounts")
public final class AccountsController {

	/** One grant, as the page shows it. */
	public record GrantBody(long userRoleId, @NonNull String role, @Nullable String department,
	                        @Nullable Integer academicYear, boolean live,
	                        @NonNull String grantedAt) {
	}

	/** One account and what it holds. Never a hash, and never a token. */
	public record AccountBody(long accountId, @NonNull String kind, @Nullable Long profId,
	                          @Nullable Long studentId, @NonNull String displayName,
	                          @NonNull String loginEmail, boolean mustRotate, boolean locked,
	                          boolean disabled, @Nullable String lastLoginAt,
	                          @NonNull List<GrantBody> roles) {
	}

	/**
	 * @param role         optional. A role granted in the same transaction as the account, so
	 *                     that "an auditor" is one request and cannot half-happen
	 * @param department   required for {@code HOD}, refused for anything else
	 * @param academicYear required for {@code HOD}, refused for anything else
	 */
	public record NewAccount(@Nullable String kind, @Nullable String displayName,
	                         @Nullable String loginEmail, @Nullable Long profId,
	                         @Nullable Long studentId, @Nullable String password,
	                         @Nullable String role, @Nullable String department,
	                         @Nullable Integer academicYear,
	                         @Nullable NewPerson person) {
	}

	/**
	 * A professor who is not on the server yet, created with the account that is for them.
	 * <p>
	 * Needed because there was no other way. A professor's account has to name a
	 * {@code professors} row, and until now nothing in this service could write one — they
	 * arrived only through a device backup import or by hand in {@code psql}. A fresh server
	 * therefore had no professors, so it could have no professor accounts, and the
	 * administration page could only say "nobody on file yet".
	 * <p>
	 * Students are deliberately not creatable this way: a student's identity is their roll
	 * number, the roll number lives in {@code student_year}, and a bare {@code students} row is
	 * a person on nobody's roll. The roster CSV is the way in, and it is the office's.
	 */
	public record NewPerson(@Nullable String name, @Nullable String email,
	                        @Nullable String department, @Nullable String designation) {
	}

	public record NewRole(@Nullable String role, @Nullable String department,
	                      @Nullable Integer academicYear, @Nullable String reason) {
	}

	public record Revocation(@Nullable String reason) {
	}

	/** What the forms need to offer, so that no list is written twice. */
	public record Options(@NonNull List<String> departments, @NonNull List<Integer> sessions,
	                      @NonNull List<String> roles, @NonNull List<String> kinds) {
	}

	/** A professor or a student an account can be hung on, for the form's pickers. */
	public record Person(long id, @NonNull String name, @Nullable String email,
	                     @Nullable String detail) {
	}

	@NonNull
	private final ActorDatabase database;

	public AccountsController(@NonNull ActorDatabase database) {
		this.database = database;
	}

	// ── Reading ──────────────────────────────────────────────────────────

	@GetMapping
	@NonNull
	public List<AccountBody> list(@NonNull HttpServletRequest http) throws SQLException {

		RequestActor actor = RequestActor.of(http);

		return database.inTransaction(actor, connection -> {

			refuseUnlessAdmin(connection);

			List<AccountBody> accounts = new ArrayList<>();

			for (AccountDirectory.Summary account : AccountDirectory.list(connection)) {

				List<GrantBody> roles = new ArrayList<>();

				for (Roles.Grant grant : Roles.of(connection, account.accountId())) {
					roles.add(new GrantBody(grant.userRoleId(), grant.role(),
							grant.department(), grant.academicYear(), grant.live(),
							grant.grantedAt().toInstant().toString()));
				}

				accounts.add(new AccountBody(account.accountId(), account.kind().name(),
						account.profId(), account.studentId(), account.displayName(),
						account.loginEmail(), account.mustRotate(),
						account.lockedUntil() != null
								&& account.lockedUntil().toInstant().isAfter(java.time.Instant.now()),
						account.disabledAt() != null,
						account.lastLoginAt() == null
								? null : account.lastLoginAt().toInstant().toString(),
						roles));
			}

			return accounts;
		});
	}

	/**
	 * The professors or students an account can be attached to.
	 * <p>
	 * Here so the form can offer a list rather than ask somebody to type a key. Read under the
	 * caller's own actor, so what comes back is what the policies allow them to see — which for
	 * an admin is everybody, and for anybody else is beside the point, since the request is
	 * refused above.
	 */
	@GetMapping("/people")
	@NonNull
	public List<Person> people(@RequestParam("kind") @NonNull String kind,
	                           @NonNull HttpServletRequest http) throws SQLException {

		RequestActor actor = RequestActor.of(http);
		AccountKind wanted = accountKind(kind);

		if (wanted == AccountKind.STAFF) {
			throw new ApiException(400, "no_people",
					"A staff account is attached to nobody: there is no list to offer.");
		}

		return database.inTransaction(actor, connection -> {

			refuseUnlessAdmin(connection);

			String sql = wanted == AccountKind.PROFESSOR
					? "SELECT p.prof_id AS id, p.name, p.email, "
							+ "p.department::text || ' - ' || p.designation AS detail "
							+ "FROM professors p ORDER BY p.name"
					: "SELECT s.student_id AS id, s.name, s.email, "
							+ "(SELECT y.roll_no FROM student_year y "
							+ " WHERE y.student_id = s.student_id "
							+ " ORDER BY y.academic_year DESC LIMIT 1) AS detail "
							+ "FROM students s ORDER BY s.name";

			List<Person> people = new ArrayList<>();

			try (Statement statement = connection.createStatement();
			     ResultSet results = statement.executeQuery(sql)) {

				while (results.next()) {
					people.add(new Person(results.getLong("id"), results.getString("name"),
							results.getString("email"), results.getString("detail")));
				}
			}

			return people;
		});
	}

	/**
	 * What a grant form has to offer: the departments and the sessions.
	 * <p>
	 * Read from the database rather than written into the page. The departments come from the
	 * {@code department} enum itself, in declaration order, so a department added in a migration
	 * appears here without anybody remembering to edit a list — which is the drift this project
	 * has a test for everywhere else and should not reintroduce in a web page.
	 */
	@GetMapping("/options")
	@NonNull
	public Options options(@NonNull HttpServletRequest http) throws SQLException {

		RequestActor actor = RequestActor.of(http);

		return database.inTransaction(actor, connection -> {

			refuseUnlessAdmin(connection);

			List<String> departments = new ArrayList<>();
			List<Integer> sessions = new ArrayList<>();

			try (Statement statement = connection.createStatement();
			     ResultSet results = statement.executeQuery(
					     "SELECT e.enumlabel FROM pg_enum e "
							     + "JOIN pg_type t ON t.oid = e.enumtypid "
							     + "WHERE t.typname = 'department' "
							     + "ORDER BY e.enumsortorder")) {

				while (results.next()) {
					departments.add(results.getString(1));
				}
			}

			try (Statement statement = connection.createStatement();
			     ResultSet results = statement.executeQuery(
					     "SELECT academic_year FROM academic_years "
							     + "ORDER BY academic_year DESC")) {

				while (results.next()) {
					sessions.add(results.getInt(1));
				}
			}

			return new Options(departments, sessions,
					List.of("HOD", "OFFICE", "AUDITOR", "ADMIN"),
					List.of("PROFESSOR", "STUDENT", "STAFF"));
		});
	}

	// ── Writing ──────────────────────────────────────────────────────────

	@PostMapping
	@NonNull
	public AccountBody create(@RequestBody @NonNull NewAccount body,
	                          @NonNull HttpServletRequest http) throws SQLException {

		RequestActor actor = RequestActor.of(http);

		AccountKind kind = accountKind(body.kind());
		String email = text(body.loginEmail(), "loginEmail");
		String name = text(body.displayName(), "displayName");
		String password = text(body.password(), "password");

		String weak = Passwords.tooWeak(password.toCharArray());

		if (weak != null) {
			throw new ApiException(400, "weak_password", weak);
		}

		// Checked here rather than left to the CHECK constraint, because the constraint's
		// message names a constraint and this one names what to do about it.
		if (kind == AccountKind.PROFESSOR && body.profId() == null && body.person() == null) {
			throw new ApiException(400, "needs_person",
					"A professor's account has to name the professor it is for, or carry the "
							+ "details of a new one.");
		}

		// Before the one below, because it is the more specific complaint: somebody who sent
		// a person for a student has made a different mistake from somebody who sent none.
		if (body.person() != null && kind != AccountKind.PROFESSOR) {
			throw new ApiException(400, "only_professors",
					"Only a professor can be created along with their account. A student's "
							+ "identity is their roll number, which lives on their enrolment, "
							+ "so students reach the server through the roster CSV.");
		}

		if (kind == AccountKind.STUDENT && body.studentId() == null) {
			throw new ApiException(400, "needs_person",
					"A student's account has to name the student it is for. Students reach the "
							+ "server through the roster CSV, because a roll number is what "
							+ "identifies them and it lives on their enrolment.");
		}

		if (kind == AccountKind.STAFF && (body.profId() != null || body.studentId() != null)) {
			throw new ApiException(400, "staff_has_nobody",
					"A staff account is attached to nobody: office staff are not professors, "
							+ "and giving them a professors row would corrupt every query "
							+ "that counts teaching staff.");
		}

		String role = body.role() == null || body.role().isBlank()
				? null : body.role().trim().toUpperCase(Locale.ROOT);

		if (role != null) {
			checkRoleShape(role, body.department(), body.academicYear());
		}

		return database.inTransaction(actor, connection -> {

			refuseUnlessAdmin(connection);

			// The person first, in this same transaction: a professors row left behind by an
			// account that then failed to be created is a name in the staff list that nobody
			// asked for and nothing points at.
			//
			// An if and not a ternary. One arm is a long and the other a Long, so the
			// conditional operator promotes to long and unboxes the null -- the same trap as
			// the Double/Long one in SyncJson, which cost a 55-bit key, and it is worth being
			// as wary of it here as there.
			Long profId = body.profId();

			if (profId == null && body.person() != null) {
				profId = createProfessor(connection, body.person());
			}

			if (emailTaken(connection, email, kind)) {
				// Per door, which is what the unique index says: one address may hold a staff
				// account and a student account, and the server keeps the two separate.
				throw new ApiException(409, "email_taken",
						"There is already a " + (kind == AccountKind.STUDENT
								? "student" : "staff or professor")
								+ " account for " + email + ".");
			}

			long accountId = AccountDirectory.create(connection, kind, profId,
					body.studentId(), name, email, password.toCharArray(),
					actor.session().accountId());

			List<GrantBody> granted = new ArrayList<>();

			if (role != null) {
				// Same transaction as the account: "create an auditor" is one act, and an
				// account that exists with no role because the second statement failed is a
				// login that can do nothing and nobody knows why.
				long grantId = Roles.grant(connection, accountId, role, body.department(),
						body.academicYear(), actor.session().accountId(),
						"granted with the account");

				granted.add(new GrantBody(grantId, role, body.department(),
						body.academicYear(), true, java.time.Instant.now().toString()));
			}

			// No audit entry of this endpoint's own. The triggers on app_account and user_role
			// wrote one row entry each, carrying this actor, this session and this device,
			// which says more than "an account was created" could.
			return new AccountBody(accountId, kind.name(), profId, body.studentId(),
					name, email, true, false, false, null, granted);
		});
	}

	@PostMapping("/{accountId}/roles")
	@NonNull
	public GrantBody grant(@PathVariable long accountId, @RequestBody @NonNull NewRole body,
	                       @NonNull HttpServletRequest http) throws SQLException {

		RequestActor actor = RequestActor.of(http);
		String role = text(body.role(), "role").trim().toUpperCase(Locale.ROOT);

		checkRoleShape(role, body.department(), body.academicYear());

		return database.inTransaction(actor, connection -> {

			refuseUnlessAdmin(connection);

			if (!accountExists(connection, accountId)) {
				throw new ApiException(404, "no_account", "There is no account " + accountId);
			}

			long grantId = Roles.grant(connection, accountId, role, body.department(),
					body.academicYear(), actor.session().accountId(), body.reason());

			return new GrantBody(grantId, role, body.department(), body.academicYear(), true,
					java.time.Instant.now().toString());
		});
	}

	@PostMapping("/{accountId}/roles/{userRoleId}/revoke")
	@NonNull
	public GrantBody revoke(@PathVariable long accountId, @PathVariable long userRoleId,
	                        @RequestBody(required = false) @Nullable Revocation body,
	                        @NonNull HttpServletRequest http) throws SQLException {

		RequestActor actor = RequestActor.of(http);

		return database.inTransaction(actor, connection -> {

			refuseUnlessAdmin(connection);

			boolean revoked = Roles.revoke(connection, userRoleId,
					actor.session().accountId(), body == null ? null : body.reason());

			if (!revoked) {
				throw new ApiException(404, "no_live_grant",
						"That grant is not there, or was revoked already.");
			}

			for (Roles.Grant grant : Roles.of(connection, accountId)) {

				if (grant.userRoleId() == userRoleId) {
					return new GrantBody(grant.userRoleId(), grant.role(), grant.department(),
							grant.academicYear(), false, grant.grantedAt().toInstant().toString());
				}
			}

			throw new ApiException(404, "no_live_grant",
					"That grant does not belong to account " + accountId + ".");
		});
	}

	/**
	 * Writes a professors row and returns its key.
	 * <p>
	 * Keyed by {@link ServerIds}, under node 0, which is what makes a row the server invented
	 * tell apart for ever from one a phone issued. Whether this actor may write it at all is
	 * V11's {@code professors_office} policy, not a check here: an admin matches it, and
	 * anybody who does not is refused by the database.
	 */
	private static long createProfessor(@NonNull Connection connection,
	                                    @NonNull NewPerson person) throws SQLException {

		String name = text(person.name(), "person.name");
		String email = text(person.email(), "person.email");
		String department = text(person.department(), "person.department");
		String designation = text(person.designation(), "person.designation");

		ServerIds ids = new ServerIds();
		ids.primeFrom(connection);

		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO professors (prof_id, name, email, department, designation, "
						+ "created_at) VALUES (?, ?, ?, ?::department, ?, now()) "
						+ "RETURNING prof_id")) {

			statement.setLong(1, ids.next());
			statement.setString(2, name);
			statement.setString(3, email);
			statement.setString(4, department);
			statement.setString(5, designation);

			try (ResultSet results = statement.executeQuery()) {

				if (!results.next()) {
					throw new SQLException("no professor id came back from the insert");
				}
				return results.getLong(1);
			}

		} catch (SQLException e) {

			if ("23505".equals(e.getSQLState())) {
				throw new ApiException(409, "professor_exists",
						"There is already a professor with that email address. Pick them from "
								+ "the list instead of creating a second row for them.");
			}
			throw e;
		}
	}

	// ── The rules this endpoint keeps ────────────────────────────────────

	/**
	 * V12's function, asked before anything else.
	 * <p>
	 * One statement per request. It could be read from the session instead, and is not on
	 * purpose: a role revoked while somebody is working takes effect on their next request
	 * rather than on their next sign-in, which is the same property the policies have.
	 */
	private static void refuseUnlessAdmin(@NonNull Connection connection) throws SQLException {

		try (Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery("SELECT actor_may_manage_accounts()")) {

			if (!results.next() || !results.getBoolean(1)) {
				throw new ApiException(403, "not_allowed",
						"Accounts and roles are an administrator's.");
			}
		}
	}

	/**
	 * The two CHECK constraints in V5, asked before the insert.
	 * <p>
	 * Not a second opinion: the constraints are what enforce this, and a request that got past
	 * here with a department on an auditor would still be refused. This is only so the answer
	 * says which half is wrong.
	 */
	private static void checkRoleShape(@NonNull String role, @Nullable String department,
	                                   @Nullable Integer academicYear) {

		if (!List.of("HOD", "OFFICE", "AUDITOR", "ADMIN").contains(role)) {
			throw new ApiException(400, "unknown_role",
					"Unknown role: " + role + ". Expected HOD, OFFICE, AUDITOR or ADMIN.");
		}

		boolean head = role.equals("HOD");

		if (head && (department == null || academicYear == null)) {
			throw new ApiException(400, "incomplete_grant",
					"A head of department is appointed for a department and a session, so "
							+ "both are needed. A head for 2025-26 should not still be one "
							+ "in 2027-28.");
		}

		if (!head && (department != null || academicYear != null)) {
			throw new ApiException(400, "scoped_grant",
					role + " is college-wide and holds until it is revoked, so it takes "
							+ "neither a department nor a session.");
		}
	}

	private static boolean emailTaken(@NonNull Connection connection, @NonNull String email,
	                                  @NonNull AccountKind kind) throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT 1 FROM app_account "
						+ "WHERE lower(login_email) = lower(?) AND student_login = ?")) {

			statement.setString(1, email);
			statement.setBoolean(2, kind == AccountKind.STUDENT);

			try (ResultSet results = statement.executeQuery()) {
				return results.next();
			}
		}
	}

	private static boolean accountExists(@NonNull Connection connection, long accountId)
			throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT 1 FROM app_account WHERE account_id = ?")) {

			statement.setLong(1, accountId);

			try (ResultSet results = statement.executeQuery()) {
				return results.next();
			}
		}
	}

	@NonNull
	private static AccountKind accountKind(@Nullable String kind) {

		try {
			return AccountKind.valueOf(text(kind, "kind").trim().toUpperCase(Locale.ROOT));

		} catch (IllegalArgumentException e) {
			throw new ApiException(400, "unknown_kind",
					"Unknown kind: " + kind + ". Expected PROFESSOR, STUDENT or STAFF.");
		}
	}

	@NonNull
	private static String text(@Nullable String value, @NonNull String field) {

		if (value == null || value.isBlank()) {
			throw new ApiException(400, "incomplete", field + " is needed.");
		}
		return value.trim();
	}
}
