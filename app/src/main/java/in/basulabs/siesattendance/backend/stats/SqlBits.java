package in.basulabs.siesattendance.backend.stats;

import androidx.annotation.NonNull;

import java.util.function.Function;

/**
 * Shared SQL fragments for the statistics queries.
 * <p>
 * Small, but worth having in one place: the expression below is repeated across the group-by
 * definitions, the WHERE clause and the join to the sessions table, and a copy of it that drifted
 * would produce a report that quietly disagreed with itself.
 */
public final class SqlBits {

	/**
	 * Extracts the epoch-second prefix from a stored {@code ZonedDateTime}.
	 * <p>
	 * {@code TypeConvertors} writes timestamps as "epochSecond|nano|zone", so the leading
	 * integer has to be sliced out and cast before SQLite will treat it as a number. Comparing
	 * the raw string would sort lexicographically and break the moment the epoch gains a digit.
	 */
	public static final String EPOCH_START =
			"CAST(SUBSTR(l.startDateTime, 1, INSTR(l.startDateTime, '|') - 1) AS INTEGER)";

	/**
	 * The lecture's date on the wall calendar, as an epoch day: the same number the device
	 * stores a {@code LocalDate} as, so it compares directly with {@code student_year.joinDate}.
	 * Local time rather than UTC, because a lecture at 01:00 in Kolkata is the previous day in
	 * UTC and would otherwise count as held before somebody who joined that morning.
	 */
	public static final String LECTURE_LOCAL_DAY =
			"CAST(julianday(date(" + EPOCH_START + ", 'unixepoch', 'localtime')) - 2440587.5 "
					+ "AS INTEGER)";

	/**
	 * Whether a lecture counts for the enrolment {@code y}: between the day the student joined
	 * and the day they left, both included, with a missing date leaving that end open. A late
	 * joiner's attendance is counted from the day they joined, so a defaulter list does not hold
	 * the weeks before against them; and a leaver's stops on the day they left, which it did
	 * not until R10 -- a mark recorded against them afterwards still counted.
	 * {@code PgSqlBits.COUNTS_FOR_ENROLMENT} is the same rule.
	 */
	public static final String COUNTS_FOR_ENROLMENT =
			"((y.joinDate IS NULL OR " + LECTURE_LOCAL_DAY + " >= y.joinDate)"
					+ " AND (y.leaveDate IS NULL OR " + LECTURE_LOCAL_DAY + " <= y.leaveDate))";

	/** The session a lecture falls in: the latest one whose bounds contain its start. */
	private static final String SESSION_OF_LECTURE =
			"(SELECT ay2.academicYear FROM academic_years ay2"
					+ " WHERE " + EPOCH_START + " BETWEEN ay2.startEpoch AND ay2.endEpoch"
					+ " ORDER BY ay2.academicYear DESC LIMIT 1)";

	/**
	 * Joins {@code y}, the student's enrolment in the session of lecture {@code l}, for the
	 * student {@code a}, so {@link #COUNTS_FOR_ENROLMENT} can be applied. A left join: a mark in
	 * a session the student is no longer enrolled in has no row, and is kept, since
	 * {@code COUNTS_FOR_ENROLMENT} passes a null join date.
	 */
	public static final String ENROLMENT_AT_LECTURE =
			" LEFT JOIN student_year y ON y.studentID = a.studentID"
					+ "    AND y.academicYear = " + SESSION_OF_LECTURE + " ";

	/**
	 * The marks on record, as {@code (lectureID, studentID, present)}: what every report counts.
	 * <p>
	 * Attendance is computed from the marks alone. A register writes a mark, present or
	 * absent, for everybody on its sheet, so the marks are the record of who a lecture was
	 * held for on the day; a student with no mark at a lecture was not on its sheet, and the
	 * lecture counts neither for nor against them.
	 * <p>
	 * This used to add an absence for every lecture of a student's <em>current</em> batch that
	 * they had no mark for. Batch membership is only what is true today, and students change
	 * batch during the year, so that charged a student who moved into a batch with every
	 * lecture it had held before they arrived. {@code ExportQueryBuilder} counts the same way,
	 * so the register export and the statistics give the same figures.
	 * <p>
	 * A parenthesised subquery, to be given an alias by the query using it. Kept as a name, and
	 * not replaced by the table, so that there is one place that says what a report reads.
	 */
	public static final String MARKS =
			"(SELECT m.lectureID AS lectureID, m.studentID AS studentID, m.present AS present"
					+ " FROM attendance m)";

	private SqlBits() {
	}

	/**
	 * A CASE expression turning an enum column's stored value into the label shown for it.
	 * <p>
	 * Enums are stored by {@code name()}, which is an identifier and not something to show
	 * anyone: {@code COMPUTER_SCIENCE}, {@code MAJOR_PRACTICALS}, {@code Masters_P1}. A report
	 * row is labelled by whatever the query selects, so the translation has to happen here, in
	 * the SQL, rather than after the fact — a class label is several columns joined together
	 * and cannot be taken apart again reliably once it has been built.
	 * <p>
	 * Generated from the enum itself, one {@code WHEN} per constant, so a constant added later
	 * is labelled here without anything else to update. The labels are spliced into the
	 * statement as literals, which is safe only because they are compile-time constants of the
	 * app: nothing a user types ever reaches this method. Quotes are doubled anyway, so a label
	 * reworded to contain an apostrophe cannot break the statement.
	 * <p>
	 * An unrecognised stored value falls through to itself rather than to null. A row labelled
	 * with a raw name is at least visibly wrong; a blank one would hide that there is a row at
	 * all.
	 *
	 * @param column the column holding the stored name
	 * @param type   the enum the column is stored from
	 * @param label  how each constant is shown
	 */
	@NonNull
	public static <E extends Enum<E>> String labelOf(@NonNull String column,
	                                                 @NonNull Class<E> type,
	                                                 @NonNull Function<E, String> label) {

		StringBuilder sql = new StringBuilder("(CASE ").append(column);

		for (E constant : type.getEnumConstants()) {
			sql.append(" WHEN ").append(literal(constant.name()))
					.append(" THEN ").append(literal(label.apply(constant)));
		}

		return sql.append(" ELSE ").append(column).append(" END)").toString();
	}

	/** As {@link #labelOf(String, Class, Function)}, showing each constant by its toString. */
	@NonNull
	public static <E extends Enum<E>> String labelOf(@NonNull String column,
	                                                 @NonNull Class<E> type) {
		return labelOf(column, type, E::toString);
	}

	/** A SQL string literal, with any quote in it doubled. */
	@NonNull
	private static String literal(@NonNull String text) {
		return "'" + text.replace("'", "''") + "'";
	}
}
