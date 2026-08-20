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

	MAJOR("Major"),
	MINOR("Minor"),
	MAJOR_PRACTICALS("Major practicals"),
	MINOR_PRACTICALS("Minor practicals"),
	OE("OE"),
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

	@Nullable
	public static CourseType fromAny(@Nullable String raw) {
		return EnumLookup.find(CourseType.class, raw);
	}
}
