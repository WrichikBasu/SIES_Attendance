package in.basulabs.siesattendance.server.importer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import in.basulabs.siesattendance.backend.PostgresEnumTypes;
import in.basulabs.siesattendance.backend.StoredTimes;
import in.basulabs.siesattendance.backend.StructuredId;
import in.basulabs.siesattendance.backend.sync.DeviceTables;
import in.basulabs.siesattendance.server.sync.DeviceValue;
import in.basulabs.siesattendance.server.sync.DeviceValue.Field;

/**
 * One run of the importer: reads every table out of a staged backup and folds it into
 * PostgreSQL.
 *
 * <h3>Why row identity is the whole problem</h3>
 * Primary keys are issued by the app rather than described by the world, and a key alone cannot
 * be trusted to say two rows are the same row. Keys from before schema v7 were counted out by
 * SQLite from one in each file independently, so student 14 from one phone and student 14 from
 * another are almost certainly different people. So every table is matched on a natural key —
 * something that identifies the row in the world — and {@link DeviceTables} is where those live,
 * shared with the app's own merge so the two cannot disagree.
 *
 * <h3>How a row is resolved, in order</h3>
 * <ol>
 *     <li><b>By key.</b> A structured key is unique across every device by construction, so a
 *     server row already carrying it is the same row. A legacy key is looked up in
 *     {@code id_remap}, which is what a previous import of this same device wrote down. This
 *     step is what the app's merge cannot do, and it is why a second import of a device whose
 *     roll numbers have since been corrected updates the right students instead of inserting
 *     them again.</li>
 *     <li><b>By natural key.</b> The row has not been seen from this device before, but the
 *     world may already know it — the same student entered by hand on two phones, or a course
 *     with the same code.</li>
 *     <li><b>Insert.</b> Keeping the device's key where that key still means something, and
 *     minting a server one where it does not.</li>
 * </ol>
 * As each parent row is resolved its device key is recorded against the server key it landed
 * on, and every child table's foreign keys are rewritten through those maps. That is what keeps
 * an inserted lecture pointing at the right course and an inserted attendance row at the right
 * student. Nothing is ever deleted.
 *
 * <h3>Where this deliberately differs from the app's merge</h3>
 * Three places, each because the server knows something a phone does not:
 * <ul>
 *     <li>Resolution by key comes first, as above. The app has no {@code id_remap} and so
 *     cannot.</li>
 *     <li>A lecture is identified by its <em>instant</em>, not by the stored text. The device
 *     compares {@code "epochSecond|nano|zone"} as a string, so the same lecture recorded on a
 *     phone set to UTC would look like a different lecture. The instant is what identifies it;
 *     a zone that disagrees is reported rather than silently kept or dropped.</li>
 *     <li>A natural key already owned by a different parent is reported and skipped, not
 *     repointed. If roll number 42 belongs to one student on the server and to another in the
 *     backup, repointing it would move attendance onto the wrong person, and no screen would
 *     ever say so.</li>
 * </ul>
 */
final class ImportSession {


	/** A server row the generic engine found, and the parents it currently points at. */
	private record Local(@Nullable Long surrogate, @NonNull Map<String, Long> foreignKeys) {
	}

	/** Raised for a row that cannot be placed; caught per row and reported. */
	private static final class SkipRow extends RuntimeException {

		private static final long serialVersionUID = 1L;

		SkipRow(@NonNull String reason) {
			super(reason);
		}
	}

	@NonNull
	private final Connection device;

	@NonNull
	private final Connection server;

	@NonNull
	private final ServerIds ids;

	private final int sourceNode;

	@NonNull
	private final ImportReport report;

	@NonNull
	private final Progress progress;

	/** Table name to device key to server key, built as each table is resolved. */
	private final Map<String, Map<Long, Long>> maps = new HashMap<>();

	ImportSession(@NonNull Connection device, @NonNull Connection server,
	              @NonNull ServerIds ids, int sourceNode, @NonNull ImportReport report,
	              @NonNull Progress progress) {
		this.device = device;
		this.server = server;
		this.ids = ids;
		this.sourceNode = sourceNode;
		this.report = report;
		this.progress = progress;
	}

	/** Every table, in dependency order. */
	void importAll() throws SQLException {

		long rows = 0;

		for (DeviceTables.Table table : DeviceTables.inDependencyOrder()) {
			rows += countDeviceRows(table);
		}

		progress.stage("Importing", rows);

		for (DeviceTables.Table table : DeviceTables.inDependencyOrder()) {

			report.seen(table.name());
			progress.rename("Importing " + table.name().replace('_', ' '));

			switch (table.name()) {
				case "students" -> importStudents(table);
				case "professors" -> importProfessors(table);
				default -> importGeneric(table);
			}
		}
	}

	// ── Students, matched by hand ────────────────────────────────────────

	/**
	 * Resolves every student in the backup to a server student, inserting the ones that are new.
	 * <p>
	 * Students are the one table whose natural key is not in its own row. A student record holds
	 * a name, pronouns and an optional email, none of which identify a person reliably: two
	 * students can share a name, and most rows have no email at all. What does identify them is
	 * the roll number, which lives in {@code student_year}. So each backup student is looked up
	 * through the roll numbers they hold in any session, and only then by email.
	 * <p>
	 * Getting this wrong in either direction is expensive: matching too eagerly merges two
	 * people into one, and matching too little leaves a duplicate whose attendance is split
	 * across two records. Roll number within a session is the identifier the college itself
	 * uses, which is why it comes first.
	 */
	private void importStudents(@NonNull DeviceTables.Table table) throws SQLException {

		Map<Long, Long> map = new HashMap<>();
		maps.put(table.name(), map);

		for (Map<String, Object> row : readDeviceRows(table)) {

			progress.advance();

			long deviceId = ((Number) row.get("studentID")).longValue();
			String email = (String) row.get("email");

			try {
				Map<String, List<Field>> fields = convertRow(table, row);

				Long serverId = resolveByKey(table, deviceId);

				if (serverId == null) {
					serverId = studentByRollNumber(deviceId);
				}

				if (serverId == null && email != null && !email.isEmpty()) {
					serverId = queryLong(
							"SELECT student_id FROM students WHERE email = ? LIMIT 1", email);
				}

				if (serverId != null) {

					// The email column is unique. If the backup wants to give this student an
					// address another server student already holds, the update would fail, so
					// the existing address is kept and the clash reported rather than guessed
					// at.
					if (email != null && !email.isEmpty()
							&& !emailIsFree("students", "student_id", email, serverId)) {

						fields.remove("email");
						report.skip("students: kept the existing email for student "
								+ serverId + "; the backup's address is already in use.");
					}

					update(table, serverId, fields);

				} else {

					if (email != null && !email.isEmpty()
							&& !emailIsFree("students", "student_id", email, null)) {

						fields.remove("email");
						report.skip("students: added a student without their email; "
								+ "the address is already in use.");
					}

					serverId = insertWithKey(table, deviceId, fields);
					report.inserted(table.name(), serverId == deviceId);
				}

				map.put(deviceId, serverId);

			} catch (SkipRow e) {
				report.skip(table.name() + ": " + e.getMessage());
			}
		}
	}

	/**
	 * Looks for a server student holding any of this backup student's roll numbers, in the
	 * matching session. The first hit wins: a student appearing in several sessions gives
	 * several chances to recognise them, and they all point at the same person.
	 */
	@Nullable
	private Long studentByRollNumber(long deviceStudentId) throws SQLException {

		List<Object[]> rolls = new ArrayList<>();

		try (PreparedStatement statement = device.prepareStatement(
				"SELECT rollNo, academicYear FROM student_year WHERE studentID = ? "
						+ "ORDER BY academicYear")) {

			statement.setLong(1, deviceStudentId);

			try (ResultSet results = statement.executeQuery()) {
				while (results.next()) {
					rolls.add(new Object[]{results.getString(1), results.getLong(2)});
				}
			}
		}

		for (Object[] roll : rolls) {

			Long found = queryLong("SELECT student_id FROM student_year "
					+ "WHERE roll_no = ? AND academic_year = ? LIMIT 1", roll[0], roll[1]);

			if (found != null) {
				return found;
			}
		}
		return null;
	}

	// ── Professors, matched by hand ──────────────────────────────────────

	/**
	 * Resolves every professor in the backup to a server professor.
	 * <p>
	 * Email is tried first, since it is uniquely indexed and is what tells apart two professors
	 * who share a name and a department. On its own it is not enough: a professor whose address
	 * had been corrected since the backup was taken would match nothing, and the import would
	 * insert them a second time and leave the stale row in the list with the old designation on
	 * it. Name and department are therefore tried next, under the guards in
	 * {@link #professorByNameAndDepartment}.
	 */
	private void importProfessors(@NonNull DeviceTables.Table table) throws SQLException {

		Map<Long, Long> map = new HashMap<>();
		maps.put(table.name(), map);

		// Server professors already spoken for by an earlier backup row. Only the name and
		// department fallback consults it: a match on email cannot collide, since the column
		// is unique on both sides.
		Set<Long> claimed = new HashSet<>();

		for (Map<String, Object> row : readDeviceRows(table)) {

			progress.advance();

			long deviceId = ((Number) row.get("profID")).longValue();
			String email = (String) row.get("email");
			String name = (String) row.get("name");
			String department = (String) row.get("department");

			try {
				Map<String, List<Field>> fields = convertRow(table, row);

				Long serverId = resolveByKey(table, deviceId);

				if (serverId == null && email != null && !email.isEmpty()) {
					serverId = queryLong(
							"SELECT prof_id FROM professors WHERE email = ? LIMIT 1", email);
				}

				if (serverId == null) {
					serverId = professorByNameAndDepartment(deviceId, name, department,
							claimed);
				}

				if (serverId != null) {

					if (email != null && !email.isEmpty()
							&& !emailIsFree("professors", "prof_id", email, serverId)) {

						fields.remove("email");
						report.skip("professors: kept the existing email for "
								+ (name == null ? "a professor" : name)
								+ "; the backup's address is already in use.");
					}

					update(table, serverId, fields);

				} else {
					serverId = insertWithKey(table, deviceId, fields);
					report.inserted(table.name(), serverId == deviceId);
				}

				map.put(deviceId, serverId);
				claimed.add(serverId);

			} catch (SkipRow e) {
				report.skip(table.name() + ": " + e.getMessage());
			}
		}
	}

	/**
	 * The server professor this backup row describes by name and department, or null.
	 * <p>
	 * Recognising the wrong professor is worse than failing to recognise the right one: a
	 * duplicate is visible in the list and can be corrected, whereas fusing two people merges
	 * one's courses into the other's name and there is nothing on screen to say it happened.
	 * <p>
	 * So the fallback is refused unless exactly one server professor carries that name and
	 * department, that professor has not already been claimed by an earlier backup row, and
	 * their current address is not one the backup gives to somebody else. The last of those is
	 * what separates the two readings of a name that appears twice: an address the backup knows
	 * as another professor's means this server row is that other person, not this one.
	 */
	@Nullable
	private Long professorByNameAndDepartment(long deviceId, @Nullable String name,
	                                          @Nullable String department,
	                                          @NonNull Set<Long> claimed) throws SQLException {

		if (name == null || department == null) {
			return null;
		}

		String departmentCast = "?::" + PostgresEnumTypes.of(
				in.basulabs.siesattendance.backend.Department.class);

		Long candidates = queryLong("SELECT COUNT(*) FROM professors "
				+ "WHERE name = ? AND department = " + departmentCast, name, department);

		if (candidates == null || candidates != 1L) {
			return null;
		}

		Long serverId = queryLong("SELECT prof_id FROM professors "
						+ "WHERE name = ? AND department = " + departmentCast + " LIMIT 1",
				name, department);

		if (serverId == null || claimed.contains(serverId)) {
			return null;
		}

		String serverEmail = queryString(
				"SELECT email FROM professors WHERE prof_id = ?", serverId);

		if (serverEmail != null && !serverEmail.isEmpty()) {

			Long owner = deviceLong("SELECT profID FROM professors WHERE email = ? LIMIT 1",
					serverEmail);

			if (owner != null && owner != deviceId) {
				return null;
			}
		}
		return serverId;
	}

	// ── The generic engine ───────────────────────────────────────────────

	/**
	 * Imports one table by its natural key, rewriting its foreign keys through the maps the
	 * tables before it built.
	 */
	private void importGeneric(@NonNull DeviceTables.Table table) throws SQLException {

		Map<Long, Long> map = table.surrogate() == null ? null : new HashMap<>();

		if (map != null) {
			maps.put(table.name(), map);
		}

		for (Map<String, Object> row : readDeviceRows(table)) {

			progress.advance();

			Long deviceId = table.surrogate() == null
					? null
					: ((Number) row.get(table.surrogate())).longValue();

			try {
				Map<String, List<Field>> fields = convertRow(table, row);

				Long serverId = deviceId == null ? null : resolveByKey(table, deviceId);

				if (serverId != null) {
					// The device's own key already stands for a server row, so that row is
					// this row whatever its natural key now says.
					updateBySurrogate(table, serverId, fields);

				} else {

					// The primary key, where it says more than the natural key does. A
					// student's enrolment row is identified by the student and the session;
					// matching it only on the roll number would try to insert a second row
					// for the same student the first time somebody corrects one.
					List<String> keyColumns = table.primaryKeyDiffersFromNaturalKey()
							? table.primaryKey()
							: table.naturalKey();

					Local local = findBy(table, fields, keyColumns);

					if (local == null && table.primaryKeyDiffersFromNaturalKey()) {
						keyColumns = table.naturalKey();
						local = findBy(table, fields, keyColumns);
					}

					if (local != null) {
						refuseIfAnotherParentOwnsIt(table, fields, local);
						serverId = local.surrogate();
						updateBy(table, fields, keyColumns);

					} else if (deviceId == null) {
						insertWithoutKey(table, fields);
						report.inserted(table.name(), true);

					} else {
						serverId = insertWithKey(table, deviceId, fields);
						report.inserted(table.name(), serverId.equals(deviceId));
					}
				}

				if (map != null) {
					refuseIfTwoDeviceRowsCollapseOntoOne(table, map, deviceId, serverId,
							fields);
					map.put(deviceId, serverId);
				}

			} catch (SkipRow e) {
				report.skip(table.name() + ": " + e.getMessage());
			}
		}
	}

	/**
	 * Refuses a row whose natural key is already held by a different parent.
	 * <p>
	 * Roll number 42 in one session identifies one student. If the server has it against one
	 * student and the backup against another, the two databases disagree about who 42 is, and
	 * the import cannot settle it: updating would move the row and every piece of attendance
	 * that follows the student key onto the wrong person. So it is reported with both keys and
	 * left alone.
	 */
	private void refuseIfAnotherParentOwnsIt(@NonNull DeviceTables.Table table,
	                                         @NonNull Map<String, List<Field>> fields,
	                                         @NonNull Local local) {

		for (Map.Entry<String, String> fk : table.foreignKeys().entrySet()) {

			DeviceTables.Column column = table.column(fk.getKey());
			Long existing = local.foreignKeys().get(column.postgres());

			if (existing == null) {
				continue;
			}

			List<Field> incoming = fields.get(fk.getKey());

			if (incoming == null || incoming.isEmpty()) {
				continue;
			}

			Object value = incoming.get(0).value();

			if (value instanceof Number number && existing != number.longValue()) {
				throw new SkipRow("natural key " + naturalKeyDescription(table, fields)
						+ " is held by " + fk.getValue() + " " + existing
						+ " on the server but by " + number.longValue()
						+ " in the backup, so the two disagree about whose row it is.");
			}
		}
	}

	/**
	 * Reports two rows from the same backup landing on one server row.
	 * <p>
	 * It happens where a device permits something the server's natural key does not
	 * distinguish. Two batches named "Batch A" in one session is the real case: Room's index on
	 * {@code (batchID, academicYear)} guarantees nothing, because {@code batchID} is already
	 * the primary key, so a device will hold both — and both resolve to the same
	 * {@code (batch_name, academic_year)} here, so the second quietly updates the first and
	 * their memberships and lectures end up pooled together.
	 * <p>
	 * It is not refused, because there is nothing to refuse: the merge on the phone does
	 * exactly the same thing, and the import would be rejecting data the app itself produced.
	 * It is said out loud instead, which the phone never does, so the two can be told apart by
	 * hand before anybody relies on the numbers.
	 */
	private void refuseIfTwoDeviceRowsCollapseOntoOne(@NonNull DeviceTables.Table table,
	                                                  @NonNull Map<Long, Long> map,
	                                                  @Nullable Long deviceId,
	                                                  @Nullable Long serverId,
	                                                  @NonNull Map<String, List<Field>> fields) {

		if (deviceId == null || serverId == null) {
			return;
		}

		for (Map.Entry<Long, Long> seen : map.entrySet()) {

			if (serverId.equals(seen.getValue()) && !deviceId.equals(seen.getKey())) {

				report.skip(table.name() + ": this backup holds two rows, " + seen.getKey()
						+ " and " + deviceId + ", that are the same row here — both match "
						+ naturalKeyDescription(table, fields) + ". They have been pooled "
						+ "into one; tell them apart by hand if that is wrong.");
				return;
			}
		}
	}

	// ── Resolving a key ──────────────────────────────────────────────────

	/**
	 * The server row this device key already stands for, or null if it stands for none yet.
	 * <p>
	 * A structured key is globally unique by construction, so a server row carrying it is the
	 * same row and no bookkeeping is needed. A legacy key means nothing outside the file it came
	 * from, so the answer is whatever a previous import of this device wrote into
	 * {@code id_remap} — and nothing at all if there was no previous import.
	 */
	@Nullable
	private Long resolveByKey(@NonNull DeviceTables.Table table, long deviceId)
			throws SQLException {

		String column = table.column(table.surrogate()).postgres();

		if (StructuredId.isStructured(deviceId)) {

			Long found = queryLong("SELECT " + column + " FROM " + table.name()
					+ " WHERE " + column + " = ? LIMIT 1", deviceId);

			if (found != null) {
				return found;
			}
			// It may still have been remapped: a structured key can collide if this same
			// device's backup was imported before the server had its own node, or if two
			// installs drew the same provisional node. The remap is checked either way.
		}

		return queryLong("SELECT server_id FROM id_remap "
						+ "WHERE table_name = ? AND source_node = ? AND source_id = ?",
				table.name(), nodeFor(deviceId), deviceId);
	}

	/**
	 * Which device's numbering space a key belongs to.
	 * <p>
	 * A structured key says so itself, in its node field. A legacy key does not — that is the
	 * whole problem with legacy keys — so it belongs to whichever device the operator said this
	 * backup came from.
	 */
	private int nodeFor(long deviceId) {
		return StructuredId.isStructured(deviceId)
				? StructuredId.nodeOf(deviceId)
				: sourceNode;
	}

	/**
	 * The key a row being inserted should be written under: the one it already carries, wherever
	 * that key still means what it meant on the device, and a freshly minted server one
	 * otherwise.
	 * <p>
	 * Keeping it is the better answer whenever it is available, because a row that arrives twice
	 * under two different keys is a row that has to be recognised by its natural key all over
	 * again, every time, for ever. Two conditions have to hold, and both are checked rather than
	 * assumed: the key has to be one the scheme issued, and no server row may already hold it.
	 */
	private long keyForInsert(@NonNull DeviceTables.Table table, long deviceId)
			throws SQLException {

		String column = table.column(table.surrogate()).postgres();

		if (StructuredId.isStructured(deviceId)) {

			boolean taken = queryLong("SELECT " + column + " FROM " + table.name()
					+ " WHERE " + column + " = ? LIMIT 1", deviceId) != null;

			if (!taken) {
				return deviceId;
			}
		}

		long minted = ids.next();

		// The device's key could not be carried, so the mapping has to be written down or a
		// second backup from this phone would insert the same row again.
		execute("INSERT INTO id_remap (table_name, source_node, source_id, server_id) "
						+ "VALUES (?, ?, ?, ?)",
				table.name(), nodeFor(deviceId), deviceId, minted);

		return minted;
	}

	// ── Reading the device ───────────────────────────────────────────────

	private long countDeviceRows(@NonNull DeviceTables.Table table) throws SQLException {

		try (Statement statement = device.createStatement();
		     ResultSet results = statement.executeQuery(
				     "SELECT COUNT(*) FROM " + table.name())) {

			return results.next() ? results.getLong(1) : 0;
		}
	}

	/**
	 * Every row of one table, in a deterministic order so that two runs over the same backup
	 * mint keys in the same sequence and produce the same report.
	 */
	@NonNull
	private List<Map<String, Object>> readDeviceRows(@NonNull DeviceTables.Table table)
			throws SQLException {

		List<String> order = table.surrogate() != null
				? List.of(table.surrogate())
				: table.naturalKey();

		StringBuilder sql = new StringBuilder("SELECT * FROM ").append(table.name());

		if (!order.isEmpty()) {
			sql.append(" ORDER BY ");
			for (int i = 0; i < order.size(); i++) {
				sql.append(i == 0 ? "" : ", ").append('`').append(order.get(i)).append('`');
			}
		}

		List<Map<String, Object>> rows = new ArrayList<>();

		try (Statement statement = device.createStatement();
		     ResultSet results = statement.executeQuery(sql.toString())) {

			while (results.next()) {

				Map<String, Object> row = new LinkedHashMap<>();

				for (DeviceTables.Column column : table.columns()) {
					row.put(column.room(), results.getObject(column.room()));
				}
				rows.add(row);
			}
		}
		return rows;
	}

	// ── Converting a row ─────────────────────────────────────────────────

	/**
	 * Turns one device row into the PostgreSQL fields it becomes, keyed by the Room column name
	 * so the natural key and the foreign keys can still be found by the names
	 * {@link DeviceTables} uses.
	 */
	@NonNull
	private Map<String, List<Field>> convertRow(@NonNull DeviceTables.Table table,
	                                            @NonNull Map<String, Object> row) {

		Map<String, List<Field>> fields = new LinkedHashMap<>();

		for (DeviceTables.Column column : table.columns()) {

			if (!column.isCarried()) {
				continue;
			}

			String parent = table.foreignKeys().get(column.room());

			if (parent != null) {
				fields.put(column.room(), List.of(rewriteForeignKey(column, parent,
						row.get(column.room()))));
				continue;
			}

			fields.put(column.room(), convert(table, column, row.get(column.room())));
		}
		return fields;
	}

	/**
	 * A foreign key, pointed at the server row its parent landed on.
	 * <p>
	 * A parent that is not in the backup is the one case that cannot be carried on with:
	 * inserting the child would either break the constraint or, worse, attach it to whichever
	 * row happens to hold that key on the server.
	 */
	@NonNull
	private Field rewriteForeignKey(@NonNull DeviceTables.Column column, @NonNull String parent,
	                                @Nullable Object raw) {

		if (raw == null) {
			return new Field(column.postgres(), null, "");
		}

		long deviceId = ((Number) raw).longValue();
		Map<Long, Long> map = maps.get(parent);
		Long mapped = map == null ? null : map.get(deviceId);

		if (mapped == null) {
			throw new SkipRow("a row referred to " + parent + " " + deviceId
					+ ", which is not in the backup.");
		}
		return new Field(column.postgres(), mapped, "");
	}

	/**
	 * One ordinary column, converted from the way SQLite stored it to the way PostgreSQL will.
	 * <p>
	 * The conversion itself is {@link DeviceValue}'s, because the sync endpoint reads the same
	 * values out of a JSON body and the two must agree about what the device meant. What is left
	 * here is the translation of its refusal into this class's own: a value nothing wrote is a
	 * row this import skips, with the reason reported against it.
	 */
	@NonNull
	private List<Field> convert(@NonNull DeviceTables.Table table,
	                            @NonNull DeviceTables.Column column, @Nullable Object raw) {

		try {
			return DeviceValue.convert(table, column, raw);
		} catch (DeviceValue.Unconvertible e) {
			throw new SkipRow(e.getMessage());
		}
	}

	// ── Writing ──────────────────────────────────────────────────────────

	/** Inserts a row whose key the server decides, returning the key it was written under. */
	@NonNull
	private Long insertWithKey(@NonNull DeviceTables.Table table, long deviceId,
	                           @NonNull Map<String, List<Field>> fields) throws SQLException {

		long key = keyForInsert(table, deviceId);

		Map<String, List<Field>> withKey = new LinkedHashMap<>(fields);
		withKey.put(table.surrogate(),
				List.of(new Field(table.column(table.surrogate()).postgres(), key, "")));

		insert(table, withKey);
		return key;
	}

	/** Inserts a row whose key is its natural key. */
	private void insertWithoutKey(@NonNull DeviceTables.Table table,
	                              @NonNull Map<String, List<Field>> fields) throws SQLException {
		insert(table, fields);
	}

	private void insert(@NonNull DeviceTables.Table table,
	                    @NonNull Map<String, List<Field>> fields) throws SQLException {

		List<Field> flat = flatten(fields);

		StringBuilder sql = new StringBuilder("INSERT INTO ").append(table.name()).append(" (");
		StringBuilder values = new StringBuilder(") VALUES (");

		for (int i = 0; i < flat.size(); i++) {
			sql.append(i == 0 ? "" : ", ").append(flat.get(i).column());
			values.append(i == 0 ? "" : ", ").append(flat.get(i).placeholder());
		}

		run(table, fields, sql.append(values).append(')').toString(), flat);
	}

	/** Updates the row carrying this key, leaving the key itself alone. */
	private void updateBySurrogate(@NonNull DeviceTables.Table table, @NonNull Long serverId,
	                               @NonNull Map<String, List<Field>> fields)
			throws SQLException {
		update(table, serverId, fields);
	}

	private void update(@NonNull DeviceTables.Table table, @NonNull Long serverId,
	                    @NonNull Map<String, List<Field>> fields) throws SQLException {

		String column = table.column(table.surrogate()).postgres();
		List<Field> assignments = flattenExcept(fields, table.surrogate());

		if (assignments.isEmpty()) {
			report.unchanged(table.name());
			return;
		}

		StringBuilder sql = new StringBuilder("UPDATE ").append(table.name()).append(" SET ");

		for (int i = 0; i < assignments.size(); i++) {
			sql.append(i == 0 ? "" : ", ").append(assignments.get(i).column())
					.append(" = ").append(assignments.get(i).placeholder());
		}
		sql.append(" WHERE ").append(column).append(" = ?");

		List<Field> bound = new ArrayList<>(assignments);
		bound.add(new Field(column, serverId, ""));

		appendUnchangedGuard(sql, bound, assignments);

		if (run(table, fields, sql.toString(), bound) == 0) {
			report.unchanged(table.name());
		} else {
			report.updated(table.name());
		}
	}

	/** Updates the row these columns pick out, for a table with no key of its own. */
	private void updateBy(@NonNull DeviceTables.Table table,
	                      @NonNull Map<String, List<Field>> fields,
	                      @NonNull List<String> keyColumns) throws SQLException {

		List<Field> assignments = flattenExcept(fields,
				table.surrogate() == null ? "" : table.surrogate());

		if (assignments.isEmpty()) {
			report.unchanged(table.name());
			return;
		}

		StringBuilder sql = new StringBuilder("UPDATE ").append(table.name()).append(" SET ");

		for (int i = 0; i < assignments.size(); i++) {
			sql.append(i == 0 ? "" : ", ").append(assignments.get(i).column())
					.append(" = ").append(assignments.get(i).placeholder());
		}

		List<Field> key = keyFields(table, fields, keyColumns);
		sql.append(" WHERE ").append(keyPredicate(key));

		List<Field> bound = new ArrayList<>(assignments);
		bound.addAll(key);

		appendUnchangedGuard(sql, bound, assignments);

		if (run(table, fields, sql.toString(), bound) == 0) {
			report.unchanged(table.name());
		} else {
			report.updated(table.name());
		}
	}

	/**
	 * Narrows an UPDATE so it does nothing when the row is already identical.
	 * <p>
	 * Without this an import of an unchanged backup reports having updated every row, which is
	 * indistinguishable from having rewritten the lot — and "importing the same backup twice
	 * changes nothing" is the property this phase is meant to deliver, so the report has to be
	 * able to say it. The affected-row count then answers the question directly instead of the
	 * report inferring it.
	 * <p>
	 * A row comparison rather than a column-by-column one, because {@code IS DISTINCT FROM}
	 * over a row constructor gets nulls right and {@code <>} does not: a column going from
	 * null to null is not a change, and {@code null <> null} is null rather than false.
	 */
	private void appendUnchangedGuard(@NonNull StringBuilder sql, @NonNull List<Field> bound,
	                                  @NonNull List<Field> assignments) {

		StringBuilder columns = new StringBuilder();
		StringBuilder values = new StringBuilder();

		for (int i = 0; i < assignments.size(); i++) {
			columns.append(i == 0 ? "" : ", ").append(assignments.get(i).column());
			values.append(i == 0 ? "" : ", ").append(assignments.get(i).placeholder());
		}

		sql.append(" AND ROW(").append(columns).append(") IS DISTINCT FROM ROW(")
				.append(values).append(')');

		bound.addAll(assignments);
	}

	/**
	 * Runs a write, turning a constraint violation into something that names the row.
	 * <p>
	 * V2 states rules the device schema could not, and the ones it will reject are real defects
	 * in historical data rather than faults in the translation. The whole import is one
	 * transaction, so a rejection stops everything — which is the intended behaviour, and the
	 * message has to be good enough to act on a week later.
	 */
	private int run(@NonNull DeviceTables.Table table,
	                @NonNull Map<String, List<Field>> fields, @NonNull String sql,
	                @NonNull List<Field> bound) throws SQLException {

		try (PreparedStatement statement = server.prepareStatement(sql)) {

			for (int i = 0; i < bound.size(); i++) {
				statement.setObject(i + 1, bound.get(i).value());
			}
			return statement.executeUpdate();

		} catch (SQLException e) {
			throw new SQLException(table.name() + ": the row with natural key "
					+ naturalKeyDescription(table, fields) + " was refused by the database: "
					+ e.getMessage(), e.getSQLState(), e);
		}
	}

	// ── Finding a row by its natural key ─────────────────────────────────

	/**
	 * The server row these columns pick out, with the parents it points at, or null.
	 */
	@Nullable
	private Local findBy(@NonNull DeviceTables.Table table,
	                     @NonNull Map<String, List<Field>> fields,
	                     @NonNull List<String> keyColumns) throws SQLException {

		List<Field> key = keyFields(table, fields, keyColumns);

		if (key.isEmpty()) {
			return null;
		}

		List<String> selected = new ArrayList<>();
		selected.add(table.surrogate() == null
				? "NULL::bigint"
				: table.column(table.surrogate()).postgres());

		List<String> fkColumns = new ArrayList<>();

		for (String roomColumn : table.foreignKeys().keySet()) {
			String pgColumn = table.column(roomColumn).postgres();
			fkColumns.add(pgColumn);
			selected.add(pgColumn);
		}

		String sql = "SELECT " + String.join(", ", selected) + " FROM " + table.name()
				+ " WHERE " + keyPredicate(key) + " LIMIT 1";

		try (PreparedStatement statement = server.prepareStatement(sql)) {

			for (int i = 0; i < key.size(); i++) {
				statement.setObject(i + 1, key.get(i).value());
			}

			try (ResultSet results = statement.executeQuery()) {

				if (!results.next()) {
					return null;
				}

				long surrogate = results.getLong(1);
				Long key2 = results.wasNull() ? null : surrogate;

				Map<String, Long> parents = new LinkedHashMap<>();

				for (int i = 0; i < fkColumns.size(); i++) {
					long value = results.getLong(i + 2);
					parents.put(fkColumns.get(i), results.wasNull() ? null : value);
				}
				return new Local(key2, parents);
			}
		}
	}

	/**
	 * The fields that make up a natural key.
	 * <p>
	 * A timestamp contributes only its instant, not the zone beside it: the instant is what
	 * identifies the lecture, and the zone is a note about how it was recorded. See the class
	 * notes on where this differs from the device.
	 */
	@NonNull
	private List<Field> keyFields(@NonNull DeviceTables.Table table,
	                              @NonNull Map<String, List<Field>> fields,
	                              @NonNull List<String> keyColumns) {

		List<Field> key = new ArrayList<>();

		for (String roomColumn : keyColumns) {

			List<Field> candidates = fields.get(roomColumn);

			if (candidates == null || candidates.isEmpty()) {
				throw new IllegalStateException(table.name() + " has no value for its key "
						+ "column " + roomColumn);
			}
			key.add(candidates.get(0));
		}
		return key;
	}

	/**
	 * The WHERE clause matching a row on its natural key.
	 * <p>
	 * {@code IS NOT DISTINCT FROM} rather than {@code =}, because a natural key can contain
	 * nulls — a lecture with no batch being the case that arises — and {@code = NULL} is never
	 * true. Using {@code =} would make every whole-class lecture look new on every import and
	 * insert a duplicate each time. It is the same reason the device's merge uses {@code IS}.
	 */
	@NonNull
	private String keyPredicate(@NonNull List<Field> key) {

		StringBuilder where = new StringBuilder();

		for (int i = 0; i < key.size(); i++) {
			where.append(i == 0 ? "" : " AND ").append(key.get(i).column())
					.append(" IS NOT DISTINCT FROM ").append(key.get(i).placeholder());
		}
		return where.toString();
	}

	/** The natural key as text, for a message somebody has to act on. */
	@NonNull
	private String naturalKeyDescription(@NonNull DeviceTables.Table table,
	                                     @NonNull Map<String, List<Field>> fields) {

		List<String> parts = new ArrayList<>();

		for (String roomColumn : table.naturalKey()) {

			List<Field> candidates = fields.get(roomColumn);
			Object value = candidates == null || candidates.isEmpty()
					? null
					: candidates.get(0).value();

			parts.add(roomColumn + "=" + value);
		}
		return parts.isEmpty() ? "(matched by hand)" : "(" + String.join(", ", parts) + ")";
	}

	// ── Small helpers ────────────────────────────────────────────────────

	@NonNull
	private static List<Field> flatten(@NonNull Map<String, List<Field>> fields) {

		List<Field> flat = new ArrayList<>();

		for (List<Field> group : fields.values()) {
			flat.addAll(group);
		}
		return flat;
	}

	@NonNull
	private static List<Field> flattenExcept(@NonNull Map<String, List<Field>> fields,
	                                         @NonNull String roomColumn) {

		List<Field> flat = new ArrayList<>();

		for (Map.Entry<String, List<Field>> entry : fields.entrySet()) {
			if (!entry.getKey().equals(roomColumn)) {
				flat.addAll(entry.getValue());
			}
		}
		return flat;
	}

	/** Whether an address can be given to a row without colliding with its unique index. */
	private boolean emailIsFree(@NonNull String table, @NonNull String keyColumn,
	                            @NonNull String email, @Nullable Long exceptId)
			throws SQLException {

		Long holder = queryLong("SELECT " + keyColumn + " FROM " + table
				+ " WHERE email = ? LIMIT 1", email);

		return holder == null || holder.equals(exceptId);
	}

	private void execute(@NonNull String sql, @NonNull Object... args) throws SQLException {

		try (PreparedStatement statement = server.prepareStatement(sql)) {

			for (int i = 0; i < args.length; i++) {
				statement.setObject(i + 1, args[i]);
			}
			statement.executeUpdate();
		}
	}

	@Nullable
	private Long queryLong(@NonNull String sql, @NonNull Object... args) throws SQLException {

		try (PreparedStatement statement = server.prepareStatement(sql)) {

			for (int i = 0; i < args.length; i++) {
				statement.setObject(i + 1, args[i]);
			}

			try (ResultSet results = statement.executeQuery()) {

				if (!results.next()) {
					return null;
				}
				long value = results.getLong(1);
				return results.wasNull() ? null : value;
			}
		}
	}

	@Nullable
	private String queryString(@NonNull String sql, @NonNull Object... args)
			throws SQLException {

		try (PreparedStatement statement = server.prepareStatement(sql)) {

			for (int i = 0; i < args.length; i++) {
				statement.setObject(i + 1, args[i]);
			}

			try (ResultSet results = statement.executeQuery()) {
				return results.next() ? results.getString(1) : null;
			}
		}
	}

	/** The same, against the backup rather than the server. */
	@Nullable
	private Long deviceLong(@NonNull String sql, @NonNull Object... args) throws SQLException {

		try (PreparedStatement statement = device.prepareStatement(sql)) {

			for (int i = 0; i < args.length; i++) {
				statement.setObject(i + 1, args[i]);
			}

			try (ResultSet results = statement.executeQuery()) {

				if (!results.next()) {
					return null;
				}
				long value = results.getLong(1);
				return results.wasNull() ? null : value;
			}
		}
	}
}
