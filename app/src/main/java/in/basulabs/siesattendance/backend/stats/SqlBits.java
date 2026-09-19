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
