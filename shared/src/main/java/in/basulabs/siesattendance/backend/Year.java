package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A year of study within a degree.
 * <p>
 * The undergraduate years carry the names the college itself uses, FY, SY and TY, rather than
 * plain numbers, because those are what appear on a timetable and on a student's own record. The
 * postgraduate years restart the count instead of continuing it, so a master's student in their
 * second year is {@link #Masters_P2} and never {@link #TY}. {@code AcademicRules} relies on that
 * split through {@link #isMasters()} when it works out which years a stream can offer.
 * <p>
 * As with every enum persisted by this app, {@link #name()} is the stored form and must not
 * change once rows exist that use it. {@link #toString()} is display text and can be reworded
 * freely.
 */
public enum Year {

	FY("FY"),
	SY("SY"),
	TY("TY"),
	Masters_P1("Masters Part 1"),
	Masters_P2("Masters Part 2");

	@NonNull
	private final String label;

	Year(@NonNull String label) {
		this.label = label;
	}

	@NonNull
	@Override
	public String toString() {
		return label;
	}

	/**
	 * True for the two postgraduate years.
	 * <p>
	 * Used throughout the academic rules to keep undergraduate and postgraduate values from
	 * being paired with each other, since the two sets of years are otherwise interchangeable
	 * as far as the type system is concerned.
	 */
	public boolean isMasters() {
		return this == Masters_P1 || this == Masters_P2;
	}

	/**
	 * Resolves a year from stored data or user input, accepting either the constant name or the
	 * display label, with case and spacing ignored. Returns null when nothing matches, which is
	 * how an unrecognised value in an imported file is reported.
	 */
	@Nullable
	public static Year fromAny(@Nullable String raw) {
		return EnumLookup.find(Year.class, raw);
	}
}
