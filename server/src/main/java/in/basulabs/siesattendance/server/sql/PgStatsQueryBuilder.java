package in.basulabs.siesattendance.server.sql;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

import in.basulabs.siesattendance.backend.CourseType;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Division;
import in.basulabs.siesattendance.backend.PostgresEnumTypes;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;
import in.basulabs.siesattendance.backend.stats.StatsFilter;

/**
 * Turns a {@link StatsFilter} into PostgreSQL.
 * <p>
 * The server's counterpart to the app's {@code StatsQueryBuilder}, taking the very same filter
 * object — the one the statistics screen fills in — so that a question asked on a phone and the
 * same question asked of the server cannot be two different questions. Every filter value is
 * bound as a positional argument; only the grouping and sorting come from enum constants, whose
 * SQL is fixed at compile time. Nothing the user types is ever spliced into the statement.
 * <p>
 * Three translations are worth stating, because they are the ones that could change an answer
 * rather than merely the spelling of one:
 * <ol>
 *     <li><b>The session.</b> SQLite matched a lecture's epoch against
 *     {@code academic_years.startEpoch}, a column computed at the writing device's time zone
 *     and dropped by the Phase 1 migration for exactly that reason. Here the lecture's own
 *     zone decides, through {@code session_year_of}, which applies the same
 *     latest-containing-session tie-break the app's subquery did.</li>
 *     <li><b>The date window.</b> Compared as dates in the lecture's zone, rather than as
 *     epoch seconds derived from the reader's. "Up to 17 September" therefore means the whole
 *     of that day without the {@code + 86399} arithmetic the app needs.</li>
 *     <li><b>Ordering.</b> Pinned to byte order rather than left to the database's collation.
 *     See {@link PgSqlBits}.</li>
 * </ol>
 */
public final class PgStatsQueryBuilder {

	/**
	 * Attendance is the grain of the whole report: one row per student per lecture.
	 * <p>
	 * The join to {@code academic_years} is the important one. A lecture carries no session
	 * column, only a timestamp, so the session is decided by matching that timestamp against
	 * the session bounds. Everything downstream then follows from the lecture's own date rather
	 * than from whichever session the user happens to be looking at.
	 * <p>
	 * {@code student_year} is joined on that resolved session, so a student appears under the
	 * roll number and class they held <em>at the time of the lecture</em>. That is what makes a
	 * report for a past year read correctly after the student has been promoted.
	 * <p>
	 * The session join is INNER by design: a lecture outside every defined session has no
	 * session to be counted in, and silently attributing it to a neighbouring one would be
	 * worse than leaving it out. The {@code lectures_without_session} view added by
	 * {@code V2__constraints.sql} exists to surface exactly that case so the sessions can be
	 * extended.
	 * <p>
	 * {@code session_year_of} resolves to exactly one session rather than joining on the bounds
	 * directly. Sessions are not supposed to overlap, and {@code academic_years_no_overlap} now
	 * makes an overlap impossible to create here, but an imported backup can carry one in:
	 * {@code DatabaseMerger} matches {@code academic_years} on the year alone and never
	 * rechecks the dates. A plain BETWEEN join would then match a lecture twice and silently
	 * double every figure in the report.
	 */
	private static final String FROM_CLAUSE =
			" FROM attendance a"
					+ " INNER JOIN lectures l ON l.lecture_id = a.lecture_id"
					+ " INNER JOIN courses c ON c.course_id = l.course_id"
					+ " INNER JOIN students s ON s.student_id = a.student_id"
					+ " INNER JOIN academic_years ay"
					+ "    ON ay.academic_year = session_year_of(l.start_at, l.start_zone)"
					+ " INNER JOIN student_year y ON y.student_id = a.student_id"
					+ "    AND y.academic_year = ay.academic_year"
					+ " LEFT JOIN batch_desc b ON b.batch_id = l.batch_id"
					+ " LEFT JOIN professors p ON p.prof_id = c.prof_id";

	/**
	 * Attendance is a boolean here, where SQLite stored 0 and 1. Counted rather than summed
	 * with a cast, so the expression reads the same in the ORDER BY below.
	 */
	private static final String ATTENDED = "SUM(CASE WHEN a.present THEN 1 ELSE 0 END)";

	private PgStatsQueryBuilder() {
	}

	/**
	 * The grouped report: one row per value of the chosen dimension.
	 * <p>
	 * The two DISTINCT counts matter. Attendance is one row per student per lecture, so a plain
	 * count of lectures would multiply each one by the size of its sheet.
	 * <p>
	 * The aliases are quoted. PostgreSQL folds an unquoted identifier to lower case, which
	 * would hand back {@code primarylabel} and quietly fail to populate a {@code StatRow}.
	 */
	@NonNull
	public static BoundStatement grouped(@NonNull StatsFilter filter) {

		List<Object> args = new ArrayList<>();

		PostgresStatsDialect.Fragments group = PostgresStatsDialect.of(filter.groupBy);

		String sql = "SELECT " + group.idExpr + " AS \"rowId\", "
				+ group.primaryExpr + " AS \"primaryLabel\", "
				+ group.secondaryExpr + " AS \"secondaryLabel\", "
				+ ATTENDED + " AS attended, "
				+ "COUNT(*) AS total, "
				+ "COUNT(DISTINCT l.lecture_id) AS lectures, "
				+ "COUNT(DISTINCT a.student_id) AS students"
				+ FROM_CLAUSE
				+ whereClause(filter, args)
				+ " GROUP BY " + group.groupByExpr
				+ " ORDER BY " + orderByClause(filter, group);

		return new BoundStatement(sql, args);
	}

	/**
	 * The headline figures for the same filter: one row, no grouping.
	 * <p>
	 * Shares {@link #FROM_CLAUSE} and {@link #whereClause} with the grouped query, which is the
	 * point: the totals shown above a report are computed from exactly the same rows as the
	 * report itself, and cannot drift from it.
	 */
	@NonNull
	public static BoundStatement summary(@NonNull StatsFilter filter) {

		List<Object> args = new ArrayList<>();

		String sql = "SELECT"
				+ " " + ATTENDED + " AS attended,"
				+ " COUNT(*) AS total,"
				+ " COUNT(DISTINCT l.lecture_id) AS lectures,"
				+ " COUNT(DISTINCT a.student_id) AS students,"
				+ " COUNT(DISTINCT c.course_id) AS courses"
				+ FROM_CLAUSE
				+ whereClause(filter, args);

		return new BoundStatement(sql, args);
	}

	// ── Shared clause construction ───────────────────────────────────────

	/**
	 * Builds the WHERE clause, appending a bound argument for each active filter. The argument
	 * order here must match the order the fragments are appended, which is why both happen in
	 * one place.
	 */
	@NonNull
	private static String whereClause(@NonNull StatsFilter filter, @NonNull List<Object> args) {

		// Marks on lectures before a late joiner joined do not count against them.
		StringBuilder where = new StringBuilder(" WHERE ")
				.append(PgSqlBits.COUNTS_FOR_ENROLMENT);

		// A null academic year means every session, which is what lets one report compare
		// this year against last.
		if (filter.academicYear != null) {
			where.append(" AND ay.academic_year = ?");
			args.add(filter.academicYear);
		}

		/*
		 * Dates, not epochs. The app has to turn the window into epoch seconds and add 86399
		 * to the upper bound to cover the last day, because the column it compares against is
		 * an epoch. Here the comparison is between calendar dates in the lecture's own zone, so
		 * the inclusive upper bound is simply <=.
		 *
		 * The cast is needed because a bare parameter next to a date has no type PostgreSQL can
		 * infer.
		 */
		if (filter.from != null) {
			where.append(" AND ").append(PgSqlBits.LECTURE_LOCAL_DATE).append(" >= ?::date");
			args.add(filter.from.toString());
		}

		if (filter.to != null) {
			where.append(" AND ").append(PgSqlBits.LECTURE_LOCAL_DATE).append(" <= ?::date");
			args.add(filter.to.toString());
		}

		// Several courses at once are the ordinary case here, so this is an IN rather than an
		// equality. An empty set contributes no condition at all: "IN ()" is a syntax error in
		// PostgreSQL, and matching nothing is the opposite of what an untouched filter means.
		if (!filter.courseIDs.isEmpty()) {
			where.append(" AND c.course_id IN (")
					.append(PgSqlBits.placeholders(filter.courseIDs.size()))
					.append(")");
			args.addAll(filter.courseIDs);
		}

		// Matched on the students in the batch, not on the batch the lecture was held for.
		//
		// The two differ whenever batches overlap, and they overlap by design: a class is split
		// into A, and A into B and C for the practicals. A lecture held for A is attended by
		// every student in B, so asking for B and getting nothing back — which is what
		// comparing l.batch_id would give — is wrong in exactly the case the batches exist for.
		//
		// Membership is per session, so it is matched against the session the lecture resolved
		// to rather than the one being viewed. A student who moved between batches between
		// years is then counted under the batch they were in at the time.
		if (filter.batchID != null) {
			where.append(" AND EXISTS (SELECT 1 FROM batch_student_map bm")
					.append("   WHERE bm.batch_id = ? AND bm.student_id = a.student_id")
					.append("   AND bm.academic_year = ay.academic_year)");
			args.add(filter.batchID);
		}

		if (filter.professorID != null) {
			where.append(" AND c.prof_id = ?");
			args.add(filter.professorID);
		}

		if (filter.studentID != null) {
			where.append(" AND a.student_id = ?");
			args.add(filter.studentID);
		}

		/*
		 * Several types at once, for the same reason the courses above are a set: "every kind
		 * of practical" and "the theory and the lab of this subject together" are one report
		 * each, not one per type.
		 *
		 * Enums are stored by name(), so name() is what gets bound — never toString(), which is
		 * a display label and is not unique across constants. The placeholders carry a cast to
		 * the column's enum type, which is not optional; see PgSqlBits.
		 */
		if (!filter.courseTypes.isEmpty()) {
			where.append(" AND c.course_type IN (")
					.append(PgSqlBits.enumPlaceholders(filter.courseTypes.size(),
							CourseType.class))
					.append(")");
			for (CourseType type : filter.courseTypes) {
				args.add(type.name());
			}
		}

		appendEnum(where, args, "y.year", filter.year, Year.class);
		appendEnum(where, args, "y.stream", filter.stream, Stream.class);
		appendEnum(where, args, "y.major_dept", filter.department, Department.class);
		appendEnum(where, args, "y.division", filter.division, Division.class);

		return where.toString();
	}

	/** One optional enum equality, cast at the placeholder. */
	private static void appendEnum(@NonNull StringBuilder where, @NonNull List<Object> args,
	                               @NonNull String column, Enum<?> value,
	                               @NonNull Class<? extends Enum<?>> type) {

		if (value == null) {
			return;
		}

		where.append(" AND ").append(column)
				.append(" = ?::").append(PostgresEnumTypes.of(type));
		args.add(value.name());
	}

	/**
	 * The ORDER BY body. Built from the enum alone, with nothing bound, since sort order is a
	 * fixed choice rather than a value.
	 * <p>
	 * Every branch falls back to the primary label as a tiebreak, so a report does not reshuffle
	 * rows that compare equal each time it is refreshed.
	 * <p>
	 * The label expression is repeated here rather than referenced by its alias. PostgreSQL
	 * will resolve a bare output alias in ORDER BY, but the moment the alias is part of a larger
	 * expression — and attaching a collation makes it one — it looks for an input column of
	 * that name instead and fails. The app's builder repeats its percentage expression for a
	 * similar reason.
	 * <p>
	 * The id tiebreak exists because of this translation. Comparing the two engines row for row
	 * turned up one disagreement, and it was not a translation error: two lectures of the same
	 * course on the same day carry the identical label, and with nothing after the label the
	 * order between them was whatever each engine happened to produce. Both builders now settle
	 * it the same way. The groupings whose id is the constant {@code "0"} get no tiebreak and
	 * need none — their label is the group key.
	 */
	@NonNull
	private static String orderByClause(@NonNull StatsFilter filter,
	                                    @NonNull PostgresStatsDialect.Fragments group) {

		String label = "(" + group.primaryExpr + ") " + PgSqlBits.BYTE_ORDER;
		String percent = "(" + ATTENDED + " * 1.0 / COUNT(*))";
		String tiebreak = group.idExpr.equals("0") ? "" : ", " + group.idExpr + " ASC";

		return switch (filter.sortOrder) {
			case PERCENT_DESC -> percent + " DESC, " + label + " ASC" + tiebreak;
			case LABEL_ASC -> label + " ASC" + tiebreak;
			case TOTAL_DESC -> "COUNT(*) DESC, " + label + " ASC" + tiebreak;
			case CHRONOLOGICAL ->
				// Time-based groupings read wrong worst-first; they want oldest to newest.
					label + " ASC" + tiebreak;
			default -> percent + " ASC, " + label + " ASC" + tiebreak;
		};
	}
}
