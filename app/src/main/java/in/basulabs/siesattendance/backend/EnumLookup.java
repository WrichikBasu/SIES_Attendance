package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Resolves an enum constant from whatever form a string happens to be in.
 * <p>
 * Values reach the app three ways: as the constant name from the database, as the display label
 * from a dropdown, and as whatever a person typed into a CSV. Matching is therefore tried
 * against both the name and the label, exactly first and then loosely, with spaces, hyphens,
 * underscores and case ignored.
 */
public final class EnumLookup {

	private EnumLookup() {
	}

	/**
	 * Finds the constant a string refers to, or null when none of them do.
	 * <p>
	 * Both exact passes run before the loose one, over the whole set each time. Doing it the
	 * other way round would let a loose match on an early constant win over an exact match on a
	 * later one, which for {@code Stream} is the difference between "BSc" resolving to
	 * {@code BSc} and resolving to {@code BSc_unaided}.
	 *
	 * @param raw whitespace is trimmed; null and blank both yield null
	 */
	@Nullable
	public static <E extends Enum<E>> E find(@NonNull Class<E> type, @Nullable String raw) {

		if (raw == null || raw.trim().isEmpty()) {
			return null;
		}

		String trimmed = raw.trim();
		E[] constants = type.getEnumConstants();

		if (constants == null) {
			return null;
		}

		// Exact matches first: a constant name is unambiguous, a label may not be.
		for (E constant : constants) {
			if (constant.name().equals(trimmed)) {
				return constant;
			}
		}

		for (E constant : constants) {
			if (constant.toString().equals(trimmed)) {
				return constant;
			}
		}

		String loose = normalise(trimmed);

		for (E constant : constants) {
			if (normalise(constant.name()).equals(loose)
					|| normalise(constant.toString()).equals(loose)) {
				return constant;
			}
		}

		return null;
	}

	@NonNull
	private static String normalise(@NonNull String value) {
		// Brackets are stripped so the display label "BSc (unaided)" matches the constant name
		// BSc_unaided, and so a CSV written as "BSc unaided" matches both.
		return value.toLowerCase(Locale.ROOT)
				.replace("_", "")
				.replace("-", "")
				.replace(",", "")
				.replace("(", "")
				.replace(")", "")
				.replace(" ", "");
	}
}
