package in.basulabs.siesattendance.backend.stats;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.Set;

import in.basulabs.siesattendance.backend.CourseType;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Division;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;

/**
 * Everything a statistics query can be narrowed by. Every field is optional: a filter left null
 * simply contributes no condition, so one object covers "all attendance this academic year" and
 * "TY BSc Physics majors in Dr Sharma's practicals during September" alike.
 * <p>
 * A plain mutable holder rather than anything immutable, because the statistics screen edits it
 * in place as the user works through the filter sheet. {@link #copy()} exists for the cases that
 * need a snapshot, such as holding on to the filter a running query was built from.
 * <p>
 * Nothing here is validated. A combination that matches nothing is a legitimate answer, and the
 * screen reports an empty result rather than refusing the filter.
 */
public class StatsFilter {

	/**
	 * Academic session, as the starting calendar year. Null means every session, which is what
	 * a year-on-year comparison needs.
	 */
	@Nullable
	public Integer academicYear;

	/** The dimension rows are grouped by. Students by default, which is what is usually wanted. */
	public StatsGroupBy groupBy = StatsGroupBy.STUDENT;

	/**
	 * Ordering by the primary label is the default. For the student grouping that label is the
	 * roll number, so a report opens in register order, which is how a class list is read.
	 */
	public SortOrder sortOrder = SortOrder.LABEL_ASC;

	// ── Date window ──────────────────────────────────────────────────────

	@Nullable
	public LocalDate from;

	@Nullable
	public LocalDate to;

	/** Which preset produced the current window, kept so the UI can show it selected. */
	@NonNull
	public DateRange dateRange = DateRange.ALL_TIME;

	// ── Subject filters ──────────────────────────────────────────────────

	/**
	 * The courses the report is restricted to. Empty means every course, so a filter nobody has
	 * touched does not have to enumerate the syllabus to say so.
	 * <p>
	 * A set rather than a single ID because the question asked of this screen is usually about
	 * a handful of courses at once — a student's majors, the practicals of one department, the
	 * two papers a professor shares — and the headline figures are then the combined attendance
	 * across exactly those, computed over the same rows the report lists.
	 */
	@NonNull
	public final Set<Long> courseIDs = new LinkedHashSet<>();

	/**
	 * Restricts to the students who are in one batch, rather than to the lectures held for it.
	 * <p>
	 * Batches nest: a class is split into A, and A into B and C for the practicals. Matching
	 * the lecture's own batch meant asking for B returned nothing from a lecture held for A,
	 * even though every student in B sat it. Matching membership instead makes a subset batch
	 * answerable, and includes the whole-class lectures its students attended, which is what
	 * "how is batch B doing" means.
	 */
	@Nullable
	public Long batchID;

	/** Matched against the professor who owns the course, not who took the individual lecture. */
	@Nullable
	public Long professorID;

	@Nullable
	public Long studentID;

	/**
	 * The course types the report is restricted to. Empty means every type.
	 * <p>
	 * A set rather than a single value for the same reason {@link #courseIDs} is one: the
	 * questions asked here are about a group of types at once — every kind of practical, the
	 * theory and the laboratory half of a subject together — and the headline figures are then
	 * the combined attendance across exactly those, over the same rows the report lists.
	 * Selecting each type separately and averaging the answers by hand would give the wrong
	 * number anyway, since the types carry different numbers of lectures behind them.
	 */
	@NonNull
	public final Set<CourseType> courseTypes = new LinkedHashSet<>();

	// ── Class filters, applied to the student rather than the course ─────

	@Nullable
	public Year year;

	@Nullable
	public Stream stream;

	@Nullable
	public Department department;

	/**
	 * Narrows to one division of a class. Only the divided streams have any, so on every other
	 * stream setting this returns nothing, which is the honest answer rather than an error:
	 * see the note above about combinations that match nothing.
	 */
	@Nullable
	public Division division;

	/** Attendance threshold used for the "below" count. Most universities use 75. */
	public double threshold = 75d;

	public StatsFilter(@Nullable Integer academicYear) {
		this.academicYear = academicYear;
	}

	/**
	 * How the grouped rows are ordered. {@link #CHRONOLOGICAL} exists for the time-based
	 * groupings, which read wrong sorted worst-first.
	 */
	public enum SortOrder {
		PERCENT_ASC("Lowest %"),
		PERCENT_DESC("Highest %"),
		LABEL_ASC("Roll no. / name"),
		TOTAL_DESC("Most lectures"),
		CHRONOLOGICAL("Oldest first");

		@NonNull
		public final String label;

		SortOrder(@NonNull String label) {
			this.label = label;
		}
	}

	/**
	 * Preset windows. {@link #CUSTOM} keeps whatever {@code from} and {@code to} already hold.
	 */
	public enum DateRange {
		ALL_TIME("Whole session"),
		THIS_MONTH("This month"),
		LAST_MONTH("Last month"),
		LAST_30_DAYS("Last 30 days"),
		SESSION_FIRST_HALF("First half of session"),
		SESSION_SECOND_HALF("Second half of session"),
		CUSTOM("Custom range");

		@NonNull
		public final String label;

		DateRange(@NonNull String label) {
			this.label = label;
		}
	}

	/**
	 * Recomputes {@link #from} and {@link #to} for the chosen preset.
	 * <p>
	 * The half-session presets are derived from the selected session's own dates rather than
	 * from a hardcoded month, so a college running August to May gets a sensible split without
	 * any code change. The two bounds are passed as plain dates rather than as a session row,
	 * because that row is a Room entity on the app side and a result set on the server, and
	 * this class is compiled into both. Either may be null when no session is selected, in
	 * which case the half-session options fall back to leaving the window open.
	 * <p>
	 * "Whole session" leaves the date window unset on purpose: the academic year filter already
	 * bounds the query through the session join, so adding redundant date conditions would only
	 * be another thing that could disagree with it.
	 */
	public void applyDateRange(@NonNull DateRange range,
	                           @Nullable LocalDate sessionStart,
	                           @Nullable LocalDate sessionEnd) {

		dateRange = range;
		LocalDate today = LocalDate.now();

		switch (range) {
			case ALL_TIME:
				from = null;
				to = null;
				break;

			case THIS_MONTH:
				from = today.withDayOfMonth(1);
				to = today.withDayOfMonth(today.lengthOfMonth());
				break;

			case LAST_MONTH:
				// The previous calendar month, whole. Distinct from the rolling thirty days
				// below, which is the question people actually mean less often than they
				// reach for it: "last month" almost always means October, not the 30th of
				// October back to the 1st.
				LocalDate lastMonth = today.minusMonths(1);
				from = lastMonth.withDayOfMonth(1);
				to = lastMonth.withDayOfMonth(lastMonth.lengthOfMonth());
				break;

			case LAST_30_DAYS:
				from = today.minusDays(29);
				to = today;
				break;

			case SESSION_FIRST_HALF:
			case SESSION_SECOND_HALF:
				if (sessionStart == null || sessionEnd == null) {
					from = null;
					to = null;
					break;
				}

				long days = ChronoUnit.DAYS.between(sessionStart, sessionEnd) + 1;
				LocalDate midpoint = sessionStart.plusDays(days / 2);

				if (range == DateRange.SESSION_FIRST_HALF) {
					from = sessionStart;
					to = midpoint.minusDays(1);
				} else {
					from = midpoint;
					to = sessionEnd;
				}
				break;

			case CUSTOM:
				// Left as set by the caller.
				break;
		}
	}

	/** Start of the window as an epoch second, or null when unbounded. */
	@Nullable
	public Long fromEpoch() {
		return from == null ? null : from.atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
	}

	/** End of the window as an epoch second, inclusive of the whole final day. */
	@Nullable
	public Long toEpoch() {
		return to == null
				? null
				: to.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toEpochSecond() - 1;
	}

	/**
	 * True when anything beyond the academic year and grouping is narrowing the result. Drives
	 * the "filters active" indicator, so the session and grouping are excluded on purpose:
	 * those are always set, and counting them would leave the indicator permanently lit.
	 */
	public boolean hasActiveFilters() {
		return from != null || to != null || !courseIDs.isEmpty() || batchID != null
				|| professorID != null || studentID != null || !courseTypes.isEmpty()
				|| year != null || stream != null || department != null || division != null;
	}

	/**
	 * True when a date window has been set on top of the session filter.
	 */
	public boolean hasDateWindow() {
		return from != null || to != null;
	}

	/**
	 * A detached copy. Every field bar the two sets is immutable or a primitive, so a shallow
	 * copy is a genuine one; the sets are copied properly, since a query in flight must not see
	 * a course or a type added or removed while it runs.
	 */
	@NonNull
	public StatsFilter copy() {
		StatsFilter c = new StatsFilter(academicYear);
		c.groupBy = groupBy;
		c.sortOrder = sortOrder;
		c.from = from;
		c.to = to;
		c.dateRange = dateRange;
		c.courseIDs.addAll(courseIDs);
		c.batchID = batchID;
		c.professorID = professorID;
		c.studentID = studentID;
		c.courseTypes.addAll(courseTypes);
		c.year = year;
		c.stream = stream;
		c.department = department;
		c.division = division;
		c.threshold = threshold;
		return c;
	}

	/**
	 * Resets everything the "clear filters" action covers. The academic year, grouping, sort
	 * order and threshold survive deliberately: they are how the report is being read, not what
	 * it has been narrowed to.
	 */
	public void clearFilters() {
		from = null;
		to = null;
		dateRange = DateRange.ALL_TIME;
		courseIDs.clear();
		batchID = null;
		professorID = null;
		studentID = null;
		courseTypes.clear();
		year = null;
		stream = null;
		department = null;
		division = null;
	}
}
