package in.basulabs.siesattendance.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import in.basulabs.siesattendance.backend.sync.DeviceTables;
import in.basulabs.siesattendance.server.sync.DeviceValue;
import in.basulabs.siesattendance.server.web.LoginRateLimiter;

/**
 * Database-free white-box probes of the server's small pure pieces: the value converter that
 * both sync and the backup importer go through, the password rules, and the login limiter.
 */
class UnitEdgeProbeTest {

	// ── DeviceValue.convert ──────────────────────────────────────────────

	private static DeviceTables.Column column(String table, String room) {
		DeviceTables.Table described = DeviceTables.of(table);
		return described.column(room);
	}

	/**
	 * BUG: text where a number belongs is a ClassCastException, not an {@code Unconvertible}.
	 * Every caller turns {@code Unconvertible} into a rejected row with a reason; a
	 * ClassCastException escapes them, so sync answers 500 and an import aborts.
	 */
	@Test
	void textInAnIntegerColumnIsUnconvertible() {
		DeviceTables.Table lectures = DeviceTables.of("lectures");
		assertThrows(DeviceValue.Unconvertible.class,
				() -> DeviceValue.convert(lectures, column("lectures", "courseID"), "3001x"));
	}

	/** BUG: the same through the BOOLEAN branch. */
	@Test
	void textInABooleanColumnIsUnconvertible() {
		DeviceTables.Table attendance = DeviceTables.of("attendance");
		assertThrows(DeviceValue.Unconvertible.class,
				() -> DeviceValue.convert(attendance, column("attendance", "present"), "yes"));
	}

	/**
	 * A fractional number in a 0/1 flag is truncated: 0.9 becomes absent. Characterised; a
	 * release never writes it, but the converter does not object either.
	 */
	@Test
	void aFractionalFlagIsTruncated() {
		DeviceTables.Table attendance = DeviceTables.of("attendance");
		Object value = DeviceValue.convert(attendance, column("attendance", "present"), 0.9)
				.get(0).value();
		assertEquals(Boolean.FALSE, value);
	}

	@Test
	void anUnknownEnumConstantIsUnconvertible() {
		DeviceTables.Table courses = DeviceTables.of("courses");
		assertThrows(DeviceValue.Unconvertible.class,
				() -> DeviceValue.convert(courses, column("courses", "courseType"),
						"NOT_A_TYPE"));
	}

	// ── Passwords ────────────────────────────────────────────────────────

	/**
	 * The constants {@code needsRehash} compares against must describe the encoder actually in
	 * use, or every login would rewrite the hash (constants too high) or none ever would
	 * (constants too low).
	 */
	@Test
	void aFreshHashNeverNeedsRehashing() {
		String fresh = Passwords.encoder().encode("correct horse battery staple");
		assertFalse(Passwords.needsRehash(fresh), fresh);
	}

	@Test
	void malformedStoredHashesAskForARehashRatherThanThrowing() {
		assertTrue(Passwords.needsRehash(""));
		assertTrue(Passwords.needsRehash("$argon2id$v=19$m=,t=2,p=1$x$y"));
		assertTrue(Passwords.needsRehash("$argon2id$v=19$m=99999999999999,t=2,p=1$x$y"));
		assertTrue(Passwords.needsRehash("{bcrypt}$2a$10$abcdefghijklmnopqrstuv"));
	}

	@Test
	void theRuleIsLengthAndNotOnlySpaces() {
		assertNotNull(Passwords.tooWeak("elevenchars".toCharArray()));
		assertNotNull(Passwords.tooWeak("            ".toCharArray()));
		assertNotNull(Passwords.tooWeak("\t\t\t\t\t\t\n\n\n\n\n\n".toCharArray()));
		assertNull(Passwords.tooWeak("twelve chars".toCharArray()));
		// Characterised: eleven spaces and a letter is a legal password.
		assertNull(Passwords.tooWeak("           a".toCharArray()));
		// Non-breaking spaces are not whitespace to Character.isWhitespace.
		assertNull(Passwords.tooWeak(" ".repeat(12).toCharArray()));
	}

	@Test
	void aTemporaryPasswordIsReadableAndStrongEnough() {
		for (int run = 0; run < 200; run++) {
			char[] temporary = Passwords.temporary();
			String text = new String(temporary);

			assertEquals(19, temporary.length);
			assertTrue(text.matches("[a-hjkmnp-z2-9]{4}(-[a-hjkmnp-z2-9]{4}){3}"), text);
			assertNull(Passwords.tooWeak(temporary), text);
		}
	}

	// ── LoginRateLimiter ─────────────────────────────────────────────────

	@Test
	void theLimitHoldsForOneAddress() {
		LoginRateLimiter limiter = new LoginRateLimiter(3, Duration.ofMinutes(5));
		assertTrue(limiter.allow("10.0.0.1"));
		assertTrue(limiter.allow("10.0.0.1"));
		assertTrue(limiter.allow("10.0.0.1"));
		assertFalse(limiter.allow("10.0.0.1"));
	}

	/**
	 * Every IPv6 address is its own key. One client with an ordinary /64 has 2^64 of them, so
	 * the per-address allowance is no limit at all. Characterised and reported as hardening:
	 * the account lock still stands behind it.
	 */
	@Test
	void oneIpv6SubnetIsCountedAsManyAddresses() {
		LoginRateLimiter limiter = new LoginRateLimiter(3, Duration.ofMinutes(5));
		int allowed = 0;
		for (int host = 1; host <= 50; host++) {
			if (limiter.allow("2001:db8:0:1::" + Integer.toHexString(host))) {
				allowed++;
			}
		}
		assertEquals(50, allowed, "the /64 was limited after all");
	}

	/**
	 * Once 10,000 addresses are tracked, every new address is allowed without counting.
	 * Documented as the intended fail-open; characterised here so a change is noticed.
	 */
	@Test
	void aFullTableLetsNewAddressesThroughUncounted() {
		LoginRateLimiter limiter = new LoginRateLimiter(1, Duration.ofMinutes(5));
		for (int at = 0; at < 10_000; at++) {
			limiter.allow("fd00::" + Integer.toHexString(at));
		}
		for (int at = 0; at < 20; at++) {
			assertTrue(limiter.allow("192.0.2.77"), "attempt " + at);
		}
	}

	@Test
	void noAddressIsNeverLimited() {
		LoginRateLimiter limiter = new LoginRateLimiter(1, Duration.ofMinutes(5));
		for (int at = 0; at < 20; at++) {
			assertTrue(limiter.allow(null));
		}
	}
}
