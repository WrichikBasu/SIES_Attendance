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
	 * course, with lectures held and lectures attended.
	 * <p>
	 * Both figures are counted from the student's own marks and nothing else. Held is the
	 * lectures of the course the student has a mark for, present or absent; attended is the
	 * ones marked present. A register writes a mark for everybody on its sheet, so the marks
	 * are the record of who each lecture was held for on the day.
	 * <p>
	 * Batches are deliberately not consulted for the figures. Membership is only what is true
	 * today, and students change batch during the year: counting the lectures of a student's
	 * current batch charged somebody who moved in October with every lecture that batch had
	 * held since June, and somebody taken out of a batch lost the course altogether.
	 * <p>
	 * A student has a row for a course when they hold a mark in it this session, or are on
	 * its roll now (a member of one of its linked batches, as
	 * {@code DaoStudent.getRollForCourseSync} has it). The first keeps the attendance of a
	 * student who is no longer in any of the course's batches; the second lists a student who
	 * has just been put on the roll and has nothing marked yet, with nought held. Which rows
	 * appear does not depend on the date bounds, which only change the figures in them.
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

		// held: the lectures of the course the student has a mark for, present or absent
		sql.append("(SELECT COUNT(*) FROM attendance a ")
				.append(" INNER JOIN lectures l ON l.lectureID = a.lectureID ")
				.append(" WHERE a.studentID = s.studentID ")
				.append("   AND l.courseID = c.courseID ")
				.append("   AND ").append(lectureWindow())
				.append("   AND ").append(SqlBits.COUNTS_FOR_ENROLMENT)
				.append(") AS held, ");
		addWindowArgs(filter, args);

		// attended: the same marks, narrowed to present, so it can never exceed held
		sql.append("(SELECT COUNT(*) FROM attendance a ")
				.append(" INNER JOIN lectures l ON l.lectureID = a.lectureID ")
				.append(" WHERE a.studentID = s.studentID AND a.present = 1 ")
				.append("   AND l.courseID = c.courseID ")
				.append("   AND ").append(lectureWindow())
				.append("   AND ").append(SqlBits.COUNTS_FOR_ENROLMENT)
				.append(") AS attended ");
		addWindowArgs(filter, args);

		sql.append("FROM students s ")
				.append("INNER JOIN student_year y ON y.studentID = s.studentID ")
				.append("   AND y.academicYear = ? ")
				.append("INNER JOIN academic_years ay ON ay.academicYear = ? ")
				// On the course's roll now, or holding a mark in it this session.
				.append("INNER JOIN courses c ON (EXISTS (SELECT 1 FROM course_batch_map cb ")
				.append("   INNER JOIN batch_student_map m ON m.batchID = cb.batchID ")
				.append("   WHERE cb.courseID = c.courseID ")
				.append("     AND m.studentID = y.studentID ")
				.append("     AND m.academicYear = ?) ")
				.append(" OR EXISTS (SELECT 1 FROM attendance a ")
				.append("   INNER JOIN lectures l ON l.lectureID = a.lectureID ")
				.append("   WHERE a.studentID = y.studentID ")
				.append("     AND l.courseID = c.courseID ")
				.append("     AND ").append(SqlBits.EPOCH_START)
				.append("         BETWEEN ay.startEpoch AND ay.endEpoch ")
				.append("     AND ").append(SqlBits.COUNTS_FOR_ENROLMENT).append(")) ");
		// The two in student_year and academic_years, then the one in the roll condition, in
		// the order the placeholders appear.
		args.add(academicYear);
		args.add(academicYear);
		args.add(academicYear);

		// A student who has left is not on the register any more.
		sql.append("WHERE y.leaveDate IS NULL ");

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
