package in.basulabs.siesattendance.backend.util;

import androidx.annotation.NonNull;

import java.time.LocalDate;

/**
 * The years a date in a file can be about.
 * <p>
 * A roster's join and leave dates and an attendance sheet's class dates are typed by people,
 * and the parsers took whatever {@code java.time} would: {@code 0025-06-15}, a two-digit year
 * padded out, was stored as the year 25, and {@code +12345-06-15} as a year in the future no
 * session will ever cover (R21). Neither is a date anybody joined or held a class on; both are
 * typos, and a typo stored is a student counted from the wrong day or a class no report can
 * find.
 * <p>
 * The range is wide on purpose: the college's records begin well after 2000, and nothing in
 * this app will still be filing attendance in 2100. A date outside it is refused, with the line,
 * rather than corrected: guessing that 25 meant 2025 would be right most of the time and silently
 * wrong the rest.
 */
public final class PlausibleDates {

	/** The first year a date may fall in. */
	public static final int FIRST_YEAR = 2000;

	/** The last year a date may fall in. */
	public static final int LAST_YEAR = 2099;

	private PlausibleDates() {
	}

	/** Whether the date falls in a year this app can be about. */
	public static boolean plausible(@NonNull LocalDate date) {
		return date.getYear() >= FIRST_YEAR && date.getYear() <= LAST_YEAR;
	}

	/** The sentence for a date that is not, naming what it was meant to be. */
	@NonNull
	public static String refusal(@NonNull String what, @NonNull String asWritten) {
		return "the " + what + " \"" + asWritten + "\" is not in the years " + FIRST_YEAR
				+ " to " + LAST_YEAR + "; write the year in full, as 2026";
	}
}
