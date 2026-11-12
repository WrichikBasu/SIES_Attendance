package in.basulabs.siesattendance.backend.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The one spelling of a roll number or a course code: ASCII letters and digits only, in upper
 * case. {@code cs-fy-001}, {@code CS/FY/001} and {@code CS FY 001} are all {@code CSFY001}.
 * <p>
 * Applied wherever one of these is written or looked up: the roster and attendance-sheet
 * parsers, both importers, the app's forms and DAOs, and the server's backup importer. The
 * server's V57 migration does the same in SQL, as {@code canonical_code()}, and a trigger
 * applies it to every row written to {@code student_year} and {@code courses}, so a phone on
 * an older build or anything else that bypasses this class is still stored canonically. The
 * two are kept in step by a test that runs both over the same inputs.
 * <p>
 * ASCII on purpose, not {@link Character#isLetterOrDigit}: that would let Devanagari digits and
 * full-width letters through, which look like the same roll number to a person and are not the
 * same to an index.
 */
public final class CanonicalCode {

	/** What a stored value matches, in full. The same expression as V57's CHECK constraints. */
	public static final Pattern STORED = Pattern.compile("[A-Z0-9]+");

	private static final Pattern DROPPED = Pattern.compile("[^A-Za-z0-9]");

	private CanonicalCode() {
	}

	/**
	 * The canonical spelling: every character but an ASCII letter or digit dropped, and the
	 * letters upper-cased. Empty when nothing is left, which no stored value may be; callers
	 * refuse that with their own message.
	 */
	@NonNull
	public static String of(@Nullable String raw) {
		if (raw == null) {
			return "";
		}
		return DROPPED.matcher(raw).replaceAll("").toUpperCase(Locale.ROOT);
	}

	/** Whether this is already in the stored spelling. */
	public static boolean isCanonical(@Nullable String value) {
		return value != null && STORED.matcher(value).matches();
	}
}
