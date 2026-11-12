package in.basulabs.siesattendance.backend.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes one row a pull brought into a device that already holds rows of its own.
 * <p>
 * The ordinary case is two statements: change the row where it is ({@link DeviceSql#updateRow}),
 * and insert it when it is not here ({@link DeviceSql#insertRow}). What this class adds is the
 * case those two cannot handle: the server's row is new here, and a row this device made holds
 * the same natural key under another id (C3).
 * <ul>
 *     <li>Another phone recorded the same class first. The server kept that lecture, and folded
 *     this phone's into it (V62).</li>
 *     <li>Two professors typed in the same course.</li>
 *     <li>A professor added a student on the phone and the office loaded the roster with the
 *     same roll number under another student.</li>
 * </ul>
 * The insert met the device's own unique index, and the statement used to fail the page: the
 * watermark never moved, and the phone said "Could not sync" from then on and received nothing
 * more. Now the collision is settled and the page goes on:
 * <ul>
 *     <li><b>The same row under two ids</b> -- a lecture in the same slot, or a course with the
 *     same code that is the same course -- is re-keyed to the server's id. Everything here that
 *     points at it follows, and so does everything queued for it, so marks taken on this phone
 *     reach the server under the lecture the server kept.</li>
 *     <li><b>An enrolment</b> whose roll number the server gives to somebody else gives way:
 *     the server's row replaces this device's. The server refused this device's version for
 *     good, and said so when it was pushed.</li>
 *     <li><b>Anything else</b> -- a course code reused for a different course, two people with
 *     one email -- is set aside: the server's row is not written, and the rest of the page is.
 *     People are never merged by a sync. The sweep after the next snapshot settles what is
 *     left.</li>
 * </ul>
 * A constraint failure that is not such a collision still fails, loudly: a row the server sends
 * that will not go in for some other reason is a defect to hear about.
 * <p>
 * And a row with a change still queued here is not written at all (C5). The feed sends a phone
 * its own push straight back, and the worker pulls right after it pushes: a mark corrected in
 * between was overwritten by the echo of the value just sent, and since the queue holds keys and
 * not values, the next push read the echo and sent the old mark again. The change waiting here
 * is newer than anything the server can be sending about that row, and it goes out on the next
 * push; until then this device's version stands, as the sweep already lets it.
 * <p>
 * Shared, and written against {@link Device} rather than Android's database, so that the server's
 * tests run exactly what the phone runs against the phone's real schema.
 */
public final class PulledRows {

	/** What writing a pulled row came to. */
	public enum Outcome {

		/** Written, by updating the row here or inserting it. */
		WRITTEN,

		/** Written, after re-keying this device's copy of the same row to the server's id. */
		REKEYED,

		/** Written, after removing this device's row that claimed the same natural key. */
		REPLACED,

		/** Not written: it collides with a row here that is not the same thing. */
		SET_ASIDE,

		/** Not written: this device has a change to that row still waiting to be sent. */
		KEPT_LOCAL
	}

	/** The device's database, as the drain and the tests each reach it. */
	public interface Device {

		/**
		 * Runs a statement and says how many rows it changed.
		 *
		 * @throws Conflict when a constraint refuses it
		 */
		int change(@NonNull String sql, @NonNull Object[] values);

		/** The first row's columns, read as whole numbers, or null when there is no row. */
		@Nullable
		List<Long> firstRow(@NonNull String sql, @NonNull Object[] values);
	}

	/** A statement a constraint refused, whatever the database calls that. */
	public static final class Conflict extends RuntimeException {

		public Conflict(@NonNull Throwable cause) {
			super(cause.getMessage(), cause);
		}
	}

	/**
	 * The device's unique indexes beyond the primary key, as the entities declare them, by
	 * table: what a pulled row can collide with. Only these can make the insert below fail on
	 * a row that is not here.
	 */
	private static final Map<String, List<List<String>>> UNIQUE = Map.of(
			"lectures", List.of(List.of("courseID", "batchID", "startDateTime", "endDateTime")),
			"courses", List.of(List.of("courseCode")),
			"student_year", List.of(List.of("rollNo", "academicYear")),
			"students", List.of(List.of("email"), List.of("apaarId")),
			"professors", List.of(List.of("email")));

	/**
	 * The tables where a row holding the same unique value is the same row, and what else has
	 * to match for that to be true. A lecture is its slot. A course is its code only when it is
	 * the same course: the same code for another year or type is a mistake, and the server
	 * refuses it in words rather than folding it (V62).
	 */
	private static final Map<String, List<String>> SAME_ROW_WHEN = Map.of(
			"lectures", List.of(),
			"courses", List.of("stream", "offeringDepartment", "year", "semester", "courseType"));

	/**
	 * The column whose value is changed for a moment to free the slot while a row is re-keyed:
	 * text, and part of the unique index.
	 */
	private static final Map<String, String> FREED_BY = Map.of(
			"lectures", "startDateTime",
			"courses", "courseCode");

	/** The tables whose colliding row here gives way to the server's: see the class notes. */
	private static final Set<String> SERVER_WINS = Set.of("student_year");

	private PulledRows() {
	}

	/**
	 * Writes the row, settling a collision with a row this device made.
	 *
	 * @param row the row as {@link DeviceDerived#fill} completes it, by Room column name
	 * @throws Conflict when a constraint refuses it for any other reason
	 */
	@NonNull
	public static Outcome write(@NonNull Device device, @NonNull DeviceTables.Table table,
	                            @NonNull Map<String, Object> row) {

		if (queued(device, table, keyOf(table, row))) {
			return Outcome.KEPT_LOCAL;
		}

		String update = DeviceSql.updateRow(table);

		if (update != null
				&& device.change(update, bindings(DeviceSql.updateColumns(table), row)) > 0) {
			return Outcome.WRITTEN;
		}

		try {
			insert(device, table, row);
			return Outcome.WRITTEN;

		} catch (Conflict conflict) {

			List<Long> mine = collidingRow(device, table, row);

			if (mine == null) {
				throw conflict;
			}

			List<String> sameWhen = SAME_ROW_WHEN.get(table.name());

			if (sameWhen != null) {

				if (!same(device, table, mine, row, sameWhen)) {
					return Outcome.SET_ASIDE;
				}

				rekey(device, table, mine.get(0), keyOf(table, row).get(0), row);
				return Outcome.REKEYED;
			}

			if (SERVER_WINS.contains(table.name())) {

				try {
					device.change(DeviceSql.deleteRow(table), mine.toArray());
				} catch (Conflict stillPointedAt) {
					return Outcome.SET_ASIDE;
				}

				forgetQueued(device, table, mine);
				insert(device, table, row);
				return Outcome.REPLACED;
			}

			return Outcome.SET_ASIDE;
		}
	}

	private static void insert(@NonNull Device device, @NonNull DeviceTables.Table table,
	                           @NonNull Map<String, Object> row) {
		device.change(DeviceSql.insertRow(table), bindings(DeviceSql.insertColumns(table), row));
	}

	/**
	 * The key of a row here that holds one of the incoming row's unique values under another
	 * key, or null when there is none -- in which case the conflict was something else.
	 */
	@Nullable
	private static List<Long> collidingRow(@NonNull Device device,
	                                       @NonNull DeviceTables.Table table,
	                                       @NonNull Map<String, Object> row) {

		List<List<String>> indexes = UNIQUE.get(table.name());

		if (indexes == null) {
			return null;
		}

		List<Long> incoming = keyOf(table, row);

		for (List<String> columns : indexes) {

			List<Object> values = new ArrayList<>();
			StringBuilder sql = new StringBuilder("SELECT ").append(keyColumns(table))
					.append(" FROM `").append(table.name()).append("` WHERE ");

			for (int at = 0; at < columns.size(); at++) {
				sql.append(at == 0 ? "" : " AND ").append('`').append(columns.get(at))
						.append("` = ?");
				values.add(row.get(columns.get(at)));
			}

			// A null never collides in a SQLite unique index, so a null value cannot be what
			// refused the row; = rather than IS keeps it from matching.
			if (values.contains(null)) {
				continue;
			}

			List<Long> found = device.firstRow(sql.append(" LIMIT 1").toString(),
					values.toArray());

			if (found != null && !found.equals(incoming)) {
				return found;
			}
		}
		return null;
	}

	/** Whether the row here and the incoming one agree on every one of those columns. */
	private static boolean same(@NonNull Device device, @NonNull DeviceTables.Table table,
	                            @NonNull List<Long> mine, @NonNull Map<String, Object> row,
	                            @NonNull List<String> columns) {

		if (columns.isEmpty()) {
			return true;
		}

		List<Object> values = new ArrayList<>(mine);
		StringBuilder sql = new StringBuilder("SELECT 1 FROM `").append(table.name())
				.append("` WHERE ").append(keyPredicate(table));

		for (String column : columns) {
			sql.append(" AND `").append(column).append("` IS ?");
			values.add(row.get(column));
		}

		return device.firstRow(sql.toString(), values.toArray()) != null;
	}

	/**
	 * Moves this device's copy of a row to the server's id, with everything that points at it
	 * and everything queued for it, and writes the server's row.
	 * <p>
	 * In an order the foreign keys accept without being deferred: the slot is freed on the copy,
	 * the server's row goes in, every child is pointed at it, and the copy goes. The server's id
	 * was not here a moment ago, so no child can already point at it and nothing collides on the
	 * way. The copy's own queued intent goes with it: the server's row is the record now, and
	 * what this device would have sent for it is what the server folded (V62).
	 */
	private static void rekey(@NonNull Device device, @NonNull DeviceTables.Table table,
	                          long mine, long theirs, @NonNull Map<String, Object> row) {

		String key = table.primaryKey().get(0);
		String freed = FREED_BY.get(table.name());

		device.change("UPDATE `" + table.name() + "` SET `" + freed + "` = `" + freed
				+ "` || ? WHERE `" + key + "` = ?", new Object[]{" (re-keyed " + mine + ")", mine});

		insert(device, table, row);

		for (DeviceTables.Table child : DeviceTables.inDependencyOrder()) {
			for (Map.Entry<String, String> reference : child.foreignKeys().entrySet()) {

				if (!reference.getValue().equals(table.name())) {
					continue;
				}

				device.change("UPDATE `" + child.name() + "` SET `" + reference.getKey()
						+ "` = ? WHERE `" + reference.getKey() + "` = ?", new Object[]{theirs, mine});

				int position = child.primaryKey().indexOf(reference.getKey());

				if (position >= 0) {
					// A queued change to a child is keyed by the child's key, which held the
					// old id. Moved with it, so it is sent under the row the server kept.
					String slot = "key" + (position + 1);
					device.change("UPDATE OR IGNORE `" + OutboxTriggers.OUTBOX + "` SET `" + slot
							+ "` = ? WHERE `tableName` = ? AND `" + slot + "` = ?",
							new Object[]{theirs, child.name(), mine});
					device.change("DELETE FROM `" + OutboxTriggers.OUTBOX
							+ "` WHERE `tableName` = ? AND `" + slot + "` = ?",
							new Object[]{child.name(), mine});
				}
			}
		}

		device.change(DeviceSql.deleteRow(table), new Object[]{mine});
		forgetQueued(device, table, List.of(mine));
	}

	/**
	 * Whether a change to that row is waiting to be sent, under the key the outbox stores it by:
	 * the row's key, with {@link OutboxTriggers#UNUSED_KEY} in the slots it does not use.
	 */
	public static boolean queued(@NonNull Device device, @NonNull DeviceTables.Table table,
	                             @NonNull List<Long> key) {

		Object[] values = new Object[4];
		values[0] = table.name();

		for (int at = 0; at < 3; at++) {
			values[at + 1] = at < key.size() ? key.get(at) : OutboxTriggers.UNUSED_KEY;
		}

		return device.firstRow("SELECT 1 FROM `" + OutboxTriggers.OUTBOX
				+ "` WHERE `tableName` = ? AND `key1` = ? AND `key2` = ? AND `key3` = ?",
				values) != null;
	}

	/** Drops whatever is queued for that row: the server's row is the record now. */
	private static void forgetQueued(@NonNull Device device, @NonNull DeviceTables.Table table,
	                                 @NonNull List<Long> key) {

		StringBuilder sql = new StringBuilder("DELETE FROM `").append(OutboxTriggers.OUTBOX)
				.append("` WHERE `tableName` = ?");
		List<Object> values = new ArrayList<>();
		values.add(table.name());

		for (int at = 0; at < key.size(); at++) {
			sql.append(" AND `key").append(at + 1).append("` = ?");
			values.add(key.get(at));
		}
		device.change(sql.toString(), values.toArray());
	}

	@NonNull
	private static List<Long> keyOf(@NonNull DeviceTables.Table table,
	                                @NonNull Map<String, Object> row) {

		List<Long> key = new ArrayList<>();

		for (String column : table.primaryKey()) {
			key.add(((Number) row.get(column)).longValue());
		}
		return key;
	}

	@NonNull
	private static String keyColumns(@NonNull DeviceTables.Table table) {

		StringBuilder columns = new StringBuilder();

		for (int at = 0; at < table.primaryKey().size(); at++) {
			columns.append(at == 0 ? "" : ", ").append('`').append(table.primaryKey().get(at))
					.append('`');
		}
		return columns.toString();
	}

	@NonNull
	private static String keyPredicate(@NonNull DeviceTables.Table table) {

		StringBuilder predicate = new StringBuilder();

		for (int at = 0; at < table.primaryKey().size(); at++) {
			predicate.append(at == 0 ? "" : " AND ").append('`')
					.append(table.primaryKey().get(at)).append("` = ?");
		}
		return predicate.toString();
	}

	/** A row's values in the order a statement's columns list them. */
	@NonNull
	public static Object[] bindings(@NonNull List<DeviceTables.Column> columns,
	                                @NonNull Map<String, Object> row) {

		Object[] values = new Object[columns.size()];

		for (int at = 0; at < columns.size(); at++) {
			values[at] = row.get(columns.get(at).room());
		}
		return values;
	}
}
