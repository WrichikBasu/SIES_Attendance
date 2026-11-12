package in.basulabs.siesattendance.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Duration;

/**
 * The limit on sign-in attempts from one address.
 * <p>
 * Tested here rather than over HTTP because every request in {@link WireTest} comes from
 * {@code 127.0.0.1}: a limit low enough to trip would trip during that class's own fixtures, and
 * raising it there is what lets the rest of the gate be about anything else.
 */
class LoginRateLimiterTest {

	private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);

	@Test
	void anAddressGetsItsAllowanceAndThenIsRefused() {

		LoginRateLimiter limiter = new LoginRateLimiter(3, FIVE_MINUTES);

		assertTrue(limiter.allow("198.51.100.7"));
		assertTrue(limiter.allow("198.51.100.7"));
		assertTrue(limiter.allow("198.51.100.7"));
		assertFalse(limiter.allow("198.51.100.7"), "the fourth of three is one too many");
	}

	@Test
	void oneAddressRunningOutDoesNotAffectAnother() {

		LoginRateLimiter limiter = new LoginRateLimiter(1, FIVE_MINUTES);

		assertTrue(limiter.allow("198.51.100.7"));
		assertFalse(limiter.allow("198.51.100.7"));

		// The whole point of counting per address: a college is not locked out because one
		// machine in it is misbehaving.
		assertTrue(limiter.allow("198.51.100.8"));
	}

	@Test
	void theAllowanceComesBackWhenTheWindowPasses() {

		// A window already over by the time the first attempt is counted, which is the same
		// thing as waiting it out and takes no time to test.
		LoginRateLimiter limiter = new LoginRateLimiter(1, Duration.ZERO);

		assertTrue(limiter.allow("198.51.100.7"));
		assertTrue(limiter.allow("198.51.100.7"),
				"an attempt older than the window is not an attempt in it");
	}

	@Test
	void aRequestWithNoAddressIsAllowed() {

		LoginRateLimiter limiter = new LoginRateLimiter(1, FIVE_MINUTES);

		assertTrue(limiter.allow(null));
		assertTrue(limiter.allow(null),
				"refusing for want of an address would mean one misconfigured proxy locks out "
						+ "the whole college");
	}

	@Test
	void anAllowanceOfZeroWouldRefuseEverybodyAndIsClamped() {

		// A configuration mistake -- or a zero from an unset environment variable -- must not
		// be a way to turn the login endpoint off.
		LoginRateLimiter limiter = new LoginRateLimiter(0, FIVE_MINUTES);

		assertTrue(limiter.allow("198.51.100.7"));
	}

	@Test
	void everyAttemptCountsAndNotOnlyTheFailures() {

		// The limiter never learns whether the password was right, by design: one that counted
		// only failures would let somebody with one valid account hammer the service freely.
		LoginRateLimiter limiter = new LoginRateLimiter(2, FIVE_MINUTES);

		assertTrue(limiter.allow("198.51.100.7"));
		assertTrue(limiter.allow("198.51.100.7"));
		assertFalse(limiter.allow("198.51.100.7"));
	}

	@Test
	void aLabSigningInFromOneAddressIsNotRefused() {

		// Sixty people behind the college's one public address, each signing in once (R4).
		LoginRateLimiter limiter = new LoginRateLimiter(10, FIVE_MINUTES);

		for (int student = 0; student < 60; student++) {
			assertTrue(limiter.allow("198.51.100.7", "student" + student + "@sies.edu.in"),
					"student " + student + " was refused for the others' sign-ins");
		}
	}

	@Test
	void oneAccountFromOneAddressIsStillHeldToTheAllowance() {

		LoginRateLimiter limiter = new LoginRateLimiter(3, FIVE_MINUTES);

		for (int attempt = 0; attempt < 3; attempt++) {
			assertTrue(limiter.allow("198.51.100.7", "a.cs@sies.edu.in"));
		}
		assertFalse(limiter.allow("198.51.100.7", "A.CS@sies.edu.in"),
				"the same account, whatever its case, successes included");
		assertTrue(limiter.allow("198.51.100.7", "b.chem@sies.edu.in"));
	}

	@Test
	void aListOfAccountsTriedFromOneAddressIsStopped() {

		LoginRateLimiter limiter = new LoginRateLimiter(2, FIVE_MINUTES);
		int allowed = 0;

		// Every attempt a different account, every one failing.
		for (int account = 0; account < 100; account++) {
			if (limiter.allow("198.51.100.7", "guess" + account + "@sies.edu.in")) {
				allowed++;
				limiter.failed("198.51.100.7");
			}
		}

		assertEquals(2 * LoginRateLimiter.FAILURES_PER_ALLOWANCE, allowed,
				"failures across accounts are counted per address");
		assertTrue(limiter.allow("198.51.100.8", "guess0@sies.edu.in"),
				"and another address is unaffected");
	}
}
