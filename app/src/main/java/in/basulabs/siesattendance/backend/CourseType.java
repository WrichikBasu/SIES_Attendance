package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * How a student is taking a course, which decides who is on its roll.
 * <p>
 * Constants are stored in the database by name, so a name may not be changed or removed once
 * courses exist that use it. The labels are display text and can change freely. New constants
 * are safe to add, but a constant that carries no department condition also has to be listed in
 * the roster query in {@code DaoStudent}, or courses of that type will find no students at all.
 */
public enum CourseType {

	/** Taken by students majoring in the offering department. */
	MAJOR("Major"),

	/**
	 * Taken as a secondary subject. The roll for a minor course also admits students whose
	 * major is the offering department, because the minor field on the student form is
	 * optional and often left blank; see the roster query in {@code DaoStudent} for why.
	 */
	MINOR("Minor"),

	/** The laboratory half of a major course, following the same department condition. */
	MAJOR_PRACTICALS("Major practicals"),

	/** The laboratory half of a minor course, following the same department condition. */
	MINOR_PRACTICALS("Minor practicals"),

	/** Open elective, matched against the student's declared OE department. */
	OE("OE"),

	/*
	 * The types below are taken by a whole year and stream regardless of department, so no
	 * department condition applies to them at all. They are the ones that have to be listed
	 * explicitly in the roster query.
	 */

	VSC("VSC"),
	VSC_PRACTICALS("VSC practicals"),
	SEC("SEC"),
	SEC_PRACTICALS("SEC practicals"),
	AEC("AEC"),
	IKS("IKS");

	@NonNull
	private final String label;

	CourseType(@NonNull String label) {
		this.label = label;
	}

	@NonNull
	@Override
	public String toString() {
		return label;
	}

	/**
	 * Resolves a course type from stored data or user input, accepting the constant name or the
	 * display label. Returns null when nothing matches.
	 */
	@Nullable
	public static CourseType fromAny(@Nullable String raw) {
		return EnumLookup.find(CourseType.class, raw);
	}
}
