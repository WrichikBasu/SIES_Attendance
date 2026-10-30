package in.basulabs.siesattendance.server.web;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.springframework.web.bind.annotation.DeleteMapping;
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
import in.basulabs.siesattendance.backend.AuditAction;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Designations;
import in.basulabs.siesattendance.backend.GrantAuthority;
import in.basulabs.siesattendance.server.importer.ServerIds;
import in.basulabs.siesattendance.server.security.AccountDirectory;
import in.basulabs.siesattendance.server.security.AuditLog;
import in.basulabs.siesattendance.server.security.Delegates;
import in.basulabs.siesattendance.server.security.Passwords;
import in.basulabs.siesattendance.server.security.Roles;
import in.basulabs.siesattendance.server.security.Sessions;

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
 *     <li><b>Choose a password for somebody else's account.</b> A new account gets an initial
 *     one and {@code must_rotate} is true from the insert, so the person who typed it is not the
 *     person who ends up knowing it. Somebody who has forgotten theirs is given a temporary one
 *     by {@link #resetPassword}, which the server makes up and which is good for one sign-in:
 *     {@code must_rotate} is set again, and every session the account had is ended. An
 *     administrator could still sign in with it before its owner does, so the reset is in the
 *     audit trail under their name, and an owner who finds the password they were given no
 *     longer works knows to ask why.</li>
 *     <li><b>Delete anything with a history.</b> A role is revoked and never deleted — see
 *     {@link Roles} — and an account that has signed in or held a role is disabled rather
 *     than deleted, because its sessions, grants and the audit log are the record of what it
 *     did. Only an account nothing points at yet, one created by mistake, can be deleted.</li>
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

	/**
	 * One account and what it holds. Never a hash, and never a token.
	 * <p>
	 * {@code profId} and {@code studentId} are text, and that is not a style choice. They are
	 * {@code StructuredId}s — 59 bits of time, sequence and node — and the only client of this
	 * endpoint is a page written in JavaScript, where every number is a double. A key of that
	 * size does not survive the round trip: see the note on {@link Person}.
	 * <p>
	 * {@code accountId} stays a number because it is a sequence value counted from one, and a
	 * college will not reach nine quadrillion accounts.
	 *
	 * @param everHeld whether the account has ever held a role or an appointment, revoked ones
	 *                 included. Tells a new account, which the page lists, from one whose
	 *                 last grant was revoked, which it does not
	 */
	public record AccountBody(long accountId, @NonNull String kind, @Nullable String profId,
	                          @Nullable String studentId, @NonNull String displayName,
	                          @NonNull String loginEmail, boolean mustRotate, boolean locked,
	                          boolean disabled, @Nullable String lastLoginAt,
	                          @NonNull List<GrantBody> roles,
	                          @NonNull List<Delegates.Delegation> delegations,
	                          boolean everHeld) {
	}

	/**
	 * @param role         optional. A role granted in the same transaction as the account, so
	 *                     that "an auditor" is one request and cannot half-happen
	 * @param department   required for {@code HOD}, refused for anything else
	 * @param academicYear required for {@code HOD}, refused for anything else
	 */
	public record NewAccount(@Nullable String kind, @Nullable String displayName,
	                         @Nullable String loginEmail, @Nullable String profId,
	                         @Nullable String studentId, @Nullable String password,
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

	/**
	 * A class representative, for one course in one session.
	 *
	 * @param courseId     the course, as digits in a string. A key and a browser; see
	 *                     {@link Person}
	 * @param academicYear the session the grant is for. Not optional and not defaulted: a
	 *                     representative of a course in 2026 is not one in 2027, and guessing
	 *                     "the current session" would make the grant's own meaning depend on
	 *                     when the form was submitted
	 */
	public record NewDelegation(@Nullable String courseId, @Nullable Integer academicYear,
	                            @Nullable String reason) {
	}

	/**
	 * Which professor a staff account becomes: one on file, or one created with it, exactly as
	 * when an account is created.
	 */
	public record Promotion(@Nullable String profId, @Nullable NewPerson person) {
	}

	public record Revocation(@Nullable String reason) {
	}

	/**
	 * One thing a form can offer: what gets stored, and what a person reads.
	 * <p>
	 * Both, rather than either. The value is what the column holds and what every policy and
	 * query compares against, so it cannot be prettied up; the label is
	 * {@code Department.toString()}, which that enum's own notes call "the only thing that
	 * should ever be shown". A page that shows {@code COMPUTER_SCIENCE} is showing a column
	 * name to a professor.
	 */
	public record Choice(@NonNull String value, @NonNull String label) {
	}

	/**
	 * What the forms need to offer, so that no list is written twice.
	 *
	 * @param designations the titles a professor may hold, in rank order; see
	 *                     {@link Designations}
	 */
	public record Options(@NonNull List<Choice> departments, @NonNull List<Integer> sessions,
	                      @NonNull List<String> roles, @NonNull List<String> kinds,
	                      @NonNull List<String> designations) {
	}


	/**
	 * A professor or a student an account can be hung on, for the form's pickers.
	 * <p>
	 * The id is text. It is a {@code StructuredId}, and this is read by a page: JavaScript has
	 * one numeric type, a double, so a 59-bit key cannot be carried as a JSON number. The
	 * failure is not a visible rounding either — the double holds 370666965485748224 exactly,
	 * and then prints itself as the shortest decimal that identifies it, which is
	 * 370666965485748200. Jackson reads that back as a different long, the insert fails on the
	 * foreign key, and the page says the server went wrong. It did not; the key did. Text all
	 * the way through, in both directions, and the digits are the digits.
	 */
	public record Person(@NonNull String id, @NonNull String name, @Nullable String email,
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
				List<Roles.Grant> grants = Roles.of(connection, account.accountId());
				List<Delegates.Delegation> delegations = account.studentId() == null
						? List.of() : Delegates.forStudent(connection, account.studentId());

				for (Roles.Grant grant : grants) {

					// Live ones only. A revoked grant used to appear struck through, which
					// reads as "this account nearly has this role" and makes a column of
					// ghosts out of an account that has been through a few terms. What
					// happened to it is a question for the audit trail, which keeps the grant,
					// the revocation, who did each and when -- far more than a crossed-out tag
					// could say. Roles.of still returns both, because that is the history.
					if (grant.live()) {
						roles.add(new GrantBody(grant.userRoleId(), grant.role(),
								grant.department(), grant.academicYear(), true,
								grant.grantedAt().toInstant().toString()));
					}
				}

				accounts.add(new AccountBody(account.accountId(), account.kind().name(),
						digits(account.profId()), digits(account.studentId()),
						account.displayName(),
						account.loginEmail(), account.mustRotate(),
						account.lockedUntil() != null
								&& account.lockedUntil().toInstant().isAfter(java.time.Instant.now()),
						account.disabledAt() != null,
						account.lastLoginAt() == null
								? null : account.lastLoginAt().toInstant().toString(),
						roles,
						// A student's reach is its delegations, so the list that shows what an
						// account holds has to show them beside its roles.
						live(delegations),
						!grants.isEmpty() || !delegations.isEmpty()));
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

			// The department comes back as its stored name and is labelled below rather than
			// concatenated here: a picker that read "COMPUTER_SCIENCE - Assistant Professor"
			// was showing a column's contents where it meant to show a department.
			String sql = wanted == AccountKind.PROFESSOR
					? "SELECT p.prof_id AS id, p.name, p.email, "
							+ "p.department::text AS department, p.designation "
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
					people.add(new Person(Long.toString(results.getLong("id")),
							results.getString("name"), results.getString("email"),
							wanted == AccountKind.PROFESSOR
									? describe(results.getString("department"),
											results.getString("designation"))
									: results.getString("detail")));
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

			List<Choice> departments = new ArrayList<>();
			List<Integer> sessions = new ArrayList<>();

			try (Statement statement = connection.createStatement();
			     ResultSet results = statement.executeQuery(
					     "SELECT e.enumlabel FROM pg_enum e "
							     + "JOIN pg_type t ON t.oid = e.enumtypid "
							     + "WHERE t.typname = 'department' "
							     + "ORDER BY e.enumsortorder")) {

				while (results.next()) {

					String stored = results.getString(1);
					Department known = Department.fromAny(stored);

					// The database's list, labelled by the app's enum. In that order on
					// purpose: a department exists because the type says so, and if SQL has
					// one this build has never heard of, it is still offered -- under its
					// stored name, which is the honest thing to show when there is no label.
					departments.add(new Choice(stored,
							known == null ? stored : known.toString()));
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
					List.of("PROFESSOR", "STUDENT", "STAFF"), Designations.ALL);
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

		Long wantedProf = key(body.profId(), "profId");
		Long wantedStudent = key(body.studentId(), "studentId");

		String weak = Passwords.tooWeak(password.toCharArray());

		if (weak != null) {
			throw new ApiException(400, "weak_password", weak);
		}

		// Checked here rather than left to the CHECK constraint, because the constraint's
		// message names a constraint and this one names what to do about it.
		if (kind == AccountKind.PROFESSOR && wantedProf == null && body.person() == null) {
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

		if (kind == AccountKind.STUDENT && wantedStudent == null) {
			throw new ApiException(400, "needs_person",
					"A student's account has to name the student it is for. Students reach the "
							+ "server through the roster CSV, because a roll number is what "
							+ "identifies them and it lives on their enrolment.");
		}

		if (kind == AccountKind.STAFF && (wantedProf != null || wantedStudent != null)) {
			throw new ApiException(400, "staff_has_nobody",
					"A staff account is attached to nobody: office staff are not professors, "
							+ "and giving them a professors row would corrupt every query "
							+ "that counts teaching staff.");
		}

		String role = body.role() == null || body.role().isBlank()
				? null : body.role().trim().toUpperCase(Locale.ROOT);

		// A professor's account is given no role by hand. The one role a professor holds is
		// the head's, and that follows from their designation, below.
		if (kind == AccountKind.PROFESSOR && role != null) {
			throw new ApiException(400, "professor_role_from_designation",
					"A professor's account takes no role. A professor whose designation is "
							+ "HOD is made head of their department with it.");
		}

		if (role != null) {
			checkRoleShape(role, body.department(), body.academicYear());
			checkRoleFits(kind, role);
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
			Long profId = wantedProf;

			if (profId == null && body.person() != null) {
				profId = createProfessor(connection, body.person());
			}

			// Checked rather than left to the foreign key, because the foreign key's refusal
			// arrives as a SQLException with nowhere useful to go: it used to reach the edge
			// as "Something went wrong at the server", which is exactly the wrong thing to
			// tell somebody whose only mistake was naming a person the office has not loaded
			// yet. Inside the transaction, so a professor created a moment ago counts.
			if (profId != null) {
				onFile(connection, "professors", "prof_id", profId,
						"That professor is not on the server. The picker's list is what there "
								+ "is; a professor who is not on it can be created along with "
								+ "their account.");
			}

			if (wantedStudent != null) {
				onFile(connection, "students", "student_id", wantedStudent,
						"That student is not on the server. Students arrive through the "
								+ "roster CSV, because a roll number is what identifies them "
								+ "and it lives on their enrolment.");
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
					wantedStudent, name, email, password.toCharArray(),
					actor.session().accountId());

			List<GrantBody> granted = new ArrayList<>();

			// A professor whose designation is HOD heads their own department, until the
			// appointment is revoked: the designation is what the office records, and the role
			// is what the policies read.
			if (kind == AccountKind.PROFESSOR && profId != null) {
				GrantBody head = grantHeadIfDesignated(connection, accountId, profId,
						actor.session().accountId());
				if (head != null) {
					granted.add(head);
				}
			}

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
			return new AccountBody(accountId, kind.name(), digits(profId),
					digits(wantedStudent), name, email, true, false, false, null, granted,
					// Nothing yet: a delegation names a course, and a course is chosen after
					// the account exists.
					List.of(), !granted.isEmpty());
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

			AccountKind kind = kindOf(connection, accountId);

			if (kind == null) {
				throw new ApiException(404, "no_account", "There is no account " + accountId);
			}

			checkRoleFits(kind, role);

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
			refuseOwnAdministration(connection, actor, userRoleId);

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

	/** @param ended how many live sessions this put an end to */
	public record SessionsEnded(int ended) {
	}

	/**
	 * Ends every session of an account, wherever it is signed in.
	 * <p>
	 * For a lost phone, or one being handed to somebody else. The account itself is untouched:
	 * its owner signs in again with the same password. Each phone finds out at its next
	 * request, drops its token and shows the sign-in screen; the next account to sign in there
	 * empties it first. The administrator's own session is left alone, so ending their own
	 * accounts sessions does not sign this page out.
	 */
	@PostMapping("/{accountId}/sessions/end")
	@NonNull
	public SessionsEnded endSessions(@PathVariable long accountId,
	                                 @NonNull HttpServletRequest http) throws SQLException {

		RequestActor actor = RequestActor.of(http);

		return database.inTransaction(actor, connection -> {

			refuseUnlessAdmin(connection);

			if (!accountExists(connection, accountId)) {
				throw new ApiException(404, "no_account", "There is no account " + accountId);
			}

			int ended = Sessions.revokeAllFor(connection, accountId,
					actor.session().sessionId(), "ended by an administrator");

			AuditLog.record(connection, AuditAction.SESSIONS_END, "app_account",
					AuditLog.detail("account_id", accountId),
					AuditLog.detail("account_id", accountId, "ended", ended));

			return new SessionsEnded(ended);
		});
	}

	/**
	 * @param temporaryPassword the password to hand to the account's owner; shown once, and
	 *                          never stored anywhere it can be read back
	 * @param ended             how many live sessions the reset put an end to
	 */
	public record PasswordReset(@NonNull String temporaryPassword, int ended) {
	}

	/**
	 * Gives an account a temporary password, for somebody who has forgotten theirs or is locked
	 * out.
	 * <p>
	 * The server makes the password up and returns it this once. It clears any lock, and it is
	 * good for one sign-in: the account has to choose a new one before it may do anything else.
	 * Every session the account had ends, so a phone signed in by whoever may have learnt the
	 * old password is signed out with it. Not one's own account, which changes its password
	 * knowing the current one; and not a disabled one, which could not sign in with it anyway.
	 */
	@PostMapping("/{accountId}/password/reset")
	@NonNull
	public PasswordReset resetPassword(@PathVariable long accountId,
	                                   @NonNull HttpServletRequest http) throws SQLException {

		RequestActor actor = RequestActor.of(http);

		if (accountId == actor.accountId()) {
			throw new ApiException(409, "own_account",
					"Change your own password with the current one. If you have forgotten it, "
							+ "another administrator has to reset it.");
		}

		return database.inTransaction(actor, connection -> {

			refuseUnlessAdmin(connection);

			try (PreparedStatement statement = connection.prepareStatement(
					"SELECT disabled_at IS NOT NULL FROM app_account WHERE account_id = ?")) {

				statement.setLong(1, accountId);

				try (ResultSet results = statement.executeQuery()) {
					if (!results.next()) {
						throw new ApiException(404, "no_account",
								"There is no account " + accountId);
					}
					if (results.getBoolean(1)) {
						throw new ApiException(409, "account_disabled",
								"This account is disabled. Enable it first, then reset its "
										+ "password.");
					}
				}
			}

			char[] password = Passwords.temporary();
			String handedOver = new String(password);

			// resetPassword wipes the array; the String is the one copy that goes back.
			AccountDirectory.resetPassword(connection, accountId, password);

			int ended = Sessions.revokeAllFor(connection, accountId, null,
					"password reset by an administrator");

			return new PasswordReset(handedOver, ended);
		});
	}

	/**
	 * Makes a professor's account a staff one: it signs in as before and keeps its roles, but it
	 * no longer acts as the professor.
	 * <p>
	 * The {@code professors} row stays, because lectures, courses and the audit trail point at
	 * it; only the account lets go of it. Who an account is is read from the account on every
	 * request, so this takes effect at once, and its sessions are ended anyway so each phone
	 * signs in again and starts over with what a staff account may read. Not one's own account:
	 * the session this page is using would keep its professor until it ended.
	 */
	@PostMapping("/{accountId}/demote")
	@NonNull
	public SessionsEnded demote(@PathVariable long accountId,
	                            @NonNull HttpServletRequest http) throws SQLException {

		RequestActor actor = RequestActor.of(http);

		refuseOwnAccount(actor, accountId);

		return database.inTransaction(actor, connection -> {

			refuseUnlessAdmin(connection);

			if (!accountExists(connection, accountId)) {
				throw new ApiException(404, "no_account", "There is no account " + accountId);
			}

			try (PreparedStatement statement = connection.prepareStatement(
					"UPDATE app_account SET kind = 'STAFF', prof_id = NULL, "
							+ "updated_at = now(), updated_by = ? "
							+ "WHERE account_id = ? AND kind = 'PROFESSOR'")) {

				statement.setLong(1, actor.accountId());
				statement.setLong(2, accountId);

				if (statement.executeUpdate() == 0) {
					throw new ApiException(409, "not_a_professor",
							"That account is not a professor's.");
				}
			}

			return new SessionsEnded(Sessions.revokeAllFor(connection, accountId, null,
					"made a staff account by an administrator"));
		});
	}

	/**
	 * Makes a staff account a professor's: the way back from {@link #demote}, and the way to
	 * a professor account for somebody whose address already has a staff one.
	 * <p>
	 * The professor is one on file without an account, or one created here in the same
	 * transaction. Its sessions end, as a demotion's do, so each phone signs in again and
	 * starts over as the professor. Not one's own account, for the same reason.
	 */
	@PostMapping("/{accountId}/promote")
	@NonNull
	public SessionsEnded promote(@PathVariable long accountId,
	                             @RequestBody @NonNull Promotion body,
	                             @NonNull HttpServletRequest http) throws SQLException {

		RequestActor actor = RequestActor.of(http);
		Long wantedProf = key(body.profId(), "profId");

		refuseOwnAccount(actor, accountId);

		if ((wantedProf == null) == (body.person() == null)) {
			throw new ApiException(400, "needs_person",
					"Name the professor this account is for, or give the details of a new one, "
							+ "but not both.");
		}

		return database.inTransaction(actor, connection -> {

			refuseUnlessAdmin(connection);

			if (!accountExists(connection, accountId)) {
				throw new ApiException(404, "no_account", "There is no account " + accountId);
			}

			// An if and not a ternary, for the reason create() gives.
			Long profId = wantedProf;

			if (profId == null) {
				profId = createProfessor(connection, body.person());
			}

			onFile(connection, "professors", "prof_id", profId,
					"That professor is not on the server.");

			try (PreparedStatement statement = connection.prepareStatement(
					"SELECT account_id FROM app_account WHERE prof_id = ?")) {

				statement.setLong(1, profId);

				try (ResultSet results = statement.executeQuery()) {
					if (results.next()) {
						throw new ApiException(409, "professor_has_account",
								"That professor already has an account (#"
										+ results.getLong(1) + ").");
					}
				}
			}

			try (PreparedStatement statement = connection.prepareStatement(
					"UPDATE app_account SET kind = 'PROFESSOR', prof_id = ?, "
							+ "updated_at = now(), updated_by = ? "
							+ "WHERE account_id = ? AND kind = 'STAFF'")) {

				statement.setLong(1, profId);
				statement.setLong(2, actor.accountId());
				statement.setLong(3, accountId);

				if (statement.executeUpdate() == 0) {
					throw new ApiException(409, "not_staff",
							"Only a staff account can be made a professor's.");
				}
			}

			// As when a professor's account is created: a head's designation makes them head.
			grantHeadIfDesignated(connection, accountId, profId, actor.accountId());

			return new SessionsEnded(Sessions.revokeAllFor(connection, accountId, null,
					"made a professor account by an administrator"));
		});
	}

	/**
	 * Disables an account: it can no longer sign in, and its sessions stop working at once.
	 * Everything it did stays, and {@link #enable} undoes it. The way to remove somebody who
	 * has left, since an account with a history cannot be deleted.
	 */
	@PostMapping("/{accountId}/disable")
	@NonNull
	public SessionsEnded disable(@PathVariable long accountId,
	                             @NonNull HttpServletRequest http) throws SQLException {

		RequestActor actor = RequestActor.of(http);
		refuseOwnAccount(actor, accountId);

		return database.inTransaction(actor, connection -> {

			refuseUnlessAdmin(connection);
			setDisabled(connection, actor, accountId, true);

			return new SessionsEnded(Sessions.revokeAllFor(connection, accountId, null,
					"account disabled by an administrator"));
		});
	}

	/** Lets a disabled account sign in again, with the password it had. */
	@PostMapping("/{accountId}/enable")
	@NonNull
	public SessionsEnded enable(@PathVariable long accountId,
	                            @NonNull HttpServletRequest http) throws SQLException {

		RequestActor actor = RequestActor.of(http);

		return database.inTransaction(actor, connection -> {

			refuseUnlessAdmin(connection);
			setDisabled(connection, actor, accountId, false);
			return new SessionsEnded(0);
		});
	}

	/**
	 * Deletes an account that nothing points at yet: one that has never signed in and never
	 * held a role or an appointment, which is an account created by mistake. Anything else is
	 * refused by the foreign keys, and the answer says to disable it instead. A professor
	 * created along with the account stays on file; lectures and courses may name them.
	 */
	@DeleteMapping("/{accountId}")
	@NonNull
	public SessionsEnded delete(@PathVariable long accountId,
	                            @NonNull HttpServletRequest http) throws SQLException {

		RequestActor actor = RequestActor.of(http);
		refuseOwnAccount(actor, accountId);

		return database.inTransaction(actor, connection -> {

			refuseUnlessAdmin(connection);

			try (PreparedStatement statement = connection.prepareStatement(
					"DELETE FROM app_account WHERE account_id = ?")) {

				statement.setLong(1, accountId);

				if (statement.executeUpdate() == 0) {
					throw new ApiException(404, "no_account",
							"There is no account " + accountId);
				}

			} catch (SQLException e) {

				// 23001 is what an ON DELETE RESTRICT key reports, and every key on
				// app_account is one; 23503 in case one is ever made NO ACTION.
				if (!"23001".equals(e.getSQLState()) && !"23503".equals(e.getSQLState())) {
					throw e;
				}
				throw new ApiException(409, "has_history",
						"This account has signed in or held a role, and the record of that "
								+ "points at it. Disable it instead: it can no longer sign "
								+ "in, and its history stays.");
			}

			return new SessionsEnded(0);
		});
	}

	/**
	 * Refuses an administrator revoking their own ADMIN: another administrator has to, which
	 * also means there is one left afterwards. V43 holds the database to that last part
	 * whichever way a statement arrives; this is the refusal with a reason in it.
	 */
	private static void refuseOwnAdministration(@NonNull Connection connection,
	                                            @NonNull RequestActor actor, long userRoleId)
			throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT 1 FROM user_role WHERE user_role_id = ? AND account_id = ? "
						+ "AND role = 'ADMIN' AND revoked_at IS NULL")) {

			statement.setLong(1, userRoleId);
			statement.setLong(2, actor.accountId());

			try (ResultSet results = statement.executeQuery()) {
				if (results.next()) {
					throw new ApiException(409, "own_administration",
							"You cannot revoke your own administrator role. Another "
									+ "administrator has to, so that one is always left.");
				}
			}
		}
	}

	private static void refuseOwnAccount(@NonNull RequestActor actor, long accountId) {
		if (accountId == actor.accountId()) {
			throw new ApiException(409, "own_account",
					"Another administrator has to change your own account.");
		}
	}

	private static void setDisabled(@NonNull Connection connection, @NonNull RequestActor actor,
	                                long accountId, boolean disabled) throws SQLException {

		if (!accountExists(connection, accountId)) {
			throw new ApiException(404, "no_account", "There is no account " + accountId);
		}

		// Only a change is written, so the audit trail has no entry for disabling an account
		// that already was.
		try (PreparedStatement statement = connection.prepareStatement(
				"UPDATE app_account SET disabled_at = " + (disabled ? "now()" : "NULL")
						+ ", updated_at = now(), updated_by = ? "
						+ "WHERE account_id = ? AND disabled_at IS "
						+ (disabled ? "NULL" : "NOT NULL"))) {

			statement.setLong(1, actor.accountId());
			statement.setLong(2, accountId);
			statement.executeUpdate();
		}
	}

	/**
	 * The academic years on file, newest first.
	 * <p>
	 * Not an administrator's endpoint, unlike the rest of this controller: a session is a pair
	 * of dates and a number, the representatives panel cannot offer an appointment without
	 * one, and the professor using that panel is not an administrator. Nothing here says who
	 * teaches what or who is enrolled.
	 */
	@GetMapping("/sessions")
	@NonNull
	public List<Integer> sessions(@NonNull HttpServletRequest http) throws SQLException {

		RequestActor actor = RequestActor.of(http);

		return database.inTransaction(actor, connection -> {

			List<Integer> sessions = new ArrayList<>();

			try (Statement statement = connection.createStatement();
			     ResultSet results = statement.executeQuery(
					     "SELECT academic_year FROM academic_years "
							     + "ORDER BY academic_year DESC")) {

				while (results.next()) {
					sessions.add(results.getInt(1));
				}
			}

			return sessions;
		});
	}

	/**
	 * The courses a delegation can name, for the form's picker.
	 * <p>
	 * The courses the caller could appoint one for, asked the way {@code Delegates.capacityFor}
	 * asks it: every course for an administrator, the ones they teach for a professor, and the
	 * department's for its head. Not every course they may read, which is a wider list -- a
	 * course read for any other reason led to a roll that refused it. The grant itself is still
	 * checked again on the roll and twice in SQL.
	 */
	@GetMapping("/courses")
	@NonNull
	public List<Choice> courses(@NonNull HttpServletRequest http) throws SQLException {

		RequestActor actor = RequestActor.of(http);

		return database.inTransaction(actor, connection -> {

			List<Choice> courses = new ArrayList<>();

			try (Statement statement = connection.createStatement();
			     ResultSet results = statement.executeQuery(
					     "SELECT course_id, course_code, course_topic, year::text AS year "
							     + "FROM courses WHERE date_discontinued IS NULL "
							     + "  AND (actor_instructs(course_id) "
							     + "       OR actor_heads(offering_department) "
							     + "       OR actor_has_role('ADMIN')) "
							     + "ORDER BY course_code")) {

				while (results.next()) {
					courses.add(new Choice(Long.toString(results.getLong("course_id")),
							results.getString("course_code") + " · "
									+ results.getString("course_topic") + " · "
									+ results.getString("year")));
				}
			}

			return courses;
		});
	}

	/*
	 * Appointing a class representative used to live here, as POST
	 * /accounts/{id}/delegations. It moved to RepresentativesController, keyed by the course
	 * rather than by the account: the policies let a course's instructor appoint one, and the
	 * form for it needed a list of student accounts, which only an administrator may read --
	 * so the endpoint a professor was meant to use could be reached with curl and never from
	 * the page. The roll is the list, and the roll is theirs.
	 */

	/**
	 * Makes the account head of its professor's department, until revoked, when the professor's
	 * designation is a head's and the account is not head of it already.
	 *
	 * @return the grant made, or null when none was
	 */
	@Nullable
	private static GrantBody grantHeadIfDesignated(@NonNull Connection connection,
	                                               long accountId, long profId, long grantedBy)
			throws SQLException {

		String[] held = professorDepartmentAndDesignation(connection, profId);

		if (held[0] == null || !Designations.isHeads(held[1])) {
			return null;
		}

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT 1 FROM user_role WHERE account_id = ? AND role = 'HOD' "
						+ "AND department = ?::department AND revoked_at IS NULL")) {

			statement.setLong(1, accountId);
			statement.setString(2, held[0]);

			try (ResultSet results = statement.executeQuery()) {
				if (results.next()) {
					return null;
				}
			}
		}

		long grantId = Roles.grant(connection, accountId, "HOD", held[0], null, grantedBy,
				"a professor whose designation is HOD");

		return new GrantBody(grantId, "HOD", held[0], null, true,
				java.time.Instant.now().toString());
	}

	/** A professor's department, as stored, and designation. */
	@NonNull
	private static String[] professorDepartmentAndDesignation(@NonNull Connection connection,
	                                                          long profId) throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT department::text, designation FROM professors WHERE prof_id = ?")) {

			statement.setLong(1, profId);

			try (ResultSet results = statement.executeQuery()) {
				return results.next()
						? new String[]{results.getString(1), results.getString(2)}
						: new String[]{null, null};
			}
		}
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
		String designation = text(person.designation(), "person.designation").trim();

		if (!Designations.isListed(designation)) {
			throw new ApiException(400, "bad_designation",
					"A professor's designation is one of "
							+ String.join(", ", Designations.ALL) + ".");
		}

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

		if (head && department == null) {
			throw new ApiException(400, "incomplete_grant",
					"A head of department is appointed for a department, so that is needed. "
							+ "A session is optional: without one the appointment runs until "
							+ "it is revoked, which is what an appointment normally does.");
		}

		// A session only ever belongs to a head, and now only when somebody is standing in
		// for one year. See V18: requiring it meant re-granting the same person the same
		// department every June, and a grant nobody re-made was a head who silently stopped
		// being one.
		if (!head && (department != null || academicYear != null)) {
			throw new ApiException(400, "scoped_grant",
					role + " is college-wide and holds until it is revoked, so it takes "
							+ "neither a department nor a session.");
		}
	}

	/**
	 * Which roles an account of that kind may hold. A professor may be made head of a department
	 * and nothing else: the office's roles belong to staff accounts, and a professor who also
	 * works in the office signs in with one of those. A staff account is never a head, which is
	 * a professor's appointment. A student holds no role at all; a class representative is
	 * appointed on a course instead.
	 */
	private static void checkRoleFits(@NonNull AccountKind kind, @NonNull String role) {

		switch (kind) {
			case STUDENT -> throw new ApiException(400, "student_takes_no_role",
					"A student's account takes no role. A class representative is appointed "
							+ "on the course, from its roll.");
			case PROFESSOR -> {
				if (!role.equals("HOD")) {
					throw new ApiException(400, "professor_only_heads",
							"A professor may be made head of a department and nothing else. "
									+ role + " is a staff account's role.");
				}
			}
			case STAFF -> {
				if (role.equals("HOD")) {
					throw new ApiException(400, "staff_never_heads",
							"A staff account cannot head a department: that is a "
									+ "professor's appointment.");
				}
			}
		}
	}

	/** The kind of that account, or null when there is no such account. */
	@Nullable
	private static AccountKind kindOf(@NonNull Connection connection, long accountId)
			throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT kind FROM app_account WHERE account_id = ?")) {

			statement.setLong(1, accountId);

			try (ResultSet results = statement.executeQuery()) {
				return results.next() ? AccountKind.valueOf(results.getString(1)) : null;
			}
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

	/** The delegations still in force. The ended ones are in the audit trail. */
	@NonNull
	private static List<Delegates.Delegation> live(
			@NonNull List<Delegates.Delegation> delegations) {

		List<Delegates.Delegation> kept = new ArrayList<>();

		for (Delegates.Delegation delegation : delegations) {
			if (delegation.live()) {
				kept.add(delegation);
			}
		}

		return kept;
	}

	/** A key as the wire carries it — digits in a string — or null when it is absent. */
	@Nullable
	private static Long key(@Nullable String value, @NonNull String field) {

		if (value == null || value.isBlank()) {
			return null;
		}

		try {
			return Long.valueOf(value.trim());

		} catch (NumberFormatException notAKey) {
			throw new ApiException(400, "not_a_key",
					field + " has to be a whole number, as the picker sends it.");
		}
	}

	/** How a professor is told apart from another of the same name: department, then post. */
	@NonNull
	private static String describe(@Nullable String department, @Nullable String designation) {

		Department known = department == null ? null : Department.fromAny(department);

		return (known == null ? String.valueOf(department) : known.toString())
				+ (designation == null ? "" : " - " + designation);
	}

	/** A key on its way out, as text. See {@link Person}. */
	@Nullable
	private static String digits(@Nullable Long key) {
		return key == null ? null : Long.toString(key);
	}

	/**
	 * Refuses with {@code complaint} unless that row is there.
	 * <p>
	 * The table and column are this class's own literals and never a caller's; the key is
	 * bound. Read under the caller's actor like everything else here, which for an
	 * administrator is every row.
	 */
	private static void onFile(@NonNull Connection connection, @NonNull String table,
	                           @NonNull String column, long key, @NonNull String complaint)
			throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT 1 FROM " + table + " WHERE " + column + " = ?")) {

			statement.setLong(1, key);

			try (ResultSet results = statement.executeQuery()) {
				if (!results.next()) {
					throw new ApiException(404, "no_such_person", complaint);
				}
			}
		}
	}
}
