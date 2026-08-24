package in.basulabs.siesattendance.backend.util;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A set of courses whose attendance is totalled together for the export.
 * <p>
 * Built at export time and thrown away afterwards. Groups are how a percentage that actually
 * matters gets computed: a college's defaulter rule is usually stated over a semester's
 * courses, or over theory separately from practicals, and neither is the same number as the
 * per-course figures or the overall total.
 * <p>
 * Courses are held by code rather than by ID because the code is what the user recognises and
 * types, and because a group is transient and never has to survive a course being renumbered.
 */
public class CourseGroup {

	/** What the group is called on the sheet. Supplied by the user, and not required to be unique. */
	@NonNull
	public String name;

	/**
	 * Course codes in this group. A set, so selecting the same course twice is harmless, and
	 * ordered so the sheet lists them the way they were chosen.
	 */
	@NonNull
	public final Set<String> courseCodes = new LinkedHashSet<>();

	public CourseGroup(@NonNull String name) {
		this.name = name;
	}

	/**
	 * Whether the group would contribute anything. An empty group is not an error, since one is
	 * created the moment the user adds a row and before they have chosen any courses; it is
	 * simply skipped when the sheet is written.
	 */
	public boolean isUsable() {
		return !courseCodes.isEmpty();
	}

	/** The member courses as a single line, for the summary shown under the group's name. */
	@NonNull
	public String describeCourses() {
		return String.join(", ", courseCodes);
	}

	/**
	 * An empty, mutable list of groups, for the common case of an export with no grouping. A new
	 * list each time rather than a shared constant, because callers add to what they are given.
	 */
	@NonNull
	public static List<CourseGroup> none() {
		return new ArrayList<>();
	}
}
