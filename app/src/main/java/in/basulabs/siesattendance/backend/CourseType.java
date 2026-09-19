package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

	/**
	 * Discipline specific elective. Chosen from the basket the student's own discipline offers,
	 * so it carries the same department condition as a major course: the roll is the students
	 * majoring in the offering department.
	 */
	DSE("DSE"),

	/** The laboratory half of a DSE, following the same department condition. */
	DSE_PRACTICALS("DSE practicals"),

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
	 * Whether this is a laboratory type, decided by the constant's own name.
	 * <p>
	 * Derived rather than declared per constant on purpose: the practical types are named for
	 * what they are, and a new one added later is a laboratory type the moment it is named like
	 * one, with nothing else to remember to update. That is the whole reason the "practicals"
	 * filter can promise to cover every kind of practical rather than the five that existed
	 * when it was written.
	 * <p>
	 * Matched on {@code PRACTICAL} rather than the full {@code _PRACTICALS} suffix so a
	 * singular or differently-decorated name still counts.
	 */
	public boolean isPractical() {
		return name().contains("PRACTICAL");
	}

	/**
	 * Whether this is the lecture half of a subject that is also taught in a laboratory, which
	 * is to say a type that has a {@code _PRACTICALS} counterpart of its own.
	 * <p>
	 * That is a narrower thing than "not a practical": {@link #OE}, {@link #AEC} and
	 * {@link #IKS} are taught as lectures too, but they are not one half of a split subject and
	 * so are not what is being asked for by "the theory". The pairing is read off the names for
	 * the same reason {@link #isPractical()} is — a subject split into a lecture and a lab
	 * later is covered the moment its laboratory type is named, with nothing else to update.
	 */
	public boolean isTheory() {

		if (isPractical()) {
			return false;
		}

		for (CourseType other : values()) {
			if (other.name().equals(name() + "_PRACTICALS")) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Every laboratory type, in declaration order. This is what the "practicals" shortcut on the
	 * report and the student breakdown selects, and it is computed rather than listed for the
	 * reason given on {@link #isPractical()}.
	 */
	@NonNull
	public static List<CourseType> practicals() {
		return matching(true);
	}

	/**
	 * Every lecture half of a split subject, in declaration order: the "theory" shortcut, and
	 * the exact complement of {@link #practicals()} among the paired types.
	 */
	@NonNull
	public static List<CourseType> theory() {
		return matching(false);
	}

	/** The paired types on one side or the other, which is all either shortcut is. */
	@NonNull
	private static List<CourseType> matching(boolean practical) {

		List<CourseType> out = new ArrayList<>();

		for (CourseType type : values()) {
			if (practical ? type.isPractical() : type.isTheory()) {
				out.add(type);
			}
		}
		return Collections.unmodifiableList(out);
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
