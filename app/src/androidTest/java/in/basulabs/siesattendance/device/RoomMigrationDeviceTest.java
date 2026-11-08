package in.basulabs.siesattendance.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.database.Cursor;

import androidx.sqlite.db.SupportSQLiteDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import in.basulabs.siesattendance.backend.ConstantsAndStatics;
import in.basulabs.siesattendance.backend.EntityAttendance;
import in.basulabs.siesattendance.backend.EntityLecture;
import in.basulabs.siesattendance.backend.EntityStudent;
import in.basulabs.siesattendance.backend.IdGenerator;
import in.basulabs.siesattendance.backend.MainDatabase;
import in.basulabs.siesattendance.backend.migration.MigrationContext;
import in.basulabs.siesattendance.backend.migration.SchemaMigrations;
import in.basulabs.siesattendance.backend.sync.OutboxTriggers;
import in.basulabs.siesattendance.device.support.OldPhone;
import in.basulabs.siesattendance.device.support.StagingFiles;

/**
 * Room, on this phone's SQLite, bringing an old file up to the current schema.
 * <p>
 * The JVM tests record what each migration says ({@code MigrationSqlTest}) and the server runs
 * the steps on xerial's SQLite. Neither of them is Room: neither runs the generated
 * {@code onValidateSchema}, which compares every table, column, index and foreign key Room
 * expects against what the migrations actually left, and neither runs on the SQLite build a
 * phone ships. Both are what decide whether a teacher's phone opens after an update or crashes
 * on launch.
 * <p>
 * Parameterised by the version Room starts from. Each case builds version 6 by hand, seeds it
 * with legacy autoincrement keys, walks it forward on the framework connection to the start
 * version, and then hands it to {@link MainDatabase#openStaging}, so every step from that
 * version on runs inside Room. Starting at 6 runs all of 6 to 13 through Room; starting at
 * 12 runs only the last. Together they cover every step Room can be asked to run from a file
 * that exists in the wild.
 */
@RunWith(Parameterized.class)
public class RoomMigrationDeviceTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	private static final MigrationContext CONTEXT =
			new MigrationContext(IST, LocalDate.of(2026, 3, 1));

	private static final int SESSION = 2025;

	private static final ZonedDateTime FIRST_LECTURE =
			ZonedDateTime.of(2025, 7, 14, 9, 0, 0, 0, IST);

	@Parameterized.Parameters(name = "Room migrates from v{0}")
	public static Collection<Object[]> startVersions() {

		List<Object[]> versions = new ArrayList<>();

		for (int v = OldPhone.BASE_VERSION; v < SchemaMigrations.CURRENT_VERSION; v++) {
			versions.add(new Object[]{v});
		}
		return versions;
	}

	private final int startVersion;

	private String name;

	private MainDatabase room;

	public RoomMigrationDeviceTest(int startVersion) {
		this.startVersion = startVersion;
	}

	@Before
	public void buildAnOldPhoneAndLetRoomOpenIt() {

		name = StagingFiles.freshName();

		try (OldPhone phone = OldPhone.atVersion6(name)) {

			phone.professor(1, "A CS", "a.cs@sies.edu.in", "COMPUTER_SCIENCE", "Professor");
			phone.professor(2, "B CH", "b.ch@sies.edu.in", "CHEMISTRY", "Professor");
			phone.session(SESSION, LocalDate.of(2025, 6, 1), LocalDate.of(2026, 5, 31), IST);

			phone.student(1, "CS ONE", "she/her", "cs.one@example.org");
			phone.student(2, "CS TWO", "he/him", null);
			phone.student(3, "CH ONE", "they/them", null);
			phone.studentYear(1, SESSION, "CS-1", "FY", "BSc_unaided", "COMPUTER_SCIENCE", 1);
			phone.studentYear(2, SESSION, "CS-2", "FY", "BSc_unaided", "COMPUTER_SCIENCE", null);
			phone.studentYear(3, SESSION, "CH-1", "FY", "BSc", "CHEMISTRY", 1);

			phone.course(1, 1, "CS-101", "Programming", "BSc_unaided", "COMPUTER_SCIENCE",
					"FY", 1, "MAJOR");
			phone.course(2, 2, "CH-101", "Inorganic", "BSc", "CHEMISTRY", "FY", 1, "MAJOR");

			phone.batch(1, "CS A", SESSION);
			phone.batchMember(1, 1, SESSION);
			phone.batchMember(1, 2, SESSION);
			phone.courseBatch(1, 1);

			// One for the batch, one for the whole class.
			phone.lecture(1, 1, 1L, FIRST_LECTURE, FIRST_LECTURE.plusHours(1));
			phone.lecture(2, 2, null, FIRST_LECTURE.plusDays(1),
					FIRST_LECTURE.plusDays(1).plusHours(1));

			phone.attendance(1, 1, true);
			phone.attendance(1, 2, false);
			phone.attendance(2, 3, true);

			phone.migrateTo(startVersion, CONTEXT);
		}

		room = MainDatabase.openStaging(StagingFiles.context(), name);
		// Opening is lazy; this is the line that runs the migrations and the validation.
		room.getOpenHelper().getWritableDatabase();
	}

	@After
	public void throwItAway() {
		if (room != null) {
			room.close();
		}
		StagingFiles.delete(name);
	}

	@Test
	public void roomAcceptsTheMigratedSchemaAndRecordsTheCurrentVersion() {
		// Reaching here at all means onValidateSchema passed.
		assertEquals(ConstantsAndStatics.DATABASE_VERSION, sqlite().getVersion());
	}

	@Test
	public void theFileIsIntactAndNoForeignKeyWasLeftDangling() {

		assertEquals("ok", text("PRAGMA integrity_check"));

		try (Cursor broken = sqlite().query("PRAGMA foreign_key_check")) {
			assertEquals("rows whose parent a rebuild lost", 0, broken.getCount());
		}

		assertEquals("Room turns foreign keys on for every connection it opens",
				1L, number("PRAGMA foreign_keys"));
	}

	@Test
	public void everyRowSurvivesWithTheKeyItHad() {

		assertEquals(3L, number("SELECT COUNT(*) FROM students"));
		assertEquals(2L, number("SELECT COUNT(*) FROM professors"));
		assertEquals(2L, number("SELECT COUNT(*) FROM courses"));
		assertEquals(1L, number("SELECT COUNT(*) FROM batch_desc"));
		assertEquals(2L, number("SELECT COUNT(*) FROM batch_student_map"));
		assertEquals(2L, number("SELECT COUNT(*) FROM lectures"));
		assertEquals(3L, number("SELECT COUNT(*) FROM attendance"));

		EntityStudent student = room.daoStudent().getStudentSync(1);
		assertNotNull("legacy key 1 is still student 1", student);
		assertEquals("CS ONE", student.name);
		assertEquals("cs.one@example.org", student.email);

		assertEquals(List.of(1L), room.daoAttendance().getPresentIdsSync(1));
	}

	@Test
	public void storedTimesReadBackThroughTheTypeConvertersUnchanged() {

		EntityLecture lecture = room.daoLecture().getByIdSync(1);

		assertNotNull(lecture);
		assertEquals(FIRST_LECTURE, lecture.startDateTime);
		assertEquals(IST, lecture.startDateTime.getZone());
		assertEquals(Long.valueOf(1L), lecture.batchID);

		assertNull("the whole-class lecture keeps its null batch",
				room.daoLecture().getByIdSync(2).batchID);
	}

	@Test
	public void historyIsNotQueuedButTheTriggersAreInstalledAndLive() {

		assertEquals("migrating must not claim old attendance as local changes",
				0L, number("SELECT COUNT(*) FROM outbox"));

		assertEquals(OutboxTriggers.names().size(), number(
				"SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' "
						+ "AND name LIKE 'outbox\\_%' ESCAPE '\\'"));

		assertEquals(0L, number("SELECT applying FROM sync_state WHERE id = 1"));

		room.daoAttendance().saveSheet(2, List.of(new EntityAttendance(2, 3, false)));

		assertTrue("a mark made after the upgrade is queued",
				number("SELECT COUNT(*) FROM outbox WHERE tableName = 'attendance'") > 0);
	}

	@Test
	public void newKeysAreStructuredAndCannotCollideWithLegacyOnes() {

		long id = room.daoStudent().insertStudent(
				new EntityStudent("NEW ONE", "she/her", null));

		assertTrue(IdGenerator.isStructured(id));
		assertEquals(IdGenerator.nodeID(), IdGenerator.nodeOf(id));
		assertTrue(id > number("SELECT MAX(studentID) FROM students WHERE studentID <> " + id));
	}

	// ── helpers ──────────────────────────────────────────────────────────

	private SupportSQLiteDatabase sqlite() {
		return room.getOpenHelper().getWritableDatabase();
	}

	private long number(String sql) {
		try (Cursor cursor = sqlite().query(sql)) {
			assertTrue(sql, cursor.moveToFirst());
			assertFalse(sql + " is NULL", cursor.isNull(0));
			return cursor.getLong(0);
		}
	}

	private String text(String sql) {
		try (Cursor cursor = sqlite().query(sql)) {
			assertTrue(sql, cursor.moveToFirst());
			return cursor.getString(0);
		}
	}

}
