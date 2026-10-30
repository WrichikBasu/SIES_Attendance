package in.basulabs.siesattendance.backend.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * What happened to a row, as far as the server needs to know.
 * <p>
 * Two values, not three. A row that was inserted and a row that was changed are the same intent
 * — "this is the current state of this row, take it" — because the outbox records a dirty key
 * rather than a diff, and the sender reads the row as it stands when it drains. That is what
 * makes a row dirtied twice before the phone finds a signal cost one message instead of two, and
 * it is why replaying an intent is harmless: sending the current state twice has the same result
 * as sending it once.
 * <p>
 * Stored as {@code name()} in {@code outbox.op}, which is a text column rather than this enum
 * because the triggers that write it are SQL and cannot see Java.
 */
public enum OutboxOp {

	/**
	 * The row exists and the server should have it as it now is. Covers an insert and an update
	 * alike; see the class notes.
	 */
	UPSERT,

	/**
	 * The row is gone. Carries only the key, because there is nothing left to read: by the time
	 * the outbox is drained the row has been deleted, which is also why a delete cannot be
	 * collapsed into an earlier upsert of the same key — the last intent for a key is the only
	 * one that is true.
	 */
	DELETE;

	/** Resolves what a trigger wrote, or null for a value this build does not know. */
	@Nullable
	public static OutboxOp fromStored(@Nullable String raw) {

		if (raw == null) {
			return null;
		}

		for (OutboxOp op : values()) {
			if (op.name().equals(raw)) {
				return op;
			}
		}
		return null;
	}

	/** The literal a trigger writes, quoted for SQL. */
	@NonNull
	public String sqlLiteral() {
		return "'" + name() + "'";
	}
}
