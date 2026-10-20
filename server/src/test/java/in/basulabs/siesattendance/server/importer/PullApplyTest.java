package in.basulabs.siesattendance.server.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import in.basulabs.siesattendance.backend.StoredTimes;
import in.basulabs.siesattendance.backend.StructuredId;
import in.basulabs.siesattendance.backend.sync.DeviceDerived;
import in.basulabs.siesattendance.backend.sync.DeviceSql;
import in.basulabs.siesattendance.backend.sync.DeviceTables;

/**
 * Every table the server can send, written into a real device database.
 * <p>
 * This is the test that was missing, and a handset found the gap before it did: a pulled
 * {@code academic_years} row could not be written at all, because {@code startEpoch} is
 * {@code NOT NULL} on the device and the server deliberately does not keep it. The symptom was
 * the worst shape a sync bug can have — the page could not be applied, so the watermark never
 * moved, so every later page was stuck behind it, for ever, with the device reporting only
 * "sync failed".
 *
 * <h3>Why it is generic</h3>
 * {@code SyncTest} proves the server sends the right rows and {@code DeviceSqlTest} proves the
 * statements are right for the two tables it names. Neither asks the question this one does:
 * <em>for every table in the feed</em>, does a row shaped the way the server shapes it actually
 * land in the schema the device has? That question has eleven answers and V11 changed nine of
 * them from "never asked" to "asked on every pull".
 *
 * <h3>What it cannot see</h3>
 * Room, a cursor and WorkManager, as ever. The statements and the schema are real; the thing
 * running them here is sqlite-jdbc rather than Android.
 */
class PullApplyTest {

	private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

	/** Keys that satisfy each other, so the tables can be filled in dependency order. */
	private static final long PROF = 1001;
	private static final long STUDENT = 2001;
	private static final long COURSE = 3001;
	private static final long LECTURE = 4001;
	private static final long BATCH = 5001;
	private static final int SESSION = 2025;

	private static final LocalDate STARTS = LocalDate.of(2025, 6, 1);
	private static final LocalDate ENDS = LocalDate.of(2026, 5, 31);

	@TempDir
	Path work;

	private DeviceBackup device;

	private Connection connection;

	@BeforeEach
	void buildDeviceDatabase() throws Exception {

		device = DeviceBackup.emptyAtVersion6(work.resolve("devices"));
		device.migrateToCurrent(ZONE);
		connection = device.connection();

		// DeviceBackup turns foreign keys off, because building a backup writes rows in
		// whatever order the fixture finds convenient. Applying a pulled page is not that:
		// Room enforces them on a handset, and the one defect this file exists to catch was
		// invisible with them off. See aRowTheDeviceAlreadyHasKeepsTheRowsThatPointAtIt.
		execute("PRAGMA foreign_keys = ON");
	}

	@AfterEach
	void close() throws Exception {
		if (device != null) {
			device.close();
		}
	}

	@Test
	void everyTableInTheFeedTakesAServerShapedRow() throws Exception {

		List<String> landed = new ArrayList<>();

		// Dependency order, which is the order a snapshot arrives in, so each row's parents
		// are already there by the time it is written.
		for (DeviceTables.Table table : DeviceTables.inDependencyOrder()) {

			Map<String, Object> incoming = serverShapedRow(table);

			// Exactly what OutboxDrain.apply does, including the fill: the columns the device
			// needs and the server does not carry.
			applyRow(table, DeviceDerived.fill(table, incoming, ZONE));

			assertEquals(1, count("SELECT count(*) FROM `" + table.name() + "`"),
					table.name() + ": a row the server sent did not land");

			landed.add(table.name());
		}

		assertEquals(11, landed.size(), landed.toString());
	}

	@Test
	void aSessionsEpochBoundsAreDerivedFromTheDatesThatTravelled() throws Exception {

		DeviceTables.Table sessions = DeviceTables.of("academic_years");

		applyRow(sessions, DeviceDerived.fill(sessions, serverShapedRow(sessions), ZONE));

		Map<String, Long> row = new LinkedHashMap<>();

		try (Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(
				     "SELECT startDate, endDate, startEpoch, endEpoch FROM academic_years")) {

			assertTrue(results.next());
			row.put("startDate", results.getLong("startDate"));
			row.put("endDate", results.getLong("endDate"));
			row.put("startEpoch", results.getLong("startEpoch"));
			row.put("endEpoch", results.getLong("endEpoch"));
		}

		// The same arithmetic as EntityAcademicYear.setDates, which is what the app does when
		// somebody edits a session by hand. The two have to agree to the second, or a lecture
		// on the first or last day of a session falls outside it in one place and inside it in
		// the other.
		assertEquals(STARTS.atStartOfDay(ZONE).toEpochSecond(), (long) row.get("startEpoch"));
		assertEquals(ENDS.plusDays(1).atStartOfDay(ZONE).toEpochSecond() - 1,
				(long) row.get("endEpoch"));

		assertEquals(STARTS.toEpochDay(), (long) row.get("startDate"));
		assertEquals(ENDS.toEpochDay(), (long) row.get("endDate"));

		// And the end bound is the last second of the final day, not its midnight: a lecture
		// at five in the afternoon on the last day of term is inside the session.
		assertTrue(row.get("endEpoch")
						> ENDS.atStartOfDay(ZONE).toEpochSecond() + 16 * 3600,
				"the final day has to be included in full");
	}

	@Test
	void withoutTheFillTheRowCannotBeWrittenAtAll() throws Exception {

		DeviceTables.Table sessions = DeviceTables.of("academic_years");

		// The bug as it was, kept as a test so the fill cannot be quietly removed: the columns
		// the server carries are not enough for this table's schema.
		SQLException refused = assertThrows(SQLException.class,
				() -> applyCarriedOnly(sessions, serverShapedRow(sessions)));

		assertTrue(refused.getMessage().contains("NOT NULL")
						|| refused.getMessage().contains("constraint"),
				refused.getMessage());
	}

	@Test
	void applyingTheSameSessionTwiceRecomputesRatherThanDuplicates() throws Exception {

		DeviceTables.Table sessions = DeviceTables.of("academic_years");
		Map<String, Object> incoming = serverShapedRow(sessions);

		applyRow(sessions, DeviceDerived.fill(sessions, incoming, ZONE));

		// The session's end moved, as it does when somebody corrects the calendar.
		incoming.put("endDate", ENDS.plusDays(10).toEpochDay());
		applyRow(sessions, DeviceDerived.fill(sessions, incoming, ZONE));

		assertEquals(1, count("SELECT count(*) FROM academic_years"));
		assertEquals(ENDS.plusDays(11).atStartOfDay(ZONE).toEpochSecond() - 1,
				count("SELECT endEpoch FROM academic_years"),
				"the update sets every column the row can change, so the derived bound "
						+ "follows the date it was derived from");
	}

	@Test
	void aRowTheDeviceAlreadyHasKeepsTheRowsThatPointAtIt() throws Exception {

		// A handset found this one, after a backup was imported on the server: the importer
		// kept the keys of the lectures that had them, so the next pull sent this phone its
		// own lectures back -- lectures it had marks against. INSERT OR REPLACE means delete
		// the row that is there and insert this one, the marks point at it with RESTRICT, and
		// the delete is refused: FOREIGN KEY constraint failed, code 1811, which is the
		// internal trigger RESTRICT is implemented with. The page rolled back, the watermark
		// never moved, and every later page queued up behind it for ever. "sync failed" was
		// the whole of what the phone could say.
		for (DeviceTables.Table table : DeviceTables.inDependencyOrder()) {
			applyRow(table, DeviceDerived.fill(table, serverShapedRow(table), ZONE));
		}

		assertEquals(1, count("SELECT count(*) FROM attendance"));

		DeviceTables.Table lectures = DeviceTables.of("lectures");
		Map<String, Object> again = serverShapedRow(lectures);
		again.put("notes", "moved to the lab");

		applyRow(lectures, DeviceDerived.fill(lectures, again, ZONE));

		assertEquals(1, count("SELECT count(*) FROM lectures"));
		assertEquals("moved to the lab", text("SELECT notes FROM lectures"),
				"the server's version of the row has to win");
		assertEquals(1, count("SELECT count(*) FROM attendance"),
				"the mark on that lecture is still here, which is the point");
	}

	@Test
	void aMembershipTheDeviceAlreadyHasIsNotAnError() throws Exception {

		// Three tables in the feed are nothing but their key. There is no column to update,
		// so the pair cannot be "update, then insert" for them, and a second copy of the same
		// membership must not be a UNIQUE failure that takes the page down with it.
		for (DeviceTables.Table table : DeviceTables.inDependencyOrder()) {
			applyRow(table, DeviceDerived.fill(table, serverShapedRow(table), ZONE));
		}

		DeviceTables.Table memberships = DeviceTables.of("course_batch_map");

		assertNull(DeviceSql.updateRow(memberships), "there is nothing to set");

		applyRow(memberships,
				DeviceDerived.fill(memberships, serverShapedRow(memberships), ZONE));

		assertEquals(1, count("SELECT count(*) FROM course_batch_map"));
	}

	@Test
	void theNodeAPhonePickedForItselfIsRecoverableFromItsOwnKeys() throws Exception {

		// The last resort for an install made before that node was kept in preferences: once a
		// real node is assigned the number is gone from there, but every row written before
		// then still carries it in its key. This is what a re-registration reports when
		// preferences have nothing.
		long selfPicked = 37498;
		long assigned = 1;

		// The course these hang off, because foreign keys are enforced here exactly as Room
		// enforces them: a lecture on a course that does not exist is not a row a phone could
		// have written in the first place.
		for (String parent : List.of("professors", "courses")) {
			DeviceTables.Table table = DeviceTables.of(parent);
			applyRow(table, DeviceDerived.fill(table, serverShapedRow(table), ZONE));
		}

		execute("INSERT INTO lectures (lectureID, courseID, batchID, startDateTime, "
				+ "endDateTime, notes) VALUES ("
				+ StructuredId.compose(StructuredId.MIN_COUNTER + 1, (int) selfPicked)
				+ ", " + COURSE + ", NULL, '0|0|Asia/Kolkata', '0|0|Asia/Kolkata', NULL)");
		execute("INSERT INTO lectures (lectureID, courseID, batchID, startDateTime, "
				+ "endDateTime, notes) VALUES ("
				+ StructuredId.compose(StructuredId.MIN_COUNTER + 2, (int) selfPicked)
				+ ", " + COURSE + ", NULL, '0|0|Asia/Kolkata', '0|0|Asia/Kolkata', NULL)");

		// One row from after registration, which must not be reported: its node is assignable
		// and so was never picked by a device.
		execute("INSERT INTO lectures (lectureID, courseID, batchID, startDateTime, "
				+ "endDateTime, notes) VALUES ("
				+ StructuredId.compose(StructuredId.MIN_COUNTER + 3, (int) assigned)
				+ ", " + COURSE + ", NULL, '0|0|Asia/Kolkata', '0|0|Asia/Kolkata', NULL)");

		List<Long> nodes = new ArrayList<>();
		List<Long> counts = new ArrayList<>();

		try (Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(DeviceSql.selfPickedNodes())) {

			while (results.next()) {
				nodes.add(results.getLong("node"));
				counts.add(results.getLong("rows"));
			}
		}

		assertEquals(List.of(selfPicked), nodes,
				"only the self-picked node, and the assigned one left out: " + nodes);
		assertEquals(List.of(2L), counts, "both rows written under it");
	}

	// ── A row shaped the way the server shapes one ───────────────────────

	/**
	 * The carried columns and nothing else, which is what a pull delivers.
	 * <p>
	 * Values by what the column is rather than by hand per table, so a column added to
	 * {@link DeviceTables} is covered by this test the day it is added rather than the day
	 * somebody remembers to extend a fixture.
	 */
	private Map<String, Object> serverShapedRow(DeviceTables.Table table) {

		Map<String, Object> row = new LinkedHashMap<>();

		for (DeviceTables.Column column : table.carriedColumns()) {
			row.put(column.room(), valueFor(column));
		}
		return row;
	}

	private Object valueFor(DeviceTables.Column column) {

		// Keys and foreign keys by name, so every row points at one that is already there.
		switch (column.room()) {
			case "academicYear":
				return (long) SESSION;
			case "profID":
				return PROF;
			case "studentID":
				return STUDENT;
			case "courseID":
				return COURSE;
			case "lectureID":
				return LECTURE;
			case "batchID":
				return BATCH;
			case "startDate":
				return STARTS.toEpochDay();
			case "endDate":
				return ENDS.toEpochDay();
			default:
				break;
		}

		if (column.nullable()) {
			// Deliberately null: the server sends nulls for these and a NOT NULL on the device
			// would be the same class of defect as startEpoch's.
			return null;
		}

		return switch (column.kind()) {
			case TEXT -> column.room() + " value";
			case INTEGER -> 1L;
			case BOOLEAN -> 1L;
			case EPOCH_DAY -> STARTS.toEpochDay();
			case ENUM -> column.enumType().getEnumConstants()[0].name();
			case ZONED_DATE_TIME -> StoredTimes.format(
					ZonedDateTime.of(2025, 7, 14, 9, 0, 0, 0, ZONE));
		};
	}

	// ── The device's own statements ──────────────────────────────────────

	/** Exactly the pair {@code OutboxDrain.apply} runs: change it where it is, else insert. */
	private void applyRow(DeviceTables.Table table, Map<String, Object> row)
			throws SQLException {

		String update = DeviceSql.updateRow(table);

		if (update != null && run(update, DeviceSql.updateColumns(table), row) > 0) {
			return;
		}

		run(DeviceSql.insertRow(table), DeviceSql.insertColumns(table), row);
	}

	private int run(String sql, List<DeviceTables.Column> columns, Map<String, Object> row)
			throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(sql)) {

			for (int at = 0; at < columns.size(); at++) {
				statement.setObject(at + 1, row.get(columns.get(at).room()));
			}
			return statement.executeUpdate();
		}
	}

	/** The statement as it was before the fill existed: carried columns only. */
	private void applyCarriedOnly(DeviceTables.Table table, Map<String, Object> row)
			throws SQLException {

		StringBuilder sql = new StringBuilder("INSERT OR REPLACE INTO `")
				.append(table.name()).append("` (");
		List<DeviceTables.Column> columns = table.carriedColumns();

		for (int at = 0; at < columns.size(); at++) {
			sql.append(at == 0 ? "" : ", ").append('`').append(columns.get(at).room())
					.append('`');
		}

		sql.append(") VALUES (");

		for (int at = 0; at < columns.size(); at++) {
			sql.append(at == 0 ? "" : ", ").append('?');
		}

		try (PreparedStatement statement = connection.prepareStatement(
				sql.append(")").toString())) {

			for (int at = 0; at < columns.size(); at++) {
				statement.setObject(at + 1, row.get(columns.get(at).room()));
			}
			statement.executeUpdate();
		}
	}

	private void execute(String sql) throws SQLException {

		try (Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	private long count(String sql) throws SQLException {

		try (Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(sql)) {

			assertTrue(results.next());
			return results.getLong(1);
		}
	}

	private String text(String sql) throws SQLException {

		try (Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(sql)) {

			assertTrue(results.next());
			return results.getString(1);
		}
	}
}
