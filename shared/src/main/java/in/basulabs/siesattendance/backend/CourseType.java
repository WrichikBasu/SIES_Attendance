package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * How a student is taking a course. A course's roll is its batches, whatever its type; the
 * type decides whether its stream has to be one its offering department runs (see
 * {@link #followsOfferingDepartment()}), and what the reports group it under.
 * <p>
 * Constants are stored in the database by name, so a name may not be changed or removed once
 * courses exist that use it. The labels are display text and can change freely. A new constant
 * is appended, never inserted, because the server's {@code course_type} enum can only grow at
 * the end, and needs a migration adding it there.
 */
public enum CourseType {

	/** Taken by students majoring in the offering department. */
	MAJOR("Major", true),

	/** Taken as a secondary subject, usually taught by another department. */
	MINOR("Minor", false),

	/** The laboratory half of a major course, following the same department condition. */
	MAJOR_PRACTICALS("Major practicals", true),

	/** The laboratory half of a minor course, following the same department condition. */
	MINOR_PRACTICALS("Minor practicals", false),

	/**
	 * Discipline specific elective. Chosen from the basket the student's own discipline offers,
	 * so its students are the offering department's own majors, as for a major course.
	 */
	DSE("DSE", true),

	/** The laboratory half of a DSE, following the same department condition. */
	DSE_PRACTICALS("DSE practicals", true),

	/** Open elective, taken from another department's offering. */
	OE("OE", false),

	/*
	 * The types below are taken by a whole year and stream regardless of department. Their
	 * offering department records who teaches them and is not the students' own, which is why
	 * none of them follows it.
	 */

	VSC("VSC", false),
	VSC_PRACTICALS("VSC practicals", false),
	SEC("SEC", false),
	SEC_PRACTICALS("SEC practicals", false),
	AEC("AEC", false),
	IKS("IKS", false),

	/** Value education course, taken by the whole year and stream like AEC and IKS. */
	VEC("VEC", false);

	@NonNull
	private final String label;

	private final boolean ownDepartment;

	CourseType(@NonNull String label, boolean ownDepartment) {
		this.label = label;
		this.ownDepartment = ownDepartment;
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
	 * Whether the offering department is necessarily the student's own major department.
	 * <p>
	 * True for a major, a DSE and the laboratory halves of both: they are taken by the offering
	 * department's own majors, so the department teaching the course is by definition one the
	 * student's own stream is studied under.
	 * <p>
	 * False for everything else, for two different reasons. A minor and an open elective are
	 * deliberately taught by another department. AEC, IKS, VEC, VSC and SEC are taken by the
	 * whole year and stream, so their offering department only records who teaches them.
	 * <p>
	 * What this decides is whether a course's stream has to be one its offering department
	 * offers. For a type that follows the department it does, and the server enforces that as a
	 * composite foreign key onto {@code department_stream}. For a type that does not, the
	 * requirement is simply untrue: Mathematics is studied as BSc, so a Mathematics minor taken
	 * by a BSc_unaided Computer Science class is a course whose stream its own department does
	 * not offer, and refusing it would make that minor unrecordable. The server exempts those
	 * types through a generated column that is null for them; see
	 * {@code V4__cross_department_courses.sql}.
	 * <p>
	 * Declared per constant rather than read off the name, because it is a fact about how the
	 * college teaches the type. A laboratory type does have to agree with the type it is the
	 * laboratory half of, which {@code DomainRulesTest} checks.
	 */
	public boolean followsOfferingDepartment() {
		return ownDepartment;
	}

	/**
	 * The types whose offering department is the student's own, in declaration order: exactly
	 * the types the server's {@code courses} to {@code department_stream} foreign key applies
	 * to. {@code PostgresSchemaDriftTest} compares this list against the committed SQL.
	 */
	@NonNull
	public static List<CourseType> followingOfferingDepartment() {

		List<CourseType> out = new ArrayList<>();

		for (CourseType type : values()) {
			if (type.followsOfferingDepartment()) {
				out.add(type);
			}
		}
		return Collections.unmodifiableList(out);
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
