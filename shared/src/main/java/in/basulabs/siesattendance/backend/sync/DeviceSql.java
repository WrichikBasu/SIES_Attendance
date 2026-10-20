package in.basulabs.siesattendance.backend.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;

import in.basulabs.siesattendance.backend.StructuredId;
import java.util.List;

/**
 * The statements the device runs on its own SQLite to send a row and to apply one.
 * <p>
 * Built from {@link DeviceTables} rather than written out, for the same reason the triggers are:
 * there is one description of what a table is keyed on and what it carries, and anything that
 * restates it is something that can fall out of step.
 * <p>
 * In {@code :shared} rather than in the app because this is the one half of the device's sync
 * that can be tested without a handset: the statements are text, and the server's test module has
 * a real SQLite to run them against. {@code DeviceSqlTest} does exactly that, on a database the
 * shared migrations built.
 *
 * <h3>Why a row goes out exactly as SQLite holds it</h3>
 * No conversion anywhere on the phone. A boolean is the 0 or 1 Room wrote, a timestamp is the
 * {@code "epochSecond|nano|zone"} string Room wrote, an enum is the constant's name. The server
 * converts, in both directions, with the code the backup importer already uses — so there is one
 * place that knows what the device meant, and it is the place that has been reading device
 * databases since Phase 3.
 */
public final class DeviceSql {

	private DeviceSql() {
	}

	/**
	 * Reads one row by its primary key, for an intent that has to carry the row.
	 * <p>
	 * Every column the server keeps, in {@link DeviceTables.Table#carriedColumns()} order. Not
	 * {@code SELECT *}: a column the device has and the server does not — {@code startEpoch},
	 * which V1 dropped — would travel as a field nothing on the other side expects.
	 */
	@NonNull
	public static String selectRow(@NonNull DeviceTables.Table table) {

		StringBuilder sql = new StringBuilder("SELECT ");
		List<DeviceTables.Column> columns = table.carriedColumns();

		for (int at = 0; at < columns.size(); at++) {
			sql.append(at == 0 ? "" : ", ").append('`').append(columns.get(at).room())
					.append('`');
		}

		return sql.append(" FROM `").append(table.name()).append("` WHERE ")
				.append(keyPredicate(table)).toString();
	}

	/**
	 * Whether a row is here, by its primary key: one result row if it is, none if it is not.
	 * <p>
	 * What a pulled row's parents are checked with before the row is written. A row whose
	 * parent is not on the device cannot be stored, and finding that out from the constraint
	 * instead would fail the statement and, with it, the whole page.
	 */
	@NonNull
	public static String rowExists(@NonNull DeviceTables.Table table) {
		return "SELECT 1 FROM `" + table.name() + "` WHERE " + keyPredicate(table) + " LIMIT 1";
	}

	/**
	 * Changes a row the server sent, where the device already has it, or null when there is
	 * nothing a row of this table could change.
	 * <p>
	 * Half of the pair that writes a pulled row; {@link #insertRow} is the other. The caller
	 * runs this one first and only inserts when it matched nothing, which is the whole point:
	 * <p>
	 * This used to be a single {@code INSERT OR REPLACE}, on the reasoning that it is what
	 * Room's own {@code OnConflictStrategy.REPLACE} compiles to. It is — and REPLACE means
	 * <em>delete the row that is there and insert this one</em>. Deleting a row the device's
	 * own rows point at is refused, because every foreign key here is {@code RESTRICT}, so the
	 * phone could not accept the server's copy of a lecture it already had marks against:
	 * {@code FOREIGN KEY constraint failed (code 1811 SQLITE_CONSTRAINT_TRIGGER)}, raised by
	 * the internal trigger RESTRICT is implemented with, and the whole page rolled back. A
	 * watermark that cannot move jams every page behind it for ever.
	 * <p>
	 * An UPDATE changes the row in place, so the children keep pointing at it. SQLite counts a
	 * row the WHERE matched as changed even when every value is identical, so "matched nothing"
	 * means "is not here" and nothing else. {@code ON CONFLICT … DO UPDATE} would say this in
	 * one statement, and cannot be used: it arrived in SQLite 3.24 and this app runs on API 26,
	 * where the system's SQLite is older than that.
	 * <p>
	 * Null for a table that is nothing but its key — a membership either exists or does not,
	 * and {@code UPDATE … SET} with no columns is not a statement. {@link #insertRow} ignores
	 * the conflict for those instead.
	 */
	@Nullable
	public static String updateRow(@NonNull DeviceTables.Table table) {

		List<DeviceTables.Column> columns = changeableColumns(table);

		if (columns.isEmpty()) {
			return null;
		}

		StringBuilder sql = new StringBuilder("UPDATE `").append(table.name()).append("` SET ");

		for (int at = 0; at < columns.size(); at++) {
			sql.append(at == 0 ? "" : ", ").append('`').append(columns.get(at).room())
					.append("` = ?");
		}

		return sql.append(" WHERE ").append(keyPredicate(table)).toString();
	}

	/**
	 * Writes a row the server sent that the device does not have.
	 * <p>
	 * The other half of the pair, run when {@link #updateRow} matched nothing. A plain INSERT
	 * on purpose: a row the server sends that will not go in is something to hear about, and
	 * {@code OR IGNORE} would turn the {@code academic_years} defect a handset found — a
	 * {@code NOT NULL} column the server does not keep — from a loud failure into a row that
	 * silently is not there. Conflict resolution would not have hidden the constraint that
	 * actually matters either way, since it never applies to foreign keys.
	 * <p>
	 * {@code OR IGNORE} for a table that is nothing but its key, where it is the only thing it
	 * can mean: the row the server is describing is already exactly here.
	 */
	@NonNull
	public static String insertRow(@NonNull DeviceTables.Table table) {

		StringBuilder sql = new StringBuilder("INSERT ")
				.append(changeableColumns(table).isEmpty() ? "OR IGNORE " : "")
				.append("INTO `").append(table.name()).append("` (");

		List<DeviceTables.Column> columns = insertColumns(table);

		for (int at = 0; at < columns.size(); at++) {
			sql.append(at == 0 ? "" : ", ").append('`').append(columns.get(at).room())
					.append('`');
		}

		sql.append(") VALUES (");

		for (int at = 0; at < columns.size(); at++) {
			sql.append(at == 0 ? "" : ", ").append('?');
		}

		return sql.append(")").toString();
	}

	/**
	 * The self-picked nodes appearing in this device's own keys, commonest first.
	 * <p>
	 * The last resort for an install made before the node it picked for itself was recorded in
	 * preferences. The information is not lost while the rows are still here: a key carries its
	 * node in the low sixteen bits, and a node above
	 * {@link in.basulabs.siesattendance.backend.StructuredId#MAX_ASSIGNED_NODE_ID} can only
	 * have been picked by a device for itself.
	 * <p>
	 * Ordered by how many rows carry each, because a device that somehow shows two should
	 * report the one it wrote most of its data under. Only the tables with a key of their own
	 * are asked: everywhere else the key belongs to another row.
	 */
	@NonNull
	public static String selfPickedNodes() {

		StringBuilder sql = new StringBuilder("SELECT `node`, SUM(`rows`) AS `rows` FROM (");
		boolean first = true;

		for (DeviceTables.Table table : DeviceTables.inDependencyOrder()) {

			if (table.surrogate() == null) {
				continue;
			}

			if (!first) {
				sql.append(" UNION ALL ");
			}
			first = false;

			// Nothing here comes from outside the program: the names are DeviceTables' and the
			// numbers are StructuredId's.
			sql.append("SELECT (`").append(table.surrogate()).append("` & ")
					.append(StructuredId.MAX_NODE_ID).append(") AS `node`, count(*) AS `rows` ")
					.append("FROM `").append(table.name()).append("` WHERE `")
					.append(table.surrogate()).append("` >= ")
					.append(StructuredId.FIRST_STRUCTURED_ID).append(" AND (`")
					.append(table.surrogate()).append("` & ")
					.append(StructuredId.MAX_NODE_ID).append(") > ")
					.append(StructuredId.MAX_ASSIGNED_NODE_ID)
					.append(" GROUP BY `node`");
		}

		return sql.append(") GROUP BY `node` ORDER BY `rows` DESC").toString();
	}

	/** Removes a row the server says is gone. */
	@NonNull
	public static String deleteRow(@NonNull DeviceTables.Table table) {
		return "DELETE FROM `" + table.name() + "` WHERE " + keyPredicate(table);
	}

	/**
	 * The columns an {@link #insertRow} statement binds, in order.
	 * <p>
	 * Handed out so the caller cannot bind them in a different order from the one the statement
	 * lists — which would compile, run, and write a student's roll number into their name.
	 * <p>
	 * The columns the server carries, and then the ones only the device has
	 * ({@link DeviceDerived#columnsOf}). Those last are not optional: {@code startEpoch} is
	 * {@code NOT NULL} on the device and absent from the server, so a statement that did not
	 * name it could not write an {@code academic_years} row at all — and a row that cannot be
	 * written is a watermark that never moves, which jams every later page behind it.
	 */
	@NonNull
	public static List<DeviceTables.Column> insertColumns(@NonNull DeviceTables.Table table) {

		List<DeviceTables.Column> columns = new ArrayList<>(table.carriedColumns());
		columns.addAll(DeviceDerived.columnsOf(table));

		return columns;
	}

	/**
	 * The columns an {@link #updateRow} statement binds, in order: what it sets, then the key
	 * it finds the row by.
	 */
	@NonNull
	public static List<DeviceTables.Column> updateColumns(@NonNull DeviceTables.Table table) {

		List<DeviceTables.Column> columns = new ArrayList<>(changeableColumns(table));

		for (String key : table.primaryKey()) {
			columns.add(columnNamed(table, key));
		}

		return columns;
	}

	/**
	 * Everything {@link #insertColumns} has that is not part of the key.
	 * <p>
	 * The key is what the row is; these are what it says. A table with none of them is a
	 * membership, and the two statements treat it differently for that reason.
	 */
	@NonNull
	private static List<DeviceTables.Column> changeableColumns(
			@NonNull DeviceTables.Table table) {

		List<DeviceTables.Column> columns = new ArrayList<>();

		for (DeviceTables.Column column : insertColumns(table)) {
			if (!table.primaryKey().contains(column.room())) {
				columns.add(column);
			}
		}

		return columns;
	}

	/** One column of a table by its Room name. */
	@NonNull
	private static DeviceTables.Column columnNamed(@NonNull DeviceTables.Table table,
	                                               @NonNull String room) {

		for (DeviceTables.Column column : insertColumns(table)) {
			if (column.room().equals(room)) {
				return column;
			}
		}

		throw new IllegalArgumentException(table.name() + " has no column " + room);
	}

	/**
	 * Whether a column reads back as text rather than as a whole number.
	 * <p>
	 * The drain has to ask, rather than calling something like {@code getObject}: a cursor's
	 * idea of a value's type is the driver's, and the two drivers in play do not agree —
	 * Android's gives a long where sqlite-jdbc gives an Integer for the same column. Reading by
	 * what the column <em>is</em> rather than by what the driver guessed is what keeps a key a
	 * key.
	 * <p>
	 * Four of the six kinds are stored as text on the device and two as integers; the split is
	 * {@link DeviceTables.Kind}'s and this is just where it is asked.
	 */
	public static boolean readsAsText(@NonNull DeviceTables.Column column) {

		return switch (column.kind()) {
			case TEXT, ENUM, ZONED_DATE_TIME -> true;
			case INTEGER, BOOLEAN, EPOCH_DAY -> false;
		};
	}

	/** {@code `k1` = ? AND `k2` = ?}, in the table's own key order. */
	@NonNull
	private static String keyPredicate(@NonNull DeviceTables.Table table) {

		StringBuilder predicate = new StringBuilder();
		List<String> key = table.primaryKey();

		for (int at = 0; at < key.size(); at++) {
			predicate.append(at == 0 ? "" : " AND ").append('`').append(key.get(at))
					.append("` = ?");
		}
		return predicate.toString();
	}
}
