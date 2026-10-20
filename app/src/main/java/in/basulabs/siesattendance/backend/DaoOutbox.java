package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import java.util.List;

/**
 * The queue of local changes, and the device's sync bookkeeping.
 * <p>
 * Read-and-delete only. Nothing here inserts an intent: the triggers do that, inside the
 * statement that changed the row, which is what makes a mark and its intent inseparable. A method
 * that queued one by hand would be a second way for the queue to be filled, and the first one
 * nobody would remember to keep in step.
 *
 * @see in.basulabs.siesattendance.backend.sync.OutboxTriggers
 */
@Dao
public interface DaoOutbox {

	/**
	 * The oldest pending intents, up to a limit.
	 * <p>
	 * In sequence order, which is the order the rows were last touched. The sender reorders them
	 * by table dependency before sending — a lecture has to reach the server before its
	 * attendance does — so this ordering is about fairness rather than correctness: a row dirtied
	 * an hour ago should not wait behind one dirtied a moment ago for ever.
	 */
	@NonNull
	@Query("SELECT * FROM outbox ORDER BY seq ASC LIMIT :limit")
	List<EntityOutbox> pendingSync(int limit);

	/** How many changes are waiting, for a screen to show. */
	@NonNull
	@Query("SELECT COUNT(*) FROM outbox")
	LiveData<Integer> countPending();

	@Query("SELECT COUNT(*) FROM outbox")
	int countPendingSync();

	/**
	 * Removes the intents that were sent, by sequence.
	 * <p>
	 * By their own sequence numbers and not by a range: a row dirtied again while the batch was
	 * in flight has replaced its intent with a <em>new</em> sequence, so "delete everything up to
	 * the highest I sent" would throw away a change nobody has sent yet. The replacement survives
	 * because its number is not in this list.
	 */
	@Query("DELETE FROM outbox WHERE seq IN (:sequences)")
	void deleteSentSync(@NonNull List<Long> sequences);

	/** Records that a batch could not be sent, so a stuck device can say so. */
	@Query("UPDATE outbox SET attempts = attempts + 1, lastError = :error "
			+ "WHERE seq IN (:sequences)")
	void recordFailureSync(@NonNull List<Long> sequences, @Nullable String error);

	// ── The sync state ───────────────────────────────────────────────────

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	void insertStateIfAbsent(@NonNull EntitySyncState state);

	@Nullable
	@Query("SELECT * FROM sync_state WHERE id = 1")
	EntitySyncState stateSync();

	/**
	 * The same row, observed.
	 * <p>
	 * For the one screen that reports on syncing. Room invalidates it when a pull records its
	 * watermark or a push records its time, so the screen's two directions update themselves
	 * while a sync is running rather than when somebody comes back to the screen.
	 */
	@Query("SELECT * FROM sync_state WHERE id = 1")
	LiveData<EntitySyncState> observeState();

	/**
	 * Marks the beginning and end of applying what the server sent.
	 * <p>
	 * Must be called inside the same transaction as the writes it guards, so that a crash takes
	 * the flag back with them. See {@link EntitySyncState}.
	 */
	@Query("UPDATE sync_state SET applying = :applying WHERE id = 1")
	void setApplyingSync(int applying);

	@Query("UPDATE sync_state SET watermark = :watermark, lastPullAt = :at WHERE id = 1")
	void recordPullSync(@Nullable String watermark, long at);

	@Query("UPDATE sync_state SET lastPushAt = :at WHERE id = 1")
	void recordPushSync(long at);

	/**
	 * The watermark, or null before the first pull.
	 * <p>
	 * Through the state row rather than as its own query so that a database somehow without the
	 * row answers "nothing has been pulled" instead of failing — which is also the right answer.
	 */
	@Transaction
	@Nullable
	default String watermarkSync() {

		EntitySyncState state = stateSync();
		return state == null ? null : state.watermark;
	}
}
