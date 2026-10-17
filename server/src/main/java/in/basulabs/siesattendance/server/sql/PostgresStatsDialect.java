package in.basulabs.siesattendance.server.sql;

import androidx.annotation.NonNull;

import java.util.EnumMap;
import java.util.Map;

import in.basulabs.siesattendance.backend.CourseType;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Division;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;
import in.basulabs.siesattendance.backend.pojo.ClassKey;
import in.basulabs.siesattendance.backend.stats.StatsGroupBy;

/**
 * The PostgreSQL expressions behind each {@link StatsGroupBy}.
 * <p>
 * The exact counterpart of {@code SqliteStatsDialect} in the app, dimension for dimension. Read
 * them side by side: where they differ, the difference is the point, and the differ in
 * {@code app/src/test/sql/} is what proves the differences do not change a single row.
 * <p>
 * The four expressions of a dimension have to agree with each other. Anything in
 * {@link Fragments#primaryExpr} or {@link Fragments#secondaryExpr} that is not an aggregate
 * must also appear in {@link Fragments#groupByExpr} — PostgreSQL will refuse the statement
 * outright, which is friendlier than SQLite, where it picks an arbitrary row's value instead.
 * <p>
 * An enum column is never selected bare into a label. It is stored as its {@code name()}, which
 * is an identifier, so every one goes through {@link PgSqlBits#labelOf} and comes out as the
 * text the app shows for it. It is still grouped by the stored column: the label is derived
 * from it one-to-one, and grouping on the raw value keeps two constants that happen to share a
 * label from being merged into one row.
 */
public final class PostgresStatsDialect {

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

	/**
	 * The local wall-clock time of the lecture, in the zone it was recorded in. Every time-based
	 * grouping below reads from this rather than from the raw instant, so a 9 p.m. lecture is
	 * filed under the day it was actually taught and not under whichever day it happens to be
	 * where the report is being read.
	 */
	private static final String LOCAL = PgSqlBits.LECTURE_LOCAL_TIMESTAMP;

	private static final Map<StatsGroupBy, Fragments> BY_DIMENSION =
			new EnumMap<>(StatsGroupBy.class);

	static {
		/*
		 * SQLite padded the trailing year with SUBSTR('0' || x, -2); lpad says the same thing
		 * and says it legibly. 2099-2100 reads "2099-00", matching
		 * EntityAcademicYear.labelFor.
		 */
		put(StatsGroupBy.ACADEMIC_YEAR,
				"ay.academic_year",
				"ay.academic_year || '-' "
						+ "|| lpad((((ay.academic_year + 1) % 100))::text, 2, '0')",
				"''",
				"ay.academic_year");

		put(StatsGroupBy.STUDENT,
				"s.student_id",
				"y.roll_no",
				"s.name",
				"s.student_id, s.name, y.roll_no");

		put(StatsGroupBy.COURSE,
				"c.course_id",
				"c.course_code",
				"c.course_topic",
				"c.course_id, c.course_code, c.course_topic");

		put(StatsGroupBy.MONTH,
				"0",
				"to_char(" + LOCAL + ", 'YYYY-MM')",
				"''",
				"to_char(" + LOCAL + ", 'YYYY-MM')");

		/*
		 * The one grouping with no PostgreSQL function that means what SQLite meant.
		 *
		 * SQLite's strftime('%W') numbers weeks from the first Monday of the year: the days
		 * before it are week 00, and 1 January is in week 01 only when it is itself a Monday.
		 * PostgreSQL offers 'IW', the ISO week, which is a different rule — ISO puts 1 January
		 * 2025 in week 01 of 2025 even though that week began in December 2024 — and 'WW',
		 * which counts from 1 January regardless of weekday. Neither matches, and a report
		 * grouped by week that silently shifted a lecture into the neighbouring week would be
		 * wrong in the way nobody checks.
		 *
		 * So the rule is spelled out: the count of whole Monday-started weeks elapsed, where
		 * doy is 1-based and isodow is 1 for Monday. Monday 1 January gives (1 + 6 - 0) / 7 = 1;
		 * Sunday 1 January gives (1 + 6 - 6) / 7 = 0, which is week 00. The differ checks it
		 * against SQLite on dates either side of both boundaries, in two different years,
		 * because algebra of this kind is exactly where quiet off-by-ones live.
		 */
		put(StatsGroupBy.WEEK,
				"0",
				weekLabel(),
				"''",
				weekLabel());

		put(StatsGroupBy.SEMESTER,
				"c.semester",
				"'Semester ' || c.semester",
				"''",
				"c.semester");

		/*
		 * Concatenating a null division would make the whole label null, so the suffix is built
		 * first and coalesced away when there is none. PostgreSQL groups nulls together, as
		 * SQLite does, so the students of an undivided stream land in one row.
		 */
		put(StatsGroupBy.CLASS,
				"0",
				PgSqlBits.labelOf("y.year", Year.class)
						+ " || ' ' || " + PgSqlBits.labelOf("y.stream", Stream.class,
						ClassKey::streamLabel)
						+ " || ' ' || " + PgSqlBits.labelOf("y.major_dept", Department.class)
						+ " || COALESCE(' ' || " + PgSqlBits.labelOf("y.division",
						Division.class, Division::longLabel) + ", '')",
				"''",
				"y.year, y.stream, y.major_dept, y.division");

		put(StatsGroupBy.DEPARTMENT,
				"0",
				PgSqlBits.labelOf("c.offering_department", Department.class),
				"''",
				"c.offering_department");

		// Whole-class lectures have a null batch, which the grouping would otherwise drop, so
		// both expressions coalesce it into a row of its own.
		put(StatsGroupBy.BATCH,
				"COALESCE(l.batch_id, 0)",
				"COALESCE(b.batch_name, 'Whole class')",
				"''",
				"l.batch_id, b.batch_name");

		put(StatsGroupBy.COURSE_TYPE,
				"0",
				PgSqlBits.labelOf("c.course_type", CourseType.class),
				"''",
				"c.course_type");

		put(StatsGroupBy.PROFESSOR,
				"p.prof_id",
				"p.name",
				PgSqlBits.labelOf("p.department", Department.class),
				"p.prof_id, p.name, p.department");

		// extract(dow) numbers Sunday 0 through Saturday 6, which is exactly what
		// strftime('%w') returned, so the CASE below needs no reordering. It returns numeric
		// since PostgreSQL 14, hence the cast.
		put(StatsGroupBy.WEEKDAY,
				"extract(dow from " + LOCAL + ")::integer",
				"CASE extract(dow from " + LOCAL + ")::integer "
						+ "WHEN 0 THEN 'Sunday' WHEN 1 THEN 'Monday' WHEN 2 THEN 'Tuesday' "
						+ "WHEN 3 THEN 'Wednesday' WHEN 4 THEN 'Thursday' WHEN 5 THEN 'Friday' "
						+ "ELSE 'Saturday' END",
				"''",
				"extract(dow from " + LOCAL + ")::integer");

		put(StatsGroupBy.LECTURE,
				"l.lecture_id",
				"c.course_code || ' · ' || to_char(" + LOCAL + ", 'DD MM YYYY')",
				"COALESCE(b.batch_name, 'Whole class')",
				"l.lecture_id, c.course_code, l.start_at, l.start_zone, b.batch_name");

		// A dimension added to the shared enum and forgotten here would otherwise fail only
		// when somebody asked for it. Failing at class load makes it the first thing anyone
		// notices, and PostgresStatsDialectTest fails it earlier still.
		for (StatsGroupBy dimension : StatsGroupBy.values()) {
			if (!BY_DIMENSION.containsKey(dimension)) {
				throw new IllegalStateException(
						"no PostgreSQL expressions for grouping " + dimension);
			}
		}
	}

	private PostgresStatsDialect() {
	}

	/** See the comment on the WEEK dimension; this is the whole of that rule. */
	@NonNull
	private static String weekLabel() {
		return "to_char(" + LOCAL + ", 'YYYY') || '-W' || lpad(floor(("
				+ "extract(doy from " + LOCAL + ") + 6 "
				+ "- (extract(isodow from " + LOCAL + ") - 1)) / 7)::integer::text, 2, '0')";
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
			throw new IllegalStateException(
					"no PostgreSQL expressions for grouping " + dimension);
		}
		return fragments;
	}
}
