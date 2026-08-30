package in.basulabs.siesattendance.backend.stats;

import androidx.annotation.NonNull;

/**
 * The dimension a statistics query is grouped by.
 * <p>
 * Each constant carries the SQL fragments needed to build its part of the query. Keeping them
 * here rather than in the builder means adding a new breakdown is one enum entry, and it also
 * guarantees the group-by clause can only ever be one of a fixed set of compile-time literals.
 * Nothing the user supplies is ever concatenated into the statement; filter values are bound as
 * arguments by {@link StatsQueryBuilder}.
 * <p>
 * The four expressions have to agree with each other. Anything in {@link #primaryExpr} or
 * {@link #secondaryExpr} that is not an aggregate must also appear in {@link #groupByExpr}, or
 * SQLite will pick an arbitrary row's value for it. The aliases they are selected under match the
 * field names on {@code StatRow}, which is how Room populates it.
 * <p>
 * A grouping with no natural key uses {@code "0"} as its id expression. Those rows cannot be
 * drilled into, which {@link #isDrillable()} reports.
 */
public enum StatsGroupBy {

	/**
	 * One row per academic session, which is what makes "this year versus last year" a single
	 * report rather than two.
	 */
	ACADEMIC_YEAR("Academic year",
			"ay.academicYear",
			"ay.academicYear || '-' || SUBSTR('0' || ((ay.academicYear + 1) % 100), -2)",
			"''",
			"ay.academicYear"),

	/**
	 * One row per student.
	 * <p>
	 * The roll number is the primary label and the name the secondary, matching the student
	 * list elsewhere in the app: the roll number is what a register is read by, and it is also
	 * what the default sort orders on.
	 */
	STUDENT("Student",
			"s.studentID",
			"y.rollNo",
			"s.name",
			"s.studentID, s.name, y.rollNo",
			NameField.SECONDARY),

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

	/**
	 * One row per semester number, taken from the course rather than from the student. A course
	 * belongs to exactly one semester, whereas the semester on a student record is optional and
	 * often unset, so the course is the reliable source.
	 */
	SEMESTER("Semester",
			"c.semester",
			"'Semester ' || c.semester",
			"''",
			"c.semester"),

	/**
	 * One row per class, e.g. SY BSc COMPUTER_SCIENCE, or SY BSc PHYSICS Div B where the class
	 * is split into divisions.
	 * <p>
	 * The division is part of the grouping because it is part of the class: two divisions of
	 * the same year and stream are taught apart, and merging them hides exactly the comparison
	 * this breakdown is for. Concatenating a null division would make the whole label null, so
	 * the suffix is built first and coalesced away when there is none; every undivided class
	 * therefore reads exactly as it did before. SQLite groups nulls together, so the students
	 * of an undivided stream still land in one row.
	 */
	CLASS("Class",
			"0",
			"y.year || ' ' || y.stream || ' ' || y.majorDept"
					+ " || COALESCE(' Div ' || y.division, '')",
			"''",
			"y.year, y.stream, y.majorDept, y.division"),

	/** One row per department offering the course. */
	DEPARTMENT("Department",
			"0",
			"c.offeringDepartment",
			"''",
			"c.offeringDepartment"),

	/**
	 * One row per batch. Whole-class lectures have a null batch, which would otherwise be
	 * dropped by the grouping, so both expressions coalesce it into a row of its own.
	 * <p>
	 * This is the batch a lecture was <em>held for</em>, which is not what
	 * {@code StatsFilter.batchID} means: that one follows the students in a batch. The two read
	 * together rather than contradicting each other — filtering by a batch and grouping by one
	 * shows which sessions its students sat, whole-class lectures included — but they answer
	 * different questions and the difference is worth keeping in mind here.
	 */
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
			"p.profID, p.name, p.department",
			NameField.PRIMARY),

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

	/**
	 * One row per individual lecture, the most granular view available. Labelled with the course
	 * code and date, since a lecture has no name of its own.
	 */
	LECTURE("Lecture",
			"l.lectureID",
			"c.courseCode || ' · ' || strftime('%d %m %Y', " + SqlBits.EPOCH_START
					+ ", 'unixepoch', 'localtime')",
			"COALESCE(b.batchName, 'Whole class')",
			"l.lectureID, c.courseCode, l.startDateTime, b.batchName");

	/**
	 * Which label, if either, holds a person's name and therefore needs case correction on
	 * display. Rosters arrive in whatever case the office typed them.
	 */
	public enum NameField {
		NONE,
		PRIMARY,
		SECONDARY
	}

	/** Shown on the group-by chip. Display text only, safe to reword. */
	@NonNull
	public final String label;

	/**
	 * Expression yielding a stable numeric id for the row, used for drill-down. {@code "0"} for
	 * the groupings that have no single subject to drill into.
	 */
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

	/** Which of the two labels, if either, is a person's name. */
	@NonNull
	public final NameField nameField;

	StatsGroupBy(@NonNull String label, @NonNull String idExpr, @NonNull String primaryExpr,
	             @NonNull String secondaryExpr, @NonNull String groupByExpr) {
		this(label, idExpr, primaryExpr, secondaryExpr, groupByExpr, NameField.NONE);
	}

	StatsGroupBy(@NonNull String label, @NonNull String idExpr, @NonNull String primaryExpr,
	             @NonNull String secondaryExpr, @NonNull String groupByExpr,
	             @NonNull NameField nameField) {
		this.label = label;
		this.idExpr = idExpr;
		this.primaryExpr = primaryExpr;
		this.secondaryExpr = secondaryExpr;
		this.groupByExpr = groupByExpr;
		this.nameField = nameField;
	}

	/**
	 * Whether tapping a row can drill down to a single subject. Only groupings whose id is a
	 * real primary key qualify.
	 */
	public boolean isDrillable() {
		return this == STUDENT || this == COURSE;
	}
}
