package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

import in.basulabs.siesattendance.backend.sync.OutboxTriggers;

/**
 * The device's sync bookkeeping: one row, for ever.
 * <p>
 * Two things live here and they are here for different reasons.
 *
 * <h3>{@code applying}</h3>
 * Applying what the server sent is itself a write, and the outbox triggers would queue every
 * incoming row straight back. This flag is what they are conditioned on: the pull sets it for the
 * length of its own transaction, so rows that arrived from the server are not announced back to
 * it. It is in the database rather than in memory because the triggers are SQL and cannot see a
 * Java field.
 * <p>
 * A crash mid-pull would leave it set, which would stop the outbox recording anything — so the
 * pull sets it inside the same transaction as the writes it guards. SQLite rolls the flag back
 * with them.
 *
 * <h3>{@code watermark}</h3>
 * Where the last pull got to, as the server described it. Opaque on purpose: it is the server's
 * cursor, and a device that tried to interpret it would be a device with an opinion about the
 * server's ordering.
 */
@Entity(tableName = EntitySyncState.TABLE_NAME)
public class EntitySyncState {

	@Ignore
	public static final String TABLE_NAME = OutboxTriggers.SYNC_STATE;

	/** Always 1. One device, one row; see {@link OutboxTriggers}. */
	@PrimaryKey
	public int id;

	/** 1 while a pull is writing what the server sent. See the class notes. */
	public int applying;

	/** The server's cursor from the last successful pull, or null before the first one. */
	@Nullable
	public String watermark;

	/** When that pull finished, in milliseconds, or 0. For a screen to show. */
	public long lastPullAt;

	/** When the outbox was last drained to completion, in milliseconds, or 0. */
	public long lastPushAt;

	public EntitySyncState(int id, int applying, @Nullable String watermark, long lastPullAt,
	                       long lastPushAt) {

		this.id = id;
		this.applying = applying;
		this.watermark = watermark;
		this.lastPullAt = lastPullAt;
		this.lastPushAt = lastPushAt;
	}

	/** The row a fresh database starts with. */
	@Ignore
	@NonNull
	public static EntitySyncState initial() {
		return new EntitySyncState(1, 0, null, 0L, 0L);
	}
}
