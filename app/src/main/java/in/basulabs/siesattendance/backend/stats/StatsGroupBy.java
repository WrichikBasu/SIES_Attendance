package in.basulabs.siesattendance.backend.stats;

import androidx.annotation.NonNull;

/**
 * The dimension a statistics query is grouped by.
 * <p>
 * Each constant carries the SQL fragments needed to build its part of the query. Keeping them
 * here rather than in the builder means adding a new breakdown is one enum entry, and it also
 * guarantees the group-by clause can only ever be one of a fixed set of literals — the user's
 * filter values are bound as arguments, never concatenated.
 */
public enum StatsGroupBy {

	/** One row per student. */
	STUDENT("Student",
			"s.studentID",
			"s.name",
			"y.rollNo",
			"s.studentID, s.name, y.rollNo"),

	/** One row per course. */
	COURSE("Course",
			"c.courseID",
			"c.courseCode",
			"c.courseTopic",
			"c.courseID, c.courseCode, c.courseTopic"),

	/**
	 * One row per calendar month. The stored timestamp is "epochSecond|nano|zone", so the
	 * leading integer is extracted and handed to strftime as a unix epoch, converted to local
	 * time so a late-evening lecture is not filed under the next day.
	 */
	MONTH("Month",
			"0",
			"strftime('%Y-%m', " + SqlBits.EPOCH_START + ", 'unixepoch', 'localtime')",
			"''",
			"strftime('%Y-%m', " + SqlBits.EPOCH_START + ", 'unixepoch', 'localtime')"),

	/** One row per week, labelled by the ISO-ish year and week number. */
	WEEK("Week",
			"0",
			"strftime('%Y-W%W', " + SqlBits.EPOCH_START + ", 'unixepoch', 'localtime')",
			"''",
			"strftime('%Y-W%W', " + SqlBits.EPOCH_START + ", 'unixepoch', 'localtime')"),

	/** One row per semester number, taken from the course rather than the calendar. */
	SEMESTER("Semester",
			"c.semester",
			"'Semester ' || c.semester",
			"''",
			"c.semester"),

	/** One row per class, e.g. SY BSc COMPUTER_SCIENCE. */
	CLASS("Class",
			"0",
			"y.year || ' ' || y.stream || ' ' || y.majorDept",
			"''",
			"y.year, y.stream, y.majorDept"),

	/** One row per department offering the course. */
	DEPARTMENT("Department",
			"0",
			"c.offeringDepartment",
			"''",
			"c.offeringDepartment"),

	/** One row per batch. Whole-class lectures have no batch and group together. */
	BATCH("Batch",
			"COALESCE(l.batchID, 0)",
			"COALESCE(b.batchName, 'Whole class')",
			"''",
			"l.batchID, b.batchName"),

	/** One row per course type: major, minor, practicals, OE, AEC, IKS. */
	COURSE_TYPE("Course type",
			"0",
			"c.courseType",
			"''",
			"c.courseType"),

	/** One row per professor. */
	PROFESSOR("Professor",
			"p.profID",
			"p.name",
			"p.department",
			"p.profID, p.name, p.department"),

	/** One row per day of the week, to expose a slot that is chronically poorly attended. */
	WEEKDAY("Day of week",
			"CAST(strftime('%w', " + SqlBits.EPOCH_START + ", 'unixepoch', 'localtime') "
					+ "AS INTEGER)",
			"CASE CAST(strftime('%w', " + SqlBits.EPOCH_START + ", 'unixepoch', 'localtime') "
					+ "AS INTEGER) "
					+ "WHEN 0 THEN 'Sunday' WHEN 1 THEN 'Monday' WHEN 2 THEN 'Tuesday' "
					+ "WHEN 3 THEN 'Wednesday' WHEN 4 THEN 'Thursday' WHEN 5 THEN 'Friday' "
					+ "ELSE 'Saturday' END",
			"''",
			"CAST(strftime('%w', " + SqlBits.EPOCH_START + ", 'unixepoch', 'localtime') "
					+ "AS INTEGER)"),

	/** One row per individual lecture — the most granular view available. */
	LECTURE("Lecture",
			"l.lectureID",
			"c.courseCode || ' · ' || strftime('%d %m %Y', " + SqlBits.EPOCH_START
					+ ", 'unixepoch', 'localtime')",
			"COALESCE(b.batchName, 'Whole class')",
			"l.lectureID, c.courseCode, l.startDateTime, b.batchName");

	/** Shown on the group-by chip. */
	@NonNull
	public final String label;

	/** Expression yielding a stable numeric id for the row, used for drill-down. */
	@NonNull
	public final String idExpr;

	/** Expression for the row's main label. */
	@NonNull
	public final String primaryExpr;

	/** Expression for the row's supporting label; {@code ''} when there is none. */
	@NonNull
	public final String secondaryExpr;

	/** The GROUP BY clause body. */
	@NonNull
	public final String groupByExpr;

	StatsGroupBy(@NonNull String label, @NonNull String idExpr, @NonNull String primaryExpr,
	             @NonNull String secondaryExpr, @NonNull String groupByExpr) {
		this.label = label;
		this.idExpr = idExpr;
		this.primaryExpr = primaryExpr;
		this.secondaryExpr = secondaryExpr;
		this.groupByExpr = groupByExpr;
	}

	/**
	 * Whether tapping a row can drill down to a single subject. Only groupings whose id is a
	 * real primary key qualify.
	 */
	public boolean isDrillable() {
		return this == STUDENT || this == COURSE;
	}
}
