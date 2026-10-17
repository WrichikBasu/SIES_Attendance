package in.basulabs.siesattendance.server.sql;

import androidx.annotation.NonNull;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Division;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;
import in.basulabs.siesattendance.backend.util.ExportFilter;

/**
 * Assembles the export query with whatever filters were asked for.
 * <p>
 * The server's counterpart to the app's {@code ExportQueryBuilder}, built from the same
 * {@link ExportFilter}. Built as an assembled statement for the same reason the app's is: the
 * shape changes with the filter. Every user-supplied value is bound as an argument rather than
 * concatenated, so a course code or a date can never alter the statement.
 * <p>
 * The date bounds narrow the lecture window that is otherwise the academic session, and they
 * narrow it in both the held and attended subqueries. Applying them to only one would produce a
 * percentage over two different denominators, which is worse than no filter at all.
 */
public final class PgExportQueryBuilder {

	private PgExportQueryBuilder() {
	}

	/**
	 * One row per student per course they are on the roll for, with lectures held and lectures
	 * attended.
	 * <p>
	 * The join from students to courses repeats the roster condition the app's
	 * {@code DaoStudent.getRollForCourseSync} applies. The two have to agree, since a student
	 * who counts as taking a course in one and not the other would appear on a sheet without
	 * appearing in the report, or the reverse. The course types are written as literals rather
	 * than bound, so they need no cast: an unquoted literal is untyped and resolves to the
	 * column's enum type. A <em>parameter</em> would not.
	 * <p>
	 * Argument order is the thing to be careful about here. The arguments are positional and
	 * the subqueries are appended ahead of the FROM clause they refer to, so each block adds its
	 * own arguments immediately after appending its SQL rather than all of them at the end.
	 */
	@NonNull
	public static BoundStatement build(int academicYear, @NonNull ExportFilter filter) {

		List<Object> args = new ArrayList<>();

		StringBuilder sql = new StringBuilder();

		// Quoted aliases: PostgreSQL folds an unquoted identifier to lower case, and the
		// exporter looks these up by the names the app's query returns.
		sql.append("SELECT s.student_id AS \"studentID\", y.roll_no AS \"rollNo\", ")
				.append("s.name AS \"studentName\", c.course_id AS \"courseID\", ")
				.append("c.course_code AS \"courseCode\", ")
				.append("c.course_topic AS \"courseTopic\", ");

		// held
		sql.append("(SELECT COUNT(DISTINCT l.lecture_id) FROM lectures l ")
				.append(" WHERE l.course_id = c.course_id ")
				.append("   AND (l.batch_id IS NULL OR l.batch_id IN ")
				.append("        (SELECT m.batch_id FROM batch_student_map m ")
				.append("         WHERE m.student_id = s.student_id ")
				.append("           AND m.academic_year = ?)) ")
				.append("   AND ").append(lectureWindow())
				.append(") AS held, ");
		args.add(academicYear);
		addWindowArgs(filter, args);

		// attended
		sql.append("(SELECT COUNT(*) FROM attendance a ")
				.append(" INNER JOIN lectures l ON l.lecture_id = a.lecture_id ")
				.append(" WHERE a.student_id = s.student_id AND a.present ")
				.append("   AND l.course_id = c.course_id ")
				.append("   AND (l.batch_id IS NULL OR l.batch_id IN ")
				.append("        (SELECT m2.batch_id FROM batch_student_map m2 ")
				.append("         WHERE m2.student_id = s.student_id ")
				.append("           AND m2.academic_year = ?)) ")
				.append("   AND ").append(lectureWindow())
				.append(") AS attended ");
		args.add(academicYear);
		addWindowArgs(filter, args);

		sql.append("FROM students s ")
				.append("INNER JOIN student_year y ON y.student_id = s.student_id ")
				.append("   AND y.academic_year = ? ")
				.append("INNER JOIN academic_years ay ON ay.academic_year = ? ")
				.append("INNER JOIN courses c ON c.year = y.year AND c.stream = y.stream AND (")
				.append("   (c.course_type IN ('MAJOR', 'MAJOR_PRACTICALS', ")
				.append("                      'DSE', 'DSE_PRACTICALS') ")
				.append("      AND y.major_dept = c.offering_department) ")
				.append("   OR (c.course_type IN ('MINOR', 'MINOR_PRACTICALS') ")
				.append("      AND (y.minor_dept = c.offering_department ")
				.append("           OR y.major_dept = c.offering_department)) ")
				.append("   OR (c.course_type = 'OE' AND y.oe_dept = c.offering_department) ")
				.append("   OR c.course_type IN ('AEC', 'IKS', 'VSC', 'VSC_PRACTICALS', ")
				.append("                        'SEC', 'SEC_PRACTICALS')) ");
		args.add(academicYear);
		args.add(academicYear);

		sql.append("WHERE 1 = 1 ");

		appendIn(sql, args, "c.course_id", filter.courseIDs);
		appendEnumIn(sql, args, "y.major_dept", filter.departments, Department.class);
		appendEnumIn(sql, args, "y.year", filter.years, Year.class);
		appendEnumIn(sql, args, "y.stream", filter.streams, Stream.class);
		appendEnumIn(sql, args, "y.division", filter.divisions, Division.class);

		// Byte order rather than the database's collation, so a register reads the same as it
		// does on the device. See PgSqlBits.
		sql.append("ORDER BY y.roll_no ").append(PgSqlBits.BYTE_ORDER).append(" ASC, ")
				.append("c.course_code ").append(PgSqlBits.BYTE_ORDER).append(" ASC");

		return new BoundStatement(sql.toString(), args);
	}

	/**
	 * The lecture window used by both subqueries: inside the session, and inside any requested
	 * date bounds. Written once so the held and attended counts cannot drift onto different
	 * windows.
	 * <p>
	 * Both subqueries alias the lectures table as {@code l}, which is what
	 * {@link PgSqlBits#LECTURE_LOCAL_DATE} expects.
	 * <p>
	 * The app passes each bound as a flag plus a value, because a null compared with BETWEEN is
	 * never true and SQLite gave it no better way to say "no bound". Here the absent bound is
	 * simply a null, tested for directly. The cast is needed either way: a bare parameter next
	 * to a date has no type PostgreSQL can infer.
	 */
	@NonNull
	private static String lectureWindow() {
		String date = PgSqlBits.LECTURE_LOCAL_DATE;
		return "(" + date + " BETWEEN ay.start_date AND ay.end_date "
				+ "AND (?::date IS NULL OR " + date + " >= ?::date) "
				+ "AND (?::date IS NULL OR " + date + " <= ?::date))";
	}

	/**
	 * Four arguments per window, as in the app, but each pair is the same date twice — once for
	 * the null test and once for the comparison — rather than a flag and a value.
	 */
	private static void addWindowArgs(@NonNull ExportFilter filter, @NonNull List<Object> args) {

		args.add(text(filter.from));
		args.add(text(filter.from));
		args.add(text(filter.to));
		args.add(text(filter.to));
	}

	/**
	 * A date as the ISO text PostgreSQL parses, or null. Bound as text with an explicit cast
	 * rather than as a {@code java.sql.Date}, so that the statement and its arguments can be
	 * dumped and replayed verbatim by the differ.
	 */
	private static String text(LocalDate date) {
		return date == null ? null : date.toString();
	}

	/**
	 * Adds an {@code IN} restriction for a set of IDs, or nothing at all when the set is empty.
	 * An empty set means no restriction, never an empty {@code IN ()}, which is a syntax error.
	 */
	private static void appendIn(@NonNull StringBuilder sql, @NonNull List<Object> args,
	                             @NonNull String column, @NonNull Set<Long> values) {

		if (values.isEmpty()) {
			return;
		}

		sql.append("AND ").append(column).append(" IN (")
				.append(PgSqlBits.placeholders(values.size())).append(") ");
		args.addAll(values);
	}

	/**
	 * Enum columns are stored as constant names, so the name is what gets bound — and the
	 * placeholder carries a cast to the column's type, without which PostgreSQL has no
	 * comparison to apply.
	 */
	private static void appendEnumIn(@NonNull StringBuilder sql, @NonNull List<Object> args,
	                                 @NonNull String column,
	                                 @NonNull Set<? extends Enum<?>> values,
	                                 @NonNull Class<? extends Enum<?>> type) {

		if (values.isEmpty()) {
			return;
		}

		sql.append("AND ").append(column).append(" IN (")
				.append(PgSqlBits.enumPlaceholders(values.size(), type)).append(") ");

		for (Enum<?> value : values) {
			args.add(value.name());
		}
	}
}
