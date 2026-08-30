package in.basulabs.siesattendance.backend.util;

import androidx.annotation.NonNull;
import androidx.sqlite.db.SimpleSQLiteQuery;
import androidx.sqlite.db.SupportSQLiteQuery;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import in.basulabs.siesattendance.backend.stats.SqlBits;

/**
 * Assembles the export query with whatever filters were asked for.
 * <p>
 * Built as a raw query for the same reason the statistics are: the shape changes with the
 * filter, and Room's annotated queries are fixed strings. Every user-supplied value is bound as
 * an argument rather than concatenated, so a course code or a date can never alter the
 * statement.
 * <p>
 * The date bounds narrow the lecture window that is otherwise the academic year, and they
 * narrow it in both the held and attended subqueries. Applying them to only one would produce
 * a percentage over two different denominators, which is worse than no filter at all.
 */
public final class ExportQueryBuilder {

	private ExportQueryBuilder() {
	}

	/**
	 * Builds the query behind {@code DaoAttendance.getExportRowsSync}: one row per student per
	 * course they are on the roll for, with lectures held and lectures attended.
	 * <p>
	 * The join from students to courses repeats the roster condition from
	 * {@code DaoStudent.getRollForCourseSync}. The two have to agree, since a student who counts
	 * as taking a course in one and not the other would appear on a sheet without appearing in
	 * the report, or the reverse.
	 * <p>
	 * Argument order is the thing to be careful about here. The arguments are positional and the
	 * subqueries are appended ahead of the FROM clause they refer to, so each block adds its own
	 * arguments immediately after appending its SQL rather than all of them at the end.
	 */
	@NonNull
	public static SupportSQLiteQuery build(int academicYear, @NonNull ExportFilter filter) {

		List<Object> args = new ArrayList<>();

		StringBuilder sql = new StringBuilder();

		sql.append("SELECT s.studentID AS studentID, y.rollNo AS rollNo, ")
				.append("s.name AS studentName, c.courseID AS courseID, ")
				.append("c.courseCode AS courseCode, c.courseTopic AS courseTopic, ");

		// held
		sql.append("(SELECT COUNT(DISTINCT l.lectureID) FROM lectures l ")
				.append(" WHERE l.courseID = c.courseID ")
				.append("   AND (l.batchID IS NULL OR l.batchID IN ")
				.append("        (SELECT m.batchID FROM batch_student_map m ")
				.append("         WHERE m.studentID = s.studentID AND m.academicYear = ?)) ")
				.append("   AND ").append(lectureWindow())
				.append(") AS held, ");
		args.add(academicYear);
		addWindowArgs(filter, args);

		// attended
		sql.append("(SELECT COUNT(*) FROM attendance a ")
				.append(" INNER JOIN lectures l ON l.lectureID = a.lectureID ")
				.append(" WHERE a.studentID = s.studentID AND a.present = 1 ")
				.append("   AND l.courseID = c.courseID ")
				.append("   AND (l.batchID IS NULL OR l.batchID IN ")
				.append("        (SELECT m2.batchID FROM batch_student_map m2 ")
				.append("         WHERE m2.studentID = s.studentID AND m2.academicYear = ?)) ")
				.append("   AND ").append(lectureWindow())
				.append(") AS attended ");
		args.add(academicYear);
		addWindowArgs(filter, args);

		sql.append("FROM students s ")
				.append("INNER JOIN student_year y ON y.studentID = s.studentID ")
				.append("   AND y.academicYear = ? ")
				.append("INNER JOIN academic_years ay ON ay.academicYear = ? ")
				.append("INNER JOIN courses c ON c.year = y.year AND c.stream = y.stream AND (")
				.append("   (c.courseType IN ('MAJOR', 'MAJOR_PRACTICALS', ")
				.append("                     'DSE', 'DSE_PRACTICALS') ")
				.append("      AND y.majorDept = c.offeringDepartment) ")
				.append("   OR (c.courseType IN ('MINOR', 'MINOR_PRACTICALS') ")
				.append("      AND (y.minorDept = c.offeringDepartment ")
				.append("           OR y.majorDept = c.offeringDepartment)) ")
				.append("   OR (c.courseType = 'OE' AND y.oeDept = c.offeringDepartment) ")
				.append("   OR c.courseType IN ('AEC', 'IKS', 'VSC', 'VSC_PRACTICALS', ")
				.append("                       'SEC', 'SEC_PRACTICALS')) ");
		args.add(academicYear);
		args.add(academicYear);

		sql.append("WHERE 1 = 1 ");

		appendIn(sql, args, "c.courseID", filter.courseIDs);
		appendEnumIn(sql, args, "y.majorDept", filter.departments);
		appendEnumIn(sql, args, "y.year", filter.years);
		appendEnumIn(sql, args, "y.stream", filter.streams);
		appendEnumIn(sql, args, "y.division", filter.divisions);

		sql.append("ORDER BY y.rollNo ASC, c.courseCode ASC");

		return new SimpleSQLiteQuery(sql.toString(), args.toArray());
	}

	/**
	 * The lecture window used by both subqueries: inside the session, and inside any requested
	 * date bounds. Written once so the held and attended counts cannot drift onto different
	 * windows.
	 * <p>
	 * Both subqueries alias the lectures table as {@code l}, which is what
	 * {@link SqlBits#EPOCH_START} already expects.
	 */
	@NonNull
	private static String lectureWindow() {
		String epoch = SqlBits.EPOCH_START;
		return "(" + epoch + " BETWEEN ay.startEpoch AND ay.endEpoch "
				+ "AND (? = 0 OR " + epoch + " >= ?) "
				+ "AND (? = 0 OR " + epoch + " <= ?))";
	}

	/**
	 * Bounds are passed as a flag plus a value rather than a nullable one, because a null
	 * compared with BETWEEN is never true and would silently return nothing at all. The flag
	 * makes "no bound" explicit in the SQL.
	 */
	private static void addWindowArgs(@NonNull ExportFilter filter, @NonNull List<Object> args) {

		args.add(filter.from == null ? 0 : 1);
		args.add(filter.from == null ? 0L : startOfDay(filter.from));
		args.add(filter.to == null ? 0 : 1);
		args.add(filter.to == null ? 0L : endOfDay(filter.to));
	}

	private static long startOfDay(@NonNull LocalDate date) {
		return date.atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
	}

	/** Inclusive of the whole final day, so a lecture at 5 p.m. on the last date is counted. */
	private static long endOfDay(@NonNull LocalDate date) {
		return date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toEpochSecond() - 1;
	}

	/**
	 * Adds an {@code IN} restriction for a set of IDs, or nothing at all when the set is empty.
	 * An empty set means no restriction, never an empty {@code IN ()}, which SQLite would reject.
	 */
	private static void appendIn(@NonNull StringBuilder sql, @NonNull List<Object> args,
	                             @NonNull String column, @NonNull Set<Long> values) {

		if (values.isEmpty()) {
			return;
		}

		sql.append("AND ").append(column).append(" IN (").append(placeholders(values.size()))
				.append(") ");
		args.addAll(values);
	}

	/** Enum columns are stored as constant names, so the name is what gets bound. */
	private static void appendEnumIn(@NonNull StringBuilder sql, @NonNull List<Object> args,
	                                 @NonNull String column,
	                                 @NonNull Set<? extends Enum<?>> values) {

		if (values.isEmpty()) {
			return;
		}

		sql.append("AND ").append(column).append(" IN (").append(placeholders(values.size()))
				.append(") ");

		for (Enum<?> value : values) {
			args.add(value.name());
		}
	}

	/** A comma-separated run of {@code ?} placeholders, one per value being bound. */
	@NonNull
	private static String placeholders(int count) {

		StringBuilder out = new StringBuilder();

		for (int i = 0; i < count; i++) {
			if (i > 0) {
				out.append(", ");
			}
			out.append("?");
		}
		return out.toString();
	}
}
