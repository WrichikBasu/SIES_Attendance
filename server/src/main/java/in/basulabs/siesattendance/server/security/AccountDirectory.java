package in.basulabs.siesattendance.server.security;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.CharBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;

import javax.sql.DataSource;

import in.basulabs.siesattendance.backend.AccountKind;
import in.basulabs.siesattendance.backend.AuditAction;

/**
 * The accounts, and whether a password is theirs.
 * <p>
 * This exists because there is no login through the college's identity provider. Everything
 * below — the hash, the failure counter, the lockout, the forced rotation — is work an external
 * provider would have done, and it is the reason Phase 4 is longer than the phases around it.
 *
 * <h3>Two doors</h3>
 * Professors and students sign in on separate screens, so the door is part of the lookup rather
 * than something inferred from the address. {@code app_account} is unique on
 * {@code (student_login, lower(login_email))}, which means one address may hold a student
 * account and a staff account at once — a research assistant finishing an MSc — and the screen
 * the person used decides which one they get.
 * <p>
 * Addresses are matched case-insensitively here, unlike {@code students.email} and
 * {@code professors.email}, which V1 left case-sensitive to match Room exactly. Somebody typing
 * their own address into a login box will not reproduce its capitalisation, and treating two
 * casings as two accounts is how a person gets locked out of the one they have.
 *
 * <h3>What the caller may say out loud</h3>
 * {@link Refusal} distinguishes an unknown address from a wrong password because the server's
 * own log should record which it was. A response to the person must not: telling an unauthenticated
 * caller that an address exists turns the login box into a directory of the college. Collapse
 * every refusal into one message at the edge.
 */
public final class AccountDirectory {

	/** Which login screen the attempt came from. */
	public enum Door {

		/** Professors and office staff. */
		STAFF(false),

		/** Students, which in practice means class representatives. */
		STUDENT(true);

		private final boolean studentLogin;

		Door(boolean studentLogin) {
			this.studentLogin = studentLogin;
		}

		/** The value of {@code app_account.student_login} this door matches. */
		public boolean studentLogin() {
			return studentLogin;
		}

		/** The door an account of this kind signs in at. */
		@NonNull
		public static Door of(@NonNull AccountKind kind) {
			return kind.isStudentLogin() ? STUDENT : STAFF;
		}
	}

	/** Why an attempt was refused. For the log, not for the person. */
	public enum Refusal {
		NO_SUCH_ACCOUNT, WRONG_PASSWORD, LOCKED, DISABLED
	}

	/** Who signed in, and what the service needs to know about them. */
	public record Principal(long accountId, @NonNull AccountKind kind, @Nullable Long profId,
	                        @Nullable Long studentId, @NonNull String displayName,
	                        boolean mustRotate) {
	}

	/**
	 * The result of an attempt: exactly one of {@code principal} and {@code refusal} is set.
	 *
	 * @param rehashed whether the stored hash was replaced with one made to the current
	 *                 parameters, which happens at most once per password per parameter change
	 */
	public record Attempt(@Nullable Principal principal, @Nullable Refusal refusal,
	                      boolean rehashed) {

		public boolean succeeded() {
			return principal != null;
		}
	}

	/**
	 * Consecutive failures before an account is locked at all.
	 * <p>
	 * Five, and then a window that doubles. There is no lockout policy that cannot be used to
	 * inconvenience somebody — an attacker who knows an address can always spend five wrong
	 * guesses to lock it — so the shape to want is a first window short enough that a real
	 * person waits rather than telephones, growing fast enough that sustained guessing is
	 * hopeless. One minute, then two, four, eight, capped at an hour.
	 */
	private static final int FAILURES_BEFORE_LOCK = 5;

	@NonNull
	private final DataSource dataSource;

	public AccountDirectory(@NonNull DataSource dataSource) {
		this.dataSource = dataSource;
	}

	/**
	 * Checks a password against the account at this door, and records the outcome.
	 * <p>
	 * The password array is wiped before this returns, however it returns. A {@code char[]}
	 * rather than a {@code String} for exactly that reason: a String cannot be cleared and
	 * sits in the heap until it is collected, where a heap dump taken for an unrelated reason
	 * would contain it.
	 */
	@NonNull
	public Attempt authenticate(@NonNull String email, @NonNull Door door,
	                            @NonNull char[] password) throws SQLException {

		try (Connection connection = dataSource.getConnection()) {

			// One transaction, because the failure counter and the audit entry that records
			// the failure have to land together. Committed whichever way the attempt went: a
			// refused attempt is not a rolled-back one, it is a thing that happened and has
			// to be counted.
			connection.setAutoCommit(false);

			try {
				Attempt attempt = authenticate(connection, email, door, password);
				connection.commit();
				return attempt;

			} catch (SQLException | RuntimeException e) {
				connection.rollback();
				throw e;
			} finally {
				connection.setAutoCommit(true);
			}

		} finally {
			Arrays.fill(password, '\0');
		}
	}

	@NonNull
	private Attempt authenticate(@NonNull Connection connection, @NonNull String email,
	                             @NonNull Door door, @NonNull char[] password)
			throws SQLException {

		Stored stored = find(connection, email, door);

		if (stored == null) {
			// Hash the supplied password against nothing, so that an unknown address costs
			// the same time as a known one. Without this, the difference between "no such
			// account" and "wrong password" is measurable from outside, and the login box
			// becomes a way to ask whether somebody works here.
			Passwords.encoder().matches(CharBuffer.wrap(password), DUMMY_HASH);

			// Logged with no account and no address. The fact that somebody tried an address
			// nobody has is worth a line; the address itself is not, because a complete log of
			// failed logins is a list of valid addresses as soon as somebody reads it
			// alongside the successes.
			ActorSession.announceUnattributed(connection, "an unknown address, at the "
					+ door.name().toLowerCase(java.util.Locale.ROOT) + " door");
			AuditLog.record(connection, AuditAction.LOGIN_FAILED);

			return new Attempt(null, Refusal.NO_SUCH_ACCOUNT, false);
		}

		ActorSession.announce(connection, ActorSession.Actor.ofAccount(
				stored.accountId, stored.displayName));

		if (stored.disabledAt != null) {
			AuditLog.record(connection, AuditAction.LOGIN_FAILED,
					AuditLog.detail("refused", "the account is disabled"));
			return new Attempt(null, Refusal.DISABLED, false);
		}

		if (stored.lockedUntil != null
				&& stored.lockedUntil.toInstant().isAfter(java.time.Instant.now())) {
			// Deliberately not checked against the password: an account that is locked is
			// locked, and verifying first would let an attacker learn whether their guess was
			// right by whether the refusal changed.
			AuditLog.record(connection, AuditAction.LOGIN_FAILED,
					AuditLog.detail("refused", "the account is locked",
							"until", stored.lockedUntil.toInstant().toString()));
			return new Attempt(null, Refusal.LOCKED, false);
		}

		if (!Passwords.encoder().matches(CharBuffer.wrap(password), stored.passwordHash)) {

			boolean nowLocked = recordFailure(connection, stored.accountId);

			AuditLog.record(connection, AuditAction.LOGIN_FAILED,
					AuditLog.detail("refused", "the password did not match"));

			// A separate entry, because "locked at 14:02 after five tries" is the line
			// somebody looks for, and finding it by counting LOGIN_FAILED entries is the kind
			// of reconstruction a log exists to make unnecessary.
			if (nowLocked) {
				AuditLog.record(connection, AuditAction.ACCOUNT_LOCKED,
						AuditLog.detail("after", FAILURES_BEFORE_LOCK + " consecutive "
								+ "failures or more"));
			}

			return new Attempt(null, Refusal.WRONG_PASSWORD, false);
		}

		boolean rehashed = false;

		if (Passwords.needsRehash(stored.passwordHash)) {
			// The one moment the plaintext is in hand and the hash is known to be weak.
			writeHash(connection, stored.accountId,
					Passwords.encoder().encode(CharBuffer.wrap(password)), stored.mustRotate);
			rehashed = true;
		}

		recordSuccess(connection, stored.accountId);

		AuditLog.record(connection, AuditAction.LOGIN, rehashed
				? AuditLog.detail("door", door.name(), "rehashed", true)
				: AuditLog.detail("door", door.name()));

		return new Attempt(new Principal(stored.accountId, stored.kind, stored.profId,
				stored.studentId, stored.displayName, stored.mustRotate), null, rehashed);
	}

	/**
	 * Replaces an account's password and clears the rotation flag.
	 * <p>
	 * Clears the failure counter and any lock as well: somebody who has just proved they can
	 * set the password is not mid-way through guessing it.
	 */
	public void setPassword(long accountId, @NonNull char[] password) throws SQLException {
		setPassword(accountId, password, null);
	}

	/**
	 * The same, recording who did it when that is not the owner of the account.
	 *
	 * @param actedBy the account performing the reset, for the audit entry. Null means the
	 *                owner is changing their own, which is the ordinary case
	 */
	public void setPassword(long accountId, @NonNull char[] password, @Nullable Long actedBy)
			throws SQLException {

		try (Connection connection = dataSource.getConnection()) {

			connection.setAutoCommit(false);

			try {
				writeHash(connection, accountId,
						Passwords.encoder().encode(CharBuffer.wrap(password)), false);

				try (PreparedStatement statement = connection.prepareStatement(
						"UPDATE app_account SET failed_logins = 0, locked_until = NULL "
								+ "WHERE account_id = ?")) {
					statement.setLong(1, accountId);
					statement.executeUpdate();
				}

				long actor = actedBy == null ? accountId : actedBy;

				ActorSession.announce(connection, ActorSession.Actor.ofAccount(
						actor, "account " + actor));

				// Which account's password, since a reset by somebody else is the interesting
				// case and the actor alone does not say whose it was.
				AuditLog.record(connection, AuditAction.PASSWORD_ROTATE, "app_account",
						AuditLog.detail("account_id", accountId),
						AuditLog.detail("reset_by_another", actedBy != null));

				connection.commit();

			} catch (SQLException | RuntimeException e) {
				connection.rollback();
				throw e;
			} finally {
				connection.setAutoCommit(true);
			}

		} finally {
			Arrays.fill(password, '\0');
		}
	}

	/**
	 * Creates an account. Returns its id.
	 * <p>
	 * {@code mustRotate} starts true, so whoever typed the first password is not also the
	 * person who ends up knowing it.
	 */
	public long create(@NonNull AccountKind kind, @Nullable Long profId,
	                   @Nullable Long studentId, @NonNull String displayName,
	                   @NonNull String loginEmail, @NonNull char[] password,
	                   @Nullable Long createdBy) throws SQLException {

		try (Connection connection = dataSource.getConnection()) {
			return create(connection, kind, profId, studentId, displayName, loginEmail,
					password, createdBy);
		}
	}

	/**
	 * The same, on a caller's own transaction.
	 * <p>
	 * For a caller that has more to do in the same unit — the bootstrap, which creates an
	 * account and grants it a role and must not be able to do only one of those.
	 */
	public static long create(@NonNull Connection connection, @NonNull AccountKind kind,
	                          @Nullable Long profId, @Nullable Long studentId,
	                          @NonNull String displayName, @NonNull String loginEmail,
	                          @NonNull char[] password, @Nullable Long createdBy)
			throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				     "INSERT INTO app_account (kind, prof_id, student_id, display_name, "
						     + "login_email, password_hash, must_rotate, created_by) "
						     + "VALUES (?::account_kind, ?, ?, ?, ?, ?, true, ?) "
						     + "RETURNING account_id")) {

			statement.setString(1, kind.name());
			setNullableLong(statement, 2, profId);
			setNullableLong(statement, 3, studentId);
			statement.setString(4, displayName);
			statement.setString(5, loginEmail);
			statement.setString(6, Passwords.encoder().encode(CharBuffer.wrap(password)));
			setNullableLong(statement, 7, createdBy);

			try (ResultSet results = statement.executeQuery()) {
				if (!results.next()) {
					throw new SQLException("no account id came back from the insert");
				}
				return results.getLong(1);
			}
		} finally {
			Arrays.fill(password, '\0');
		}
	}

	// ── Internals ────────────────────────────────────────────────────────

	/**
	 * A real Argon2id hash of a value nobody knows, used only to spend the same time on an
	 * unknown address as on a known one. Generated once at class load rather than written in,
	 * so it cannot be recognised from the source.
	 */
	private static final String DUMMY_HASH = Passwords.encoder()
			.encode(java.util.UUID.randomUUID().toString());

	private record Stored(long accountId, @NonNull AccountKind kind, @Nullable Long profId,
	                      @Nullable Long studentId, @NonNull String displayName,
	                      @NonNull String passwordHash, boolean mustRotate,
	                      @Nullable Timestamp lockedUntil, @Nullable Timestamp disabledAt) {
	}

	@Nullable
	private static Stored find(@NonNull Connection connection, @NonNull String email,
	                           @NonNull Door door) throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT account_id, kind, prof_id, student_id, display_name, password_hash, "
						+ "must_rotate, locked_until, disabled_at "
						+ "FROM app_account "
						+ "WHERE student_login = ? AND lower(login_email) = lower(?)")) {

			statement.setBoolean(1, door.studentLogin());
			statement.setString(2, email);

			try (ResultSet results = statement.executeQuery()) {

				if (!results.next()) {
					return null;
				}

				AccountKind kind = AccountKind.valueOf(results.getString("kind"));

				return new Stored(
						results.getLong("account_id"), kind,
						nullableLong(results, "prof_id"),
						nullableLong(results, "student_id"),
						results.getString("display_name"),
						results.getString("password_hash"),
						results.getBoolean("must_rotate"),
						results.getTimestamp("locked_until"),
						results.getTimestamp("disabled_at"));
			}
		}
	}

	/**
	 * Counts a failure and locks the account once there have been enough of them.
	 * <p>
	 * The window is computed in SQL so the count and the lock move in one statement; working
	 * it out in Java would mean reading the counter, deciding, and writing it back, with room
	 * between the two for a second attempt to land and the counter to go backwards.
	 */
	private static boolean recordFailure(@NonNull Connection connection, long accountId)
			throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				"UPDATE app_account SET failed_logins = failed_logins + 1, "
						+ "locked_until = CASE "
						+ "  WHEN failed_logins + 1 >= ? THEN now() + least("
						+ "      interval '1 minute' * power(2, failed_logins + 1 - ?),"
						+ "      interval '1 hour')"
						+ "  ELSE locked_until END, "
						+ "updated_at = now() "
						+ "WHERE account_id = ? "
						// Whether this failure is the one that locked it, read back from the
						// same statement rather than worked out in Java from a counter that
						// another attempt may already have moved.
						+ "RETURNING failed_logins >= ?")) {

			statement.setInt(1, FAILURES_BEFORE_LOCK);
			statement.setInt(2, FAILURES_BEFORE_LOCK);
			statement.setLong(3, accountId);
			statement.setInt(4, FAILURES_BEFORE_LOCK);

			try (ResultSet results = statement.executeQuery()) {
				return results.next() && results.getBoolean(1);
			}
		}
	}

	private static void recordSuccess(@NonNull Connection connection, long accountId)
			throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				"UPDATE app_account SET failed_logins = 0, locked_until = NULL, "
						+ "last_login_at = now() WHERE account_id = ?")) {

			statement.setLong(1, accountId);
			statement.executeUpdate();
		}
	}

	private static void writeHash(@NonNull Connection connection, long accountId,
	                              @NonNull String hash, boolean mustRotate) throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				"UPDATE app_account SET password_hash = ?, must_rotate = ?, updated_at = now() "
						+ "WHERE account_id = ?")) {

			statement.setString(1, hash);
			statement.setBoolean(2, mustRotate);
			statement.setLong(3, accountId);
			statement.executeUpdate();
		}
	}

	private static void setNullableLong(@NonNull PreparedStatement statement, int index,
	                                    @Nullable Long value) throws SQLException {
		if (value == null) {
			statement.setNull(index, java.sql.Types.BIGINT);
		} else {
			statement.setLong(index, value);
		}
	}

	@Nullable
	private static Long nullableLong(@NonNull ResultSet results, @NonNull String column)
			throws SQLException {
		long value = results.getLong(column);
		return results.wasNull() ? null : value;
	}
}
