package in.basulabs.siesattendance.backend.sync;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The triggers that put a row's key in the outbox when the row changes.
 * <p>
 * Three per table — insert, update, delete — on every table in {@link DeviceTables}, generated
 * from the table descriptions rather than written out, so a table whose key changes cannot end up
 * with a trigger that records the wrong one.
 *
 * <h3>Why triggers and not Java</h3>
 * The obvious place to append an intent is the repository, next to the write. It is also the
 * place that is wrong, for two reasons.
 * <p>
 * The first is coverage. The repository is not the only writer: a roster CSV, an attendance-record
 * CSV and a restored backup all go through their own code, and so would anything written in the
 * next five years by somebody who had not read this note. A trigger cannot be forgotten, because
 * it is the database's, not the caller's.
 * <p>
 * The second is atomicity, and it is the one that matters. SQLite runs a trigger inside the
 * statement that fired it, so the row and its intent land or fail together. An append in Java is
 * a second statement, and the window between them is exactly the moment an app is most likely to
 * be killed — the user has just tapped Save and is putting the phone in their pocket. A mark that
 * is saved locally and never queued is the failure this whole phase exists to prevent, and it
 * would be invisible: the screen would show the attendance, and the server would never hear of
 * it.
 *
 * <h3>The guard</h3>
 * Applying what the server sends is also a write, and it must not queue the rows straight back.
 * So each trigger is conditioned on {@code sync_state.applying}, which the pull sets for the
 * length of its own transaction.
 * <p>
 * The condition is written with {@code COALESCE(…, 0)} on purpose. Without it, a missing
 * {@code sync_state} row would make the condition {@code NULL = 0}, which is not true, which
 * would silently stop every intent from being recorded — a device that looks like it is working
 * and never sends anything. With it, a missing row means "not applying", so the failure direction
 * is a queued row too many rather than a mark nobody ever receives.
 *
 * <h3>What an intent holds</h3>
 * The table, up to three key columns, and the operation. Not the row's values: the sender reads
 * the row when it drains, so two edits before the phone finds a signal cost one message, and a
 * replay after a lost response sends the same state again rather than an older one. Unused key
 * columns are {@link #UNUSED_KEY} rather than null, because SQLite treats nulls as distinct in a
 * unique index and two intents for the same single-keyed row would not collapse.
 */
public final class OutboxTriggers {

	/** The table intents are queued in. */
	public static final String OUTBOX = "outbox";

	/** The one-row table holding the pull's guard and its watermark. */
	public static final String SYNC_STATE = "sync_state";

	/**
	 * What stands in for a key column a table does not have.
	 * <p>
	 * Zero, and no real key is ever zero: the app's keys are issued from
	 * {@code StructuredId.FIRST_STRUCTURED_ID} upwards, legacy keys were counted from one, and
	 * the only other key columns are academic years.
	 */
	public static final long UNUSED_KEY = 0L;

	private OutboxTriggers() {
	}

	/**
	 * Every {@code CREATE TRIGGER} statement, in table order.
	 * <p>
	 * {@code IF NOT EXISTS}, and run on every open rather than once in a migration: a fresh
	 * install, an upgraded one and a restored file all have to end up with the same triggers, and
	 * "created when the file was created" is a claim about a file this build may never have seen
	 * the creation of.
	 */
	@NonNull
	public static List<String> statements() {

		List<String> statements = new ArrayList<>();

		for (DeviceTables.Table table : DeviceTables.inDependencyOrder()) {
			statements.add(trigger(table, OutboxOp.UPSERT, "INSERT", "NEW"));
			statements.add(trigger(table, OutboxOp.UPSERT, "UPDATE", "NEW"));
			statements.add(trigger(table, OutboxOp.DELETE, "DELETE", "OLD"));
		}
		return Collections.unmodifiableList(statements);
	}

	/** The trigger names, for a test or for dropping them. */
	@NonNull
	public static List<String> names() {

		List<String> names = new ArrayList<>();

		for (DeviceTables.Table table : DeviceTables.inDependencyOrder()) {
			for (String event : List.of("insert", "update", "delete")) {
				names.add(name(table, event));
			}
		}
		return Collections.unmodifiableList(names);
	}

	/**
	 * Statements that remove every trigger, for the one caller that has to write without being
	 * recorded and cannot use the guard: a restore, which replaces the file underneath.
	 */
	@NonNull
	public static List<String> dropStatements() {

		List<String> statements = new ArrayList<>();

		for (String name : names()) {
			statements.add("DROP TRIGGER IF EXISTS `" + name + "`");
		}
		return Collections.unmodifiableList(statements);
	}

	@NonNull
	private static String name(@NonNull DeviceTables.Table table, @NonNull String event) {
		return "outbox_" + table.name() + "_" + event;
	}

	/**
	 * One trigger.
	 *
	 * @param event the SQL event, which is also what the trigger is named after
	 * @param row   {@code NEW} or {@code OLD}, depending on which one still has the key
	 */
	@NonNull
	private static String trigger(@NonNull DeviceTables.Table table, @NonNull OutboxOp op,
	                              @NonNull String event, @NonNull String row) {

		List<String> key = table.primaryKey();

		if (key.isEmpty() || key.size() > 3) {
			// Three columns is the widest key in the schema (batch_student_map). A fourth would
			// need a column in the outbox, so it fails here rather than silently queueing an
			// intent that names part of a row.
			throw new IllegalStateException(table.name() + " has a " + key.size()
					+ "-column primary key, which the outbox has no room for");
		}

		StringBuilder values = new StringBuilder();

		for (int at = 0; at < 3; at++) {
			values.append(at == 0 ? "" : ", ");

			if (at < key.size()) {
				values.append(row).append(".`").append(key.get(at)).append("`");
			} else {
				values.append(UNUSED_KEY);
			}
		}

		return "CREATE TRIGGER IF NOT EXISTS `" + name(table, event.toLowerCase(
				java.util.Locale.ROOT)) + "` "
				+ "AFTER " + event + " ON `" + table.name() + "` "
				+ "WHEN COALESCE((SELECT `applying` FROM `" + SYNC_STATE
				+ "` WHERE `id` = 1), 0) = 0 "
				+ "BEGIN "
				+ "INSERT OR REPLACE INTO `" + OUTBOX
				+ "` (`tableName`, `key1`, `key2`, `key3`, `op`, `queuedAt`) VALUES ("
				+ "'" + table.name() + "', " + values + ", " + op.sqlLiteral() + ", "
				// Seconds from SQLite's own clock, in milliseconds to match every other
				// timestamp in this schema. A trigger has no access to the device's clock
				// except through SQLite, and the only thing this value decides is the order
				// intents are drained in, which the sequence already settles.
				+ "CAST(strftime('%s','now') AS INTEGER) * 1000); "
				+ "END";
	}
}
