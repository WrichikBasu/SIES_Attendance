package in.basulabs.siesattendance.backend;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;

import androidx.annotation.NonNull;
import androidx.sqlite.db.SupportSQLiteDatabase;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hands out the primary keys for the five tables whose identifiers mean nothing in the world:
 * students, professors, courses, batches and lectures.
 * <p>
 * These used to be {@code AUTOINCREMENT} columns, left to SQLite. They are now stamped here,
 * before the row is written, and they are unique not merely within one database but across
 * every device that will ever sync into the central one. That is the whole point: a key issued
 * on one phone must never mean a different row on another, because the two will meet.
 *
 * <h3>What an ID is made of</h3>
 * The layout itself is {@link StructuredId}, in the shared module, because the server needs it
 * too: the importer reads the node out of a device's key and mints keys of its own under
 * {@link StructuredId#SERVER_NODE} for rows whose keys cannot be carried across. What stays
 * here is everything that is only true of a phone — where the node number is stored, and the
 * counter that stops one being issued twice.
 * <p>
 * A 63-bit positive {@code long}, in three fields, most significant first:
 * <pre>
 *   [ 41 bits: milliseconds since 2024-01-01Z ][ 6 bits: sequence ][ 16 bits: node ]
 * </pre>
 * <ul>
 *     <li><b>Time</b> keeps IDs sorted by creation and keeps them readable: a key still says
 *     roughly when its row was made, which is more than a sequence number ever did. 41 bits
 *     from the 2024 epoch runs to 2093.</li>
 *     <li><b>Sequence</b> separates rows created inside the same millisecond. A CSV import
 *     writes hundreds of rows before the clock moves, so an ID is never merely the current
 *     time: it is the current time or one past the last one issued, whichever is greater. The
 *     sequence is therefore strictly increasing whatever the clock does, including going
 *     backwards over a time-zone or NTP correction, and a burst longer than 64 rows simply
 *     borrows from the next millisecond.</li>
 *     <li><b>Node</b> is the device. It is what makes two people entering a student at the same
 *     instant on two phones produce two different keys, with no coordination between them and
 *     no connection to the server at the time.</li>
 * </ul>
 *
 * <h3>Where the node number comes from</h3>
 * The 16-bit space is split in half, and the halves never overlap:
 * <ul>
 *     <li>{@code 0 … 32767} are handed out by the central database, one per installation, and
 *     applied here through {@link #assignNodeID}.</li>
 *     <li>{@code 32768 … 65535} are what an installation picks for itself, at random, the first
 *     time it opens the database — before it has ever spoken to a server, which is the state
 *     every install starts in and some may stay in.</li>
 * </ul>
 * So a provisional number can never collide with an assigned one. Two installs can still draw
 * the same provisional number — with 50 of them the odds are about one in twenty-seven — and even
 * then their keys only actually clash if both also create a row in the same millisecond with
 * the same sequence value. Registering with the server replaces the guess with a number nobody
 * else has, and rows written before that keep the keys they were given: they are already
 * unique, and rewriting a primary key would mean rewriting every foreign key pointing at it.
 * <p>
 * The number lives in a preferences file, not in the database, and that is deliberate. A backup
 * carries the database and nothing else, so restoring one phone's backup onto another does not
 * hand the second phone the first phone's identity — which is exactly the way to manufacture
 * the collision this class exists to prevent.
 *
 * <h3>Rows written before all this</h3>
 * Databases from before version 7 hold keys that SQLite counted out, and briefly after it, keys
 * that were a plain {@link System#currentTimeMillis()}. Both are far below
 * {@link #FIRST_STRUCTURED_ID}, so they cannot be mistaken for a structured ID and nothing
 * issued here can ever land on one. They are left alone: they are unique in the database that
 * holds them, and the merge matches rows on natural keys anyway.
 * <p>
 * Nothing here is a substitute for the primary key constraint itself; it is what makes a clash
 * with it practically unreachable.
 */
public final class IdGenerator {

	/** @see StructuredId#EPOCH_MILLIS */
	private static final long EPOCH_MILLIS = StructuredId.EPOCH_MILLIS;

	/** @see StructuredId#NODE_BITS */
	private static final int NODE_BITS = StructuredId.NODE_BITS;

	/** @see StructuredId#SEQUENCE_BITS */
	private static final int SEQUENCE_BITS = StructuredId.SEQUENCE_BITS;

	/** @see StructuredId#TIME_BITS */
	private static final int TIME_BITS = StructuredId.TIME_BITS;

	/** @see StructuredId#MAX_NODE_ID */
	public static final int MAX_NODE_ID = StructuredId.MAX_NODE_ID;

	/** @see StructuredId#MAX_ASSIGNED_NODE_ID */
	public static final int MAX_ASSIGNED_NODE_ID = StructuredId.MAX_ASSIGNED_NODE_ID;

	/** @see StructuredId#MAX_COUNTER */
	private static final long MAX_COUNTER = StructuredId.MAX_COUNTER;

	/** @see StructuredId#FIRST_STRUCTURED_ID */
	public static final long FIRST_STRUCTURED_ID = StructuredId.FIRST_STRUCTURED_ID;

	/** @see StructuredId#MIN_COUNTER */
	private static final long MIN_COUNTER = StructuredId.MIN_COUNTER;

	/**
	 * The tables that get their keys from here, each with the column the key lives in. Used by
	 * {@link #prime}, and the one place that knows the full list.
	 */
	private static final String[][] GENERATED_KEYS = {
			{EntityStudent.TABLE_NAME, "studentID"},
			{EntityProfessor.TABLE_NAME, "profID"},
			{EntityCourse.TABLE_NAME, "courseID"},
			{EntityBatchDesc.TABLE_NAME, "batchID"},
			{EntityLecture.TABLE_NAME, "lectureID"}
	};

	private static final String PREFS_NAME = "id_generator";

	private static final String PREF_NODE_ID = "nodeID";

	/** No node number yet, which is also what {@link #nodeID} holds before {@link #init}. */
	private static final int UNASSIGNED = -1;

	/**
	 * Time and sequence of the last ID handed out, and the floor the next one must clear.
	 * Atomic because inserts run on {@code MainDatabase.DB_EXECUTOR}, which is a pool of four.
	 */
	private static final AtomicLong lastCounter = new AtomicLong(0L);

	/**
	 * This installation's node number. Volatile rather than atomic: it is written once by
	 * {@link #init} and rarely by {@link #assignNodeID}, and read by every insert.
	 */
	private static volatile int nodeID = UNASSIGNED;

	private IdGenerator() {
	}

	/**
	 * Establishes this installation's node number, reading it back from disk or drawing one the
	 * first time.
	 * <p>
	 * Called from {@code MainDatabase} before the database is built, which is the only route to
	 * any of the tables and therefore the one place that catches every path — the live
	 * database, the staging copy a merge migrates, and whatever else is added later.
	 * <p>
	 * The preferences file is opened under device-protected storage, the same place the
	 * database lives, so that it is readable after a reboot before the user has unlocked the
	 * device. A node number stored where it cannot be read is worse than none: the install
	 * would quietly draw a second one and start issuing keys as if it were two devices.
	 */
	public static synchronized void init(@NonNull Context context) {

		if (nodeID != UNASSIGNED) {
			return;
		}

		SharedPreferences prefs = prefs(context);
		int stored = prefs.getInt(PREF_NODE_ID, UNASSIGNED);

		if (stored < 0 || stored > MAX_NODE_ID) {
			stored = MAX_ASSIGNED_NODE_ID + 1 + new SecureRandom().nextInt(MAX_ASSIGNED_NODE_ID + 1);
			writeNodeID(prefs, stored);
		}

		nodeID = stored;
	}

	/**
	 * Adopts the node number the central database has issued to this installation, replacing
	 * whatever provisional one it drew for itself.
	 * <p>
	 * Safe to call repeatedly with the same number, which is what a sync that re-reads its
	 * registration will do. Rows already written keep their keys: those keys are unique
	 * already, and renumbering them would mean rewriting every foreign key that points at them.
	 *
	 * @param assignedNodeID a number from the server's range, {@code 0 … MAX_ASSIGNED_NODE_ID}
	 * @throws IllegalArgumentException if the number is outside the range the server owns —
	 *                                  including the self-assigned range above it, which the
	 *                                  server must never hand out, since two installs may have
	 *                                  guessed their way into it
	 */
	public static synchronized void assignNodeID(@NonNull Context context, int assignedNodeID) {

		if (assignedNodeID < 0 || assignedNodeID > MAX_ASSIGNED_NODE_ID) {
			throw new IllegalArgumentException("Node ID out of the assignable range 0.."
					+ MAX_ASSIGNED_NODE_ID + ": " + assignedNodeID);
		}

		if (nodeID != assignedNodeID) {
			writeNodeID(prefs(context), assignedNodeID);
			nodeID = assignedNodeID;
		}
	}

	/**
	 * This installation's node number, or {@code -1} before {@link #init} has run.
	 * <p>
	 * Worth showing on a diagnostics screen: it is the one number that says which device a key
	 * came from, and the one to quote when two installs are suspected of sharing it.
	 */
	public static int nodeID() {
		return nodeID;
	}

	/**
	 * A fresh ID, from this installation's node, the current time, and a sequence that keeps
	 * the value climbing when the clock does not.
	 *
	 * @throws IllegalStateException if {@link #init} has not run, rather than issue a key from
	 *                               a node number that does not exist yet; or, in 2093, if the
	 *                               time field has run out
	 */
	public static long newID() {

		int node = nodeID;

		if (node == UNASSIGNED) {
			throw new IllegalStateException(
					"IdGenerator.init() must run before any ID is issued.");
		}

		return StructuredId.compose(nextCounter(), node);
	}

	/**
	 * The ID a row should be inserted under: the one it already carries, or a fresh one if it
	 * has none.
	 * <p>
	 * Zero stands for "none". A key field that was never set holds it, and no generated ID can
	 * ever be zero, since the counter starts well above zero and only climbs.
	 */
	public static long idFor(long existingID) {
		return existingID == 0L ? newID() : existingID;
	}

	/**
	 * Whether this key was issued by the scheme described above, rather than inherited from a
	 * database old enough to have let SQLite count, or to have used the raw clock.
	 */
	public static boolean isStructured(long id) {
		return StructuredId.isStructured(id);
	}

	/**
	 * The node that issued this key, or {@code -1} for a key that predates node numbers.
	 * <p>
	 * The sync code is the caller with a real use for it: a row whose node is this install's is
	 * one this install created, whoever else has since edited it.
	 */
	public static int nodeOf(long id) {
		return StructuredId.nodeOf(id);
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
		return StructuredId.createdAtMillis(id);
	}

	/**
	 * Raises the floor above every ID this node has already issued into the given database.
	 * <p>
	 * Called on every open, including the throwaway one the merge stages a backup in. The
	 * counter lives in memory and dies with the process, so without this a device whose clock
	 * had been wound back — or a backup restored from a phone whose clock ran ahead — could
	 * issue an ID that is already taken, and the insert would abort.
	 * <p>
	 * Only this node's keys are considered, because only they can be clashed with: a key from
	 * another install differs in the node field however close the clocks are. That also keeps
	 * one device with a fast clock from dragging every other device's counter forward, which
	 * would make their IDs claim a creation time that never happened. Legacy keys are excluded
	 * for the same reason — they carry no node, and their low bits would match this node's by
	 * coincidence one time in 65536.
	 */
	public static void prime(@NonNull SupportSQLiteDatabase db) {

		int node = nodeID;

		if (node == UNASSIGNED) {
			// init() runs before the database is built, so this is unreachable in the app; a
			// test opening a database directly gets nothing primed rather than an exception.
			return;
		}

		StringBuilder sql = new StringBuilder("SELECT MAX(highest) FROM (");
		List<Object> args = new ArrayList<>();

		for (int i = 0; i < GENERATED_KEYS.length; i++) {

			if (i > 0) {
				sql.append(" UNION ALL ");
			}

			String table = GENERATED_KEYS[i][0];
			String column = GENERATED_KEYS[i][1];

			sql.append("SELECT MAX(`").append(column).append("`) AS highest FROM `")
					.append(table).append("` WHERE `").append(column).append("` >= ? AND (`")
					.append(column).append("` & ?) = ?");

			args.add(FIRST_STRUCTURED_ID);
			args.add((long) MAX_NODE_ID);
			args.add((long) node);
		}
		sql.append(')');

		try (Cursor cursor = db.query(sql.toString(), args.toArray())) {
			if (cursor.moveToFirst() && !cursor.isNull(0)) {
				lastCounter.accumulateAndGet(cursor.getLong(0) >>> NODE_BITS, Math::max);
			}
		}
	}

	/**
	 * The next time-and-sequence value: the clock, or one past the last one issued when the
	 * clock has not moved on, has moved backwards, or is being outrun by a bulk import.
	 */
	private static long nextCounter() {

		while (true) {

			long previous = lastCounter.get();
			long fromClock = (System.currentTimeMillis() - EPOCH_MILLIS) << SEQUENCE_BITS;
			long candidate = Math.max(Math.max(fromClock, MIN_COUNTER), previous + 1);

			if (candidate > MAX_COUNTER) {
				throw new IllegalStateException(
						"The ID time field is exhausted; the layout needs more than "
								+ TIME_BITS + " bits of time.");
			}

			if (lastCounter.compareAndSet(previous, candidate)) {
				return candidate;
			}
		}
	}

	@NonNull
	private static SharedPreferences prefs(@NonNull Context context) {
		return context.createDeviceProtectedStorageContext()
				.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
	}

	/**
	 * Writes the node number and waits for it to land. {@code commit} rather than {@code apply}
	 * because the very next thing that happens is a row being stamped with this number: if the
	 * process died in between, the install would come back, find nothing stored, draw a
	 * different number, and have issued keys under two identities.
	 */
	private static void writeNodeID(@NonNull SharedPreferences prefs, int value) {
		prefs.edit().putInt(PREF_NODE_ID, value).commit();
	}
}
