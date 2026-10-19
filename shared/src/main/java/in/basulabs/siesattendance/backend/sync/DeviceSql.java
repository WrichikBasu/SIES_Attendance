package in.basulabs.siesattendance.backend.sync;

import androidx.annotation.NonNull;

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
	 * Writes a row the server sent, replacing whatever is there.
	 * <p>
	 * {@code INSERT OR REPLACE}, which is what Room's own {@code OnConflictStrategy.REPLACE}
	 * compiles to, so a pulled row lands the same way a locally edited one does. It fires the
	 * outbox triggers like any other write, which is why the pull has to set
	 * {@code sync_state.applying} around it — otherwise the device would announce the server's
	 * own rows straight back to it.
	 */
	@NonNull
	public static String upsertRow(@NonNull DeviceTables.Table table) {

		StringBuilder sql = new StringBuilder("INSERT OR REPLACE INTO `")
				.append(table.name()).append("` (");

		List<DeviceTables.Column> columns = table.carriedColumns();

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

	/** Removes a row the server says is gone. */
	@NonNull
	public static String deleteRow(@NonNull DeviceTables.Table table) {
		return "DELETE FROM `" + table.name() + "` WHERE " + keyPredicate(table);
	}

	/**
	 * The columns an {@link #upsertRow} statement binds, in order.
	 * <p>
	 * Handed out so the caller cannot bind them in a different order from the one the statement
	 * lists — which would compile, run, and write a student's roll number into their name.
	 */
	@NonNull
	public static List<DeviceTables.Column> upsertColumns(@NonNull DeviceTables.Table table) {
		return table.carriedColumns();
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
