package in.basulabs.siesattendance.backend.stats;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.LocalDate;
import java.time.ZoneId;

import in.basulabs.siesattendance.backend.CourseType;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;

/**
 * Everything a statistics query can be narrowed by. Every field is optional: a filter left null
 * simply contributes no condition, so one object covers "all attendance this academic year" and
 * "TY BSc Physics majors in Dr Sharma's practicals during September" alike.
 */
public class StatsFilter {

	/** Academic session, as the starting calendar year. Always applied. */
	public int academicYear;

	public StatsGroupBy groupBy = StatsGroupBy.STUDENT;

	public SortOrder sortOrder = SortOrder.PERCENT_ASC;

	// ── Date window ──────────────────────────────────────────────────────

	@Nullable
	public LocalDate from;

	@Nullable
	public LocalDate to;

	/** Which preset produced the current window, kept so the UI can show it selected. */
	@NonNull
	public DateRange dateRange = DateRange.ALL_TIME;

	// ── Subject filters ──────────────────────────────────────────────────

	@Nullable
	public Long courseID;

	@Nullable
	public Long batchID;

	@Nullable
	public Long professorID;

	@Nullable
	public Long studentID;

	@Nullable
	public CourseType courseType;

	// ── Class filters, applied to the student rather than the course ─────

	@Nullable
	public Year year;

	@Nullable
	public Stream stream;

	@Nullable
	public Department department;

	/** Attendance threshold used for the "below" count. Most universities use 75. */
	public double threshold = 75d;

	public StatsFilter(int academicYear) {
		this.academicYear = academicYear;
	}

	public enum SortOrder {
		PERCENT_ASC("Lowest %"),
		PERCENT_DESC("Highest %"),
		LABEL_ASC("Name"),
		TOTAL_DESC("Most lectures");

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
		ALL_TIME("All time"),
		THIS_MONTH("This month"),
		LAST_30_DAYS("Last 30 days"),
		THIS_SEMESTER("This semester"),
		THIS_ACADEMIC_YEAR("Academic year"),
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
	 * The semester boundary is taken as June and December: a session running June to November
	 * is the odd semester and December to May the even one, which is how most Indian
	 * universities lay the year out. Adjust here if your calendar differs — it is the only
	 * place the assumption is encoded.
	 */
	public void applyDateRange(@NonNull DateRange range) {

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

			case LAST_30_DAYS:
				from = today.minusDays(29);
				to = today;
				break;

			case THIS_SEMESTER:
				if (today.getMonthValue() >= 6 && today.getMonthValue() <= 11) {
					from = LocalDate.of(today.getYear(), 6, 1);
					to = LocalDate.of(today.getYear(), 11, 30);
				} else {
					int startYear = today.getMonthValue() >= 12
							? today.getYear() : today.getYear() - 1;
					from = LocalDate.of(startYear, 12, 1);
					to = LocalDate.of(startYear + 1, 5, 31);
				}
				break;

			case THIS_ACADEMIC_YEAR:
				from = LocalDate.of(academicYear, 6, 1);
				to = LocalDate.of(academicYear + 1, 5, 31);
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

	/** True when anything beyond the academic year and grouping is narrowing the result. */
	public boolean hasActiveFilters() {
		return from != null || to != null || courseID != null || batchID != null
				|| professorID != null || studentID != null || courseType != null
				|| year != null || stream != null || department != null;
	}

	@NonNull
	public StatsFilter copy() {
		StatsFilter c = new StatsFilter(academicYear);
		c.groupBy = groupBy;
		c.sortOrder = sortOrder;
		c.from = from;
		c.to = to;
		c.dateRange = dateRange;
		c.courseID = courseID;
		c.batchID = batchID;
		c.professorID = professorID;
		c.studentID = studentID;
		c.courseType = courseType;
		c.year = year;
		c.stream = stream;
		c.department = department;
		c.threshold = threshold;
		return c;
	}

	public void clearFilters() {
		from = null;
		to = null;
		dateRange = DateRange.ALL_TIME;
		courseID = null;
		batchID = null;
		professorID = null;
		studentID = null;
		courseType = null;
		year = null;
		stream = null;
		department = null;
	}
}
