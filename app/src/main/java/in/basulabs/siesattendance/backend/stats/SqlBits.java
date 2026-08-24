package in.basulabs.siesattendance.backend.stats;

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
}
