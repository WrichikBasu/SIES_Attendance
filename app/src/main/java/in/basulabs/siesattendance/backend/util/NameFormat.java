package in.basulabs.siesattendance.backend.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Display formatting for people's names.
 * <p>
 * Names arrive from CSV imports in whatever case the college office typed them, which in
 * practice means a mix of ALL CAPS, all lowercase and correct case within the same file. These
 * helpers normalise for display only — the stored value is left exactly as entered, so nothing
 * is lost if the formatting guesses wrong.
 */
public final class NameFormat {

	private NameFormat() {
	}

	/**
	 * Capitalises the first letter of each part of a name and lowercases the rest.
	 * <p>
	 * Splits on spaces, hyphens and apostrophes, so "MARY-JANE D'SOUZA" becomes
	 * "Mary-Jane D'Souza" rather than "Mary-jane D'souza". The separators themselves are
	 * preserved, including runs of whitespace.
	 */
	@NonNull
	public static String properCase(@Nullable String raw) {

		if (raw == null) {
			return "";
		}

		// Runs of whitespace are collapsed as well as trimmed, since imported rosters often
		// carry stray double spaces between name parts.
		String trimmed = raw.trim().replaceAll("\\s+", " ");
		if (trimmed.isEmpty()) {
			return "";
		}

		StringBuilder out = new StringBuilder(trimmed.length());
		boolean startOfPart = true;

		for (int i = 0; i < trimmed.length(); i++) {
			char c = trimmed.charAt(i);

			if (isSeparator(c)) {
				out.append(c);
				startOfPart = true;
				continue;
			}

			out.append(startOfPart
					? Character.toUpperCase(c)
					: Character.toLowerCase(c));
			startOfPart = false;
		}

		return out.toString();
	}

	/**
	 * Two-letter initials from the first and last words of a name, for the avatar circles.
	 * A single-word name yields its first two letters.
	 * <p>
	 * Splitting on whitespace only, unlike {@link #properCase}, keeps a surname such as
	 * "D'Souza" whole: "Mary-Jane D'Souza" gives MD rather than MS.
	 */
	@NonNull
	public static String initials(@Nullable String raw) {

		String formatted = properCase(raw);
		if (formatted.isEmpty()) {
			return "?";
		}

		String[] parts = formatted.split("\\s+");
		if (parts.length == 0 || parts[0].isEmpty()) {
			return "?";
		}

		if (parts.length == 1) {
			// Punctuation is dropped first so a single-word surname like "O'Brien" gives OB
			// rather than the apostrophe.
			String letters = parts[0].replaceAll("[^\\p{L}]", "");
			if (letters.isEmpty()) {
				return "?";
			}
			return letters
					.substring(0, Math.min(2, letters.length()))
					.toUpperCase(Locale.getDefault());
		}

		String first = firstLetter(parts[0]);
		String last = firstLetter(parts[parts.length - 1]);

		if (first.isEmpty() && last.isEmpty()) {
			return "?";
		}

		return (first + last).toUpperCase(Locale.getDefault());
	}

	@NonNull
	private static String firstLetter(@NonNull String part) {
		for (int i = 0; i < part.length(); i++) {
			if (Character.isLetter(part.charAt(i))) {
				return String.valueOf(part.charAt(i));
			}
		}
		return "";
	}

	private static boolean isSeparator(char c) {
		return Character.isWhitespace(c) || c == '-' || c == '\'' || c == '.';
	}
}
