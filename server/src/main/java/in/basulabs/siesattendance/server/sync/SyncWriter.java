package in.basulabs.siesattendance.server.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import in.basulabs.siesattendance.backend.StructuredId;
import in.basulabs.siesattendance.backend.sync.DeviceTables;
import in.basulabs.siesattendance.backend.sync.OutboxOp;

/**
 * Applies what a device's outbox drained: one row per intent, by primary key.
 * <p>
 * Much simpler than the backup importer, and for one reason — the keys already mean something.
 * A device issues structured keys that are unique everywhere, so there is nothing to reconcile
 * and no identity map: an intent names a row, and the row is written under the key it names.
 * The importer's natural-key matching exists for history written before any of that was true.
 *
 * <h3>Idempotent by construction</h3>
 * Every upsert is {@code INSERT … ON CONFLICT (primary key) DO UPDATE}, so the same intent
 * applied twice leaves the same row. That is what makes a lost response harmless: the device
 * cannot tell whether its batch arrived, and it does not have to — it sends it again. A delete
 * is equally forgiving, because deleting a row that is not there is not an error here.
 *
 * <h3>Which tables</h3>
 * {@code synced_tables()} says, and since V11 the answer is all eleven a device holds. Every one
 * of them carries policies, so every statement below is policed by the database rather than by
 * this class: a professor pushing another department's lecture is refused, and so is one pushing a
 * roster, and the refusal arrives here as something to report rather than as a decision to make.
 * That is the whole reason the set is read from the database instead of being written down here —
 * widening it is a migration, not a release of this file.
 *
 * <h3>The node check, and the two nodes a device has</h3>
 * A key a device issues has to carry one of <em>its</em> nodes, and a phone has had two: the one
 * it picked for itself before it ever spoke to a server, and the one registration assigned it.
 * Rows created in that first period are still that device's own work — for a phone that has been
 * taking attendance for a term and is only now being pointed at a server, they are all of its
 * work — so both are accepted. V8 records the provisional node at registration precisely so this
 * is knowable, and V13 is the function that reads it.
 * <p>
 * A key a device issues has to carry that device's node. It is the one rule here that is not the
 * database's: a phone must not be able to create rows claiming to be another phone's, and nothing
 * in SQL knows which phone is holding the token.
 * <p>
 * It applies to <em>creating</em> a row and not to writing one. Before V11 the distinction did not
 * arise, because a device only ever pushed rows it had made itself; now that it pulls a roster, a
 * professor amending a course that came from the server is pushing a key some other node issued,
 * and refusing that would make the pull pointless. So a key from another node is accepted only
 * when the row is already there — and whether it may then be changed is the policies' decision,
 * which is where it belongs.
 * <p>
 * Tables with no surrogate key of their own are not checked at all and do not need to be: their
 * keys are other rows' keys, and a CR marking a register the professor created is the ordinary
 * case.
 */
public final class SyncWriter {

	/**
	 * One row a device wants written or removed.
	 *
	 * @param table the Room table name, which is the same on both sides
	 * @param key   the primary key values, in {@link DeviceTables.Table#primaryKey()} order
	 * @param row   the row as the device holds it, keyed by Room column name. Null for a delete
	 */
	public record Intent(@NonNull String table, @NonNull List<Long> key, @NonNull OutboxOp op,
	                     @Nullable Map<String, Object> row) {
	}

	/**
	 * An intent that was not applied, and the sentence the device should show.
	 *
	 * @param permanent whether sending this again could ever work. A policy refusal, a row the
	 *                  conversion cannot make sense of and a key from the wrong node are
	 *                  settled: the device drops the intent and says so, because a queue that
	 *                  retries them for ever is a queue that never empties and a phone that
	 *                  never finishes syncing. A missing parent row or a lost race is not
	 *                  settled, and those are kept and tried again
	 */
	public record Rejection(@NonNull String table, @NonNull List<Long> key,
	                        @NonNull String reason, boolean permanent) {
	}

	/**
	 * @param accepted how many intents were applied
	 * @param rejected the ones that were not, each with a reason and whether it is worth
	 *                 sending again
	 */
	public record Result(int accepted, @NonNull List<Rejection> rejected) {
	}

	/** PostgreSQL's {@code insufficient_privilege}: a policy refused the statement. */
	private static final String INSUFFICIENT_PRIVILEGE = "42501";

	/** {@code foreign_key_violation}: the row points at one that is not here. */
	private static final String FOREIGN_KEY_VIOLATION = "23503";

	/**
	 * {@code restrict_violation}, which is what {@code ON DELETE RESTRICT} raises — and it is
	 * not {@code 23503}.
	 * <p>
	 * Worth a constant of its own and a note, because the two are easy to conflate and the
	 * difference is invisible in the message: both say "violates ... foreign key constraint".
	 * {@code NO ACTION} refuses with 23503 at the end of the statement; {@code RESTRICT}
	 * refuses with 23001 immediately. Every foreign key in this schema is RESTRICT, so a
	 * delete held down by a child arrives as 23001 — and a branch written for 23503 alone
	 * never fires, which is how a refusal came back as a constraint name and was retried for
	 * ever.
	 */
	private static final String RESTRICT_VIOLATION = "23001";

	/**
	 * The tables whose refusals a professor is likely to meet, and which deserve a sentence
	 * that says what to do rather than only what went wrong.
	 */
	private static final Set<String> ROSTER_TABLES =
			Set.of("students", "student_year", "professors", "academic_years");

	/**
	 * The SQL states that mean "this row, as it stands, is not writable here" — as opposed to
	 * "not yet" or "not just then".
	 * <p>
	 * A check constraint, a not-null, a bad value: the row says something the schema forbids, and
	 * it will say the same thing tomorrow. A foreign key is deliberately <em>not</em> here, and
	 * nor is a unique violation, which usually means two devices are arguing about a natural key
	 * and one of them is about to win.
	 * <p>
	 * A policy refusal is in {@link #settled} rather than here, because it is the one that
	 * depends on what else has arrived — see that method.
	 */
	private static final Set<String> SETTLED_STATES = Set.of(
			INSUFFICIENT_PRIVILEGE,
			"23514",    // check_violation
			"23502",    // not_null_violation
			"22P02",    // invalid_text_representation
			"22003",    // numeric_value_out_of_range
			"22007",    // invalid_datetime_format
			"22008");   // datetime_field_overflow

	private SyncWriter() {
	}

	/**
	 * Applies a batch on the caller's transaction, which must already have an actor announced
	 * on it.
	 * <p>
	 * Each intent runs inside a savepoint, so one the policies refuse costs only itself and the
	 * rest of the batch still commits. That matters more here than anywhere else in this server:
	 * a phone that has been offline for a day sends everything at once, and a single bad intent
	 * must not send the whole day back to the queue for ever.
	 *
	 * @param deviceNode  the node assigned to the device presenting the token
	 * @param priorNode   the node that device issued keys under before it registered, or null.
	 *                    Accepted equally: see the class notes
	 */
	@NonNull
	public static Result apply(@NonNull Connection connection, @NonNull List<Intent> intents,
	                           @Nullable Integer deviceNode, @Nullable Integer priorNode)
			throws SQLException {

		Set<String> allowed = syncedTables(connection);
		Set<String> stamped = tablesWithProvenance(connection);
		List<Rejection> rejected = new ArrayList<>();
		int accepted = 0;

		for (Intent intent : inApplicationOrder(intents)) {

			String refusal = unacceptable(connection, intent, allowed, deviceNode, priorNode);

			if (refusal != null) {
				// Every refusal from there is about the intent itself rather than about the
				// state of the database, so none of them is worth sending again.
				rejected.add(new Rejection(intent.table(), intent.key(), refusal, true));
				continue;
			}

			Savepoint savepoint = connection.setSavepoint();

			try {
				if (intent.op() == OutboxOp.DELETE) {

					String unremovable = delete(connection, intent);

					if (unremovable != null) {
						// Not an exception: row-level security answers a DELETE nobody is
						// allowed to make by matching no rows, not by raising. See delete().
						connection.rollback(savepoint);
						rejected.add(new Rejection(intent.table(), intent.key(),
								unremovable, true));
						continue;
					}

				} else {
					upsert(connection, intent, stamped.contains(intent.table()));
				}

				connection.releaseSavepoint(savepoint);
				accepted++;

			} catch (DeviceValue.Unconvertible e) {
				connection.rollback(savepoint);
				// The row will convert the same way next time.
				rejected.add(new Rejection(intent.table(), intent.key(), e.getMessage(), true));

			} catch (SQLException e) {
				connection.rollback(savepoint);
				rejected.add(rejectionFor(connection, intent, e));
			}
		}

		return new Result(accepted, rejected);
	}

	/**
	 * The order the server applies a batch in, which is not the order the device sent it.
	 * <p>
	 * Upserts in dependency order — a lecture before the marks on it — and deletes in the
	 * reverse, because the foreign keys are {@code RESTRICT} and a lecture cannot go while its
	 * attendance is still there. The device could sort them itself and the server would still
	 * have to, because a batch is not something to take the sender's word about.
	 */
	@NonNull
	static List<Intent> inApplicationOrder(@NonNull List<Intent> intents) {

		List<String> order = new ArrayList<>();

		for (DeviceTables.Table table : DeviceTables.inDependencyOrder()) {
			order.add(table.name());
		}

		List<Intent> sorted = new ArrayList<>(intents);

		sorted.sort(Comparator
				// Upserts first: a delete of a row this batch also creates is the row being
				// made and unmade, and applying the delete last is what leaves it gone.
				.comparingInt((Intent intent) -> intent.op() == OutboxOp.DELETE ? 1 : 0)
				.thenComparingInt(intent -> {
					int at = order.indexOf(intent.table());
					int depth = at < 0 ? order.size() : at;
					return intent.op() == OutboxOp.DELETE ? -depth : depth;
				}));

		return sorted;
	}

	/**
	 * A failed intent as something to send back, and whether to send it again.
	 * <p>
	 * One case gets a sentence of its own, because a phone found it: a row pointing at a parent
	 * whose key <em>predates the id scheme</em>. That parent can never be pushed — a key counted
	 * out from 1 by SQLite means nothing outside the file it came from, which is why the check
	 * below refuses it — so the child can never land either. What arrives without this is a
	 * foreign key error naming a constraint, and a missing parent is ordinarily worth waiting
	 * for, so the device keeps it and retries. For a device whose whole database predates the
	 * scheme that is a hundred rows retried on every sync, for ever, with nothing in the message
	 * to say that waiting is hopeless.
	 */
	@NonNull
	private static Rejection rejectionFor(@NonNull Connection connection,
	                                      @NonNull Intent intent, @NonNull SQLException e)
			throws SQLException {

		String state = String.valueOf(e.getSQLState());

		// A delete refused by a foreign key is the opposite case from an upsert refused by
		// one, and it was being treated as the same. An upsert whose parent is missing is
		// worth waiting for -- the parent may be in the next batch -- so neither state is in
		// SETTLED_STATES. A delete refused by one is a row that something still points at,
		// and whether that will change depends entirely on what is pointing.
		if (intent.op() == OutboxOp.DELETE
				&& (RESTRICT_VIOLATION.equals(state)
				|| FOREIGN_KEY_VIOLATION.equals(state))) {
			return heldDown(connection, intent);
		}

		if (FOREIGN_KEY_VIOLATION.equals(state)) {

			String unsyncable = unsyncableParent(connection, intent);

			if (unsyncable != null) {
				return new Rejection(intent.table(), intent.key(), unsyncable, true);
			}
		}

		return new Rejection(intent.table(), intent.key(), explain(e, intent.table()),
				settled(connection, intent, e));
	}

	/**
	 * Why that row cannot be deleted, named by what is holding it down.
	 * <p>
	 * A handset found this one. A head of department removed a student who was a class
	 * representative, and got
	 * <pre>
	 * ERROR: update or delete on table "students" violates RESTRICT setting of foreign key
	 *        constraint "attendance_delegate_student_id_fkey" on table "attendance_delegate"
	 * </pre>
	 * — every sync, for ever. Two things wrong with that. The message is a constraint name
	 * where a sentence belongs, and the refusal was treated as transient, because 23503 is
	 * deliberately not in {@code SETTLED_STATES}: an <em>upsert</em> whose parent is missing is
	 * worth waiting for. A delete has the opposite shape, and the question is what is pointing
	 * at the row.
	 * <p>
	 * Asked of the catalogue rather than parsed out of the message. Every foreign key that
	 * references this table is looked up, and each one is asked whether it currently holds a
	 * row pointing at this key — so the answer names the tables that are actually in the way
	 * rather than the one PostgreSQL happened to notice first.
	 * <p>
	 * Permanence follows from <em>which</em> tables they are. A blocker in a table the device
	 * syncs is worth waiting for: the device may be about to delete those rows too, and a
	 * batch sends children before parents. A blocker in a table the device has never heard of
	 * — an appointment, an account — cannot be cleared from a phone at all, so retrying is a
	 * queue that never empties.
	 */
	@NonNull
	private static Rejection heldDown(@NonNull Connection connection, @NonNull Intent intent)
			throws SQLException {

		DeviceTables.Table table = DeviceTables.of(intent.table());
		List<String> blockers = new ArrayList<>();
		boolean anyUnreachable = false;

		for (Referencing referencing : referencesTo(connection, table.name())) {

			long rows = pointingAt(connection, referencing, table, intent.key());

			if (rows == 0) {
				continue;
			}

			blockers.add(describe(referencing.table(), rows));

			if (!synced(referencing.table())) {
				anyUnreachable = true;
			}
		}

		if (blockers.isEmpty()) {
			// The catalogue says nothing is pointing at it, and PostgreSQL said otherwise a
			// moment ago. Something changed under us, or a constraint this does not understand
			// refused it; either way it is worth another try rather than a confident lie.
			return new Rejection(intent.table(), intent.key(),
					"the server would not remove that row, and could not say what is holding "
							+ "it. Worth trying again.", false);
		}

		return new Rejection(intent.table(), intent.key(),
				"that row cannot be removed while " + String.join(" and ", blockers)
						+ (anyUnreachable
						? ". That has to be undone on the server first; this phone cannot "
								+ "send it."
						: ". Those are being sent too, so this will go through once they "
								+ "have."),
				anyUnreachable);
	}

	/** One foreign key pointing at a table, as the catalogue describes it. */
	private record Referencing(@NonNull String table, @NonNull List<String> columns,
	                           @NonNull List<String> referenced) {
	}

	/**
	 * Every foreign key that references that table.
	 * <p>
	 * From {@code pg_constraint}, because the alternative is a list in Java that has to be kept
	 * in step with the schema by hand — and the last time a rule was written twice in this
	 * project, the two copies disagreed for ten migrations.
	 */
	@NonNull
	private static List<Referencing> referencesTo(@NonNull Connection connection,
	                                              @NonNull String table) throws SQLException {

		List<Referencing> references = new ArrayList<>();

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT c.conrelid::regclass::text AS referencing, "
						+ "       (SELECT array_agg(a.attname ORDER BY k.ordinality) "
						+ "        FROM unnest(c.conkey) WITH ORDINALITY k(attnum, ordinality) "
						+ "        JOIN pg_attribute a ON a.attrelid = c.conrelid "
						+ "                           AND a.attnum = k.attnum) AS cols, "
						+ "       (SELECT array_agg(a.attname ORDER BY k.ordinality) "
						+ "        FROM unnest(c.confkey) WITH ORDINALITY k(attnum, ordinality) "
						+ "        JOIN pg_attribute a ON a.attrelid = c.confrelid "
						+ "                           AND a.attnum = k.attnum) AS refs "
						+ "FROM pg_constraint c "
						+ "WHERE c.confrelid = ?::regclass AND c.contype = 'f'")) {

			statement.setString(1, table);

			try (ResultSet results = statement.executeQuery()) {
				while (results.next()) {
					references.add(new Referencing(results.getString("referencing"),
							names(results.getArray("cols")), names(results.getArray("refs"))));
				}
			}
		}

		return references;
	}

	/** How many rows of that foreign key point at this key. */
	private static long pointingAt(@NonNull Connection connection,
	                               @NonNull Referencing referencing,
	                               @NonNull DeviceTables.Table table, @NonNull List<Long> key)
			throws SQLException {

		StringBuilder sql = new StringBuilder("SELECT count(*) FROM ")
				.append(referencing.table()).append(" WHERE ");

		List<Long> values = new ArrayList<>();

		for (int at = 0; at < referencing.referenced().size(); at++) {

			int position = positionOf(table, referencing.referenced().get(at));

			if (position < 0 || position >= key.size()) {
				// A foreign key naming a column that is not part of this table's primary key:
				// it cannot be the one blocking a delete by key, so it is not asked about.
				return 0;
			}

			sql.append(at == 0 ? "" : " AND ").append(referencing.columns().get(at))
					.append(" = ?");
			values.add(key.get(position));
		}

		try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {

			for (int at = 0; at < values.size(); at++) {
				statement.setLong(at + 1, values.get(at));
			}

			try (ResultSet results = statement.executeQuery()) {
				return results.next() ? results.getLong(1) : 0;
			}
		}
	}

	/** Where that column sits in the table's primary key, or -1. */
	private static int positionOf(@NonNull DeviceTables.Table table,
	                              @NonNull String postgres) {

		for (int at = 0; at < table.primaryKey().size(); at++) {
			if (postgres.equals(table.column(table.primaryKey().get(at)).postgres())) {
				return at;
			}
		}

		return -1;
	}

	/** Whether the device holds that table at all, which decides whether waiting can help. */
	private static boolean synced(@NonNull String table) {

		for (DeviceTables.Table described : DeviceTables.inDependencyOrder()) {
			if (described.name().equals(table)) {
				return true;
			}
		}

		return false;
	}

	/** What is in the way, in words a person can act on. */
	@NonNull
	private static String describe(@NonNull String table, long rows) {

		return switch (table) {
			case "attendance_delegate" -> rows == 1
					? "that student is the class representative of a course"
					: "that student is the class representative of " + rows + " courses";
			case "app_account" -> "that student has an account on the server";
			case "attendance" -> rows + (rows == 1 ? " mark" : " marks") + " still refer to it";
			case "student_year" -> rows + (rows == 1 ? " enrolment" : " enrolments")
					+ " still refer to it";
			case "batch_student_map" -> "it is still in " + rows
					+ (rows == 1 ? " batch" : " batches");
			case "lectures" -> rows + (rows == 1 ? " lecture" : " lectures")
					+ " still refer to it";
			case "course_instructor" -> "it is still named as an instructor";
			case "course_batch_map" -> "a batch is still attached to it";
			default -> rows + " row" + (rows == 1 ? "" : "s") + " in " + table
					+ " still refer to it";
		};
	}

	@NonNull
	private static List<String> names(@Nullable java.sql.Array array) throws SQLException {

		List<String> names = new ArrayList<>();

		if (array == null) {
			return names;
		}

		for (Object name : (Object[]) array.getArray()) {
			names.add(String.valueOf(name));
		}

		return names;
	}

	/**
	 * The sentence for a parent that is both missing and unpushable, or null.
	 * <p>
	 * Both halves matter. Missing alone is "not yet", which is kept. Unstructured alone is
	 * nothing: a server whose own rows came from a backup import or a roster CSV has them
	 * keyed under node 0, so a parent that is <em>here</em> is fine whatever its key looks
	 * like — and a device holding that key got it from a pull.
	 */
	@Nullable
	private static String unsyncableParent(@NonNull Connection connection,
	                                       @NonNull Intent intent) throws SQLException {

		DeviceTables.Table table = DeviceTables.of(intent.table());

		for (Map.Entry<String, String> reference : table.foreignKeys().entrySet()) {

			Object value = intent.row() == null ? null : intent.row().get(reference.getKey());

			if (value == null && table.primaryKey().contains(reference.getKey())) {
				// A key column that is also a foreign key, as both of attendance's are.
				value = intent.key().get(table.primaryKey().indexOf(reference.getKey()));
			}

			if (!(value instanceof Number parent)) {
				continue;
			}

			long key = parent.longValue();

			if (StructuredId.isStructured(key)
					|| exists(connection, DeviceTables.of(reference.getValue()),
							List.of(key))) {
				continue;
			}

			return reference.getKey() + " " + key + " predates the id scheme, so the "
					+ reference.getValue() + " row it points at cannot be pushed and this one "
					+ "cannot land. That data belongs in a backup import, not a sync.";
		}

		return null;
	}

	/**
	 * Whether this failure is worth the device trying again, or not.
	 * <p>
	 * The subtle case is {@code 42501}, and it took a test to find it. The policies on
	 * {@code attendance} reach the mark's lecture to decide anything at all, so a mark whose
	 * lecture has not arrived yet is refused by the <em>policy</em> and never gets as far as the
	 * foreign key — it comes back as "not yours to change" when the truth is "not yet". Calling
	 * every policy refusal final would therefore drop real attendance out of a queue whose next
	 * batch would have been accepted.
	 * <p>
	 * So a policy refusal is final only when everything the row points at is already here. If a
	 * parent is missing, or is one this actor cannot see, the intent is kept and tried again: a
	 * row stuck in a queue with a sentence against it is a bad outcome, and a row silently
	 * dropped is a worse one.
	 * <p>
	 * A delete needs no such care. Deleting a row that is not there succeeds, so a refused
	 * delete is a row that is there and is not the caller's, which will not change.
	 */
	private static boolean settled(@NonNull Connection connection, @NonNull Intent intent,
	                               @NonNull SQLException e) throws SQLException {

		String state = String.valueOf(e.getSQLState());

		if (INSUFFICIENT_PRIVILEGE.equals(state)) {
			return intent.op() == OutboxOp.DELETE || parentsPresent(connection, intent);
		}

		return SETTLED_STATES.contains(state);
	}

	/** Whether every row this intent points at is already here, as far as the actor can see. */
	private static boolean parentsPresent(@NonNull Connection connection,
	                                      @NonNull Intent intent) throws SQLException {

		DeviceTables.Table table = DeviceTables.of(intent.table());
		Map<String, Object> row = intent.row();

		for (Map.Entry<String, String> reference : table.foreignKeys().entrySet()) {

			Object value = row == null ? null : row.get(reference.getKey());

			if (value == null && table.primaryKey().contains(reference.getKey())) {
				// A key column that is also a foreign key, as attendance's both are.
				value = intent.key().get(table.primaryKey().indexOf(reference.getKey()));
			}

			if (!(value instanceof Number parent)) {
				// Absent or null: a nullable reference, like a lecture with no batch, points
				// at nothing and so cannot be waiting for anything.
				continue;
			}

			if (!exists(connection, DeviceTables.of(reference.getValue()),
					List.of(parent.longValue()))) {
				return false;
			}
		}

		return true;
	}

	/** Why this intent cannot be applied at all, or null. */
	@Nullable
	private static String unacceptable(@NonNull Connection connection, @NonNull Intent intent,
	                                   @NonNull Set<String> allowed,
	                                   @Nullable Integer deviceNode,
	                                   @Nullable Integer priorNode) throws SQLException {

		if (!allowed.contains(intent.table())) {
			return "this server does not accept " + intent.table() + " from a device";
		}

		DeviceTables.Table table;

		try {
			table = DeviceTables.of(intent.table());
		} catch (RuntimeException e) {
			return "unknown table " + intent.table();
		}

		if (intent.key().size() != table.primaryKey().size()) {
			return intent.table() + " is keyed on " + table.primaryKey().size()
					+ " columns, not " + intent.key().size();
		}

		if (intent.op() == OutboxOp.UPSERT && intent.row() == null) {
			return "an upsert has to carry the row";
		}

		if (table.surrogate() == null) {
			// Nothing of this table's own was issued by a device, so there is no node in its
			// key to check. See the class notes.
			return null;
		}

		long key = intent.key().get(table.primaryKey().indexOf(table.surrogate()));

		if (!StructuredId.isStructured(key)) {
			// A key from before the scheme existed means nothing outside the file it came from.
			// Those rows reach the server through the backup importer, which can renumber them;
			// a sync push cannot, because there is nothing here to tell it what the key means.
			return "key " + key + " predates the id scheme and cannot be synced; that row "
					+ "belongs in a backup import";
		}

		if (deviceNode == null) {
			return "this session is not on a registered device, so it may not write rows";
		}

		int node = StructuredId.nodeOf(key);
		boolean ours = node == deviceNode || (priorNode != null && node == priorNode);

		if (!ours && !exists(connection, table, intent.key())) {
			// Not one of this device's nodes, and no row here under the key. Creating one
			// would be this device claiming to be another; amending an existing row would
			// not, and is allowed above -- see the class notes. A row that exists but this
			// actor cannot read reaches here too, and refusing it is the same answer by a
			// different route: a row you cannot see is not one you may write.
			return "key " + key + " was issued by node " + node + ", and this device issues "
					+ "under " + deviceNode
					+ (priorNode == null ? "" : " (and used " + priorNode + " before it "
							+ "registered)")
					+ "; there is no such row here for it to amend";
		}

		return null;
	}

	/**
	 * Whether the row is already there, as far as this actor can see.
	 * <p>
	 * One statement, on the rare path: it is asked only when a key's node is not the pushing
	 * device's, which for a device pushing its own work never happens.
	 */
	private static boolean exists(@NonNull Connection connection,
	                              @NonNull DeviceTables.Table table, @NonNull List<Long> key)
			throws SQLException {

		StringBuilder sql = new StringBuilder("SELECT 1 FROM ").append(table.name())
				.append(" WHERE ");

		for (int at = 0; at < table.primaryKey().size(); at++) {
			sql.append(at == 0 ? "" : " AND ")
					.append(table.column(table.primaryKey().get(at)).postgres())
					.append(" = ?");
		}

		try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {

			for (int at = 0; at < key.size(); at++) {
				statement.setLong(at + 1, key.get(at));
			}

			try (ResultSet results = statement.executeQuery()) {
				return results.next();
			}
		}
	}

	// ── The statements ───────────────────────────────────────────────────

	/**
	 * @param stamped whether this table carries the provenance columns. The three membership
	 *                tables — batch_student_map, course_batch_map, course_instructor — are
	 *                nothing but their keys and have no created_at, updated_at or row_version
	 *                to write; every other table has all three. Asked of the catalogue rather
	 *                than listed here, so a table that gains them does not need this file
	 *                changed
	 */
	private static void upsert(@NonNull Connection connection, @NonNull Intent intent,
	                           boolean stamped) throws SQLException {

		DeviceTables.Table table = DeviceTables.of(intent.table());
		Map<String, Object> row = intent.row();

		List<DeviceValue.Field> fields = new ArrayList<>();

		for (DeviceTables.Column column : table.carriedColumns()) {
			fields.addAll(DeviceValue.convert(table, column, row.get(column.room())));
		}

		// The key as the device named it wins over whatever the row claims: the key is what the
		// intent is about, and a row whose body disagreed with its own key would otherwise be
		// written under the body's.
		for (int at = 0; at < table.primaryKey().size(); at++) {
			String postgres = table.column(table.primaryKey().get(at)).postgres();
			replace(fields, postgres, intent.key().get(at));
		}

		StringBuilder sql = new StringBuilder("INSERT INTO ").append(table.name()).append(" (");
		StringBuilder values = new StringBuilder();

		for (int at = 0; at < fields.size(); at++) {
			sql.append(at == 0 ? "" : ", ").append(fields.get(at).column());
			values.append(at == 0 ? "" : ", ").append(fields.get(at).placeholder());
		}

		// created_at, unlike the importer, which deliberately leaves it null: the importer is
		// loading history and "created now" would be a lie, while a push is a row arriving now.
		if (stamped) {
			sql.append(", created_at) VALUES (").append(values).append(", now()) ");
		} else {
			sql.append(") VALUES (").append(values).append(") ");
		}

		sql.append("ON CONFLICT (");

		for (int at = 0; at < table.primaryKey().size(); at++) {
			sql.append(at == 0 ? "" : ", ")
					.append(table.column(table.primaryKey().get(at)).postgres());
		}

		List<String> changeable = new ArrayList<>();

		for (DeviceValue.Field field : fields) {
			if (!isKeyColumn(table, field.column())) {
				changeable.add(field.column());
			}
		}

		if (changeable.isEmpty()) {
			// A table that is nothing but its key -- a membership row. There is no update to
			// make, and the row already being there is the whole of what the intent wanted.
			sql.append(") DO NOTHING");

		} else {
			sql.append(") DO UPDATE SET ");

			for (int at = 0; at < changeable.size(); at++) {
				sql.append(at == 0 ? "" : ", ").append(changeable.get(at))
						.append(" = EXCLUDED.").append(changeable.get(at));
			}

			if (stamped) {
				sql.append(", updated_at = now(), row_version = ").append(table.name())
						.append(".row_version + 1");
			}

			/*
			 * And only when something actually differs.
			 *
			 * Without this clause an upsert always writes, so updated_at and row_version move
			 * on every replay -- and a row that has changed is a row the audit trigger
			 * records. A device that sends a batch twice because it never saw the response
			 * would then put a second "changed" entry in the record against every row in it,
			 * describing a change that did not happen. The trigger already declines to record
			 * an UPDATE whose images match; this is what keeps that true through an upsert.
			 */
			sql.append(" WHERE ROW(");

			for (int at = 0; at < changeable.size(); at++) {
				sql.append(at == 0 ? "" : ", ").append(table.name()).append(".")
						.append(changeable.get(at));
			}

			sql.append(") IS DISTINCT FROM ROW(");

			for (int at = 0; at < changeable.size(); at++) {
				sql.append(at == 0 ? "" : ", ").append("EXCLUDED.").append(changeable.get(at));
			}

			sql.append(")");
		}

		try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {

			for (int at = 0; at < fields.size(); at++) {
				statement.setObject(at + 1, fields.get(at).value());
			}
			statement.executeUpdate();
		}
	}

	/**
	 * Removes a row, and says so when it could not.
	 *
	 * <h3>Why this is not simply a DELETE</h3>
	 * A DELETE that no policy's {@code USING} clause admits <strong>matches no rows</strong>. It
	 * does not raise, does not warn, and reports a row count of zero — the same answer as
	 * deleting a row that was never there. This project already wrote that finding down about
	 * UPDATE, and then the delete path relied on the leniency: it ran the statement, ignored
	 * the count, and told the device the deletion had been accepted.
	 * <p>
	 * So a professor removing a student from the roll on their phone got a successful sync, an
	 * emptied queue, and a server that still had the student. Silently, which is the worst
	 * shape a sync bug can have: nothing to see in the app, nothing in the log, and two copies
	 * of the roll that disagree until somebody reads both.
	 * <p>
	 * Zero rows is therefore two different answers, and which one it is depends on whether the
	 * row is still there:
	 * <ul>
	 * <li><b>Gone.</b> A replayed delete, or a row the server never had. Nothing to do, and
	 * accepted — a device that cannot tell whether its batch arrived has to be able to send it
	 * again.</li>
	 * <li><b>Still there.</b> The policies did not admit it. Refused, permanently: the
	 * authority will not change by sending it again, and a queue that retries it for ever is a
	 * queue that never empties.</li>
	 * </ul>
	 *
	 * @return null when the row is gone, or the sentence to report when it is still there
	 */
	@Nullable
	private static String delete(@NonNull Connection connection, @NonNull Intent intent)
			throws SQLException {

		DeviceTables.Table table = DeviceTables.of(intent.table());
		StringBuilder sql = new StringBuilder("DELETE FROM ").append(table.name())
				.append(" WHERE ");

		for (int at = 0; at < table.primaryKey().size(); at++) {
			sql.append(at == 0 ? "" : " AND ")
					.append(table.column(table.primaryKey().get(at)).postgres())
					.append(" = ?");
		}

		int removed;

		try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {

			for (int at = 0; at < intent.key().size(); at++) {
				statement.setLong(at + 1, intent.key().get(at));
			}

			removed = statement.executeUpdate();
		}

		if (removed > 0 || !exists(connection, table, intent.key())) {
			return null;
		}

		// exists() reads under the same actor, so a row they cannot see reads as gone. That is
		// the right answer here rather than a worry: a device holds the rows it was sent, and
		// a row it may no longer see is not one it can assert anything about.
		return refusedDeletion(intent.table());
	}

	/**
	 * What to tell somebody whose deletion the policies would not make.
	 * <p>
	 * Named per table, because the reason differs and the useful half of the sentence is what
	 * to do instead. The roster tables are the office's; the rest are refused because the row
	 * is not this actor's to touch.
	 */
	@NonNull
	private static String refusedDeletion(@NonNull String table) {

		return switch (table) {
			case "students", "student_year" -> "removing a student is the office's to do, so "
					+ "this deletion stays on this device. The student is still on the "
					+ "server, and will come back here the next time the roll is loaded.";
			case "professors" -> "the staff list is the office's, so removing a professor "
					+ "here cannot be sent. They are still on the server.";
			case "courses", "course_instructor", "course_batch_map" -> "a course is the "
					+ "department's to withdraw, and withdrawing it is marking it "
					+ "discontinued rather than deleting it. This deletion stays on this "
					+ "device.";
			case "academic_years" -> "a session is the college's, so removing one here cannot "
					+ "be sent.";
			default -> "the server would not remove that row: it is not this account's to "
					+ "delete. The deletion stays on this device, and the row is still on the "
					+ "server.";
		};
	}

	private static boolean isKeyColumn(@NonNull DeviceTables.Table table,
	                                   @NonNull String postgres) {

		for (String room : table.primaryKey()) {
			if (table.column(room).postgres().equals(postgres)) {
				return true;
			}
		}
		return false;
	}

	private static void replace(@NonNull List<DeviceValue.Field> fields,
	                            @NonNull String column, long value) {

		for (int at = 0; at < fields.size(); at++) {
			if (fields.get(at).column().equals(column)) {
				fields.set(at, new DeviceValue.Field(column, value, ""));
				return;
			}
		}
		fields.add(new DeviceValue.Field(column, value, ""));
	}

	/**
	 * Which of the synced tables carry created_at, updated_at and row_version.
	 * <p>
	 * One query rather than a list in this file. A list would be a second place that has to
	 * agree with V1 about which tables are nothing but their keys, and the catalogue already
	 * knows.
	 */
	@NonNull
	private static Set<String> tablesWithProvenance(@NonNull Connection connection)
			throws SQLException {

		Set<String> tables = new LinkedHashSet<>();

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT table_name FROM information_schema.columns "
						+ "WHERE table_schema = current_schema() "
						+ "  AND column_name  = 'created_at' "
						+ "  AND table_name   = ANY (synced_tables())");
		     ResultSet results = statement.executeQuery()) {

			while (results.next()) {
				tables.add(results.getString("table_name"));
			}
		}
		return tables;
	}

	/** The tables synced_tables() says a device may sync, read rather than restated here. */
	@NonNull
	private static Set<String> syncedTables(@NonNull Connection connection) throws SQLException {

		Set<String> tables = new LinkedHashSet<>();

		try (Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(
				     "SELECT unnest(synced_tables()) AS table_name")) {

			while (results.next()) {
				tables.add(results.getString("table_name"));
			}
		}
		return tables;
	}

	/**
	 * A database error as something the device can show a person.
	 * <p>
	 * A policy refusal is the interesting one and is named as such. The roster tables get a
	 * sentence of their own, because that refusal is the one a professor will actually meet —
	 * they imported a class list on their own phone, which is what the app has always let them
	 * do, and "not yours to change" would leave them with nothing to do about it. Everything
	 * else keeps the server's own message, because the alternative is a device displaying
	 * "something went wrong" about a row it cannot send.
	 */
	@NonNull
	private static String explain(@NonNull SQLException e, @NonNull String table) {

		if (INSUFFICIENT_PRIVILEGE.equals(e.getSQLState())) {

			if (ROSTER_TABLES.contains(table)) {
				return "the roll is loaded by the office, so this roster stays on this device. "
						+ "Attendance still syncs.";
			}

			return "the server refused this row: it is not yours to change";
		}

		String message = e.getMessage();

		if (message == null) {
			return "the server could not write this row";
		}

		int newline = message.indexOf('\n');
		return newline < 0 ? message : message.substring(0, newline);
	}
}
