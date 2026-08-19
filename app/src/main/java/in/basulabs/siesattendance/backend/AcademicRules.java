package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Which streams a department can be studied under.
 * <p>
 * A department does not offer every stream: Physics is a BSc or MSc subject, Commerce is BCom
 * and nothing else, Management Studies has no postgraduate course at all. Encoding that here
 * means the pairing cannot be got wrong from any screen, and the stream dropdown collapses to a
 * single unambiguous choice for most departments.
 * <p>
 * The narrowing runs one way only: department, then stream, then year, then semester. Each
 * choice restricts the ones after it and leaves the ones before it alone. Making it
 * bidirectional meant a later field could rewrite an earlier one under the user, which is
 * disorienting when you are simply correcting a typo further down the form.
 * <p>
 * Because narrowing is one-directional, a form can still be left holding a combination that
 * does not exist — a stream chosen before the department, say. {@link #validate} is the
 * backstop, and every screen calls it before saving.
 */
public final class AcademicRules {

	private static final Map<Department, List<Stream>> STREAMS =
			new EnumMap<>(Department.class);

	static {
		// Sciences: aided BSc and MSc.
		for (Department department : new Department[]{
				Department.PHYSICS, Department.CHEMISTRY, Department.MATHEMATICS,
				Department.BOTANY, Department.ZOOLOGY, Department.BIOCHEMISTRY,
				Department.STATISTICS, Department.BIOANALYTICAL_SCIENCES,
				Department.MICROBIOLOGY, Department.FTN}) {
			STREAMS.put(department, Arrays.asList(Stream.BSc, Stream.MSc));
		}

		// Unaided sciences: stored separately, shown as plain BSc and MSc.
		for (Department department : new Department[]{
				Department.COMPUTER_SCIENCE, Department.DATA_SCIENCE,
				Department.INFORMATION_TECHNOLOGY, Department.ENVIRONMENTAL_SCIENCE}) {
			STREAMS.put(department, Arrays.asList(Stream.BSc_unaided, Stream.MSc_unaided));
		}

		// Single-stream departments, none of which currently run a master's course.
		STREAMS.put(Department.MANAGEMENT_STUDIES,
				Collections.singletonList(Stream.BMS));
		STREAMS.put(Department.MASS_MEDIA,
				Collections.singletonList(Stream.BAMMC));
		STREAMS.put(Department.COMMERCE,
				Collections.singletonList(Stream.BCom));

		// Everything else is an arts subject: BA and MA.
		for (Department department : Department.values()) {
			if (!STREAMS.containsKey(department)) {
				STREAMS.put(department, Arrays.asList(Stream.BA, Stream.MA));
			}
		}
	}

	private AcademicRules() {
	}

	/**
	 * The streams this department can be studied under, in undergraduate-then-postgraduate
	 * order. Never empty.
	 */
	@NonNull
	public static List<Stream> streamsFor(@Nullable Department department) {
		if (department == null) {
			return allStreams();
		}
		List<Stream> streams = STREAMS.get(department);
		return streams == null ? allStreams() : streams;
	}

	/**
	 * The stream to select automatically for a department, or null when there is a genuine
	 * choice. A department with one stream should not make the user pick it.
	 */
	@Nullable
	public static Stream soleStreamFor(@Nullable Department department) {
		List<Stream> streams = streamsFor(department);
		return streams.size() == 1 ? streams.get(0) : null;
	}

	/**
	 * The departments that can be studied under a stream: the mirror of {@link #streamsFor}.
	 * A null stream leaves every department available.
	 */
	@NonNull
	public static List<Department> departmentsFor(@Nullable Stream stream) {

		if (stream == null) {
			return Arrays.asList(Department.values());
		}

		List<Department> departments = new ArrayList<>();
		for (Department department : Department.values()) {
			if (streamsFor(department).contains(stream)) {
				departments.add(department);
			}
		}
		return departments;
	}

	/**
	 * Checks a whole selection and describes the first problem found, or returns null when the
	 * combination is sound.
	 * <p>
	 * This is the guard that makes one-way narrowing safe. The dropdowns steer the user towards
	 * valid combinations, but nothing stops a stale value surviving a change made above it, so
	 * the pairing is confirmed here rather than trusted.
	 *
	 * @param semester may be null on screens without one
	 */
	@Nullable
	public static String validate(@Nullable Department department, @Nullable Stream stream,
	                              @Nullable Year year, @Nullable Integer semester) {

		if (department != null && stream != null && !isValid(department, stream)) {
			return department + " is not offered as " + stream;
		}

		if (year != null && stream != null && year.isMasters() != stream.isMasters()) {
			return stream + " does not have a " + year + " year";
		}

		if (year != null && department != null
				&& !yearsFor(department, stream).contains(year)) {
			return department + " does not run " + year;
		}

		if (semester != null && year != null && !semestersFor(year).contains(semester)) {
			return year + " covers semester "
					+ joinSemesters(semestersFor(year)) + ", not " + semester;
		}

		if (semester != null && year == null && stream != null
				&& !semestersFor(null, stream).contains(semester)) {
			return stream + " has no semester " + semester;
		}

		return null;
	}

	@NonNull
	private static String joinSemesters(@NonNull List<Integer> semesters) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < semesters.size(); i++) {
			if (i > 0) {
				out.append(i == semesters.size() - 1 ? " or " : ", ");
			}
			out.append(semesters.get(i));
		}
		return out.toString();
	}

	public static boolean isValid(@Nullable Department department, @Nullable Stream stream) {
		if (department == null || stream == null) {
			return true;
		}
		return streamsFor(department).contains(stream);
	}

	/** True when the department runs no postgraduate course. */
	public static boolean hasMasters(@Nullable Department department) {
		for (Stream stream : streamsFor(department)) {
			if (stream.isMasters()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The years available for a department, so a Management Studies student cannot be filed as
	 * Masters Part 1 when no such course exists.
	 */
	@NonNull
	public static List<Year> yearsFor(@Nullable Department department) {
		return yearsFor(department, null);
	}

	/**
	 * The years available for a department and stream together.
	 * <p>
	 * A postgraduate stream leaves only the master's years, and an undergraduate stream only
	 * the first three, which removes a combination that is simply not a thing: an MSc student
	 * in their second year is Masters Part 2, never SY.
	 */
	@NonNull
	public static List<Year> yearsFor(@Nullable Department department, @Nullable Stream stream) {

		List<Year> years = new ArrayList<>();
		boolean departmentHasMasters = hasMasters(department);

		for (Year year : Year.values()) {

			if (stream != null) {
				if (year.isMasters() != stream.isMasters()) {
					continue;
				}
			} else if (year.isMasters() && !departmentHasMasters) {
				continue;
			}

			years.add(year);
		}
		return years;
	}

	/**
	 * Keeps a chosen stream consistent with a department: returns the stream unchanged when the
	 * pairing is valid, the department's sole stream when there is only one, and otherwise the
	 * closest equivalent at the same level. Selecting Physics with MSc already chosen and then
	 * switching to Computer Science should land on unaided MSc, not reset to undergraduate.
	 */
	@Nullable
	public static Stream reconcile(@Nullable Department department, @Nullable Stream current) {

		if (department == null) {
			return current;
		}

		List<Stream> allowed = streamsFor(department);

		if (current != null && allowed.contains(current)) {
			return current;
		}

		if (allowed.size() == 1) {
			return allowed.get(0);
		}

		if (current != null) {
			for (Stream candidate : allowed) {
				if (candidate.isMasters() == current.isMasters()) {
					return candidate;
				}
			}
		}

		return null;
	}

	/**
	 * Resolves a stream a file or user actually asked for, without correcting it.
	 * <p>
	 * This differs from {@link #reconcile} deliberately. Reconcile exists for dropdowns, where
	 * changing the department should adapt the stream rather than throw; it will substitute a
	 * department's sole stream for whatever was there. That is wrong for an import: a row
	 * reading "COMMERCE,BSc" is a mistake in the file, and silently filing it as BCom would
	 * hide it.
	 * <p>
	 * Here a stream is accepted only when its label matches one the department offers, which is
	 * what makes the aided and unaided split invisible: "BSc" against Computer Science resolves
	 * to {@link Stream#BSc_unaided} because that is the department's BSc, while "BSc" against
	 * Commerce resolves to nothing at all.
	 *
	 * @return the matching stream, or null when the department does not offer it
	 */
	@Nullable
	public static Stream resolveRequested(@Nullable Department department,
	                                      @Nullable Stream requested) {

		if (department == null || requested == null) {
			return requested;
		}

		for (Stream candidate : streamsFor(department)) {
			// Matched on the base label, so a file naming a bare "BSc" resolves to whichever
			// BSc the department runs, aided or unaided.
			if (candidate == requested
					|| candidate.baseLabel().equals(requested.baseLabel())) {
				return candidate;
			}
		}
		return null;
	}

	// ── Semesters ────────────────────────────────────────────────────────

	/**
	 * The semesters a year of study covers.
	 * <p>
	 * Semesters run consecutively through a degree, so the year fixes the pair: first year is
	 * 1 and 2, second is 3 and 4, third is 5 and 6. A master's course restarts the count, so
	 * Part 1 is 1 and 2 again and Part 2 is 3 and 4; there is no fifth or sixth master's
	 * semester.
	 */
	@NonNull
	public static List<Integer> semestersFor(@Nullable Year year) {

		if (year == null) {
			return Arrays.asList(1, 2, 3, 4, 5, 6);
		}

		switch (year) {
			case FY:
			case Masters_P1:
				return Arrays.asList(1, 2);
			case SY:
			case Masters_P2:
				return Arrays.asList(3, 4);
			case TY:
			default:
				return Arrays.asList(5, 6);
		}
	}

	/**
	 * The years a semester can belong to: the inverse of {@link #semestersFor}. Semesters 1 and
	 * 2 are ambiguous between FY and Masters Part 1, which is why this returns a list rather
	 * than a single year.
	 */
	@NonNull
	public static List<Year> yearsForSemester(@Nullable Integer semester) {

		if (semester == null) {
			return Arrays.asList(Year.values());
		}

		List<Year> years = new ArrayList<>();
		for (Year year : Year.values()) {
			if (semestersFor(year).contains(semester)) {
				years.add(year);
			}
		}
		return years;
	}

	/**
	 * The semesters available for a year and stream together, which is what a dropdown should
	 * offer. With no year chosen the stream still rules some out: a master's course has no
	 * semester 5 or 6.
	 */
	@NonNull
	public static List<Integer> semestersFor(@Nullable Year year, @Nullable Stream stream) {

		if (year != null) {
			return semestersFor(year);
		}

		if (stream != null && stream.isMasters()) {
			return Arrays.asList(1, 2, 3, 4);
		}

		return Arrays.asList(1, 2, 3, 4, 5, 6);
	}

	/**
	 * Drops a semester the current year no longer permits, so moving a course from TY to FY
	 * does not leave semester 5 showing.
	 */
	@Nullable
	public static Integer reconcileSemester(@Nullable Year year, @Nullable Stream stream,
	                                        @Nullable Integer current) {

		if (current == null) {
			return null;
		}
		return semestersFor(year, stream).contains(current) ? current : null;
	}

	@NonNull
	private static List<Stream> allStreams() {
		return Arrays.asList(Stream.values());
	}
}
