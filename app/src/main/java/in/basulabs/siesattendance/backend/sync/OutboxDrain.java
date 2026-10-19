package in.basulabs.siesattendance.backend.sync;

import android.database.Cursor;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.sqlite.db.SupportSQLiteDatabase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import in.basulabs.siesattendance.backend.EntityOutbox;
import in.basulabs.siesattendance.backend.EntitySyncState;
import in.basulabs.siesattendance.backend.MainDatabase;

/**
 * Sends what the outbox holds, and applies what the server sends back.
 * <p>
 * The device's half of sync. Everything it does is driven by {@link DeviceTables} and
 * {@link DeviceSql} — both in {@code :shared}, in this same package, which is how the phone and
 * the server keep one description of what a row is — so it knows nothing about lectures or
 * attendance in particular and gains nothing to forget when a table joins the feed.
 *
 * <h3>Draining</h3>
 * Read the oldest intents, read each row as it now stands, send them, delete the ones the server
 * accepted. Nothing is deleted before the server has said it has it, and an intent is deleted by
 * its own sequence number rather than by a range — a row dirtied again while the batch was in
 * flight has a new sequence, so it survives.
 * <p>
 * A rejected intent is kept or dropped according to what the server said about it. A refusal
 * that could resolve itself — a lecture whose course has not arrived yet — is kept and tried
 * again, because dropping it would be losing attendance quietly. A refusal that cannot — a policy
 * saying this is not yours to change, a row the server cannot convert — is dropped, and the
 * sentence the server sent comes back in {@link Result#refusal()} for a screen to show.
 * <p>
 * Keeping everything was the earlier rule and it does not survive V11. Now that a device holds a
 * roster it did not write, a professor's phone queues changes to rows the office owns, and the
 * server refuses them every time: keeping those means a queue that never empties, a pending count
 * that never reaches zero, and real attendance stuck behind rows that will never be accepted.
 * Dropping the intent does not drop the row — it is still in the device's own database, which is
 * the system of record until cutover.
 *
 * <h3>Applying</h3>
 * Each page is written inside one transaction with {@code sync_state.applying} set, so the outbox
 * triggers do not announce the server's own rows straight back to it. The flag is set and cleared
 * inside that transaction on purpose: a crash takes it back with the writes, rather than leaving
 * a device that silently queues nothing for ever.
 * <p>
 * The watermark moves only when every row in the page landed. A row whose parent has not arrived
 * — a lecture for a course this device has never seen — fails, and the page is tried again later.
 * That can stick, and sticking is the intended behaviour: the alternative is skipping the row and
 * moving the watermark past it, which loses a lecture with nothing to show that it happened.
 */
public final class OutboxDrain {

	private static final String TAG = "OutboxDrain";

	/** How many intents one push carries. The server refuses more than a thousand. */
	public static final int BATCH = 200;

	/** How many rows one pull asks for. */
	private static final int PAGE = 500;

	/** What one run did, for the worker to decide on and a screen to show. */
	/**
	 * @param rejected how many the server refused and this device kept, to try again
	 * @param dropped  how many it refused for good, which are gone from the queue
	 * @param refusal  the last such refusal's own words, for a screen; null when there were
	 *                 none, which is what clears a stale message
	 */
	public record Result(int sent, int rejected, int dropped, @Nullable String refusal,
	                     int applied, boolean morePending) {

		public boolean didSomething() {
			return sent > 0 || applied > 0;
		}
	}

	@NonNull
	private final MainDatabase db;

	@NonNull
	private final SyncClient client;

	public OutboxDrain(@NonNull MainDatabase db, @NonNull SyncClient client) {
		this.db = db;
		this.client = client;
	}

	/**
	 * Sends one batch.
	 *
	 * @return what happened, including whether more is waiting
	 */
	@NonNull
	public Result push() throws IOException {

		List<EntityOutbox> pending = db.daoOutbox().pendingSync(BATCH);

		if (pending.isEmpty()) {
			return new Result(0, 0, 0, null, 0, false);
		}

		List<SyncIntent> intents = new ArrayList<>();
		List<Long> sequences = new ArrayList<>();

		for (EntityOutbox entry : pending) {

			SyncIntent intent = intentFor(entry);

			if (intent == null) {
				// The row is gone and the intent says UPSERT, or the table is one this build
				// does not know. Either way there is nothing to send and nothing to keep: a
				// dangling intent would be retried for ever.
				db.daoOutbox().deleteSentSync(List.of(entry.seq));
				continue;
			}

			intents.add(intent);
			sequences.add(entry.seq);
		}

		if (intents.isEmpty()) {
			return new Result(0, 0, 0, null, 0, false);
		}

		SyncJson.PushOutcome outcome;

		try {
			outcome = client.push(intents);

		} catch (IOException e) {
			// Not deleted, and counted: a phone that has been failing for a week should be
			// able to say so on a screen rather than only in a log.
			db.daoOutbox().recordFailureSync(sequences, e.getMessage());
			throw e;
		}

		List<Long> accepted = new ArrayList<>(sequences);
		List<Long> keep = new ArrayList<>();
		List<Long> drop = new ArrayList<>();
		String refusal = null;

		for (SyncJson.Rejection rejection : outcome.rejected()) {

			Long sequence = sequenceOf(pending, rejection.table(), rejection.key());

			if (sequence == null) {
				continue;
			}

			accepted.remove(sequence);
			Log.w(TAG, "the server refused " + rejection.table() + rejection.key()
					+ ": " + rejection.reason());

			if (rejection.permanent()) {
				drop.add(sequence);
				// The last one wins rather than the first: they are all shown one at a time
				// and the most recent is the one a person is most likely to recognise.
				refusal = rejection.reason();
			} else {
				keep.add(sequence);
			}
		}

		// Deleted alongside the accepted ones, in the same transaction: a refusal that cannot
		// be retried and a row that has landed both leave the queue, and for the queue they
		// are the same event.
		List<Long> finished = new ArrayList<>(accepted);
		finished.addAll(drop);

		db.runInTransaction(() -> {

			if (!finished.isEmpty()) {
				db.daoOutbox().deleteSentSync(finished);
			}

			if (!keep.isEmpty()) {
				db.daoOutbox().recordFailureSync(keep, "the server refused this row");
			}

			db.daoOutbox().recordPushSync(System.currentTimeMillis());
		});

		return new Result(accepted.size(), keep.size(), drop.size(), refusal, 0,
				db.daoOutbox().countPendingSync() > keep.size());
	}

	/**
	 * Fetches and applies one page.
	 *
	 * @return what happened, including whether the server says there is more
	 */
	@NonNull
	public Result pull() throws IOException {

		EntitySyncState state = db.daoOutbox().stateSync();
		String watermark = state == null || state.watermark == null ? "" : state.watermark;

		SyncJson.PullPage page = client.pull(watermark, PAGE);

		if (page.changes().isEmpty()) {
			// Still worth recording: the watermark moves past entries that resolved to nothing
			// this actor may see, and not storing it would fetch them again for ever.
			db.runInTransaction(() ->
					db.daoOutbox().recordPullSync(page.watermark(),
							System.currentTimeMillis()));

			return new Result(0, 0, 0, null, 0, page.more());
		}

		int[] applied = new int[1];

		db.runInTransaction(() -> {

			SupportSQLiteDatabase sqlite = db.getOpenHelper().getWritableDatabase();

			// The guard, inside the transaction: a crash rolls it back with the writes.
			db.daoOutbox().setApplyingSync(1);

			try {
				for (SyncIntent change : inApplicationOrder(page.changes())) {
					apply(sqlite, change);
					applied[0]++;
				}

				db.daoOutbox().recordPullSync(page.watermark(), System.currentTimeMillis());

			} finally {
				db.daoOutbox().setApplyingSync(0);
			}
		});

		return new Result(0, 0, 0, null, applied[0], page.more());
	}

	/**
	 * A page in the order it can be written, which is not necessarily the order it arrived.
	 * <p>
	 * The same rule as {@code SyncWriter.inApplicationOrder} on the server, for the same reason
	 * and in the other direction: upserts in dependency order so a course lands before a lecture
	 * on it, deletes last and in reverse so a lecture goes before the course it hangs off.
	 * {@link DeviceTables#depthOf} is where that order is written down.
	 * <p>
	 * A snapshot already arrives in this order, because {@code synced_tables()} is in dependency
	 * order and the server reads it that way. A page of changes arrives in the order things
	 * happened, which is usually the same thing and is not guaranteed to be — a course renamed
	 * after the lecture it carries was created is enough to break it. Sorting here costs a
	 * comparison per row and removes the question.
	 */
	@NonNull
	public static List<SyncIntent> inApplicationOrder(@NonNull List<SyncIntent> changes) {

		List<SyncIntent> sorted = new ArrayList<>(changes);

		sorted.sort(Comparator
				.comparingInt((SyncIntent change) -> change.op() == OutboxOp.DELETE ? 1 : 0)
				.thenComparingInt(change -> {
					int depth = DeviceTables.depthOf(change.table());
					return change.op() == OutboxOp.DELETE ? -depth : depth;
				}));

		return sorted;
	}

	// ── One row, each way ────────────────────────────────────────────────

	/**
	 * The intent to send for a queued key, or null when there is nothing to send.
	 * <p>
	 * An {@code UPSERT} reads the row as it now stands rather than as it was when the trigger
	 * fired, which is what makes two edits before a signal cost one message. A row that has
	 * since been deleted has no state to send, and its deletion is already queued as its own
	 * intent, so this one is simply dropped.
	 */
	@Nullable
	private SyncIntent intentFor(@NonNull EntityOutbox entry) {

		OutboxOp op = entry.operation();

		if (op == null) {
			Log.w(TAG, "an intent with an operation this build does not know: " + entry.op);
			return null;
		}

		DeviceTables.Table table;

		try {
			table = DeviceTables.of(entry.tableName);
		} catch (RuntimeException e) {
			Log.w(TAG, "an intent for a table this build does not know: " + entry.tableName);
			return null;
		}

		List<Long> key = keyOf(table, entry);

		if (op == OutboxOp.DELETE) {
			return new SyncIntent(table.name(), key, op, null);
		}

		Map<String, Object> row = readRow(table, key);

		return row == null ? null : new SyncIntent(table.name(), key, op, row);
	}

	@Nullable
	private Map<String, Object> readRow(@NonNull DeviceTables.Table table,
	                                    @NonNull List<Long> key) {

		Object[] arguments = key.toArray();

		try (Cursor cursor = db.getOpenHelper().getReadableDatabase()
				.query(DeviceSql.selectRow(table), arguments)) {

			if (!cursor.moveToFirst()) {
				return null;
			}

			Map<String, Object> row = new LinkedHashMap<>();

			for (DeviceTables.Column column : table.carriedColumns()) {

				int at = cursor.getColumnIndexOrThrow(column.room());

				if (cursor.isNull(at)) {
					row.put(column.room(), null);

				} else if (DeviceSql.readsAsText(column)) {
					row.put(column.room(), cursor.getString(at));

				} else {
					// By what the column is, not by what the cursor guessed: see
					// DeviceSql.readsAsText.
					row.put(column.room(), cursor.getLong(at));
				}
			}

			return row;
		}
	}

	private void apply(@NonNull SupportSQLiteDatabase sqlite, @NonNull SyncIntent change) {

		DeviceTables.Table table = DeviceTables.of(change.table());

		if (change.op() == OutboxOp.DELETE) {
			sqlite.execSQL(DeviceSql.deleteRow(table), change.key().toArray());
			return;
		}

		List<DeviceTables.Column> columns = DeviceSql.upsertColumns(table);
		Object[] values = new Object[columns.size()];

		for (int at = 0; at < columns.size(); at++) {
			values[at] = change.row().get(columns.get(at).room());
		}

		sqlite.execSQL(DeviceSql.upsertRow(table), values);
	}

	@NonNull
	private static List<Long> keyOf(@NonNull DeviceTables.Table table,
	                                @NonNull EntityOutbox entry) {

		List<Long> key = new ArrayList<>();
		long[] stored = {entry.key1, entry.key2, entry.key3};

		for (int at = 0; at < table.primaryKey().size(); at++) {
			key.add(stored[at]);
		}
		return key;
	}

	/** Which queued intent a rejection is about, matched on the table and the key. */
	@Nullable
	private static Long sequenceOf(@NonNull List<EntityOutbox> pending, @NonNull String table,
	                               @NonNull List<Long> key) {

		for (EntityOutbox entry : pending) {

			if (!entry.tableName.equals(table)) {
				continue;
			}

			DeviceTables.Table described;

			try {
				described = DeviceTables.of(table);
			} catch (RuntimeException e) {
				continue;
			}

			if (keyOf(described, entry).equals(key)) {
				return entry.seq;
			}
		}
		return null;
	}
}
