package in.basulabs.siesattendance.backend.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Division;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;

/**
 * What the export should cover.
 * <p>
 * Every field is optional and an empty one means no restriction, so a filter with nothing set
 * exports the whole session. That default matters: the export is most often run for everything,
 * and a filter object should not force the caller to enumerate the world to get it.
 * <p>
 * The three kinds of restriction do different jobs and are worth keeping straight:
 * <ul>
 *     <li>{@link #from} and {@link #to} decide which <em>lectures</em> count, so they change
 *     the numbers in the cells.</li>
 *     <li>{@link #courseIDs} decides which <em>columns</em> appear.</li>
 *     <li>{@link #departments}, {@link #years}, {@link #streams} and {@link #divisions}
 *     decide which <em>students</em> appear as rows.</li>
 * </ul>
 * Mixing those up is the easy mistake here: filtering by department could plausibly mean "only
 * this department's courses" or "only its students", and this class means the latter, because a
 * head of department wants their own students' figures across every course those students take,
 * including the ones another department teaches them.
 */
public class ExportFilter {

	/** Inclusive; null for no lower bound. */
	@Nullable
	public LocalDate from;

	/** Inclusive; null for no upper bound. */
	@Nullable
	public LocalDate to;

	/** Empty means every course. */
	@NonNull
	public final Set<Long> courseIDs = new LinkedHashSet<>();

	/** Matched against the student's major department. Empty means every department. */
	@NonNull
	public final Set<Department> departments = new LinkedHashSet<>();

	/** Empty means every year. */
	@NonNull
	public final Set<Year> years = new LinkedHashSet<>();

	/** Empty means every stream. */
	@NonNull
	public final Set<Stream> streams = new LinkedHashSet<>();

	/**
	 * Empty means every division, and also every student who has none.
	 * <p>
	 * This is the one narrowing the course list cannot do for you. Selecting courses already
	 * pins the year, stream and department, since a course states all three, but both divisions
	 * of a class sit the same courses; asking for Div B is the only way to get one division's
	 * sheet on its own.
	 * <p>
	 * Naming a division excludes every student without one, which is every student on a stream
	 * that is not divided. That is the intended reading: a sheet asked for by division is a
	 * sheet of that division.
	 */
	@NonNull
	public final Set<Division> divisions = new LinkedHashSet<>();

	/**
	 * True when nothing has been narrowed. Worth checking before describing the filter, since
	 * the honest description of an empty filter is a sentence rather than an empty string.
	 */
	public boolean isUnrestricted() {
		return from == null && to == null && courseIDs.isEmpty() && departments.isEmpty()
				&& years.isEmpty() && streams.isEmpty() && divisions.isEmpty();
	}

	/** A one-line description for the sheet, so a filtered export says so on its face. */
	@NonNull
	public String describe() {

		if (isUnrestricted()) {
			return "All courses and students for the session";
		}

		StringBuilder out = new StringBuilder();

		if (from != null || to != null) {
			out.append("Dates: ")
					.append(from == null ? "start of session" : from)
					.append(" to ")
					.append(to == null ? "end of session" : to);
		}

		append(out, "Courses", courseIDs.isEmpty() ? null : courseIDs.size() + " selected");
		append(out, "Departments", names(departments));
		append(out, "Years", names(years));
		append(out, "Streams", names(streams));
		append(out, "Divisions", names(divisions));

		return out.toString();
	}

	/**
	 * Appends one clause to the description, with a separator if anything precedes it. A null
	 * value means the restriction is not in use and contributes nothing.
	 */
	private static void append(@NonNull StringBuilder out, @NonNull String label,
	                           @Nullable String value) {

		if (value == null) {
			return;
		}

		if (out.length() > 0) {
			out.append("; ");
		}
		out.append(label).append(": ").append(value);
	}

	/**
	 * The display labels of a set of enum values, comma separated, or null when the set is
	 * empty. Labels rather than constant names, since this text is read by a person.
	 */
	@Nullable
	private static String names(@NonNull Set<? extends Enum<?>> values) {

		if (values.isEmpty()) {
			return null;
		}

		StringBuilder out = new StringBuilder();

		for (Enum<?> value : values) {
			if (out.length() > 0) {
				out.append(", ");
			}
			out.append(value);
		}
		return out.toString();
	}
}
