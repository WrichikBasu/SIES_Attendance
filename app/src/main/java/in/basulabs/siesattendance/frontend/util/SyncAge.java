package in.basulabs.siesattendance.frontend.util;

import androidx.annotation.NonNull;

/**
 * How long ago something happened, in the only units a person reads it in.
 * <p>
 * The sync screen reports two moments — when this phone last sent and when it last received —
 * and "1761000000000" is not a report. An exact clock time is barely better: what somebody
 * standing in a corridor wants to know is whether this happened a moment ago or last week.
 * <p>
 * Pure, and deliberately returning a bucket rather than a string: the words are in
 * {@code strings.xml} where they can be translated and plural-formed, and the arithmetic is here
 * where it can be tested. The screen is the only thing that knows which words go with which
 * bucket.
 */
public final class SyncAge {

	/** Which bucket a moment falls in. */
	public enum Scale {

		/** It has not happened. */
		NEVER,

		/** Under a minute: no number is worth printing. */
		MOMENTS,

		MINUTES,

		HOURS,

		DAYS
	}

	/**
	 * A bucket and the number that goes with it.
	 *
	 * @param quantity how many of that unit, or 0 for {@link Scale#NEVER} and
	 *                 {@link Scale#MOMENTS}
	 */
	public record Age(@NonNull Scale scale, int quantity) {
	}

	private static final long SECOND = 1000L;

	private static final long MINUTE = 60 * SECOND;

	private static final long HOUR = 60 * MINUTE;

	private static final long DAY = 24 * HOUR;

	private SyncAge() {
	}

	/**
	 * @param at  when it happened, in milliseconds, or 0 if it never did
	 * @param now the current time, passed in rather than read, so this is testable and so a
	 *            screen cannot disagree with itself between two lines of the same report
	 */
	@NonNull
	public static Age of(long at, long now) {

		if (at <= 0) {
			return new Age(Scale.NEVER, 0);
		}

		long since = now - at;

		if (since < 0) {
			// The clock moved backwards, or the row was written by a device whose clock is
			// ahead: a phone that has just changed time zone, or an NTP correction mid-sync.
			// "A moment ago" is the only honest reading of a future timestamp, and it is
			// certainly better than "in -3 minutes".
			return new Age(Scale.MOMENTS, 0);
		}

		if (since < MINUTE) {
			return new Age(Scale.MOMENTS, 0);
		}

		if (since < HOUR) {
			return new Age(Scale.MINUTES, (int) (since / MINUTE));
		}

		if (since < DAY) {
			return new Age(Scale.HOURS, (int) (since / HOUR));
		}

		return new Age(Scale.DAYS, (int) (since / DAY));
	}
}
