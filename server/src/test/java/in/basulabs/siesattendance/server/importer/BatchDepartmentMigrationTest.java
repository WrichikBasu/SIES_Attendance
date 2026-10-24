package in.basulabs.siesattendance.server.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import in.basulabs.siesattendance.backend.migration.MigrationContext;
import in.basulabs.siesattendance.backend.migration.SchemaMigrations;
import in.basulabs.siesattendance.backend.sync.OutboxTriggers;

/**
 * The device's version 12 to 13 step: a batch gets the department the server's V31 would have
 * given it, and the guess goes nowhere.
 * <p>
 * Against real SQLite, on a database built through every earlier step and seeded at version
 * 12 with the outbox triggers installed, as a phone that has been syncing would be.
 */
class BatchDepartmentMigrationTest {

	private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

	private static final MigrationContext CONTEXT =
			new MigrationContext(ZONE, LocalDate.of(2026, 3, 1));

	@TempDir
	Path work;

	private DeviceBackup device;

	private Connection connection;

	@BeforeEach
	void buildAVersion12Phone() throws Exception {

		device = DeviceBackup.emptyAtVersion6(work.resolve("devices"));
		connection = device.connection();

		for (int version = DeviceBackup.BASE_VERSION; version < 12; version++) {
			SchemaMigrations.stepFrom(version).apply(new JdbcSqliteDb(connection), CONTEXT);
		}

		for (String statement : OutboxTriggers.statements()) {
			execute(statement);
		}

		execute("UPDATE sync_state SET watermark = 'w1' WHERE id = 1");

		device.professor(1001, "A CS", "a.cs@sies.edu.in", "COMPUTER_SCIENCE", "Professor");
		device.professor(1002, "B CH", "b.ch@sies.edu.in", "CHEMISTRY", "Professor");
		device.session(2025, LocalDate.of(2025, 6, 1), LocalDate.of(2026, 5, 31), ZONE);

		device.student(2001, "CS ONE", "she/her", null);
		device.student(2002, "CH ONE", "he/him", null);
		device.studentYear(2001, 2025, "CS-1", "FY", "BSc_unaided", "COMPUTER_SCIENCE",
				null, null, 1);
		device.studentYear(2002, 2025, "CH-1", "FY", "BSc", "CHEMISTRY", null, null, 1);

		device.course(3001, 1001, "CS-101", "Programming", "BSc_unaided", "COMPUTER_SCIENCE",
				"FY", 1, "MAJOR");
		device.course(3002, 1002, "CH-101", "Inorganic", "BSc", "CHEMISTRY", "FY", 1, "MAJOR");

		// Linked to a CS course: Computer Science's, whoever is in it.
		device.batch(5001, "Linked CS", null, 2025);
		device.courseBatch(3001, 5001);
		device.batchMember(5001, 2002, 2025);

		// Only taught under a Chemistry course, never linked: Chemistry's.
		device.batch(5002, "Taught CH", null, 2025);
		ZonedDateTime start = ZonedDateTime.of(2025, 7, 14, 9, 0, 0, 0, ZONE);
		device.lecture(4001, 3002, 5002L, start, start.plusHours(1), null);

		// No course at all, and only Chemistry majors in it: Chemistry's.
		device.batch(5003, "Chemists", null, 2025);
		device.batchMember(5003, 2002, 2025);

		// Linked to both departments: nobody can say.
		device.batch(5004, "Both", null, 2025);
		device.courseBatch(3001, 5004);
		device.courseBatch(3002, 5004);

		// No course, and members of two majors: nobody can say.
		device.batch(5005, "Mixed", null, 2025);
		device.batchMember(5005, 2001, 2025);
		device.batchMember(5005, 2002, 2025);

		// Empty and unused.
		device.batch(5006, "Empty", null, 2025);

		execute("DELETE FROM outbox");

		SchemaMigrations.stepFrom(12).apply(new JdbcSqliteDb(connection), CONTEXT);
	}

	@AfterEach
	void close() throws Exception {
		device.close();
	}

	@Test
	void aBatchIsPlacedByItsCoursesAndThenItsMembers() throws Exception {

		assertEquals("COMPUTER_SCIENCE", departmentOf(5001), "its course's, not its member's");
		assertEquals("CHEMISTRY", departmentOf(5002), "a lecture says as much as a link");
		assertEquals("CHEMISTRY", departmentOf(5003), "with no course, its members' major");
		assertNull(departmentOf(5004), "two departments' courses");
		assertNull(departmentOf(5005), "two majors and no course");
		assertNull(departmentOf(5006), "nothing to go on");
	}

	@Test
	void theGuessIsNotSentAndThePhoneAsksTheServerAgain() throws Exception {

		assertEquals(0, number("SELECT count(*) FROM outbox"), "nothing queued for the server");
		assertEquals(0, number("SELECT applying FROM sync_state WHERE id = 1"),
				"and the triggers are on again");
		assertNull(text("SELECT watermark FROM sync_state WHERE id = 1"),
				"the next pull is a snapshot, which brings the server's departments");
	}

	private String departmentOf(long batchId) throws SQLException {
		return text("SELECT department FROM batch_desc WHERE batchID = " + batchId);
	}

	private String text(String sql) throws SQLException {
		try (Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(sql)) {
			results.next();
			return results.getString(1);
		}
	}

	private long number(String sql) throws SQLException {
		try (Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(sql)) {
			results.next();
			return results.getLong(1);
		}
	}

	private void execute(String sql) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}
}
