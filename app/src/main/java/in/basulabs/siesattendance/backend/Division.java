package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A division within a class.
 * <p>
 * Some classes are too large to teach in one room, so the college splits them into divisions
 * lettered from A. Only the aided BSc and BA do this; every other stream runs a single
 * undivided class, which is why a division is optional everywhere it appears. Which streams
 * are divided is stated once, on {@link Stream#hasDivisions()}.
 * <p>
 * As with every enum persisted by this app, {@link #name()} is the stored form and must not
 * change once rows exist that use it.
 */
public enum Division {

	A,
	B,
	C,
	D;

	/**
	 * The division as it is written on a timetable, so "Div B" rather than a bare letter. Used
	 * where the division appears next to other text and would otherwise read as an initial.
	 */
	@NonNull
	public String longLabel() {
		return "Div " + name();
	}

	/**
	 * Resolves a division from stored data or user input.
	 * <p>
	 * A roster is as likely to say "Div B" or "Division B" as a bare "B", so the prefix is
	 * dropped before the letter is matched. Returns null when nothing matches, which is how an
	 * unrecognised value in an imported file is reported.
	 */
	@Nullable
	public static Division fromAny(@Nullable String raw) {

		if (raw == null) {
			return null;
		}

		String trimmed = raw.trim();

		// Longest prefix first: stripping "div" from "division b" would leave "ision b".
		for (String prefix : new String[]{"division", "div.", "div"}) {
			if (trimmed.length() > prefix.length()
					&& trimmed.substring(0, prefix.length()).equalsIgnoreCase(prefix)) {
				trimmed = trimmed.substring(prefix.length()).trim();
				break;
			}
		}

		return EnumLookup.find(Division.class, trimmed);
	}
}
