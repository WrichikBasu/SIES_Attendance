package in.basulabs.siesattendance.backend.stats;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import in.basulabs.siesattendance.backend.EntityAcademicYear;

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

	/**
	 * Academic session, as the starting calendar year. Null means every session, which is what
	 * a year-on-year comparison needs.
	 */
	@Nullable
	public Integer academicYear;

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

	public StatsFilter(@Nullable Integer academicYear) {
		this.academicYear = academicYear;
	}

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
	 * any code change. {@code session} may be null when no session is selected, in which case
	 * the half-session options fall back to leaving the window open.
	 * <p>
	 * "Whole session" leaves the date window unset on purpose: the academic year filter already
	 * bounds the query through the session join, so adding redundant date conditions would only
	 * be another thing that could disagree with it.
	 */
	public void applyDateRange(@NonNull DateRange range,
	                           @Nullable EntityAcademicYear session) {

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

			case SESSION_FIRST_HALF:
			case SESSION_SECOND_HALF:
				if (session == null) {
					from = null;
					to = null;
					break;
				}

				long days = ChronoUnit.DAYS.between(session.startDate, session.endDate) + 1;
				LocalDate midpoint = session.startDate.plusDays(days / 2);

				if (range == DateRange.SESSION_FIRST_HALF) {
					from = session.startDate;
					to = midpoint.minusDays(1);
				} else {
					from = midpoint;
					to = session.endDate;
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

	/** True when anything beyond the academic year and grouping is narrowing the result. */
	public boolean hasActiveFilters() {
		return from != null || to != null || courseID != null || batchID != null
				|| professorID != null || studentID != null || courseType != null
				|| year != null || stream != null || department != null;
	}

	/**
	 * True when a date window has been set on top of the session filter.
	 */
	public boolean hasDateWindow() {
		return from != null || to != null;
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
