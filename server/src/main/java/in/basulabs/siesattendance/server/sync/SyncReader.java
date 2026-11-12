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
 * <h3>A row that changes out of sight</h3>
 * An {@code UPDATE} entry for a row the caller can no longer read is passed on as a deletion,
 * by key, like a real one. The row may be on the device from when the caller could read it —
 * a student since moved to another department — and nothing else would ever tell the device to
 * let go of it. It gives away what a deletion already does: that a row with that key changed.
 * <p>
 * Access that changes without the rows changing — the caller moved to another department — is
 * not in the feed at all. {@link #accessFingerprint} is what lets the device notice that, and
 * {@link #keys} what lets it find the rows to remove.
 *
 * <h3>The first pull, and why it can take several</h3>
 * A device with no watermark gets a snapshot: every row it may read, table by table in dependency
 * order and in key order within each, and then the head of the feed as its watermark. Not a replay
 * of the log — the log starts at V7 and most rows are older than it, so a replay would both miss
 * rows and send them in the wrong order.
 * <p>
 * A snapshot rarely fits in one page. A college roster is thousands of enrolments and a page is
 * hundreds, so the snapshot is resumable: when it fills a page it hands back a watermark that says
 * which table and key it got to, <em>carrying the head it started with</em>, and the next pull
 * continues from there and finishes on that same head. Keeping the original head is the whole
 * trick — a head taken at the end of each page would skip every change made while the pages in
 * between were being sent.
 * <p>
 * This is not a refinement. Until V11 the snapshot was two tables of a test fixture and fitted;
 * with the roster in it, a truncated snapshot that reported the head as its watermark would have
 * silently dropped every row past the first page and no pull would ever have asked for them
 * again.
 *
 * <h3>The watermark is a transaction, not a position</h3>
 * It used to be the position of the last entry sent, {@code (occurred_at, audit_id)}. Both are
 * fixed when the audit trigger fires, inside the writing transaction, and the entry is visible
 * only when that commits. A pull while an import was still running saw later entries from
 * shorter transactions, moved past the import's position, and never looked back: the import
 * never reached that phone until it started over (C1). The same hole was open across a snapshot.
 * <p>
 * Since V63 the watermark is a {@link Frontier}: the oldest transaction still open when the pull
 * ran. Every older transaction had finished and was sent; every one still open, or not yet
 * started, is at or past the frontier and is sent by a later pull whenever it commits. The cost
 * is that entries of transactions younger than an open one are sent again, which a phone absorbs
 * by applying them by key.
 * <p>
 * A phone that last pulled before V63 holds a position. It is caught up by position once, from a
 * day before that position to be safe, and then handed a frontier taken before the catch-up
 * began; see {@link Positions}.
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
			return snapshot(connection, capped, null);
		}

		if (Resume.looksLikeOne(watermark)) {
			return snapshot(connection, capped, Resume.parse(watermark));
		}

		if (Frontier.looksLikeOne(watermark)) {
			return byTransaction(connection, Frontier.parse(watermark), capped);
		}

		if (Positions.looksLikeOne(watermark)) {
			return byPosition(connection, Positions.parse(watermark), capped);
		}

		// A position issued before V63. Caught up by position once, from a day earlier in case a
		// transaction was open across it -- the hole the frontier closes -- and then moved on to
		// a frontier taken now, before anything is read.
		Cursor before = Cursor.parse(watermark);

		return byPosition(connection, new Positions(frontier(connection),
				new Cursor(before.at().minus(CATCH_UP), 0L)), capped);
	}

	/**
	 * How far behind a pre-V63 position the catch-up starts. A transaction that was open across
	 * that position for longer than this, and committed since, is the one case the catch-up can
	 * still miss; an import runs for minutes.
	 */
	private static final java.time.Duration CATCH_UP = java.time.Duration.ofDays(1);

	/**
	 * Every entry written by a transaction at or past the frontier, in transaction order.
	 * <p>
	 * The pass's own frontier is taken on its first page, before anything is read, and handed on
	 * through every page of the pass: it is the watermark the pass finishes on. Taken at the end
	 * instead, it could pass a transaction that was open at the start and committed while the
	 * pages were being sent, entries of which sort before where the pass had got to.
	 */
	@NonNull
	private static Page byTransaction(@NonNull Connection connection, @NonNull Frontier from,
	                                  int capped) throws SQLException {

		String finish = from.carry() != null ? from.carry() : frontier(connection);

		List<Change> changes = new ArrayList<>();
		String lastTxid = null;
		long lastId = 0;
		int entries = 0;

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT cursor_txid::text AS cursor_txid, cursor_id, table_name, row_key, action "
						+ "FROM changes_after(?::xid8, ?::xid8, ?, ?)")) {

			statement.setString(1, from.frontier());
			statement.setString(2, from.afterTxid());
			statement.setLong(3, from.afterId());
			statement.setInt(4, capped);

			try (ResultSet results = statement.executeQuery()) {

				while (results.next()) {
					entries++;
					lastTxid = results.getString("cursor_txid");
					lastId = results.getLong("cursor_id");
					change(connection, results, changes);
				}
			}
		}

		if (entries == capped) {
			// More of this pass to come, from where this page stopped.
			return new Page(new Frontier(from.frontier(), finish, lastTxid, lastId).toString(),
					true, dedupe(changes));
		}

		return new Page(new Frontier(finish, null, null, 0).toString(), false,
				dedupe(changes));
	}

	/**
	 * A pre-V63 position caught up by position, and then moved on to a frontier.
	 */
	@NonNull
	private static Page byPosition(@NonNull Connection connection, @NonNull Positions from,
	                               int capped) throws SQLException {

		List<Change> changes = new ArrayList<>();
		Cursor last = from.after();
		int entries = 0;

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT cursor_at, cursor_id, table_name, row_key, action "
						+ "FROM changes_since(?, ?, ?)")) {

			statement.setTimestamp(1, Timestamp.from(from.after().at()));
			statement.setLong(2, from.after().id());
			statement.setInt(3, capped);

			try (ResultSet results = statement.executeQuery()) {

				while (results.next()) {
					entries++;
					last = new Cursor(results.getTimestamp("cursor_at").toInstant(),
							results.getLong("cursor_id"));
					change(connection, results, changes);
				}
			}
		}

		if (entries == capped) {
			return new Page(new Positions(from.finish(), last).toString(), true,
					dedupe(changes));
		}

		// Caught up. The frontier was taken before the first page of the catch-up, so every
		// transaction open then -- or since -- is sent by the next pull.
		return new Page(new Frontier(from.finish(), null, null, 0).toString(), false,
				dedupe(changes));
	}

	/** One feed entry as the change to send, added to {@code changes}; or nothing. */
	private static void change(@NonNull Connection connection, @NonNull ResultSet results,
	                           @NonNull List<Change> changes) throws SQLException {

		String table = results.getString("table_name");
		List<Long> key = keyFrom(table, results.getString("row_key"));

		if (key == null) {
			// An entry whose key this build cannot read is skipped rather than fatal: the
			// watermark still moves past it, because stopping here would wedge every future
			// pull on one unreadable entry.
			return;
		}

		String action = results.getString("action");

		if ("DELETE".equals(action)) {
			changes.add(new Change(table, key, OutboxOp.DELETE, null));
			return;
		}

		// Read now, not as the entry recorded it: the device wants the row as it stands, and
		// three changes to one row between pulls should cost one row rather than three.
		Map<String, Object> row = rowFor(connection, table, key);

		if (row != null) {
			changes.add(new Change(table, key, OutboxOp.UPSERT, row));

		} else if ("UPDATE".equals(action)) {
			// Changed, and now out of this actor's sight: a student moved to another
			// department, a batch handed to another one. The device may hold it from before,
			// and nothing else would ever tell it to let go, so it hears of a deletion. An
			// insertion it cannot read was never its to hold and is still dropped. See the
			// class notes on what a deletion gives away.
			changes.add(new Change(table, key, OutboxOp.DELETE, null));
		}
	}

	/** The oldest transaction still open, as this pull's snapshot sees it; see V63. */
	@NonNull
	private static String frontier(@NonNull Connection connection) throws SQLException {

		try (Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(
				     "SELECT changes_frontier()::text")) {

			if (!results.next() || results.getString(1) == null) {
				throw new SQLException("the database gave no transaction frontier");
			}
			return results.getString(1);
		}
	}

	/**
	 * Everything the caller may read, and then the head of the feed.
	 * <p>
	 * In key order within each table, and the tables in dependency order, so the device can
	 * apply the page in the order it arrives: a lecture lands before the marks on it.
	 *
	 * @param resume where the last page of this same snapshot got to, or null to start one
	 */
	@NonNull
	private static Page snapshot(@NonNull Connection connection, int limit,
	                             @Nullable Resume resume) throws SQLException {

		// The head is taken FIRST, before the rows are read, and on a resumed snapshot it is
		// the head the FIRST page took. Taken afterwards, a row changed during the snapshot
		// would be both in the snapshot and before the watermark, and the device would never
		// hear about the change -- whereas a head taken first means that change arrives again
		// on the next pull, which costs one row and loses nothing.
		//
		// And it is a frontier, not the newest entry (V63): a transaction open while the
		// snapshot reads is in neither the rows it sees nor, were the head a position, the
		// feed after it. A frontier is at or before every such transaction.
		String head = resume == null
				? new Frontier(frontier(connection), null, null, 0).toString()
				: resume.head();

		List<Change> changes = new ArrayList<>();
		boolean reached = resume == null;
		String lastTable = null;
		List<Long> lastKey = List.of();
		boolean more = false;

		for (String table : syncedTables(connection)) {

			if (!reached) {

				if (!table.equals(resume.table())) {
					// A table the earlier pages finished with.
					continue;
				}

				// The table the snapshot stopped inside. Not skipped: it is read again from
				// the key after the last row it sent, which is what the WHERE below does.
				reached = true;
			}

			if (changes.size() >= limit) {
				more = true;
				break;
			}

			DeviceTables.Table described = DeviceTables.of(table);
			List<Long> after = resume != null && table.equals(resume.table())
					? resume.afterKey() : List.of();

			StringBuilder sql = new StringBuilder("SELECT * FROM ").append(table);

			if (!after.isEmpty()) {
				// Row-wise, against the same column order the ORDER BY uses, so "after this
				// key" means the same thing as "later in this page's order".
				sql.append(" WHERE (").append(keyColumns(described)).append(") > (");

				for (int at = 0; at < after.size(); at++) {
					sql.append(at == 0 ? "" : ", ").append("?");
				}
				sql.append(")");
			}

			sql.append(" ORDER BY ").append(keyColumns(described))
					.append(" LIMIT ").append(limit - changes.size() + 1);

			try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {

				for (int at = 0; at < after.size(); at++) {
					statement.setLong(at + 1, after.get(at));
				}

				try (ResultSet results = statement.executeQuery()) {

					while (results.next()) {

						if (changes.size() >= limit) {
							more = true;
							break;
						}

						Map<String, Object> row = readRow(results);
						List<Long> key = keyOf(described, row);

						changes.add(new Change(table, key, OutboxOp.UPSERT,
								toDevice(described, row)));

						lastTable = table;
						lastKey = key;
					}
				}
			}
		}

		if (!more || lastTable == null) {
			// Finished, and the watermark becomes the head this snapshot started from, so the
			// next pull is an ordinary one. lastTable null with more set would mean the limit
			// was reached before a single row was read, which cannot happen for a positive
			// limit and would be a watermark nothing could resume from.
			return new Page(head, more, changes);
		}

		return new Page(new Resume(lastTable, lastKey, head).toString(), true, changes);
	}

	/** The primary key columns under their PostgreSQL names, in key order. */
	@NonNull
	private static String keyColumns(@NonNull DeviceTables.Table table) {

		StringBuilder columns = new StringBuilder();

		for (int at = 0; at < table.primaryKey().size(); at++) {
			columns.append(at == 0 ? "" : ", ")
					.append(table.column(table.primaryKey().get(at)).postgres());
		}
		return columns.toString();
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

	/**
	 * A digest of what decides this actor's own access, sent with every pull. V51 says what is
	 * in it and what is not; a device that sees it change starts again from a snapshot and
	 * removes what did not come back.
	 */
	@NonNull
	public static String accessFingerprint(@NonNull Connection connection) throws SQLException {

		try (Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(
				     "SELECT actor_access_fingerprint()")) {

			return results.next() && results.getString(1) != null ? results.getString(1) : "";
		}
	}

	/**
	 * One page of the keys this actor may read in a table, for a device's sweep.
	 *
	 * @param keys in key order, each in the table's own key order
	 * @param more true when the limit was reached and another page would bring more
	 */
	public record KeyPage(@NonNull List<List<Long>> keys, boolean more) {
	}

	/**
	 * The keys of every row of a table this actor may read, after a key, in key order.
	 * <p>
	 * What a device compares its own rows against after a snapshot, to find the ones it may
	 * no longer read. Keys rather than a second snapshot, because the device has just had the
	 * rows; what it lacks is the list of what is <em>not</em> there. In key order so the device
	 * can walk its own rows alongside, holding one page at a time rather than a roster.
	 * <p>
	 * Under the caller's own policies, exactly as {@link #snapshot} reads, so the two agree on
	 * what this actor may see.
	 *
	 * @param after the last key of the previous page, or empty for the first
	 * @throws IllegalArgumentException for a table that is not synced, or a key of the wrong
	 *                                  width: both come from a phone, and both go into a
	 *                                  statement
	 */
	@NonNull
	public static KeyPage keys(@NonNull Connection connection, @NonNull String tableName,
	                           @NonNull List<Long> after, int limit) throws SQLException {

		if (!syncedTables(connection).contains(tableName)) {
			throw new IllegalArgumentException("not a synced table: " + tableName);
		}

		DeviceTables.Table table = DeviceTables.of(tableName);

		if (!after.isEmpty() && after.size() != table.primaryKey().size()) {
			throw new IllegalArgumentException("not a key of " + tableName + ": " + after);
		}

		int capped = Math.max(1, Math.min(limit, 10_000));
		StringBuilder sql = new StringBuilder("SELECT ").append(keyColumns(table))
				.append(" FROM ").append(table.name());

		if (!after.isEmpty()) {
			sql.append(" WHERE (").append(keyColumns(table)).append(") > (");

			for (int at = 0; at < after.size(); at++) {
				sql.append(at == 0 ? "" : ", ").append("?");
			}
			sql.append(")");
		}

		sql.append(" ORDER BY ").append(keyColumns(table)).append(" LIMIT ").append(capped + 1);

		List<List<Long>> keys = new ArrayList<>();
		boolean more = false;

		try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {

			for (int at = 0; at < after.size(); at++) {
				statement.setLong(at + 1, after.get(at));
			}

			try (ResultSet results = statement.executeQuery()) {

				while (results.next()) {

					if (keys.size() == capped) {
						more = true;
						break;
					}

					List<Long> key = new ArrayList<>();

					for (int column = 1; column <= table.primaryKey().size(); column++) {
						key.add(results.getLong(column));
					}
					keys.add(key);
				}
			}
		}
		return new KeyPage(keys, more);
	}

	/**
	 * How many rows a snapshot read on this connection would send, for a caller that wants to
	 * say how far it has got. Counted under the same policies, so it is this actor's number;
	 * a row changed while the snapshot runs can make it a little off, which is fine for that.
	 */
	public static long snapshotSize(@NonNull Connection connection) throws SQLException {

		long rows = 0;

		for (String table : syncedTables(connection)) {

			try (Statement statement = connection.createStatement();
			     ResultSet results = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {

				if (results.next()) {
					rows += results.getLong(1);
				}
			}
		}
		return rows;
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

	/**
	 * A snapshot in progress: the table and key it got to, and the head it will finish on.
	 * <p>
	 * It travels as the device's watermark, which is opaque to the device by contract — the
	 * device stores a string and sends it back, and that is the whole of what it knows about
	 * it. That contract is what lets this exist without the phone being taught anything.
	 * <p>
	 * {@code snap|<table>|<k1,k2>|<head>}, with the head last because it has separators of its
	 * own. The head is the watermark the snapshot finishes on: a {@link Frontier} since V63, or
	 * the {@code <instant>@<id>} position of a snapshot that started before it, which finishes
	 * on that position and is then caught up like any other.
	 */
	record Resume(@NonNull String table, @NonNull List<Long> afterKey, @NonNull String head) {

		private static final String PREFIX = "snap|";

		static boolean looksLikeOne(@NonNull String watermark) {
			return watermark.startsWith(PREFIX);
		}

		@Override
		@NonNull
		public String toString() {

			StringBuilder key = new StringBuilder();

			for (int at = 0; at < afterKey.size(); at++) {
				key.append(at == 0 ? "" : ",").append(afterKey.get(at));
			}

			return PREFIX + table + "|" + key + "|" + head;
		}

		@NonNull
		static Resume parse(@NonNull String watermark) {

			String[] parts = watermark.substring(PREFIX.length()).split("\\|", 3);

			if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty()) {
				throw new IllegalArgumentException("not a watermark: " + watermark);
			}

			List<Long> key = new ArrayList<>();

			try {
				for (String value : parts[1].split(",")) {
					key.add(Long.valueOf(value));
				}

			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("not a watermark: " + watermark);
			}

			// The table has to be one this build knows and the key the right width for it:
			// both go into a statement, and a watermark is a string that came from a phone.
			DeviceTables.Table table = DeviceTables.of(parts[0]);

			if (key.size() != table.primaryKey().size()) {
				throw new IllegalArgumentException("not a watermark: " + watermark);
			}

			// Either kind of head, checked here so a bad one is refused now and not at the end
			// of the snapshot.
			if (Frontier.looksLikeOne(parts[2])) {
				Frontier.parse(parts[2]);
			} else {
				Cursor.parse(parts[2]);
			}
			return new Resume(table.name(), key, parts[2]);
		}
	}

	/**
	 * A transaction frontier, and where a pass from it has got to.
	 * <p>
	 * {@code x|<frontier>} between passes: send every entry of a transaction at or past it.
	 * {@code x|<frontier>|<finish>|<txid>|<id>} inside a pass whose page filled: carry on from
	 * that entry, and finish on {@code <finish>}, the frontier the pass took on its first page.
	 * Transaction ids are {@code xid8}, written in decimal.
	 */
	record Frontier(@NonNull String frontier, @Nullable String carry, @Nullable String afterTxid,
	                long afterId) {

		private static final String PREFIX = "x|";

		static boolean looksLikeOne(@NonNull String watermark) {
			return watermark.startsWith(PREFIX);
		}

		@Override
		@NonNull
		public String toString() {
			return carry == null ? PREFIX + frontier
					: PREFIX + frontier + "|" + carry + "|" + afterTxid + "|" + afterId;
		}

		@NonNull
		static Frontier parse(@NonNull String watermark) {

			String[] parts = watermark.substring(PREFIX.length()).split("\\|", -1);

			if (parts.length == 1 && txid(parts[0])) {
				return new Frontier(parts[0], null, null, 0);
			}

			if (parts.length == 4 && txid(parts[0]) && txid(parts[1]) && txid(parts[2])) {
				try {
					return new Frontier(parts[0], parts[1], parts[2], Long.parseLong(parts[3]));
				} catch (NumberFormatException e) {
					throw new IllegalArgumentException("not a watermark: " + watermark);
				}
			}

			throw new IllegalArgumentException("not a watermark: " + watermark);
		}

		/** A transaction id as the database writes one: decimal, and at most 20 digits. */
		private static boolean txid(@NonNull String text) {
			return !text.isEmpty() && text.length() <= 20 && text.chars().allMatch(Character::isDigit);
		}
	}

	/**
	 * A pre-V63 position being caught up: the frontier to finish on, taken before the catch-up
	 * read anything, and the position it has got to. {@code p|<finish>|<instant>@<id>}.
	 */
	record Positions(@NonNull String finish, @NonNull Cursor after) {

		private static final String PREFIX = "p|";

		static boolean looksLikeOne(@NonNull String watermark) {
			return watermark.startsWith(PREFIX);
		}

		@Override
		@NonNull
		public String toString() {
			return PREFIX + finish + "|" + after;
		}

		@NonNull
		static Positions parse(@NonNull String watermark) {

			String[] parts = watermark.substring(PREFIX.length()).split("\\|", 2);

			if (parts.length != 2) {
				throw new IllegalArgumentException("not a watermark: " + watermark);
			}

			// Read through Frontier, so a finish that is not a transaction id is refused here.
			return new Positions(Frontier.parse(Frontier.PREFIX + parts[0]).frontier(),
					Cursor.parse(parts[1]));
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
