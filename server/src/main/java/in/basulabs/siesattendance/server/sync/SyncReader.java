package in.basulabs.siesattendance.server.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import in.basulabs.siesattendance.backend.sync.DeviceTables;
import in.basulabs.siesattendance.backend.sync.OutboxOp;

/**
 * What a device should fetch: either a snapshot, or everything since its watermark.
 *
 * <h3>The feed is the audit log</h3>
 * {@code changes_since()} in V10 reads it, and V10's header says why at length. The short version:
 * every row change is already recorded there, in order, with its table and key, so the ordering a
 * pull needs exists rather than having to be invented. This class turns those keys into rows —
 * and it reads the rows <em>under the caller's own actor</em>, so the policies decide what comes
 * back. The feed is a list of keys; the policies are what make it a list of the caller's keys.
 *
 * <h3>Why a key that resolves to nothing is dropped rather than reported</h3>
 * A feed entry for a row the caller may not read yields no row, and nothing is sent. The caller
 * learns only that their watermark moved. The one thing that does leak is a {@code DELETE}: there
 * is no row left to apply a policy to, so a deletion is passed on by key alone. That is a real
 * gap and it is stated rather than papered over — a key is a number that names no person, and the
 * alternative is a device that never hears about a deleted lecture and shows it for ever.
 *
 * <h3>The first pull</h3>
 * A device with no watermark gets a snapshot: every row it may read, in key order, and then the
 * head of the feed as its watermark. Not a replay of the log — the log starts at V7 and most
 * rows are older than it, so a replay would both miss rows and send them in the wrong order.
 */
public final class SyncReader {

	/**
	 * One row for the device to apply.
	 *
	 * @param row the row as the device holds it, keyed by Room column name; null for a deletion
	 */
	public record Change(@NonNull String table, @NonNull List<Long> key, @NonNull OutboxOp op,
	                     @Nullable Map<String, Object> row) {
	}

	/**
	 * @param watermark where to resume from, opaque to the device
	 * @param more      true when the limit was reached and another pull would bring more. The
	 *                  device should keep pulling rather than wait for the next scheduled one
	 */
	public record Page(@NonNull String watermark, boolean more, @NonNull List<Change> changes) {
	}

	/** The watermark a device that has never pulled sends: nothing. */
	public static final String FROM_THE_BEGINNING = "";

	private SyncReader() {
	}

	/**
	 * Reads one page, on the caller's transaction, which must have an actor announced on it.
	 *
	 * @param watermark what the device last stored, or {@link #FROM_THE_BEGINNING}
	 */
	@NonNull
	public static Page read(@NonNull Connection connection, @NonNull String watermark, int limit)
			throws SQLException {

		int capped = Math.max(1, Math.min(limit, 1000));

		if (watermark.isBlank()) {
			return snapshot(connection, capped);
		}

		Cursor cursor = Cursor.parse(watermark);
		List<Change> changes = new ArrayList<>();
		Cursor last = cursor;
		int entries = 0;

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT cursor_at, cursor_id, table_name, row_key, action "
						+ "FROM changes_since(?, ?, ?)")) {

			statement.setTimestamp(1, Timestamp.from(cursor.at()));
			statement.setLong(2, cursor.id());
			statement.setInt(3, capped);

			try (ResultSet results = statement.executeQuery()) {

				while (results.next()) {

					entries++;
					last = new Cursor(results.getTimestamp("cursor_at").toInstant(),
							results.getLong("cursor_id"));

					String table = results.getString("table_name");
					List<Long> key = keyFrom(table, results.getString("row_key"));

					if (key == null) {
						// An entry whose key this build cannot read is skipped rather than
						// fatal: the watermark still moves past it, because stopping here would
						// wedge every future pull on one unreadable entry.
						continue;
					}

					if ("DELETE".equals(results.getString("action"))) {
						changes.add(new Change(table, key, OutboxOp.DELETE, null));
					} else {
						// Read now, not as the entry recorded it: the device wants the row as
						// it stands, and three changes to one row between pulls should cost one
						// row rather than three.
						Map<String, Object> row = rowFor(connection, table, key);

						if (row != null) {
							changes.add(new Change(table, key, OutboxOp.UPSERT, row));
						}
					}
				}
			}
		}

		return new Page(last.toString(), entries == capped, dedupe(changes));
	}

	/**
	 * Everything the caller may read, and then the head of the feed.
	 * <p>
	 * In key order within each table, and the tables in dependency order, so the device can
	 * apply the page in the order it arrives: a lecture lands before the marks on it.
	 */
	@NonNull
	private static Page snapshot(@NonNull Connection connection, int limit) throws SQLException {

		// The head is taken FIRST, before the rows are read. Taken afterwards, a row changed
		// during the snapshot would be both in the snapshot and before the watermark, and the
		// device would never hear about the change -- whereas a head taken first means that
		// change arrives again on the next pull, which costs one row and loses nothing.
		Cursor head = head(connection);

		List<Change> changes = new ArrayList<>();
		boolean more = false;

		for (String table : syncedTables(connection)) {

			if (changes.size() >= limit) {
				more = true;
				break;
			}

			DeviceTables.Table described = DeviceTables.of(table);
			StringBuilder sql = new StringBuilder("SELECT * FROM ").append(table)
					.append(" ORDER BY ");

			for (int at = 0; at < described.primaryKey().size(); at++) {
				sql.append(at == 0 ? "" : ", ")
						.append(described.column(described.primaryKey().get(at)).postgres());
			}

			sql.append(" LIMIT ").append(limit - changes.size() + 1);

			try (Statement statement = connection.createStatement();
			     ResultSet results = statement.executeQuery(sql.toString())) {

				while (results.next()) {

					if (changes.size() >= limit) {
						more = true;
						break;
					}

					Map<String, Object> row = readRow(results);
					changes.add(new Change(table, keyOf(described, row), OutboxOp.UPSERT,
							toDevice(described, row)));
				}
			}
		}

		return new Page(head.toString(), more, changes);
	}

	// ── Reading one row ──────────────────────────────────────────────────

	@Nullable
	private static Map<String, Object> rowFor(@NonNull Connection connection,
	                                          @NonNull String table, @NonNull List<Long> key)
			throws SQLException {

		DeviceTables.Table described = DeviceTables.of(table);
		StringBuilder sql = new StringBuilder("SELECT * FROM ").append(table).append(" WHERE ");

		for (int at = 0; at < described.primaryKey().size(); at++) {
			sql.append(at == 0 ? "" : " AND ")
					.append(described.column(described.primaryKey().get(at)).postgres())
					.append(" = ?");
		}

		try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {

			for (int at = 0; at < key.size(); at++) {
				statement.setLong(at + 1, key.get(at));
			}

			try (ResultSet results = statement.executeQuery()) {
				// No row means either it has since been deleted or the policies do not let this
				// actor see it, and the two are deliberately indistinguishable from here.
				return results.next() ? toDevice(described, readRow(results)) : null;
			}
		}
	}

	@NonNull
	private static Map<String, Object> readRow(@NonNull ResultSet results) throws SQLException {

		Map<String, Object> row = new LinkedHashMap<>();

		for (int at = 1; at <= results.getMetaData().getColumnCount(); at++) {
			row.put(results.getMetaData().getColumnLabel(at), results.getObject(at));
		}
		return row;
	}

	@NonNull
	private static Map<String, Object> toDevice(@NonNull DeviceTables.Table table,
	                                            @NonNull Map<String, Object> row) {

		Map<String, Object> device = new LinkedHashMap<>();

		for (DeviceTables.Column column : table.carriedColumns()) {
			device.put(column.room(), DeviceValue.toDevice(column, row));
		}
		return device;
	}

	@NonNull
	private static List<Long> keyOf(@NonNull DeviceTables.Table table,
	                                @NonNull Map<String, Object> row) {

		List<Long> key = new ArrayList<>();

		for (String room : table.primaryKey()) {
			key.add(((Number) row.get(table.column(room).postgres())).longValue());
		}
		return key;
	}

	/**
	 * The key out of an audit entry's {@code row_key}, in the table's own key order.
	 * <p>
	 * Parsed by hand rather than with a JSON library, because it is a flat object of numbers
	 * written by {@code audit_row()} and this is the only thing that reads it. Null when a value
	 * is not a number, which means the entry was written for a table keyed on something else.
	 */
	@Nullable
	static List<Long> keyFrom(@NonNull String table, @Nullable String rowKey) {

		if (rowKey == null) {
			return null;
		}

		DeviceTables.Table described;

		try {
			described = DeviceTables.of(table);
		} catch (RuntimeException e) {
			return null;
		}

		List<Long> key = new ArrayList<>();

		for (String room : described.primaryKey()) {

			String postgres = described.column(room).postgres();
			int at = rowKey.indexOf("\"" + postgres + "\"");

			if (at < 0) {
				return null;
			}

			int colon = rowKey.indexOf(':', at);
			int end = colon;

			while (end < rowKey.length() && rowKey.charAt(end) != ',' && rowKey.charAt(end) != '}') {
				end++;
			}

			try {
				key.add(Long.parseLong(rowKey.substring(colon + 1, end).trim()));
			} catch (NumberFormatException e) {
				return null;
			}
		}
		return key;
	}

	/**
	 * One change per row, keeping the last.
	 * <p>
	 * A row changed twice inside one page appears twice in the feed, and the second entry is the
	 * one that is true — including when the second is a deletion. Sending both would be correct
	 * and wasteful; sending the first would be wrong.
	 */
	@NonNull
	private static List<Change> dedupe(@NonNull List<Change> changes) {

		Map<String, Change> last = new LinkedHashMap<>();

		for (Change change : changes) {
			last.put(change.table() + change.key(), change);
		}
		return new ArrayList<>(last.values());
	}

	@NonNull
	private static List<String> syncedTables(@NonNull Connection connection)
			throws SQLException {

		List<String> ordered = new ArrayList<>();
		List<String> allowed = new ArrayList<>();

		try (Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(
				     "SELECT unnest(synced_tables()) AS table_name")) {

			while (results.next()) {
				allowed.add(results.getString("table_name"));
			}
		}

		// Dependency order, not the order the function happens to list them in: the device
		// applies a page as it arrives, and a mark cannot be stored before its lecture.
		for (DeviceTables.Table table : DeviceTables.inDependencyOrder()) {
			if (allowed.contains(table.name())) {
				ordered.add(table.name());
			}
		}
		return ordered;
	}

	@NonNull
	private static Cursor head(@NonNull Connection connection) throws SQLException {

		try (Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(
				     "SELECT cursor_at, cursor_id FROM changes_head()")) {

			if (!results.next()) {
				return new Cursor(java.time.Instant.EPOCH, 0L);
			}

			Timestamp at = results.getTimestamp("cursor_at");

			return new Cursor(at == null ? java.time.Instant.EPOCH : at.toInstant(),
					results.getLong("cursor_id"));
		}
	}

	/**
	 * The watermark: an instant and an audit id, which together are a total order over the feed.
	 * <p>
	 * Written as text and read back as text. The device stores it without looking inside, which
	 * is the point — a device with an opinion about the server's ordering is a device that
	 * disagrees with it eventually.
	 */
	record Cursor(@NonNull java.time.Instant at, long id) {

		@Override
		@NonNull
		public String toString() {
			return at + "@" + id;
		}

		@NonNull
		static Cursor parse(@NonNull String watermark) {

			int at = watermark.lastIndexOf('@');

			if (at < 0) {
				throw new IllegalArgumentException("not a watermark: " + watermark);
			}

			try {
				return new Cursor(java.time.Instant.parse(watermark.substring(0, at)),
						Long.parseLong(watermark.substring(at + 1)));

			} catch (RuntimeException e) {
				throw new IllegalArgumentException("not a watermark: " + watermark);
			}
		}
	}
}
