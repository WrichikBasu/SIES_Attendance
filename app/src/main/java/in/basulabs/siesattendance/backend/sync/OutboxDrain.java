package in.basulabs.siesattendance.backend.sync;

import android.database.Cursor;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteStatement;

import java.io.IOException;
import java.time.ZoneId;
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
 * The watermark moves only when every row in the page has been dealt with, and a row whose
 * parent is not here is dealt with rather than allowed to fail. Waiting for the parent was the
 * earlier rule, and it waits for nothing: a page is applied in dependency order, the feed is in
 * the order things happened, and the server will not store a child before its parent, so a parent
 * that is not here by the time its child is will not arrive on a later page either. What it did
 * instead was fail the insert, roll the page back and leave the watermark where it was, so one row
 * jammed every pull behind it for ever. A phone found it: a student removed on the device while
 * its first snapshot was still on the way down, and the server's marks for that student — ones
 * the phone had not yet received, and which held the student's own deletion down on the server —
 * arriving afterwards for a student who was no longer there.
 * <p>
 * Such a row is not written, and what happens to it depends on why the parent is missing.
 * <ul>
 *     <li>This device deleted the parent and the deletion is still queued. The row is one it
 *     would have deleted along with the parent had it known about it, so its deletion is queued
 *     too. That is also what lets the parent's own deletion through: the server keeps a parent
 *     while anything points at it.</li>
 *     <li>Anything else. The row is skipped, with a warning in the log. The server is meant never
 *     to send a row whose parent it withholds, and V21 closed the one case known to break that;
 *     a skipped row is a sign of another.</li>
 * </ul>
 * <p>
 * A deletion the server refuses for good is the other half of the same drift. The device has
 * already removed the row, the server still has it, and nothing will send it back, because a row
 * that does not change never appears in the feed again. So the next pull starts over from a
 * snapshot, which brings it back along with anything that points at it.
 */
public final class OutboxDrain {

	private static final String TAG = "OutboxDrain";

	/** How many intents one push carries. The server refuses more than a thousand. */
	public static final int BATCH = 200;

	/** How many rows one pull asks for. */
	private static final int PAGE = 500;

	/**
	 * What one run did, for the worker to decide on and a screen to show.
	 *
	 * @param rejected how many the server refused and this device kept, to try again
	 * @param dropped  how many it refused for good, which are gone from the queue
	 * @param refusals every refusal the server called final in this batch, newest last. A list
	 *                 rather than one string: two rows refused for two different reasons are
	 *                 two things to tell somebody, and the one that used to win was whichever
	 *                 came last in the batch
	 */
	public record Result(int sent, int rejected, int dropped, @NonNull List<String> refusals,
	                     int applied, boolean morePending) {

		public boolean didSomething() {
			return sent > 0 || applied > 0;
		}

		/** The newest refusal, for the one line a screen has room for, or null. */
		@Nullable
		public String refusal() {
			return refusals.isEmpty() ? null : refusals.get(refusals.size() - 1);
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
			return new Result(0, 0, 0, List.of(), 0, false);
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
			return new Result(0, 0, 0, List.of(), 0, false);
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
		List<String> refusals = new ArrayList<>();
		boolean drifted = false;

		for (SyncJson.Rejection rejection : outcome.rejected()) {

			EntityOutbox entry = entryFor(pending, rejection.table(), rejection.key());

			if (entry == null) {
				continue;
			}

			Long sequence = entry.seq;

			accepted.remove(sequence);
			Log.w(TAG, "the server refused " + rejection.table() + rejection.key()
					+ ": " + rejection.reason());

			if (rejection.permanent()) {
				drop.add(sequence);

				// All of them, not only the last: a refusal is news about something somebody
				// did -- a student they removed, a course they renamed -- and the one before
				// it is somebody else's news rather than an older version of the same. The
				// drain reports them and SyncWorker is what remembers them, because keeping
				// them is a preferences job and this class is the database and the wire.
				refusals.add(rejection.reason());

				// The row is gone from here and staying on the server. See the class notes.
				drifted |= entry.operation() == OutboxOp.DELETE;

			} else {
				keep.add(sequence);
			}
		}

		// Deleted alongside the accepted ones, in the same transaction: a refusal that cannot
		// be retried and a row that has landed both leave the queue, and for the queue they
		// are the same event.
		List<Long> finished = new ArrayList<>(accepted);
		finished.addAll(drop);
		boolean resnapshot = drifted;

		db.runInTransaction(() -> {

			if (!finished.isEmpty()) {
				db.daoOutbox().deleteSentSync(finished);
			}

			if (!keep.isEmpty()) {
				db.daoOutbox().recordFailureSync(keep, "the server refused this row");
			}

			if (resnapshot) {
				db.daoOutbox().forgetWatermarkSync();
			}

			db.daoOutbox().recordPushSync(System.currentTimeMillis());
		});

		return new Result(accepted.size(), keep.size(), drop.size(), refusals, 0,
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

			return new Result(0, 0, 0, List.of(), 0, page.more());
		}

		int[] applied = new int[1];

		db.runInTransaction(() -> {

			// Signed out while this page was on its way down. SignOut empties the database
			// after forgetting the token, so a page written now would put the last account's
			// rows back for the next one to read. Inside the transaction, so it is decided
			// in the same order as the clear.
			if (!client.isSignedIn()) {
				return;
			}

			SupportSQLiteDatabase sqlite = db.getOpenHelper().getWritableDatabase();

			// The guard, inside the transaction: a crash rolls it back with the writes.
			db.daoOutbox().setApplyingSync(1);

			try {
				for (SyncIntent change : inApplicationOrder(page.changes())) {
					if (apply(sqlite, change)) {
						applied[0]++;
					}
				}

				db.daoOutbox().recordPullSync(page.watermark(), System.currentTimeMillis());

			} finally {
				db.daoOutbox().setApplyingSync(0);
			}
		});

		return new Result(0, 0, 0, List.of(), applied[0], page.more());
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

	/**
	 * Writes one pulled change.
	 *
	 * @return whether it was written; false for a row whose parent is not here, which the class
	 * notes describe
	 */
	private boolean apply(@NonNull SupportSQLiteDatabase sqlite, @NonNull SyncIntent change) {

		DeviceTables.Table table = DeviceTables.of(change.table());

		if (change.op() == OutboxOp.DELETE) {
			sqlite.execSQL(DeviceSql.deleteRow(table), change.key().toArray());
			return true;
		}

		Parent missing = missingParent(sqlite, table, change.row());

		if (missing != null) {

			if (db.daoOutbox().isDeletePendingSync(missing.table().name(), missing.key(),
					OutboxTriggers.UNUSED_KEY, OutboxTriggers.UNUSED_KEY)) {

				// Queued here rather than by a trigger, because the row was never written and
				// so nothing fired. The parent's own deletion is already queued.
				long[] key = {OutboxTriggers.UNUSED_KEY, OutboxTriggers.UNUSED_KEY,
						OutboxTriggers.UNUSED_KEY};

				for (int at = 0; at < change.key().size(); at++) {
					key[at] = change.key().get(at);
				}

				db.daoOutbox().queueSync(new EntityOutbox(0, table.name(), key[0], key[1], key[2],
						OutboxOp.DELETE.name(), System.currentTimeMillis(), 0, null));

				Log.i(TAG, "not storing " + table.name() + change.key() + ": this device deleted "
						+ missing.table().name() + "[" + missing.key() + "], so it is deleted too");

			} else {
				Log.w(TAG, "not storing " + table.name() + change.key() + ": the server sent it "
						+ "without " + missing.table().name() + "[" + missing.key() + "]");
			}
			return false;
		}

		// What the server sent, plus the columns this device needs and the server does not
		// keep. Without this an academic_years row cannot be written at all -- startEpoch is
		// NOT NULL here and absent there -- and a page that cannot be written is a watermark
		// that never moves, so the one row jams every pull behind it.
		Map<String, Object> row = DeviceDerived.fill(table, change.row(), ZoneId.systemDefault());

		// Change it where it is, and only insert when it is not here. Not INSERT OR REPLACE:
		// REPLACE deletes the row that is there first, and the device's own rows point at it
		// with RESTRICT, so the server's copy of a lecture this phone has marks against could
		// not be accepted at all. See DeviceSql.updateRow.
		String update = DeviceSql.updateRow(table);

		if (update != null
				&& change(sqlite, update, bindings(DeviceSql.updateColumns(table), row)) > 0) {
			return true;
		}

		sqlite.execSQL(DeviceSql.insertRow(table),
				bindings(DeviceSql.insertColumns(table), row));
		return true;
	}

	/** A row a pulled row points at, by its table and its key. */
	private record Parent(@NonNull DeviceTables.Table table, long key) {
	}

	/**
	 * The first row this one points at that the device does not have, or null when every
	 * parent is here.
	 * <p>
	 * Asked before writing rather than learnt from the constraint afterwards: a failed statement
	 * fails the page, and the page is exactly what this is protecting. Every parent in the
	 * schema is keyed on its surrogate alone, which is what {@link DeviceTables.Table#foreignKeys}
	 * names the column after. A null reference — a lecture with no batch — points at nothing and
	 * needs nothing.
	 */
	@Nullable
	private static Parent missingParent(@NonNull SupportSQLiteDatabase sqlite,
	                                    @NonNull DeviceTables.Table table,
	                                    @Nullable Map<String, Object> row) {

		if (row == null) {
			return null;
		}

		for (Map.Entry<String, String> reference : table.foreignKeys().entrySet()) {

			if (!(row.get(reference.getKey()) instanceof Number value)) {
				continue;
			}

			DeviceTables.Table parent = DeviceTables.of(reference.getValue());

			try (Cursor cursor = sqlite.query(DeviceSql.rowExists(parent),
					new Object[]{value.longValue()})) {

				if (!cursor.moveToFirst()) {
					return new Parent(parent, value.longValue());
				}
			}
		}
		return null;
	}

	/** A row's values in the order a statement's columns list them. */
	@NonNull
	private static Object[] bindings(@NonNull List<DeviceTables.Column> columns,
	                                 @NonNull Map<String, Object> row) {

		Object[] values = new Object[columns.size()];

		for (int at = 0; at < columns.size(); at++) {
			values[at] = row.get(columns.get(at).room());
		}

		return values;
	}

	/**
	 * Runs a statement and says how many rows it changed, which {@code execSQL} does not.
	 * <p>
	 * The count is the whole reason the update is run first: it is how the drain knows whether
	 * the device already had this row without asking a second question about it.
	 */
	private static int change(@NonNull SupportSQLiteDatabase sqlite, @NonNull String sql,
	                          @NonNull Object[] values) {

		try (SupportSQLiteStatement statement = sqlite.compileStatement(sql)) {

			for (int at = 0; at < values.length; at++) {

				Object value = values[at];

				if (value == null) {
					statement.bindNull(at + 1);

				} else if (value instanceof Long number) {
					statement.bindLong(at + 1, number);

				} else {
					// Everything the device holds is a whole number or text; the server sends
					// a boolean as 0 or 1, exactly as SQLite keeps it. See SyncJson.
					statement.bindString(at + 1, String.valueOf(value));
				}
			}

			return statement.executeUpdateDelete();

		} catch (IOException closing) {
			// SupportSQLiteStatement.close() declares it; a compiled statement does not do IO.
			throw new IllegalStateException(closing);
		}
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
	private static EntityOutbox entryFor(@NonNull List<EntityOutbox> pending, @NonNull String table,
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
				return entry;
			}
		}
		return null;
	}
}
