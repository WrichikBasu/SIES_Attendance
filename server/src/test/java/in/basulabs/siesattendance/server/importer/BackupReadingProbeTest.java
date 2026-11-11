package in.basulabs.siesattendance.server.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;

import in.basulabs.siesattendance.backend.StructuredId;
import in.basulabs.siesattendance.server.testing.TestPostgres;

/**
 * Probes for the backup-import finding that had only been read (R19 of the review). Each test
 * states the behaviour that would be right, so a failure here is the finding confirmed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BackupReadingProbeTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	private static final int SESSION = 2025;

	private static final int DEVICE = 41231;

	private static TestPostgres postgres;

	@TempDir
	Path work;

	@BeforeAll
	void startDatabase() throws Exception {
		assumeTrue(TestPostgres.isAvailable(), "Docker is not available");
		postgres = TestPostgres.start();
	}

	@AfterAll
	void stopDatabase() {
		if (postgres != null) {
			postgres.close();
		}
	}

	@BeforeEach
	void emptyTheDatabase() throws Exception {
		assumeTrue(postgres != null, "no database");
		postgres.reset();
	}

	private static long key(int sequence) {
		return StructuredId.compose(StructuredId.MIN_COUNTER + sequence, DEVICE);
	}

	/** A phone with one professor, the session, and one student or two. */
	private Path backup(String file, boolean withSecondStudent, Long secondApaar,
	                    String secondEmail) throws Exception {

		try (DeviceBackup device = DeviceBackup.emptyAtVersion6(work.resolve("device-" + file))) {
			device.professor(key(1), "Prof P", "prof.p@sies.edu.in", "COMPUTER_SCIENCE",
					"Assistant Professor");
			device.session(SESSION, LocalDate.of(SESSION, 6, 1),
					LocalDate.of(SESSION + 1, 5, 31), IST);
			device.student(key(2), "Name As First Typed", "she/her", "one@sies.edu.in");
			device.studentYear(key(2), SESSION, "CS-001", "FY", "BSc_unaided",
					"COMPUTER_SCIENCE", null, null, 1);

			if (withSecondStudent) {
				device.student(key(3), "Second Student", "they/them", secondEmail);
				device.studentYear(key(3), SESSION, "CS-002", "FY", "BSc_unaided",
						"COMPUTER_SCIENCE", null, null, 1);
			}

			device.migrateToCurrent(IST);

			if (secondApaar != null) {
				device.write("UPDATE students SET apaarId = ? WHERE studentID = ?",
						secondApaar, key(3));
			}
			return device.pack(work.resolve(file + ".siesbak"), null);
		}
	}

	private BackupImporter.Outcome importIt(Path backup) throws Exception {
		return new BackupImporter(postgres.dataSource()).run(new BackupImporter.Request(
				backup, null, "the phone", DEVICE, IST, work.resolve("staging"), false));
	}

	private String text(String sql) throws Exception {
		try (Connection connection = postgres.connection();
		     Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(sql)) {
			return results.next() ? results.getString(1) : "(no row)";
		}
	}

	@Test
	void r19_anOlderBackupDoesNotUndoACorrectionMadeOnTheServerSince() throws Exception {

		assertTrue(importIt(backup("monday", false, null, null)).succeeded());

		// The office corrects the name on the server on Tuesday.
		postgres.asSuperuser("UPDATE students SET name = 'NAME AS CORRECTED' "
				+ "WHERE student_id = " + key(2));

		// A backup of the phone as it was, imported on Wednesday: it still has Monday's
		// name. It has a second student too, so it is not the same file again.
		BackupImporter.Outcome second = importIt(backup("stale", true, null, null));
		assertTrue(second.succeeded(), () -> "import failed: " + second.failure());

		assertEquals("NAME AS CORRECTED",
				text("SELECT name FROM students WHERE student_id = " + key(2)),
				"the stale backup put the old name back over the server's correction");
	}

	@Test
	void r19_anApaarIdSomebodyElseHoldsCostsOneStudentAndNotTheWholeImport() throws Exception {

		// Somebody already on the server holds this APAAR ID.
		postgres.asSuperuser("""
				INSERT INTO students (student_id, name, pronouns, email, apaar_id) VALUES
				    (2001, 'ALREADY HERE', 'she/her', NULL, 123412341234);
				""");

		BackupImporter.Outcome outcome = importIt(
				backup("apaar", true, 123412341234L, null));

		assertTrue(outcome.succeeded(), () -> "one student's APAAR ID is taken, and nothing "
				+ "at all was imported: " + outcome.failure());
	}

	@Test
	void r19_control_anEmailSomebodyElseHoldsIsSkipped() throws Exception {

		postgres.asSuperuser("""
				INSERT INTO students (student_id, name, pronouns, email) VALUES
				    (2001, 'ALREADY HERE', 'she/her', 'taken@sies.edu.in');
				""");

		BackupImporter.Outcome outcome = importIt(
				backup("email", true, null, "taken@sies.edu.in"));

		assertTrue(outcome.succeeded(), () -> "import failed: " + outcome.failure());
	}
}
