package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import in.basulabs.siesattendance.frontend.util.PasswordChange;

/**
 * The four questions the change-password form can answer without asking the server.
 * <p>
 * The dialog itself cannot be tested here — it needs a phone — so the rule it enforces lives
 * in a pure function and is held to it here. The order matters as much as the rules do: a
 * person who typed two different passwords should be told that, not told that the first one is
 * too short.
 */
public class PasswordChangeTest {

	private static final String GOOD = "a-decent-passphrase";

	@Test
	public void aProperChangeHasNothingWrongWithIt() {
		assertNull(PasswordChange.check("the-old-one", GOOD, GOOD));
	}

	@Test
	public void eachEmptyBoxIsNamedSeparately() {

		assertEquals(PasswordChange.Problem.NO_CURRENT,
				PasswordChange.check("", GOOD, GOOD));

		assertEquals(PasswordChange.Problem.NO_NEW,
				PasswordChange.check("the-old-one", "", ""));
	}

	@Test
	public void twoThatDisagreeAreReportedBeforeAnythingElse() {

		// Both too short and different. "They do not match" is the useful sentence, because
		// one of the two boxes has a typo in it and no rule about length will find which.
		assertEquals(PasswordChange.Problem.MISMATCH,
				PasswordChange.check("the-old-one", "short", "shorter"));
	}

	@Test
	public void theServersOwnTwoRulesAreCheckedHereToo() {

		assertEquals(PasswordChange.Problem.TOO_SHORT,
				PasswordChange.check("the-old-one", "elevenchar", "elevenchar"));

		String spaces = "              ";

		assertEquals(PasswordChange.Problem.ONLY_SPACES,
				PasswordChange.check("the-old-one", spaces, spaces));
	}

	@Test
	public void theMinimumIsTheServers() {

		// Passwords.MINIMUM_LENGTH on the server. The two are separate constants because the
		// phone cannot read the server's, so this is the one place the pair is pinned.
		assertEquals(12, PasswordChange.MINIMUM_LENGTH);

		String exactly = "123456789012";

		assertEquals(PasswordChange.MINIMUM_LENGTH, exactly.length());
		assertNull(PasswordChange.check("the-old-one", exactly, exactly));
	}

	@Test
	public void changingItToWhatItAlreadyIsIsRefusedHereAsWell() {

		// The server refuses this with unchanged_password. Catching it here saves a round trip
		// and, more to the point, saves somebody being told their new password was rejected
		// without being told that it is the one they are already using.
		assertEquals(PasswordChange.Problem.SAME_AS_CURRENT,
				PasswordChange.check(GOOD, GOOD, GOOD));
	}
}
