package in.basulabs.siesattendance.backend.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.Map;

/**
 * One row on the wire, in either direction.
 * <p>
 * The same shape going up and coming down, which is not a coincidence: a push is the device
 * telling the server about a row it has, and a pull is the server telling the device about a row
 * it has. Two records would have been two chances for the two directions to disagree about what a
 * row looks like.
 *
 * @param table the Room table name, which is the same on both sides
 * @param key   the primary key values, in {@link DeviceTables.Table#primaryKey()} order. The
 *              order matters and is the table's, not the sender's
 * @param row   the row as the device holds it, keyed by Room column name, or null for a
 *              {@link OutboxOp#DELETE} — where there is nothing left to describe
 */
public record SyncIntent(@NonNull String table, @NonNull List<Long> key, @NonNull OutboxOp op,
                         @Nullable Map<String, Object> row) {

	public SyncIntent {

		if (key.isEmpty()) {
			throw new IllegalArgumentException("an intent with no key names no row");
		}

		if (op == OutboxOp.UPSERT && row == null) {
			throw new IllegalArgumentException(
					"an upsert has to carry the row: the server writes what it is given, and "
							+ "a key alone would be a row of nulls");
		}
	}

	/** A short description for a log or an error message. Never the row's contents. */
	@Override
	@NonNull
	public String toString() {
		return op + " " + table + key;
	}
}
