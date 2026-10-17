package in.basulabs.siesattendance.backend.stats;

import androidx.annotation.NonNull;

/**
 * The dimension a statistics query is grouped by.
 * <p>
 * Each constant is a question the report can answer — one row per student, per course, per
 * week — and nothing more than that. The SQL that answers it lives with the engine that runs
 * it: {@code SqliteStatsDialect} in the app, {@code PostgresStatsDialect} on the server. The
 * dimension itself is shared, so both sides offer exactly the same breakdowns and a new one
 * added here is a compile-time hole in whichever dialect has not caught up.
 * <p>
 * Keeping the grouping as an enum is also what keeps it out of reach of anything the user
 * types: a dialect's group-by clause can only ever be one of a fixed set of compile-time
 * literals, and filter <em>values</em> are bound as arguments rather than concatenated.
 * <p>
 * A grouping with no natural key cannot be drilled into, which {@link #isDrillable()} reports.
 */
public enum StatsGroupBy {

	/**
	 * One row per academic session, which is what makes "this year versus last year" a single
	 * report rather than two.
	 */
	ACADEMIC_YEAR("Academic year"),

	/**
	 * One row per student.
	 * <p>
	 * The roll number is the primary label and the name the secondary, matching the student
	 * list elsewhere in the app: the roll number is what a register is read by, and it is also
	 * what the default sort orders on.
	 */
	STUDENT("Student", NameField.SECONDARY),

	/** One row per course. */
	COURSE("Course"),

	/** One row per calendar month. */
	MONTH("Month"),

	/** One row per week, labelled by the ISO-ish year and week number. */
	WEEK("Week"),

	/**
	 * One row per semester number, taken from the course rather than from the student. A course
	 * belongs to exactly one semester, whereas the semester on a student record is optional and
	 * often unset, so the course is the reliable source.
	 */
	SEMESTER("Semester"),

	/**
	 * One row per class, e.g. SY BSc CS, or SY BSc Physics Div B where the class is split into
	 * divisions. Reads exactly as the class does everywhere else in the app, because it is
	 * built from the same rule, {@code ClassKey.label}, including dropping the unaided
	 * qualifier.
	 * <p>
	 * The division is part of the grouping because it is part of the class: two divisions of
	 * the same year and stream are taught apart, and merging them hides exactly the comparison
	 * this breakdown is for.
	 */
	CLASS("Class"),

	/** One row per department offering the course. */
	DEPARTMENT("Department"),

	/**
	 * One row per batch, with the whole-class lectures gathered into a row of their own.
	 * <p>
	 * This is the batch a lecture was <em>held for</em>, which is not what
	 * {@link StatsFilter#batchID} means: that one follows the students in a batch. The two read
	 * together rather than contradicting each other — filtering by a batch and grouping by one
	 * shows which sessions its students sat, whole-class lectures included — but they answer
	 * different questions and the difference is worth keeping in mind here.
	 */
	BATCH("Batch"),

	/** One row per course type: major, minor, practicals, OE, AEC, IKS. */
	COURSE_TYPE("Course type"),

	/** One row per professor. */
	PROFESSOR("Professor", NameField.PRIMARY),

	/** One row per day of the week, to expose a slot that is chronically poorly attended. */
	WEEKDAY("Day of week"),

	/**
	 * One row per individual lecture, the most granular view available. Labelled with the course
	 * code and date, since a lecture has no name of its own.
	 */
	LECTURE("Lecture");

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

	/** Which of the two labels, if either, is a person's name. */
	@NonNull
	public final NameField nameField;

	StatsGroupBy(@NonNull String label) {
		this(label, NameField.NONE);
	}

	StatsGroupBy(@NonNull String label, @NonNull NameField nameField) {
		this.label = label;
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
