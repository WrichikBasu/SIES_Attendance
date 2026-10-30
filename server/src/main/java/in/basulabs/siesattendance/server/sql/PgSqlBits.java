package in.basulabs.siesattendance.server.sql;

import androidx.annotation.NonNull;

import java.util.function.Function;

import in.basulabs.siesattendance.backend.PostgresEnumTypes;

/**
 * Shared PostgreSQL fragments for the statistics and export queries.
 * <p>
 * The counterpart to the app's {@code SqlBits}, and the place where the differences between the
 * two engines are written down once rather than scattered through the builders. Three of them
 * are worth reading before either builder:
 *
 * <h2>The lecture's own time zone</h2>
 * SQLite had no choice but to compare epoch seconds, and those epochs were derived from
 * whichever device wrote the row — {@code academic_years.startEpoch} was computed at the
 * device's zone, and {@code strftime(..., 'localtime')} read the zone of whoever ran the query.
 * A lecture at 9 p.m. could therefore fall in a different month depending on who asked. Here
 * the lecture carries its own zone, so {@link #LECTURE_LOCAL_DATE} and
 * {@link #LECTURE_LOCAL_TIMESTAMP} convert the instant at the zone it was recorded in, and the
 * answer is the same for every reader.
 *
 * <h2>Enum parameters need a cast</h2>
 * A PostgreSQL enum has no comparison against an untyped parameter: {@code WHERE y.year = ?}
 * with a string bound to it fails outright, unless the connection happens to be configured to
 * send strings as {@code unknown}. Relying on a connection setting for correctness is exactly
 * the kind of thing that works until someone changes a URL, so every enum parameter is cast at
 * the placeholder by {@link #enumPlaceholders}. Enum <em>literals</em> need nothing: an
 * unquoted literal in the statement is {@code unknown} and resolves to the column's type.
 *
 * <h2>Collation</h2>
 * SQLite compares text byte by byte. PostgreSQL compares it by the database's collation, which
 * on a default installation ignores case and punctuation in ways that reorder a register —
 * {@code 2025CS1} against {@code 2025cs10}. The ordering is therefore pinned to
 * {@link #BYTE_ORDER} rather than left to how the database happened to be created. Roll numbers
 * and course codes are ASCII identifiers compared against the college's own paperwork, so byte
 * order is also the right answer and not merely the matching one.
 */
public final class PgSqlBits {

	/**
	 * The lecture's start as a calendar date in the zone it was recorded in.
	 * <p>
	 * Both builders alias the lectures table as {@code l}. Used for the session bounds and for
	 * the date window, which are both expressed as dates rather than instants: a session runs
	 * from one date to another, and "up to 17 September" means the whole of that day.
	 */
	public static final String LECTURE_LOCAL_DATE = "(l.start_at AT TIME ZONE l.start_zone)::date";

	/**
	 * The lecture's start as local wall-clock time in the zone it was recorded in, for the
	 * groupings that need more than the date: the month label, the week number, the weekday.
	 */
	public static final String LECTURE_LOCAL_TIMESTAMP = "(l.start_at AT TIME ZONE l.start_zone)";

	/**
	 * Whether a lecture counts for the enrolment {@code y}: on or after the day the student
	 * joined, or any lecture when no join date is recorded. The app's
	 * {@code SqlBits.COUNTS_FOR_ENROLMENT} is the same rule.
	 */
	public static final String COUNTS_FOR_ENROLMENT =
			"(y.join_date IS NULL OR " + LECTURE_LOCAL_DATE + " >= y.join_date)";

	/** Pinned collation for every ordering that compares text. See the class comment. */
	public static final String BYTE_ORDER = "COLLATE \"C\"";

	private PgSqlBits() {
	}

	/**
	 * A CASE expression turning an enum column's stored value into the label shown for it.
	 * <p>
	 * As the app's {@code SqlBits.labelOf}, with one difference that matters: the fallback is
	 * {@code column::text} rather than the bare column. Without the cast the CASE would have
	 * the enum's own type rather than text, which breaks the moment the result is concatenated
	 * into a class label or ordered with an explicit collation — and it would print the stored
	 * name where a label was asked for.
	 * <p>
	 * Generated from the enum itself, one WHEN per constant, so a constant added later is
	 * labelled here with nothing else to update. The labels are spliced in as literals, which
	 * is safe only because they are compile-time constants of this application: nothing a user
	 * types ever reaches this method. Quotes are doubled anyway.
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

		return sql.append(" ELSE ").append(column).append("::text END)").toString();
	}

	/** As {@link #labelOf(String, Class, Function)}, showing each constant by its toString. */
	@NonNull
	public static <E extends Enum<E>> String labelOf(@NonNull String column,
	                                                 @NonNull Class<E> type) {
		return labelOf(column, type, E::toString);
	}

	/** A comma-separated run of {@code ?} placeholders, one per value being bound. */
	@NonNull
	public static String placeholders(int count) {

		StringBuilder out = new StringBuilder();

		for (int i = 0; i < count; i++) {
			if (i > 0) {
				out.append(", ");
			}
			out.append("?");
		}
		return out.toString();
	}

	/**
	 * As {@link #placeholders}, with each placeholder cast to an enum type. See the class
	 * comment for why the cast is not optional.
	 *
	 * @param type the enum whose PostgreSQL type the values are cast to
	 */
	@NonNull
	public static String enumPlaceholders(int count, @NonNull Class<? extends Enum<?>> type) {

		String cast = "?::" + PostgresEnumTypes.of(type);
		StringBuilder out = new StringBuilder();

		for (int i = 0; i < count; i++) {
			if (i > 0) {
				out.append(", ");
			}
			out.append(cast);
		}
		return out.toString();
	}

	/** A SQL string literal, with any quote in it doubled. */
	@NonNull
	static String literal(@NonNull String text) {
		return "'" + text.replace("'", "''") + "'";
	}
}
