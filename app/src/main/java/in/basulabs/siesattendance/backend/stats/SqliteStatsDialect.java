package in.basulabs.siesattendance.backend.stats;

import androidx.annotation.NonNull;

import java.util.EnumMap;
import java.util.Map;

import in.basulabs.siesattendance.backend.CourseType;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Division;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;
import in.basulabs.siesattendance.backend.pojo.ClassKey;

/**
 * The SQLite expressions behind each {@link StatsGroupBy}.
 * <p>
 * These used to be fields on the enum itself. They moved here when the server arrived, because
 * they are not properties of the dimension: "one row per month" is a question, and
 * {@code strftime('%Y-%m', ...)} is one engine's answer to it. {@code PostgresStatsDialect} in
 * the server module answers the same questions in its own dialect, and the two are held to
 * agreeing by running both over the same fixtures and comparing the rows.
 * <p>
 * The four expressions of a dimension have to agree with each other. Anything in
 * {@link Fragments#primaryExpr} or {@link Fragments#secondaryExpr} that is not an aggregate
 * must also appear in {@link Fragments#groupByExpr}, or SQLite will pick an arbitrary row's
 * value for it. The aliases they are selected under are set by {@link StatsQueryBuilder} and
 * match the field names on {@code StatRow}, which is how Room populates it.
 * <p>
 * A dimension with no natural key uses {@code "0"} as its id expression; those rows cannot be
 * drilled into, which {@link StatsGroupBy#isDrillable()} reports.
 * <p>
 * An enum column is never selected bare into a label. It is stored by {@code name()}, which is
 * an identifier, so every one goes through {@link SqlBits#labelOf} and comes out as the text the
 * rest of the app shows for it. It is still grouped by the stored column: the label is derived
 * from it one-to-one, and grouping on the raw value keeps two constants that happen to share a
 * label from being merged into one row.
 */
public final class SqliteStatsDialect {

	/** The four expressions one dimension contributes to the statement. */
	public static final class Fragments {

		/**
		 * Expression yielding a stable numeric id for the row, used for drill-down. {@code "0"}
		 * for the groupings that have no single subject to drill into.
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

		Fragments(@NonNull String idExpr, @NonNull String primaryExpr,
		          @NonNull String secondaryExpr, @NonNull String groupByExpr) {
			this.idExpr = idExpr;
			this.primaryExpr = primaryExpr;
			this.secondaryExpr = secondaryExpr;
			this.groupByExpr = groupByExpr;
		}
	}

	private static final Map<StatsGroupBy, Fragments> BY_DIMENSION =
			new EnumMap<>(StatsGroupBy.class);

	static {
		/*
		 * The session label is assembled in SQL rather than in Java because a report row is
		 * labelled by whatever the query selects. It matches EntityAcademicYear.labelFor: the
		 * trailing year is padded, so 2099-2100 reads "2099-00".
		 */
		put(StatsGroupBy.ACADEMIC_YEAR,
				"ay.academicYear",
				"ay.academicYear || '-' || SUBSTR('0' || ((ay.academicYear + 1) % 100), -2)",
				"''",
				"ay.academicYear");

		put(StatsGroupBy.STUDENT,
				"s.studentID",
				"y.rollNo",
				"s.name",
				"s.studentID, s.name, y.rollNo");

		put(StatsGroupBy.COURSE,
				"c.courseID",
				"c.courseCode",
				"c.courseTopic",
				"c.courseID, c.courseCode, c.courseTopic");

		/*
		 * The stored timestamp is "epochSecond|nano|zone", so the leading integer is extracted
		 * and handed to strftime as a unix epoch, converted to local time so a late-evening
		 * lecture is not filed under the next day.
		 */
		put(StatsGroupBy.MONTH,
				"0",
				"strftime('%Y-%m', " + SqlBits.EPOCH_START + ", 'unixepoch', 'localtime')",
				"''",
				"strftime('%Y-%m', " + SqlBits.EPOCH_START + ", 'unixepoch', 'localtime')");

		put(StatsGroupBy.WEEK,
				"0",
				"strftime('%Y-W%W', " + SqlBits.EPOCH_START + ", 'unixepoch', 'localtime')",
				"''",
				"strftime('%Y-W%W', " + SqlBits.EPOCH_START + ", 'unixepoch', 'localtime')");

		put(StatsGroupBy.SEMESTER,
				"c.semester",
				"'Semester ' || c.semester",
				"''",
				"c.semester");

		/*
		 * Concatenating a null division would make the whole label null, so the suffix is built
		 * first and coalesced away when there is none; every undivided class therefore reads
		 * exactly as it did before. SQLite groups nulls together, so the students of an
		 * undivided stream still land in one row.
		 */
		put(StatsGroupBy.CLASS,
				"0",
				SqlBits.labelOf("y.year", Year.class)
						+ " || ' ' || " + SqlBits.labelOf("y.stream", Stream.class,
						ClassKey::streamLabel)
						+ " || ' ' || " + SqlBits.labelOf("y.majorDept", Department.class)
						+ " || COALESCE(' ' || " + SqlBits.labelOf("y.division", Division.class,
						Division::longLabel) + ", '')",
				"''",
				"y.year, y.stream, y.majorDept, y.division");

		put(StatsGroupBy.DEPARTMENT,
				"0",
				SqlBits.labelOf("c.offeringDepartment", Department.class),
				"''",
				"c.offeringDepartment");

		// Whole-class lectures have a null batch, which the grouping would otherwise drop, so
		// both expressions coalesce it into a row of its own.
		put(StatsGroupBy.BATCH,
				"COALESCE(l.batchID, 0)",
				"COALESCE(b.batchName, 'Whole class')",
				"''",
				"l.batchID, b.batchName");

		put(StatsGroupBy.COURSE_TYPE,
				"0",
				SqlBits.labelOf("c.courseType", CourseType.class),
				"''",
				"c.courseType");

		put(StatsGroupBy.PROFESSOR,
				"p.profID",
				"p.name",
				SqlBits.labelOf("p.department", Department.class),
				"p.profID, p.name, p.department");

		put(StatsGroupBy.WEEKDAY,
				"CAST(strftime('%w', " + SqlBits.EPOCH_START + ", 'unixepoch', 'localtime') "
						+ "AS INTEGER)",
				"CASE CAST(strftime('%w', " + SqlBits.EPOCH_START
						+ ", 'unixepoch', 'localtime') "
						+ "AS INTEGER) "
						+ "WHEN 0 THEN 'Sunday' WHEN 1 THEN 'Monday' WHEN 2 THEN 'Tuesday' "
						+ "WHEN 3 THEN 'Wednesday' WHEN 4 THEN 'Thursday' WHEN 5 THEN 'Friday' "
						+ "ELSE 'Saturday' END",
				"''",
				"CAST(strftime('%w', " + SqlBits.EPOCH_START + ", 'unixepoch', 'localtime') "
						+ "AS INTEGER)");

		put(StatsGroupBy.LECTURE,
				"l.lectureID",
				"c.courseCode || ' · ' || strftime('%d %m %Y', " + SqlBits.EPOCH_START
						+ ", 'unixepoch', 'localtime')",
				"COALESCE(b.batchName, 'Whole class')",
				"l.lectureID, c.courseCode, l.startDateTime, b.batchName");

		// A dimension added to the shared enum and forgotten here would otherwise fail only
		// when somebody selected it. Failing at class load makes it the first thing anyone
		// notices.
		for (StatsGroupBy dimension : StatsGroupBy.values()) {
			if (!BY_DIMENSION.containsKey(dimension)) {
				throw new IllegalStateException(
						"no SQLite expressions for grouping " + dimension);
			}
		}
	}

	private SqliteStatsDialect() {
	}

	private static void put(@NonNull StatsGroupBy dimension, @NonNull String idExpr,
	                        @NonNull String primaryExpr, @NonNull String secondaryExpr,
	                        @NonNull String groupByExpr) {
		BY_DIMENSION.put(dimension,
				new Fragments(idExpr, primaryExpr, secondaryExpr, groupByExpr));
	}

	/** The expressions for one dimension. */
	@NonNull
	public static Fragments of(@NonNull StatsGroupBy dimension) {
		Fragments fragments = BY_DIMENSION.get(dimension);
		if (fragments == null) {
			throw new IllegalStateException("no SQLite expressions for grouping " + dimension);
		}
		return fragments;
	}
}
