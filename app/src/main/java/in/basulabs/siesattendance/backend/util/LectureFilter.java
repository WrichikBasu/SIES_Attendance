package in.basulabs.siesattendance.backend.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.LocalDate;
import java.time.ZoneId;

import in.basulabs.siesattendance.backend.CourseType;
import in.basulabs.siesattendance.backend.Department;

/**
 * What the lectures list is showing.
 * <p>
 * Every field is optional and a null one contributes no condition, so an untouched filter is
 * the whole record. That is the default the screen opens on, and the reason nothing here has to
 * enumerate the world to mean "everything".
 * <p>
 * The two halves narrow different things and are cleared separately. The period decides
 * <em>when</em>, and is a pair of dates however it was chosen: a session and a month are both
 * ranges underneath, which is what lets one query serve all of them. The rest decide
 * <em>which</em> lectures, and are matched against the lecture's course — its professor, its
 * offering department, its type — or against the lecture's own batch.
 * <p>
 * {@link #batchID} is the lecture's batch here, not the student's, which is the opposite of what
 * the same-named field means on {@code StatsFilter}. The difference is deliberate: this screen
 * lists lectures, so a batch is the group a lecture was held for; the statistics screen counts
 * students, so there a batch is the group a student belongs to. Both readings are the natural
 * one for their own screen and neither would be right on the other.
 */
public class LectureFilter {

	/**
	 * How the period was chosen. Only used for labelling: all four resolve to the same pair of
	 * dates, and {@link #ALL} to no dates at all.
	 */
	public enum Period {
		ALL,
		SESSION,
		MONTH,
		CUSTOM
	}

	/** Inclusive; null for no lower bound. */
	@Nullable
	public LocalDate from;

	/** Inclusive; null for no upper bound. */
	@Nullable
	public LocalDate to;

	@NonNull
	public Period period = Period.ALL;

	/**
	 * What the filter bar calls the current period, in the words it was chosen by: the session's
	 * own label, the month's name, or the two dates of a custom range. Null when the period is
	 * {@link Period#ALL}.
	 */
	@Nullable
	public String periodLabel;

	@Nullable
	public Long courseID;

	/** The professor who owns the course, not whoever happened to take the lecture. */
	@Nullable
	public Long professorID;

	/** The department offering the course, not any department of the students attending it. */
	@Nullable
	public Department department;

	/** The batch a lecture was held for. A whole-class lecture has none and is excluded by it. */
	@Nullable
	public Long batchID;

	@Nullable
	public CourseType courseType;

	/** Narrows to a period, replacing whatever was there. */
	public void setPeriod(@NonNull Period period, @Nullable LocalDate from,
	                      @Nullable LocalDate to, @Nullable String label) {
		this.period = period;
		this.from = from;
		this.to = to;
		this.periodLabel = label;
	}

	/** Back to every date, leaving the other filters alone. */
	public void clearPeriod() {
		setPeriod(Period.ALL, null, null, null);
	}

	public boolean hasPeriod() {
		return from != null || to != null;
	}

	/** True when anything other than the period is narrowing the list. */
	public boolean hasSubjectFilters() {
		return courseID != null || professorID != null || department != null
				|| batchID != null || courseType != null;
	}

	/** True when anything at all is narrowing the list, which the "filters active" line follows. */
	public boolean hasActiveFilters() {
		return hasPeriod() || hasSubjectFilters();
	}

	/** Back to every lecture on record. */
	public void clear() {
		clearPeriod();
		courseID = null;
		professorID = null;
		department = null;
		batchID = null;
		courseType = null;
	}

	/**
	 * Start of the period as an epoch second, or null when unbounded.
	 * <p>
	 * Lectures are stored as an instant, so a date has to become one before it can be compared,
	 * and it is resolved at the device's zone: the same one the lecture was recorded in.
	 */
	@Nullable
	public Long fromEpoch() {
		return from == null ? null : from.atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
	}

	/** End of the period as an epoch second, inclusive of the whole final day. */
	@Nullable
	public Long toEpoch() {
		return to == null
				? null
				: to.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toEpochSecond() - 1;
	}

	/**
	 * A detached copy, for holding on to the filter a query was built from while the screen
	 * carries on editing the live one.
	 */
	@NonNull
	public LectureFilter copy() {
		LectureFilter c = new LectureFilter();
		c.from = from;
		c.to = to;
		c.period = period;
		c.periodLabel = periodLabel;
		c.courseID = courseID;
		c.professorID = professorID;
		c.department = department;
		c.batchID = batchID;
		c.courseType = courseType;
		return c;
	}
}
