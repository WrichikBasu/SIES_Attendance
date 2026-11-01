package in.basulabs.siesattendance.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import in.basulabs.siesattendance.backend.AccountKind;
import in.basulabs.siesattendance.server.testing.TestPostgres;

/**
 * The credential half of Phase 4, against a real database.
 * <p>
 * Against a real one rather than a stub because half of what is being tested is SQL: the
 * case-insensitive lookup, the uniqueness that is per door rather than global, and a failure
 * counter that has to move in the same statement as the lock it triggers.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountDirectoryTest {

	private static TestPostgres postgres;

	private AccountDirectory accounts;

	@BeforeAll
	void startDatabase() throws Exception {

		assumeTrue(TestPostgres.isAvailable(), "Docker is not available");
		postgres = TestPostgres.start();
	}

	@AfterAll
	void stopDatabase() {
		if (postgres != null) {
			postgres.close();
		}
	}

	@BeforeEach
	void emptyTheDatabase() throws Exception {

		assumeTrue(postgres != null, "no database");

		postgres.reset();
		accounts = new AccountDirectory(postgres.dataSource());

		postgres.asSuperuser("""
				INSERT INTO professors (prof_id, name, email, department, designation)
				    VALUES (1001, 'A RAO', 'a.rao@sies.edu.in', 'COMPUTER_SCIENCE', 'Professor');
				INSERT INTO students (student_id, name, pronouns, email)
				    VALUES (2001, 'R RAO', 'she/her', 'r.rao@sies.edu.in');
				""");
	}

	// ── Signing in ───────────────────────────────────────────────────────

	@Test
	void theRightPasswordIsAccepted() throws Exception {

		long account = accounts.create(AccountKind.PROFESSOR, 1001L, null, "A RAO",
				"a.rao@sies.edu.in", "correct horse battery".toCharArray(), null);

		AccountDirectory.Attempt attempt = accounts.authenticate("a.rao@sies.edu.in",
				AccountDirectory.Door.STAFF, "correct horse battery".toCharArray());

		assertTrue(attempt.succeeded(), () -> "refused: " + attempt.refusal());
		assertEquals(account, attempt.principal().accountId());
		assertEquals(AccountKind.PROFESSOR, attempt.principal().kind());
		assertEquals(1001L, attempt.principal().profId());

		// A new account must change its password before it is of any use.
		assertTrue(attempt.principal().mustRotate(),
				"a freshly created account must be made to rotate, so that whoever typed the "
						+ "first password is not also the person who ends up knowing it");
	}

	@Test
	void anAdministratorWithNobodyToAskIsResetFromTheShell() throws Exception {

		long admin = accounts.create(AccountKind.STAFF, null, null, "AN ADMIN",
				"admin@sies.edu.in", "correct horse battery".toCharArray(), null);
		accounts.create(AccountKind.PROFESSOR, 1001L, null, "A RAO",
				"a.rao@sies.edu.in", "correct horse battery".toCharArray(), null);

		postgres.asSuperuser("INSERT INTO user_role (account_id, role, granted_by) VALUES ("
				+ admin + ", 'ADMIN', " + admin + ")");
		postgres.asSuperuser("UPDATE app_account SET must_rotate = false, failed_logins = 9, "
				+ "locked_until = now() + interval '1 hour' WHERE account_id = " + admin);

		BootstrapCommand bootstrap = new BootstrapCommand(postgres.dataSource());

		assertEquals(admin, bootstrap.resetAdministrator("ADMIN@sies.edu.in",
				"a temporary one for today".toCharArray()));

		AccountDirectory.Attempt attempt = accounts.authenticate("admin@sies.edu.in",
				AccountDirectory.Door.STAFF, "a temporary one for today".toCharArray());

		assertTrue(attempt.succeeded(), () -> "refused: " + attempt.refusal());
		assertTrue(attempt.principal().mustRotate(), "a reset password is good for one sign-in");

		// Nobody but an administrator: the rest are reset from the console, with an author.
		assertThrows(IllegalStateException.class, () -> bootstrap.resetAdministrator(
				"a.rao@sies.edu.in", "a temporary one for today".toCharArray()));
		assertThrows(IllegalArgumentException.class, () -> bootstrap.resetAdministrator(
				"admin@sies.edu.in", "short".toCharArray()));
		assertTrue(accounts.authenticate("a.rao@sies.edu.in", AccountDirectory.Door.STAFF,
				"correct horse battery".toCharArray()).succeeded());
	}

	@Test
	void theWrongPasswordIsRefusedAndCounted() throws Exception {

		long account = accounts.create(AccountKind.PROFESSOR, 1001L, null, "A RAO",
				"a.rao@sies.edu.in", "correct horse battery".toCharArray(), null);

		AccountDirectory.Attempt attempt = accounts.authenticate("a.rao@sies.edu.in",
				AccountDirectory.Door.STAFF, "incorrect horse battery".toCharArray());

		assertFalse(attempt.succeeded());
		assertEquals(AccountDirectory.Refusal.WRONG_PASSWORD, attempt.refusal());
		assertEquals(1, failedLogins(account));
	}

	@Test
	void anUnknownAddressIsRefusedWithoutSayingSoToThePerson() throws Exception {

		AccountDirectory.Attempt attempt = accounts.authenticate("nobody@sies.edu.in",
				AccountDirectory.Door.STAFF, "anything".toCharArray());

		assertFalse(attempt.succeeded());

		// The distinction exists for the server's own log. An endpoint must collapse this and
		// WRONG_PASSWORD into one message, or the login box becomes a directory of the college.
		assertEquals(AccountDirectory.Refusal.NO_SUCH_ACCOUNT, attempt.refusal());
	}

	@Test
	void theAddressIsMatchedWithoutRegardToCase() throws Exception {

		accounts.create(AccountKind.PROFESSOR, 1001L, null, "A RAO",
				"A.Rao@SIES.edu.in", "correct horse battery".toCharArray(), null);

		// Stored mixed, typed lower. Somebody entering their own address will not reproduce
		// its capitalisation.
		assertTrue(accounts.authenticate("a.rao@sies.edu.in", AccountDirectory.Door.STAFF,
				"correct horse battery".toCharArray()).succeeded());

		assertTrue(accounts.authenticate("A.RAO@SIES.EDU.IN", AccountDirectory.Door.STAFF,
				"correct horse battery".toCharArray()).succeeded());
	}

	// ── The two doors ────────────────────────────────────────────────────

	@Test
	void oneAddressMayHoldBothAStudentAndAStaffAccount() throws Exception {

		// The research assistant finishing an MSc. Uniqueness is per door, so this is two
		// accounts and not a collision.
		long staff = accounts.create(AccountKind.PROFESSOR, 1001L, null, "A RAO",
				"shared@sies.edu.in", "staff password".toCharArray(), null);
		long student = accounts.create(AccountKind.STUDENT, null, 2001L, "R RAO",
				"shared@sies.edu.in", "student password".toCharArray(), null);

		assertNotEquals(staff, student);

		// Each door finds its own account, and only its own password works there.
		AccountDirectory.Attempt atStaff = accounts.authenticate("shared@sies.edu.in",
				AccountDirectory.Door.STAFF, "staff password".toCharArray());
		assertTrue(atStaff.succeeded());
		assertEquals(staff, atStaff.principal().accountId());

		AccountDirectory.Attempt atStudent = accounts.authenticate("shared@sies.edu.in",
				AccountDirectory.Door.STUDENT, "student password".toCharArray());
		assertTrue(atStudent.succeeded());
		assertEquals(student, atStudent.principal().accountId());

		assertFalse(accounts.authenticate("shared@sies.edu.in",
				AccountDirectory.Door.STUDENT, "staff password".toCharArray()).succeeded(),
				"the staff password must not open the student door");
	}

	@Test
	void theSameAddressTwiceAtOneDoorIsRefused() throws Exception {

		accounts.create(AccountKind.PROFESSOR, 1001L, null, "A RAO",
				"a.rao@sies.edu.in", "first".toCharArray(), null);

		assertThrows(SQLException.class, () -> accounts.create(AccountKind.STAFF, null, null,
				"An impostor", "A.RAO@sies.edu.in", "second".toCharArray(), null),
				"app_account_login_uq is on lower(login_email), so differing case is the "
						+ "same address");
	}

	@Test
	void aStudentAccountCannotPointAtAProfessor() throws Exception {

		// app_account_exactly_one_person. The kind decides which column is filled, and a row
		// that disagrees with its own kind is refused by the database rather than by whichever
		// code path remembered to look.
		assertThrows(SQLException.class, () -> accounts.create(AccountKind.STUDENT, 1001L,
				null, "Confused", "confused@sies.edu.in", "password".toCharArray(), null));

		assertThrows(SQLException.class, () -> accounts.create(AccountKind.STAFF, 1001L,
				null, "Also confused", "also@sies.edu.in", "password".toCharArray(), null));
	}

	// ── Lockout ──────────────────────────────────────────────────────────

	@Test
	void fiveWrongGuessesLockTheAccountAndTheRightPasswordThenFails() throws Exception {

		long account = accounts.create(AccountKind.PROFESSOR, 1001L, null, "A RAO",
				"a.rao@sies.edu.in", "correct horse battery".toCharArray(), null);

		for (int attempt = 1; attempt <= 4; attempt++) {
			assertEquals(AccountDirectory.Refusal.WRONG_PASSWORD,
					accounts.authenticate("a.rao@sies.edu.in", AccountDirectory.Door.STAFF,
							"wrong".toCharArray()).refusal());
			assertFalse(isLocked(account), "not locked yet after " + attempt + " failures");
		}

		assertEquals(AccountDirectory.Refusal.WRONG_PASSWORD,
				accounts.authenticate("a.rao@sies.edu.in", AccountDirectory.Door.STAFF,
						"wrong".toCharArray()).refusal());
		assertTrue(isLocked(account), "the fifth failure should lock the account");

		// And now even the right password is refused, with LOCKED rather than WRONG_PASSWORD.
		// Checked before the password on purpose: verifying first would let an attacker read
		// whether their guess was right off which refusal came back.
		assertEquals(AccountDirectory.Refusal.LOCKED,
				accounts.authenticate("a.rao@sies.edu.in", AccountDirectory.Door.STAFF,
						"correct horse battery".toCharArray()).refusal());
	}

	@Test
	void aLockThatHasExpiredLetsTheRightPasswordThrough() throws Exception {

		long account = accounts.create(AccountKind.PROFESSOR, 1001L, null, "A RAO",
				"a.rao@sies.edu.in", "correct horse battery".toCharArray(), null);

		postgres.asSuperuser("UPDATE app_account SET failed_logins = 5, "
				+ "locked_until = now() - interval '1 minute' WHERE account_id = " + account);

		AccountDirectory.Attempt attempt = accounts.authenticate("a.rao@sies.edu.in",
				AccountDirectory.Door.STAFF, "correct horse battery".toCharArray());

		assertTrue(attempt.succeeded(), () -> "refused: " + attempt.refusal());

		// A success clears the counter and the stale lock together, which is why no CHECK ties
		// the two columns: resetting one without the other is an ordinary state to pass
		// through.
		assertEquals(0, failedLogins(account));
		assertFalse(isLocked(account));
	}

	@Test
	void aSuccessfulLoginClearsTheCounter() throws Exception {

		long account = accounts.create(AccountKind.PROFESSOR, 1001L, null, "A RAO",
				"a.rao@sies.edu.in", "correct horse battery".toCharArray(), null);

		accounts.authenticate("a.rao@sies.edu.in", AccountDirectory.Door.STAFF,
				"wrong".toCharArray());
		assertEquals(1, failedLogins(account));

		assertTrue(accounts.authenticate("a.rao@sies.edu.in", AccountDirectory.Door.STAFF,
				"correct horse battery".toCharArray()).succeeded());
		assertEquals(0, failedLogins(account),
				"four wrong guesses spread over a term must not add up to a lockout");
	}

	@Test
	void aDisabledAccountIsRefusedWhateverThePassword() throws Exception {

		long account = accounts.create(AccountKind.PROFESSOR, 1001L, null, "A RAO",
				"a.rao@sies.edu.in", "correct horse battery".toCharArray(), null);

		postgres.asSuperuser("UPDATE app_account SET disabled_at = now() "
				+ "WHERE account_id = " + account);

		assertEquals(AccountDirectory.Refusal.DISABLED,
				accounts.authenticate("a.rao@sies.edu.in", AccountDirectory.Door.STAFF,
						"correct horse battery".toCharArray()).refusal());
	}

	// ── Rotation ─────────────────────────────────────────────────────────

	@Test
	void settingAPasswordClearsTheRotationFlagAndAnyLock() throws Exception {

		long account = accounts.create(AccountKind.PROFESSOR, 1001L, null, "A RAO",
				"a.rao@sies.edu.in", "issued by the office".toCharArray(), null);

		postgres.asSuperuser("UPDATE app_account SET failed_logins = 7, "
				+ "locked_until = now() + interval '1 hour' WHERE account_id = " + account);

		accounts.setPassword(account, "chosen by me".toCharArray());

		assertEquals(0, failedLogins(account));
		assertFalse(isLocked(account),
				"somebody who has just set the password is not mid-way through guessing it");

		AccountDirectory.Attempt attempt = accounts.authenticate("a.rao@sies.edu.in",
				AccountDirectory.Door.STAFF, "chosen by me".toCharArray());

		assertTrue(attempt.succeeded());
		assertFalse(attempt.principal().mustRotate());

		assertFalse(accounts.authenticate("a.rao@sies.edu.in", AccountDirectory.Door.STAFF,
				"issued by the office".toCharArray()).succeeded(),
				"the old password must stop working");
	}

	@Test
	void aHashMadeWithWeakerParametersIsReplacedOnTheNextLogin() throws Exception {

		long account = accounts.create(AccountKind.PROFESSOR, 1001L, null, "A RAO",
				"a.rao@sies.edu.in", "correct horse battery".toCharArray(), null);

		String current = passwordHash(account);

		// Rewrite the recorded parameters to something weaker than today's. The hash no longer
		// verifies, which is fine: what is being tested is that needsRehash notices, and a
		// rehash only happens after the password has already matched.
		assertTrue(Passwords.needsRehash(current.replace("m=16384", "m=4096")),
				"a hash recording less memory than the current parameters must be replaced");
		assertFalse(Passwords.needsRehash(current),
				"a hash made to the current parameters must be left alone");

		// And end to end: a password that still verifies against a weak hash is re-encoded.
		String weak = org.springframework.security.crypto.argon2.Argon2PasswordEncoder
				.defaultsForSpringSecurity_v5_2().encode("correct horse battery");

		postgres.asSuperuser("UPDATE app_account SET password_hash = '" + weak
				+ "' WHERE account_id = " + account);

		AccountDirectory.Attempt attempt = accounts.authenticate("a.rao@sies.edu.in",
				AccountDirectory.Door.STAFF, "correct horse battery".toCharArray());

		assertTrue(attempt.succeeded(), () -> "refused: " + attempt.refusal());
		assertTrue(attempt.rehashed(), "the weak hash should have been replaced");
		assertNotEquals(weak, passwordHash(account));
		assertFalse(Passwords.needsRehash(passwordHash(account)));

		// Replacing the hash must not quietly clear a pending rotation.
		assertTrue(attempt.principal().mustRotate());
	}

	// ── The actor plumbing ───────────────────────────────────────────────

	@Test
	void announcingAnActorOnAnAutoCommitConnectionIsRefused() throws Exception {

		long account = accounts.create(AccountKind.PROFESSOR, 1001L, null, "A RAO",
				"a.rao@sies.edu.in", "correct horse battery".toCharArray(), null);

		try (Connection connection = postgres.appConnection()) {

			assertTrue(connection.getAutoCommit(), "a fresh connection auto-commits");

			// Rather than appearing to work: the setting is transaction-scoped, so on an
			// auto-commit connection it would be gone before the next statement, and every
			// policy would match nothing.
			assertThrows(IllegalStateException.class,
					() -> ActorSession.announce(connection, account, 1001L, null));
		}
	}

	@Test
	void anAnnouncedActorIsVisibleToTheDatabaseAndGoneAfterTheTransaction() throws Exception {

		long account = accounts.create(AccountKind.PROFESSOR, 1001L, null, "A RAO",
				"a.rao@sies.edu.in", "correct horse battery".toCharArray(), null);

		try (Connection connection = postgres.appConnection()) {

			connection.setAutoCommit(false);
			ActorSession.announce(connection, account, 1001L, null);

			assertEquals(account, scalar(connection, "SELECT current_account_id()"));
			assertEquals(1001L, scalar(connection, "SELECT current_prof_id()"));
			assertEquals(0L, scalar(connection, "SELECT coalesce(current_student_id(), 0)"),
					"the student setting is written empty, which reads back as NULL");

			connection.commit();

			// The point of transaction scope: nothing survives into the next transaction on
			// the same connection, so a pooled one cannot carry an identity across requests.
			assertEquals(0L, scalar(connection, "SELECT coalesce(current_account_id(), 0)"),
					"the actor must not outlive its transaction");
		}
	}

	// ── Helpers ──────────────────────────────────────────────────────────

	private int failedLogins(long account) throws SQLException {
		return (int) scalarAsSuperuser(
				"SELECT failed_logins FROM app_account WHERE account_id = " + account);
	}

	private boolean isLocked(long account) throws SQLException {
		return scalarAsSuperuser("SELECT (locked_until IS NOT NULL "
				+ "AND locked_until > now())::int FROM app_account WHERE account_id = "
				+ account) == 1;
	}

	private String passwordHash(long account) throws SQLException {

		try (Connection connection = postgres.connection();
		     PreparedStatement statement = connection.prepareStatement(
				     "SELECT password_hash FROM app_account WHERE account_id = ?")) {

			statement.setLong(1, account);

			try (ResultSet results = statement.executeQuery()) {
				assertTrue(results.next(), "no such account");
				return results.getString(1);
			}
		}
	}

	private long scalarAsSuperuser(String sql) throws SQLException {
		try (Connection connection = postgres.connection()) {
			return scalar(connection, sql);
		}
	}

	private static long scalar(Connection connection, String sql) throws SQLException {

		try (java.sql.Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(sql)) {

			assertTrue(results.next(), () -> "no row from: " + sql);
			return results.getLong(1);
		}
	}
}
