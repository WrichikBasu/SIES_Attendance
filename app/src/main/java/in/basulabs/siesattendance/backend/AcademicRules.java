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
 * The relationship is used in both directions. Choosing a department narrows the stream list,
 * and choosing a stream narrows the department list, so the two fields can be filled in
 * whichever order suits the person using them.
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
	 * Keeps a chosen department consistent with a stream, the counterpart to
	 * {@link #reconcile}. Returns the department unchanged when the pairing is valid, the
	 * stream's only department when there is one, and otherwise null so the field clears
	 * rather than silently holding something the stream does not permit.
	 */
	@Nullable
	public static Department reconcileDepartment(@Nullable Stream stream,
	                                             @Nullable Department current) {

		if (stream == null) {
			return current;
		}

		List<Department> allowed = departmentsFor(stream);

		if (current != null && allowed.contains(current)) {
			return current;
		}

		return allowed.size() == 1 ? allowed.get(0) : null;
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

	@NonNull
	private static List<Stream> allStreams() {
		return Arrays.asList(Stream.values());
	}
}
