package in.basulabs.siesattendance.backend.stats;

import androidx.annotation.NonNull;
import androidx.sqlite.db.SimpleSQLiteQuery;
import androidx.sqlite.db.SupportSQLiteQuery;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a {@link StatsFilter} into SQL.
 * <p>
 * Every filter value is bound as a positional argument; only the grouping and sorting come from
 * enum constants, whose SQL is fixed at compile time. Nothing the user types is ever spliced
 * into the statement.
 */
public final class StatsQueryBuilder {

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
	 * worse than leaving it out. {@code DaoAcademicYear.countOrphanLectures} exists to surface
	 * exactly that case so the sessions can be extended.
	 * <p>
	 * It resolves to exactly one session rather than joining on the bounds directly. Sessions
	 * are not supposed to overlap, and the sessions screen refuses an overlap outright, but a
	 * merged backup can carry one in: {@code DatabaseMerger} matches {@code academic_years} on
	 * the year alone and never rechecks the dates. A plain {@code BETWEEN} join would then
	 * match a lecture twice and silently double every figure in the report. Picking the latest
	 * containing session is the same rule {@code DaoAcademicYear.findContainingSync} already
	 * applies everywhere else, so an ambiguous lecture at least lands in one place
	 * consistently.
	 */
	private static final String FROM_CLAUSE =
			" FROM attendance a"
					+ " INNER JOIN lectures l ON l.lectureID = a.lectureID"
					+ " INNER JOIN courses c ON c.courseID = l.courseID"
					+ " INNER JOIN students s ON s.studentID = a.studentID"
					+ " INNER JOIN academic_years ay ON ay.academicYear = ("
					+ "    SELECT ay2.academicYear FROM academic_years ay2"
					+ "    WHERE " + SqlBits.EPOCH_START
					+ "       BETWEEN ay2.startEpoch AND ay2.endEpoch"
					+ "    ORDER BY ay2.academicYear DESC LIMIT 1)"
					+ " INNER JOIN student_year y ON y.studentID = a.studentID"
					+ "    AND y.academicYear = ay.academicYear"
					+ " LEFT JOIN batch_desc b ON b.batchID = l.batchID"
					+ " LEFT JOIN professors p ON p.profID = c.profID";

	private StatsQueryBuilder() {
	}

	/**
	 * The grouped report: one row per value of the chosen dimension.
	 * <p>
	 * The two DISTINCT counts matter. Attendance is one row per student per lecture, so a plain
	 * count of lectures would multiply each one by the size of its sheet.
	 */
	@NonNull
	public static SupportSQLiteQuery grouped(@NonNull StatsFilter filter) {

		List<Object> args = new ArrayList<>();

		String sql = "SELECT " + filter.groupBy.idExpr + " AS rowId, " +
				filter.groupBy.primaryExpr + " AS primaryLabel, " +
				filter.groupBy.secondaryExpr + " AS secondaryLabel, " +
				"SUM(CASE WHEN a.present = 1 THEN 1 ELSE 0 END) AS attended, " +
				"COUNT(*) AS total, " +
				"COUNT(DISTINCT l.lectureID) AS lectures, " +
				"COUNT(DISTINCT a.studentID) AS students" +
				FROM_CLAUSE +
				whereClause(filter, args) +
				" GROUP BY " + filter.groupBy.groupByExpr +
				" ORDER BY " + orderByClause(filter);

		return new SimpleSQLiteQuery(sql, args.toArray());
	}

	/**
	 * The headline figures for the same filter: one row, no grouping.
	 * <p>
	 * Shares {@link #FROM_CLAUSE} and {@link #whereClause} with the grouped query, which is the
	 * point: the totals shown above a report are computed from exactly the same rows as the
	 * report itself, and cannot drift from it.
	 */
	@NonNull
	public static SupportSQLiteQuery summary(@NonNull StatsFilter filter) {

		List<Object> args = new ArrayList<>();

		String sql = "SELECT"
				+ " SUM(CASE WHEN a.present = 1 THEN 1 ELSE 0 END) AS attended,"
				+ " COUNT(*) AS total,"
				+ " COUNT(DISTINCT l.lectureID) AS lectures,"
				+ " COUNT(DISTINCT a.studentID) AS students,"
				+ " COUNT(DISTINCT c.courseID) AS courses"
				+ FROM_CLAUSE
				+ whereClause(filter, args);

		return new SimpleSQLiteQuery(sql, args.toArray());
	}

	/*
	 * There used to be a third query here counting groups below the threshold. It has been
	 * removed: the groups partition the same rows the grouped query already returns, so the
	 * count is exactly the number of returned rows below the threshold and can be tallied in
	 * Java for free. Running it as SQL meant a third full scan per refresh, about a third of
	 * the report's total cost, for a number already in hand.
	 */

	// ── Shared clause construction ───────────────────────────────────────

	/**
	 * Builds the WHERE clause, appending a bound argument for each active filter. The argument
	 * order here must match the order the fragments are appended, which is why both happen in
	 * one place.
	 */
	@NonNull
	private static String whereClause(@NonNull StatsFilter filter, @NonNull List<Object> args) {

		StringBuilder where = new StringBuilder(" WHERE 1 = 1");

		// A null academic year means every session, which is what lets one report compare
		// this year against last.
		if (filter.academicYear != null) {
			where.append(" AND ay.academicYear = ?");
			args.add(filter.academicYear);
		}

		Long fromEpoch = filter.fromEpoch();
		if (fromEpoch != null) {
			where.append(" AND ").append(SqlBits.EPOCH_START).append(" >= ?");
			args.add(fromEpoch);
		}

		Long toEpoch = filter.toEpoch();
		if (toEpoch != null) {
			where.append(" AND ").append(SqlBits.EPOCH_START).append(" <= ?");
			args.add(toEpoch);
		}

		if (filter.courseID != null) {
			where.append(" AND c.courseID = ?");
			args.add(filter.courseID);
		}

		// Matched on the students in the batch, not on the batch the lecture was held for.
		//
		// The two differ whenever batches overlap, and they overlap by design: a class is split
		// into A, and A into B and C for the practicals. A lecture held for A is attended by
		// every student in B, so asking for B and getting nothing back — which is what
		// comparing l.batchID gave — is wrong in exactly the case the batches exist for.
		// Membership answers the question actually being asked: how are the students of this
		// batch doing, across whatever lectures they sat, whole-class ones included.
		//
		// Membership is per session, so it is matched against the session the lecture resolved
		// to rather than the one being viewed. A student who moved between batches between
		// years is then counted under the batch they were in at the time.
		if (filter.batchID != null) {
			where.append(" AND EXISTS (SELECT 1 FROM batch_student_map bm")
					.append("   WHERE bm.batchID = ? AND bm.studentID = a.studentID")
					.append("   AND bm.academicYear = ay.academicYear)");
			args.add(filter.batchID);
		}

		if (filter.professorID != null) {
			where.append(" AND c.profID = ?");
			args.add(filter.professorID);
		}

		if (filter.studentID != null) {
			where.append(" AND a.studentID = ?");
			args.add(filter.studentID);
		}

		// Enums are stored by name(), so name() is what gets bound. Never toString(): the
		// label is for display and is not unique across constants.
		if (filter.courseType != null) {
			where.append(" AND c.courseType = ?");
			args.add(filter.courseType.name());
		}

		if (filter.year != null) {
			where.append(" AND y.year = ?");
			args.add(filter.year.name());
		}

		if (filter.stream != null) {
			where.append(" AND y.stream = ?");
			args.add(filter.stream.name());
		}

		if (filter.department != null) {
			where.append(" AND y.majorDept = ?");
			args.add(filter.department.name());
		}

		if (filter.division != null) {
			where.append(" AND y.division = ?");
			args.add(filter.division.name());
		}

		return where.toString();
	}

	/**
	 * The ORDER BY body. Built from the enum alone, with nothing bound, since sort order is a
	 * fixed choice rather than a value.
	 * <p>
	 * Every branch falls back to the primary label as a tiebreak, so a report does not reshuffle
	 * rows that compare equal each time it is refreshed.
	 */
	@NonNull
	private static String orderByClause(@NonNull StatsFilter filter) {

		// Percentage is recomputed here rather than referencing the alias, because SQLite will
		// not accept an aggregate alias in ORDER BY on every version.
		String percent = "(SUM(CASE WHEN a.present = 1 THEN 1 ELSE 0 END) * 1.0 / COUNT(*))";

		return switch (filter.sortOrder) {
			case PERCENT_DESC -> percent + " DESC, primaryLabel ASC";
			case LABEL_ASC -> "primaryLabel ASC";
			case TOTAL_DESC -> "COUNT(*) DESC, primaryLabel ASC";
			case CHRONOLOGICAL ->
				// Time-based groupings read wrong worst-first; they want oldest to newest.
					"primaryLabel ASC";
			default -> percent + " ASC, primaryLabel ASC";
		};
	}
}
