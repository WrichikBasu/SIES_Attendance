package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import in.basulabs.siesattendance.backend.sync.OutboxOp;
import in.basulabs.siesattendance.backend.sync.OutboxTriggers;

/**
 * One row that has changed on this device and has not reached the server yet.
 * <p>
 * Written by the triggers in {@link OutboxTriggers} rather than by any Java here, so a write and
 * its intent land in the same statement and cannot come apart. Nothing in the app inserts into
 * this table; the sender reads it, sends what it names, and deletes what it sent.
 *
 * <h3>A dirty key, not a diff</h3>
 * An intent says "this row changed", not what it changed to. The sender reads the row as it
 * stands when it drains, which is what makes two edits before the phone finds a signal cost one
 * message, and what makes a replay after a lost response send the current state rather than an
 * older one. The unique index on the key is how the collapse happens: the triggers write
 * {@code INSERT OR REPLACE}, so a row dirtied again replaces its own pending intent and takes a
 * new sequence number.
 *
 * <h3>Not a foreign key</h3>
 * Deliberately. An intent outlives the row it names — that is the whole point of a
 * {@link OutboxOp#DELETE} — and a foreign key would make queueing a deletion impossible.
 */
@Entity(tableName = EntityOutbox.TABLE_NAME,
		indices = {@Index(value = {"tableName", "key1", "key2", "key3"}, unique = true)})
public class EntityOutbox {

	@Ignore
	public static final String TABLE_NAME = "outbox";

	/**
	 * The order intents were queued in, and never reused.
	 * <p>
	 * {@code AUTOINCREMENT}, which is what {@code autoGenerate} produces for a long key, and it
	 * matters here: with plain rowids SQLite reuses the highest free number after a delete, so a
	 * freshly queued intent could be handed a sequence below one that is still waiting, and a
	 * drain that deleted "everything up to the sequence I sent" would throw away an intent it
	 * had never looked at.
	 */
	@PrimaryKey(autoGenerate = true)
	public long seq;

	/** The table whose row changed, by its Room name. */
	@NonNull
	public String tableName;

	/** The first key column. */
	public long key1;

	/** The second, or {@link OutboxTriggers#UNUSED_KEY} for a table with a single-column key. */
	public long key2;

	/** The third, or {@link OutboxTriggers#UNUSED_KEY}. */
	public long key3;

	/** {@link OutboxOp}, as a name. Text because the triggers that write it are SQL. */
	@NonNull
	public String op;

	/** When the trigger fired, in milliseconds. For reading the queue, not for deciding on it. */
	public long queuedAt;

	/**
	 * How many times sending this has been tried and has failed.
	 * <p>
	 * Not a limit. A row that cannot be sent stays queued, because the alternative — dropping it
	 * — is losing attendance quietly, and a count that a screen can show is the honest way to
	 * surface a phone that has been failing to sync for a week.
	 * <p>
	 * The default is not decoration. The triggers insert the table, the key and the operation
	 * and nothing else, so every other column has to have an answer of its own — and Room does
	 * not give a primitive one unless it is asked. Without this the column is {@code NOT NULL}
	 * with no default, the triggers' insert fails, and the statement that fired them fails with
	 * it: the first draft of this entity made every write in the app impossible, which
	 * {@code OutboxTriggerTest} found on its first run.
	 */
	@ColumnInfo(defaultValue = "0")
	public int attempts;

	/** What went wrong last time, for that screen. */
	@Nullable
	public String lastError;

	public EntityOutbox(long seq, @NonNull String tableName, long key1, long key2, long key3,
	                    @NonNull String op, long queuedAt, int attempts,
	                    @Nullable String lastError) {

		this.seq = seq;
		this.tableName = tableName;
		this.key1 = key1;
		this.key2 = key2;
		this.key3 = key3;
		this.op = op;
		this.queuedAt = queuedAt;
		this.attempts = attempts;
		this.lastError = lastError;
	}

	/** The operation, or null if a later build queued something this one does not know. */
	@Ignore
	@Nullable
	public OutboxOp operation() {
		return OutboxOp.fromStored(op);
	}
}
