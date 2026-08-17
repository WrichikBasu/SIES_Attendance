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
	 * Attendance is the grain of the whole report: one row per student per lecture. Everything
	 * else hangs off it.
	 * <p>
	 * The join to {@code student_year} is what lets a report be filtered by the student's class
	 * even though attendance itself knows nothing about departments. Batch and professor are
	 * left joins because a lecture may have no batch and, in older data, a course may have no
	 * professor.
	 */
	private static final String FROM_CLAUSE =
			" FROM attendance a"
					+ " INNER JOIN lectures l ON l.lectureID = a.lectureID"
					+ " INNER JOIN courses c ON c.courseID = l.courseID"
					+ " INNER JOIN students s ON s.studentID = a.studentID"
					+ " INNER JOIN student_year y ON y.studentID = a.studentID"
					+ "    AND y.academicYear = ?"
					+ " LEFT JOIN batch_desc b ON b.batchID = l.batchID"
					+ " LEFT JOIN professors p ON p.profID = c.profID";

	private StatsQueryBuilder() {
	}

	/**
	 * The grouped report: one row per value of the chosen dimension.
	 */
	@NonNull
	public static SupportSQLiteQuery grouped(@NonNull StatsFilter filter) {

		List<Object> args = new ArrayList<>();
		args.add(filter.academicYear);

		StringBuilder sql = new StringBuilder();

		sql.append("SELECT ").append(filter.groupBy.idExpr).append(" AS rowId, ")
				.append(filter.groupBy.primaryExpr).append(" AS primaryLabel, ")
				.append(filter.groupBy.secondaryExpr).append(" AS secondaryLabel, ")
				.append("SUM(CASE WHEN a.present = 1 THEN 1 ELSE 0 END) AS attended, ")
				.append("COUNT(*) AS total, ")
				.append("COUNT(DISTINCT l.lectureID) AS lectures, ")
				.append("COUNT(DISTINCT a.studentID) AS students");

		sql.append(FROM_CLAUSE);
		sql.append(whereClause(filter, args));

		sql.append(" GROUP BY ").append(filter.groupBy.groupByExpr);
		sql.append(" ORDER BY ").append(orderByClause(filter));

		return new SimpleSQLiteQuery(sql.toString(), args.toArray());
	}

	/**
	 * The headline figures for the same filter: one row, no grouping.
	 */
	@NonNull
	public static SupportSQLiteQuery summary(@NonNull StatsFilter filter) {

		List<Object> args = new ArrayList<>();
		args.add(filter.academicYear);

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

	/**
	 * Counts subjects of the chosen grouping that fall below the threshold. Done as a query over
	 * the grouped result rather than in Java so the number stays correct even when the caller
	 * only renders the first page of rows.
	 */
	@NonNull
	public static SupportSQLiteQuery belowThreshold(@NonNull StatsFilter filter) {

		List<Object> args = new ArrayList<>();
		args.add(filter.academicYear);

		StringBuilder inner = new StringBuilder();
		inner.append("SELECT SUM(CASE WHEN a.present = 1 THEN 1 ELSE 0 END) * 100.0 / COUNT(*)")
				.append(" AS pct")
				.append(FROM_CLAUSE)
				.append(whereClause(filter, args))
				.append(" GROUP BY ").append(filter.groupBy.groupByExpr);

		args.add(filter.threshold);

		String sql = "SELECT COUNT(*) FROM (" + inner + ") WHERE pct < ?";

		return new SimpleSQLiteQuery(sql, args.toArray());
	}

	// ── Shared clause construction ───────────────────────────────────────

	/**
	 * Builds the WHERE clause, appending a bound argument for each active filter. The argument
	 * order here must match the order the fragments are appended, which is why both happen in
	 * one place.
	 */
	@NonNull
	private static String whereClause(@NonNull StatsFilter filter, @NonNull List<Object> args) {

		StringBuilder where = new StringBuilder(" WHERE 1 = 1");

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

		if (filter.batchID != null) {
			where.append(" AND l.batchID = ?");
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

		// Enums are stored as their constant names, so the bound value is the name string.
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

		return where.toString();
	}

	@NonNull
	private static String orderByClause(@NonNull StatsFilter filter) {

		// Percentage is recomputed here rather than referencing the alias, because SQLite will
		// not accept an aggregate alias in ORDER BY on every version.
		String percent = "(SUM(CASE WHEN a.present = 1 THEN 1 ELSE 0 END) * 1.0 / COUNT(*))";

		switch (filter.sortOrder) {
			case PERCENT_DESC:
				return percent + " DESC, primaryLabel ASC";
			case LABEL_ASC:
				return "primaryLabel ASC";
			case TOTAL_DESC:
				return "COUNT(*) DESC, primaryLabel ASC";
			case PERCENT_ASC:
			default:
				return percent + " ASC, primaryLabel ASC";
		}
	}
}
