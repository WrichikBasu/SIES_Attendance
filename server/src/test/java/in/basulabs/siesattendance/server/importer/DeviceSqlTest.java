package in.basulabs.siesattendance.server.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import in.basulabs.siesattendance.backend.StoredTimes;
import in.basulabs.siesattendance.backend.sync.DeviceSql;
import in.basulabs.siesattendance.backend.sync.DeviceTables;
import in.basulabs.siesattendance.backend.sync.OutboxTriggers;

/**
 * The statements the phone runs to send a row and to apply one, against real SQLite.
 * <p>
 * Here for the same reason {@code OutboxTriggerTest} is: the server module has a SQLite driver
 * and a fixture that builds a genuine device database through the shared migrations, and the app
 * module would need an emulator. Between the two of them, the device half of sync is exercised as
 * far as a JVM can reach — what is left is Room handing these statements a cursor, and WorkManager
 * deciding when, neither of which exists off a handset.
 */
class DeviceSqlTest {

	private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

	@TempDir
	Path work;

	private DeviceBackup device;

	private Connection connection;

	@BeforeEach
	void buildDeviceDatabase() throws Exception {

		device = DeviceBackup.emptyAtVersion6(work.resolve("devices"));
		device.migrateToCurrent(ZONE);
		connection = device.connection();

		for (String statement : OutboxTriggers.statements()) {
			execute(statement);
		}

		device.professor(1001, "A CS", "a.cs@sies.edu.in", "COMPUTER_SCIENCE", "Professor");
		device.session(2025, LocalDate.of(2025, 6, 1), LocalDate.of(2026, 5, 31), ZONE);
		device.student(2001, "R RAO", "she/her", null);
		device.studentYear(2001, 2025, "CS-FY-001", "FY", "BSc_unaided", "COMPUTER_SCIENCE",
				null, null, 1);
		device.course(3001, 1001, "CS-101", "Programming", "BSc_unaided", "COMPUTER_SCIENCE",
				"FY", 1, "MAJOR");
		execute("DELETE FROM outbox");
	}

	@AfterEach
	void close() throws Exception {
		if (device != null) {
			device.close();
		}
	}

	@Test
	void aRowReadsBackExactlyAsTheServerExpectsToReceiveIt() throws Exception {

		ZonedDateTime start = ZonedDateTime.of(2025, 7, 14, 9, 0, 0, 0, ZONE);
		ZonedDateTime end = ZonedDateTime.of(2025, 7, 14, 9, 50, 0, 0, ZONE);

		device.lecture(4001, 3001, null, start, end, "bring a calculator");

		DeviceTables.Table lectures = DeviceTables.of("lectures");
		Map<String, Object> row = readRow(lectures, List.of(4001L));

		// Room's column names and Room's values. The timestamp is the one string Room stores,
		// zone and all, because the server reconstructs both halves from it.
		assertEquals(4001L, row.get("lectureID"));
		assertEquals(3001L, row.get("courseID"));
		assertNull(row.get("batchID"));
		assertEquals(StoredTimes.format(start), row.get("startDateTime"));
		assertEquals("bring a calculator", row.get("notes"));

		// And nothing the server does not keep: startEpoch is on the device and was dropped in
		// V1, so a SELECT * would have sent a field nothing on the other side expects.
		assertTrue(row.containsKey("startDateTime"), row.toString());
		assertEquals(lectures.carriedColumns().size(), row.size(), row.toString());
	}

	@Test
	void aMarkTravelsAsZeroOrOne() throws Exception {

		device.lecture(4001, 3001, null,
				ZonedDateTime.of(2025, 7, 14, 9, 0, 0, 0, ZONE),
				ZonedDateTime.of(2025, 7, 14, 9, 50, 0, 0, ZONE), null);
		device.attendance(4001, 2001, false);

		Map<String, Object> row = readRow(DeviceTables.of("attendance"), List.of(4001L, 2001L));

		assertEquals(0L, row.get("present"),
				"SQLite has no boolean, and the server converts what Room wrote rather than "
						+ "what a JSON library would have guessed");
	}

	@Test
	void aPulledRowIsWrittenAndNotQueuedBack() throws Exception {

		DeviceTables.Table lectures = DeviceTables.of("lectures");

		Map<String, Object> incoming = new LinkedHashMap<>();
		incoming.put("lectureID", 4002L);
		incoming.put("courseID", 3001L);
		incoming.put("batchID", null);
		incoming.put("startDateTime", StoredTimes.format(
				ZonedDateTime.of(2025, 7, 15, 9, 0, 0, 0, ZONE)));
		incoming.put("endDateTime", StoredTimes.format(
				ZonedDateTime.of(2025, 7, 15, 9, 50, 0, 0, ZONE)));
		incoming.put("notes", null);

		// Exactly what the pull does: the guard on, the rows written, the guard off, all in one
		// transaction so a crash takes the flag back with the writes.
		connection.setAutoCommit(false);

		try {
			execute("UPDATE sync_state SET applying = 1 WHERE id = 1");
			applyRow(lectures, incoming);
			execute("UPDATE sync_state SET applying = 0 WHERE id = 1");
			connection.commit();

		} finally {
			connection.setAutoCommit(true);
		}

		assertEquals(1, count("SELECT count(*) FROM lectures WHERE lectureID = 4002"),
				"the row the server sent has to land");
		assertEquals(0, count("SELECT count(*) FROM outbox"),
				"and must not be announced straight back to the server");

		// The same statement, applied again, is how a repeated page behaves: the row is
		// replaced rather than duplicated.
		connection.setAutoCommit(false);

		try {
			execute("UPDATE sync_state SET applying = 1 WHERE id = 1");
			applyRow(lectures, incoming);
			execute("UPDATE sync_state SET applying = 0 WHERE id = 1");
			connection.commit();

		} finally {
			connection.setAutoCommit(true);
		}

		assertEquals(1, count("SELECT count(*) FROM lectures WHERE lectureID = 4002"));
	}

	@Test
	void aDeletionFromTheServerRemovesTheRowAndDoesNotQueueIt() throws Exception {

		device.lecture(4003, 3001, null,
				ZonedDateTime.of(2025, 7, 16, 9, 0, 0, 0, ZONE),
				ZonedDateTime.of(2025, 7, 16, 9, 50, 0, 0, ZONE), null);
		execute("DELETE FROM outbox");

		connection.setAutoCommit(false);

		try {
			execute("UPDATE sync_state SET applying = 1 WHERE id = 1");

			try (PreparedStatement statement = connection.prepareStatement(
					DeviceSql.deleteRow(DeviceTables.of("lectures")))) {
				statement.setLong(1, 4003L);
				statement.executeUpdate();
			}

			execute("UPDATE sync_state SET applying = 0 WHERE id = 1");
			connection.commit();

		} finally {
			connection.setAutoCommit(true);
		}

		assertEquals(0, count("SELECT count(*) FROM lectures WHERE lectureID = 4003"));
		assertEquals(0, count("SELECT count(*) FROM outbox"));
	}

	@Test
	void theStatementsAreValidSqliteForEveryTable() throws Exception {

		// Every table, not just the two that sync today: the statements are generated from the
		// table descriptions, and a description that produces something SQLite will not parse
		// should fail here rather than the first time that table is added to the feed.
		for (DeviceTables.Table table : DeviceTables.inDependencyOrder()) {

			prepare(DeviceSql.selectRow(table));
			prepare(DeviceSql.upsertRow(table));
			prepare(DeviceSql.deleteRow(table));

			assertEquals(table.carriedColumns().size(), DeviceSql.upsertColumns(table).size(),
					table.name());
		}
	}

	// ── Running them ─────────────────────────────────────────────────────

	/** What the drain does: read one row by key, as a map of Room column names. */
	private Map<String, Object> readRow(DeviceTables.Table table, List<Long> key)
			throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				DeviceSql.selectRow(table))) {

			for (int at = 0; at < key.size(); at++) {
				statement.setLong(at + 1, key.get(at));
			}

			try (ResultSet results = statement.executeQuery()) {

				assertTrue(results.next(), "no such row");
				Map<String, Object> row = new LinkedHashMap<>();

				for (DeviceTables.Column column : table.carriedColumns()) {

					// By what the column is, not by what the driver guessed: see
					// DeviceSql.readsAsText. Android's cursor and sqlite-jdbc disagree about
					// getObject, and a key read as an Integer is a key that will not compare
					// equal to the one the server sends back.
					if (DeviceSql.readsAsText(column)) {
						row.put(column.room(), results.getString(column.room()));

					} else {
						long value = results.getLong(column.room());
						row.put(column.room(), results.wasNull() ? null : value);
					}
				}
				return row;
			}
		}
	}

	/** What applying a pulled row does. */
	private void applyRow(DeviceTables.Table table, Map<String, Object> row)
			throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				DeviceSql.upsertRow(table))) {

			List<DeviceTables.Column> columns = DeviceSql.upsertColumns(table);

			for (int at = 0; at < columns.size(); at++) {
				statement.setObject(at + 1, row.get(columns.get(at).room()));
			}
			statement.executeUpdate();
		}
	}

	private void prepare(String sql) throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			assertTrue(statement.getParameterMetaData().getParameterCount() >= 1, sql);
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

			assertTrue(results.next(), () -> "no row from: " + sql);
			return results.getLong(1);
		}
	}
}
