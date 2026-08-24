package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A degree stream.
 * <p>
 * The aided and unaided divisions are labelled distinctly, because a dropdown listing "BSc"
 * twice gives the user no way to tell which one they are choosing. {@link #baseLabel()} is the
 * label without that qualifier, and is what matching against outside data goes through: a CSV
 * saying "BSc" against a Computer Science student means the unaided stream, since that is the
 * only BSc that department runs.
 */
public enum Stream {

	BA("BA", "BA"),
	BSc("BSc", "BSc"),
	BSc_unaided("BSc (unaided)", "BSc"),
	BCom("BCom", "BCom"),
	BMS("BMS", "BMS"),
	BAMMC("BAMMC", "BAMMC"),

	MA("MA", "MA"),
	MSc("MSc", "MSc"),
	MSc_unaided("MSc (unaided)", "MSc");

	@NonNull
	private final String label;

	@NonNull
	private final String baseLabel;

	Stream(@NonNull String label, @NonNull String baseLabel) {
		this.label = label;
		this.baseLabel = baseLabel;
	}

	/**
	 * The degree without the aided or unaided qualifier: both BSc constants return "BSc".
	 * Used when resolving a stream named in outside data, where the qualifier is usually
	 * absent and the department implies it.
	 */
	@NonNull
	public String baseLabel() {
		return baseLabel;
	}

	@NonNull
	@Override
	public String toString() {
		return label;
	}

	/** True for the postgraduate streams. */
	public boolean isMasters() {
		return this == MA || this == MSc || this == MSc_unaided;
	}

	/**
	 * True for the divisions the college runs on an unaided basis. Only affects display: the
	 * qualifier is dropped from class labels, which are already long enough without it.
	 */
	public boolean isUnaided() {
		return this == BSc_unaided || this == MSc_unaided;
	}

	/**
	 * Resolves a stream from stored data or user input.
	 * <p>
	 * A bare "BSc" is ambiguous on its own and resolves to the aided constant. Use
	 * {@link AcademicRules#resolveRequested} when a department is known, since that picks the
	 * variant the department actually offers.
	 */
	@Nullable
	public static Stream fromAny(@Nullable String raw) {
		return EnumLookup.find(Stream.class, raw);
	}
}
