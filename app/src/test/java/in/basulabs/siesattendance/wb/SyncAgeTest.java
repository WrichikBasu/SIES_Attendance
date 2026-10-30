package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import in.basulabs.siesattendance.frontend.util.SyncAge;

/**
 * "When did this phone last hear from the server", in the units somebody reads.
 * <p>
 * The sync screen reports two moments and a raw millisecond count is not a report. The buckets
 * are here, the words are in {@code strings.xml}, and the one case worth stating out loud is the
 * clock going backwards — which happens for real on a phone that changes time zone or takes an
 * NTP correction mid-sync, and which must never produce "in -3 minutes".
 */
public class SyncAgeTest {

	private static final long NOW = 1_780_000_000_000L;

	private static final long MINUTE = 60_000L;

	private static final long HOUR = 60 * MINUTE;

	private static final long DAY = 24 * HOUR;

	private static SyncAge.Age at(long millisAgo) {
		return SyncAge.of(NOW - millisAgo, NOW);
	}

	@Test
	public void somethingThatNeverHappenedSaysSo() {
		assertEquals(SyncAge.Scale.NEVER, SyncAge.of(0, NOW).scale());
		assertEquals(SyncAge.Scale.NEVER, SyncAge.of(-1, NOW).scale());
	}

	@Test
	public void underAMinuteIsAMomentAndCarriesNoNumber() {

		assertEquals(SyncAge.Scale.MOMENTS, at(0).scale());
		assertEquals(SyncAge.Scale.MOMENTS, at(59_999L).scale());
		assertEquals(0, at(30_000L).quantity());
	}

	@Test
	public void theBoundariesFallOnTheRightSide() {

		assertEquals(new SyncAge.Age(SyncAge.Scale.MINUTES, 1), at(MINUTE));
		assertEquals(new SyncAge.Age(SyncAge.Scale.MINUTES, 59), at(59 * MINUTE + 59_000L));
		assertEquals(new SyncAge.Age(SyncAge.Scale.HOURS, 1), at(HOUR));
		assertEquals(new SyncAge.Age(SyncAge.Scale.HOURS, 23), at(DAY - MINUTE));
		assertEquals(new SyncAge.Age(SyncAge.Scale.DAYS, 1), at(DAY));
		assertEquals(new SyncAge.Age(SyncAge.Scale.DAYS, 9), at(9 * DAY + 3 * HOUR));
	}

	@Test
	public void aTimestampInTheFutureReadsAsAMomentAgo() {

		// A phone that changed time zone, or an NTP correction between the pull and the
		// screen. "In -3 minutes" would be the alternative.
		assertEquals(SyncAge.Scale.MOMENTS, SyncAge.of(NOW + 5 * MINUTE, NOW).scale());
	}
}
