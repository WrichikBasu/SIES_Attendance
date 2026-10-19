package in.basulabs.siesattendance.server.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import in.basulabs.siesattendance.backend.StructuredId;
import in.basulabs.siesattendance.backend.sync.DeviceTables;
import in.basulabs.siesattendance.backend.sync.OutboxOp;

/**
 * Applies what a device's outbox drained: one row per intent, by primary key.
 * <p>
 * Much simpler than the backup importer, and for one reason — the keys already mean something.
 * A device issues structured keys that are unique everywhere, so there is nothing to reconcile
 * and no identity map: an intent names a row, and the row is written under the key it names.
 * The importer's natural-key matching exists for history written before any of that was true.
 *
 * <h3>Idempotent by construction</h3>
 * Every upsert is {@code INSERT … ON CONFLICT (primary key) DO UPDATE}, so the same intent
 * applied twice leaves the same row. That is what makes a lost response harmless: the device
 * cannot tell whether its batch arrived, and it does not have to — it sends it again. A delete
 * is equally forgiving, because deleting a row that is not there is not an error here.
 *
 * <h3>Which tables, and why so few</h3>
 * {@code synced_tables()} in V10 says, and the answer is {@code lectures} and {@code attendance}.
 * Both are under {@code FORCE ROW LEVEL SECURITY}, so every statement below is policed by the
 * policies rather than by this class: a professor pushing another department's lecture is refused
 * by the database, and the refusal arrives here as an error to report rather than as a decision
 * to make. The tables that have no policies are not accepted at all, because accepting them would
 * mean this class deciding who may rewrite a roster — which is exactly the thing the whole design
 * keeps out of Java.
 *
 * <h3>The node check</h3>
 * A row whose key the device issued has to carry that device's node. It is the one rule here that
 * is not the database's: a phone must not be able to write rows claiming to be another phone's,
 * and nothing in SQL knows which phone is holding the token. Attendance has no key of its own to
 * check — its key is a lecture and a student, both issued elsewhere — and it does not need one: a
 * CR marking a register the professor created is the ordinary case, and the policies decide it.
 */
public final class SyncWriter {

	/**
	 * One row a device wants written or removed.
	 *
	 * @param table the Room table name, which is the same on both sides
	 * @param key   the primary key values, in {@link DeviceTables.Table#primaryKey()} order
	 * @param row   the row as the device holds it, keyed by Room column name. Null for a delete
	 */
	public record Intent(@NonNull String table, @NonNull List<Long> key, @NonNull OutboxOp op,
	                     @Nullable Map<String, Object> row) {
	}

	/** An intent that was not applied, and the sentence the device should show. */
	public record Rejection(@NonNull String table, @NonNull List<Long> key,
	                        @NonNull String reason) {
	}

	/**
	 * @param accepted how many intents were applied
	 * @param rejected the ones that were not, each with a reason. A device keeps these rather
	 *                 than dropping them, so nothing is lost quietly
	 */
	public record Result(int accepted, @NonNull List<Rejection> rejected) {
	}

	/** PostgreSQL's {@code insufficient_privilege}: a policy refused the statement. */
	private static final String INSUFFICIENT_PRIVILEGE = "42501";

	private SyncWriter() {
	}

	/**
	 * Applies a batch on the caller's transaction, which must already have an actor announced
	 * on it.
	 * <p>
	 * Each intent runs inside a savepoint, so one the policies refuse costs only itself and the
	 * rest of the batch still commits. That matters more here than anywhere else in this server:
	 * a phone that has been offline for a day sends everything at once, and a single bad intent
	 * must not send the whole day back to the queue for ever.
	 *
	 * @param deviceNode the node of the device presenting the token; see the class notes
	 */
	@NonNull
	public static Result apply(@NonNull Connection connection, @NonNull List<Intent> intents,
	                           @Nullable Integer deviceNode) throws SQLException {

		Set<String> allowed = syncedTables(connection);
		List<Rejection> rejected = new ArrayList<>();
		int accepted = 0;

		for (Intent intent : inApplicationOrder(intents)) {

			String refusal = unacceptable(intent, allowed, deviceNode);

			if (refusal != null) {
				rejected.add(new Rejection(intent.table(), intent.key(), refusal));
				continue;
			}

			Savepoint savepoint = connection.setSavepoint();

			try {
				if (intent.op() == OutboxOp.DELETE) {
					delete(connection, intent);
				} else {
					upsert(connection, intent);
				}

				connection.releaseSavepoint(savepoint);
				accepted++;

			} catch (DeviceValue.Unconvertible e) {
				connection.rollback(savepoint);
				rejected.add(new Rejection(intent.table(), intent.key(), e.getMessage()));

			} catch (SQLException e) {
				connection.rollback(savepoint);
				rejected.add(new Rejection(intent.table(), intent.key(), explain(e)));
			}
		}

		return new Result(accepted, rejected);
	}

	/**
	 * The order the server applies a batch in, which is not the order the device sent it.
	 * <p>
	 * Upserts in dependency order — a lecture before the marks on it — and deletes in the
	 * reverse, because the foreign keys are {@code RESTRICT} and a lecture cannot go while its
	 * attendance is still there. The device could sort them itself and the server would still
	 * have to, because a batch is not something to take the sender's word about.
	 */
	@NonNull
	static List<Intent> inApplicationOrder(@NonNull List<Intent> intents) {

		List<String> order = new ArrayList<>();

		for (DeviceTables.Table table : DeviceTables.inDependencyOrder()) {
			order.add(table.name());
		}

		List<Intent> sorted = new ArrayList<>(intents);

		sorted.sort(Comparator
				// Upserts first: a delete of a row this batch also creates is the row being
				// made and unmade, and applying the delete last is what leaves it gone.
				.comparingInt((Intent intent) -> intent.op() == OutboxOp.DELETE ? 1 : 0)
				.thenComparingInt(intent -> {
					int at = order.indexOf(intent.table());
					int depth = at < 0 ? order.size() : at;
					return intent.op() == OutboxOp.DELETE ? -depth : depth;
				}));

		return sorted;
	}

	/** Why this intent cannot be applied at all, or null. */
	@Nullable
	private static String unacceptable(@NonNull Intent intent, @NonNull Set<String> allowed,
	                                   @Nullable Integer deviceNode) {

		if (!allowed.contains(intent.table())) {
			return "this server does not accept " + intent.table() + " from a device yet";
		}

		DeviceTables.Table table;

		try {
			table = DeviceTables.of(intent.table());
		} catch (RuntimeException e) {
			return "unknown table " + intent.table();
		}

		if (intent.key().size() != table.primaryKey().size()) {
			return intent.table() + " is keyed on " + table.primaryKey().size()
					+ " columns, not " + intent.key().size();
		}

		if (intent.op() == OutboxOp.UPSERT && intent.row() == null) {
			return "an upsert has to carry the row";
		}

		if (table.surrogate() == null) {
			// Nothing of this table's own was issued by a device, so there is no node in its
			// key to check. See the class notes.
			return null;
		}

		long key = intent.key().get(table.primaryKey().indexOf(table.surrogate()));

		if (!StructuredId.isStructured(key)) {
			// A key from before the scheme existed means nothing outside the file it came from.
			// Those rows reach the server through the backup importer, which can renumber them;
			// a sync push cannot, because there is nothing here to tell it what the key means.
			return "key " + key + " predates the id scheme and cannot be synced; that row "
					+ "belongs in a backup import";
		}

		if (deviceNode == null) {
			return "this session is not on a registered device, so it may not write rows";
		}

		if (StructuredId.nodeOf(key) != deviceNode) {
			return "key " + key + " was issued by node " + StructuredId.nodeOf(key)
					+ ", not by this device";
		}

		return null;
	}

	// ── The statements ───────────────────────────────────────────────────

	private static void upsert(@NonNull Connection connection, @NonNull Intent intent)
			throws SQLException {

		DeviceTables.Table table = DeviceTables.of(intent.table());
		Map<String, Object> row = intent.row();

		List<DeviceValue.Field> fields = new ArrayList<>();

		for (DeviceTables.Column column : table.carriedColumns()) {
			fields.addAll(DeviceValue.convert(table, column, row.get(column.room())));
		}

		// The key as the device named it wins over whatever the row claims: the key is what the
		// intent is about, and a row whose body disagreed with its own key would otherwise be
		// written under the body's.
		for (int at = 0; at < table.primaryKey().size(); at++) {
			String postgres = table.column(table.primaryKey().get(at)).postgres();
			replace(fields, postgres, intent.key().get(at));
		}

		StringBuilder sql = new StringBuilder("INSERT INTO ").append(table.name()).append(" (");
		StringBuilder values = new StringBuilder();

		for (int at = 0; at < fields.size(); at++) {
			sql.append(at == 0 ? "" : ", ").append(fields.get(at).column());
			values.append(at == 0 ? "" : ", ").append(fields.get(at).placeholder());
		}

		// created_at, unlike the importer, which deliberately leaves it null: the importer is
		// loading history and "created now" would be a lie, while a push is a row arriving now.
		sql.append(", created_at) VALUES (").append(values).append(", now()) ");

		sql.append("ON CONFLICT (");

		for (int at = 0; at < table.primaryKey().size(); at++) {
			sql.append(at == 0 ? "" : ", ")
					.append(table.column(table.primaryKey().get(at)).postgres());
		}

		List<String> changeable = new ArrayList<>();

		for (DeviceValue.Field field : fields) {
			if (!isKeyColumn(table, field.column())) {
				changeable.add(field.column());
			}
		}

		if (changeable.isEmpty()) {
			// A table that is nothing but its key -- a membership row. There is no update to
			// make, and the row already being there is the whole of what the intent wanted.
			sql.append(") DO NOTHING");

		} else {
			sql.append(") DO UPDATE SET ");

			for (int at = 0; at < changeable.size(); at++) {
				sql.append(at == 0 ? "" : ", ").append(changeable.get(at))
						.append(" = EXCLUDED.").append(changeable.get(at));
			}

			sql.append(", updated_at = now(), row_version = ").append(table.name())
					.append(".row_version + 1");

			/*
			 * And only when something actually differs.
			 *
			 * Without this clause an upsert always writes, so updated_at and row_version move
			 * on every replay -- and a row that has changed is a row the audit trigger
			 * records. A device that sends a batch twice because it never saw the response
			 * would then put a second "changed" entry in the record against every row in it,
			 * describing a change that did not happen. The trigger already declines to record
			 * an UPDATE whose images match; this is what keeps that true through an upsert.
			 */
			sql.append(" WHERE ROW(");

			for (int at = 0; at < changeable.size(); at++) {
				sql.append(at == 0 ? "" : ", ").append(table.name()).append(".")
						.append(changeable.get(at));
			}

			sql.append(") IS DISTINCT FROM ROW(");

			for (int at = 0; at < changeable.size(); at++) {
				sql.append(at == 0 ? "" : ", ").append("EXCLUDED.").append(changeable.get(at));
			}

			sql.append(")");
		}

		try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {

			for (int at = 0; at < fields.size(); at++) {
				statement.setObject(at + 1, fields.get(at).value());
			}
			statement.executeUpdate();
		}
	}

	private static void delete(@NonNull Connection connection, @NonNull Intent intent)
			throws SQLException {

		DeviceTables.Table table = DeviceTables.of(intent.table());
		StringBuilder sql = new StringBuilder("DELETE FROM ").append(table.name())
				.append(" WHERE ");

		for (int at = 0; at < table.primaryKey().size(); at++) {
			sql.append(at == 0 ? "" : " AND ")
					.append(table.column(table.primaryKey().get(at)).postgres())
					.append(" = ?");
		}

		try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {

			for (int at = 0; at < intent.key().size(); at++) {
				statement.setLong(at + 1, intent.key().get(at));
			}

			// The row may not be there, and that is not a failure: a delete the device sent
			// twice, or a row the server never received in the first place, both end here with
			// nothing to do.
			statement.executeUpdate();
		}
	}

	private static boolean isKeyColumn(@NonNull DeviceTables.Table table,
	                                   @NonNull String postgres) {

		for (String room : table.primaryKey()) {
			if (table.column(room).postgres().equals(postgres)) {
				return true;
			}
		}
		return false;
	}

	private static void replace(@NonNull List<DeviceValue.Field> fields,
	                            @NonNull String column, long value) {

		for (int at = 0; at < fields.size(); at++) {
			if (fields.get(at).column().equals(column)) {
				fields.set(at, new DeviceValue.Field(column, value, ""));
				return;
			}
		}
		fields.add(new DeviceValue.Field(column, value, ""));
	}

	/** The tables V10 says a device may sync, read from the database rather than restated here. */
	@NonNull
	private static Set<String> syncedTables(@NonNull Connection connection) throws SQLException {

		Set<String> tables = new LinkedHashSet<>();

		try (Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(
				     "SELECT unnest(synced_tables()) AS table_name")) {

			while (results.next()) {
				tables.add(results.getString("table_name"));
			}
		}
		return tables;
	}

	/**
	 * A database error as something the device can show a person.
	 * <p>
	 * A policy refusal is the interesting one and is named as such. Everything else keeps the
	 * server's own message, because the alternative is a device displaying "something went
	 * wrong" about a row it will go on trying to send for ever.
	 */
	@NonNull
	private static String explain(@NonNull SQLException e) {

		if (INSUFFICIENT_PRIVILEGE.equals(e.getSQLState())) {
			return "the server refused this row: it is not yours to change";
		}

		String message = e.getMessage();

		if (message == null) {
			return "the server could not write this row";
		}

		int newline = message.indexOf('\n');
		return newline < 0 ? message : message.substring(0, newline);
	}
}
