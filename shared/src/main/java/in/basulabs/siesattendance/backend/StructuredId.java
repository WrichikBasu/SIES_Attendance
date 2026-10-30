package in.basulabs.siesattendance.backend;

/**
 * The layout of an app-issued primary key, and the arithmetic for reading one.
 * <p>
 * A 63-bit positive {@code long}, in three fields, most significant first:
 * <pre>
 *   [ 41 bits: milliseconds since 2024-01-01Z ][ 6 bits: sequence ][ 16 bits: node ]
 * </pre>
 * <ul>
 *     <li><b>Time</b> keeps IDs sorted by creation and keeps them readable: a key still says
 *     roughly when its row was made. 41 bits from the 2024 epoch runs to 2093.</li>
 *     <li><b>Sequence</b> separates rows created inside the same millisecond, so a bulk import
 *     writing hundreds of rows before the clock moves still gets distinct keys.</li>
 *     <li><b>Node</b> is the device, or the server. It is what makes two people entering a
 *     student at the same instant on two phones produce two different keys, with no
 *     coordination between them.</li>
 * </ul>
 *
 * <h3>Where the node number comes from</h3>
 * The 16-bit space is split in half, and the halves never overlap:
 * <ul>
 *     <li>{@code 0 … 32767} are handed out by the central database, one per installation.
 *     {@link #SERVER_NODE} is reserved out of that range for the server's own minting, which
 *     the importer needs when it has to replace a key that cannot be carried across.</li>
 *     <li>{@code 32768 … 65535} are what an installation picks for itself, at random, the
 *     first time it opens the database — before it has ever spoken to a server, which is the
 *     state every install starts in and some may stay in.</li>
 * </ul>
 *
 * <h3>Why this is shared</h3>
 * Three programs have to agree on it, and only one of them is the app. The server mints keys
 * of its own while importing, and reads the node out of a device's key to know which install
 * wrote the row. The PostgreSQL migration implements the same arithmetic in SQL, as
 * {@code id_is_structured} and {@code id_created_at}, and the drift test compares the constants
 * in that SQL against the ones here.
 * <p>
 * {@code IdGenerator} in the app keeps everything this does not: where a device stores its own
 * node number, and the running counter that stops a key being issued twice.
 */
public final class StructuredId {

	/**
	 * The zero point for the time field: 2024-01-01T00:00:00Z, comfortably before this app
	 * existed. Counting from here rather than from 1970 buys back the 54 years that would
	 * otherwise be spent on time nothing happened in, and is what leaves room for the node and
	 * sequence fields inside a positive {@code long}.
	 * <p>
	 * Changing this value re-dates every ID issued afterwards and is not a thing that can be
	 * done to a database in the field.
	 */
	public static final long EPOCH_MILLIS = 1_704_067_200_000L;

	/** Width of the node field, at the bottom of the ID. */
	public static final int NODE_BITS = 16;

	/** Width of the sequence field, immediately above the node. */
	public static final int SEQUENCE_BITS = 6;

	/** Width of the time field, filling the rest of the 63 usable bits: 41, so, until 2093. */
	public static final int TIME_BITS = 63 - NODE_BITS - SEQUENCE_BITS;

	/** The largest node number, and the mask that picks the node out of an ID. */
	public static final int MAX_NODE_ID = (1 << NODE_BITS) - 1;

	/**
	 * The highest node number the central database may assign. Above it is the range an
	 * install draws from for itself; see the class notes.
	 */
	public static final int MAX_ASSIGNED_NODE_ID = MAX_NODE_ID >>> 1;

	/**
	 * The node the server itself issues keys under.
	 * <p>
	 * Zero, taken out of the assignable range, so a device can never be given it. The importer
	 * mints a key whenever a device's own key cannot be carried across — a key from before the
	 * scheme existed, which several devices will each have used for different rows — and those
	 * keys have to be distinguishable from every device's for ever afterwards.
	 */
	public static final int SERVER_NODE = 0;

	/** The lowest node number a device may be assigned, given the server has taken zero. */
	public static final int FIRST_ASSIGNABLE_NODE_ID = SERVER_NODE + 1;

	/** Time and sequence together, which is what a running counter actually holds. */
	public static final long MAX_COUNTER = (1L << (TIME_BITS + SEQUENCE_BITS)) - 1;

	/**
	 * The smallest ID this scheme will ever produce, and so the line between a structured ID
	 * and one inherited from an older database.
	 * <p>
	 * It is not a free choice: it is the floor the counter is clamped to, which corresponds to
	 * about a hundred days past the epoch — early 2024, long before any device running this
	 * code will legitimately think it is. Clamping is what makes the line reliable, because a
	 * phone whose clock has been reset to 1970 would otherwise compute a tiny ID and land it
	 * squarely among the legacy keys.
	 */
	public static final long FIRST_STRUCTURED_ID = 1L << 55;

	/** The counter value {@link #FIRST_STRUCTURED_ID} corresponds to. */
	public static final long MIN_COUNTER = FIRST_STRUCTURED_ID >>> NODE_BITS;

	/** No node: what {@link #nodeOf} answers for a key that predates the scheme. */
	public static final int NO_NODE = -1;

	private StructuredId() {
	}

	/**
	 * Whether this key was issued by the scheme described above, rather than inherited from a
	 * database old enough to have let SQLite count, or to have used the raw clock.
	 */
	public static boolean isStructured(long id) {
		return id >= FIRST_STRUCTURED_ID;
	}

	/**
	 * The node that issued this key, or {@link #NO_NODE} for a key that predates node numbers.
	 * <p>
	 * The importer is the caller with the sharpest use for it: a legacy key means nothing
	 * outside the file it came from, and this is how that is told apart from a key that is
	 * unique everywhere.
	 */
	public static int nodeOf(long id) {
		return isStructured(id) ? (int) (id & MAX_NODE_ID) : NO_NODE;
	}

	/**
	 * When the row was created, in milliseconds since 1970, or {@code -1} for a key that
	 * predates the scheme.
	 * <p>
	 * Accurate to the millisecond except during a burst long enough to exhaust the sequence, or
	 * on a device whose clock was behind, both of which push the value forward. It is an
	 * indication of when a row appeared, not a timestamp to store.
	 */
	public static long createdAtMillis(long id) {
		return isStructured(id) ? ((id >>> NODE_BITS) >>> SEQUENCE_BITS) + EPOCH_MILLIS : -1L;
	}

	/** Assembles a key from a time-and-sequence counter and a node. */
	public static long compose(long counter, int node) {

		if (node < 0 || node > MAX_NODE_ID) {
			throw new IllegalArgumentException("Node out of range 0.." + MAX_NODE_ID
					+ ": " + node);
		}

		if (counter > MAX_COUNTER) {
			throw new IllegalStateException(
					"The ID time field is exhausted; the layout needs more than "
							+ TIME_BITS + " bits of time.");
		}
		return (counter << NODE_BITS) | node;
	}

	/** The counter field of a key, which is its time and sequence together. */
	public static long counterOf(long id) {
		return id >>> NODE_BITS;
	}
}
