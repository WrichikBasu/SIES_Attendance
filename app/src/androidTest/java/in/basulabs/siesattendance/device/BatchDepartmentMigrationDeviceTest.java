package in.basulabs.siesattendance.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.database.Cursor;

import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.EntityBatchDesc;
import in.basulabs.siesattendance.backend.MainDatabase;
import in.basulabs.siesattendance.backend.migration.MigrationContext;
import in.basulabs.siesattendance.backend.sync.OutboxTriggers;
import in.basulabs.siesattendance.device.support.OldPhone;
import in.basulabs.siesattendance.device.support.StagingFiles;

/**
 * The 12 to 13 step, run by Room on a phone that has been syncing.
 * <p>
 * The server's {@code BatchDepartmentMigrationTest} runs the same step on xerial's SQLite with
 * no Room around it. Here the outbox triggers are already installed and armed when Room starts
 * the migration, as they are on every phone that has opened the app since version 11, and Room
 * then runs its own validation and the {@code onOpen} callbacks on top. The two things that
 * must hold are the server test's: each batch gets the department it would get there, and the
 * guess goes nowhere — nothing queued, applying cleared, the watermark dropped.
 */
@RunWith(AndroidJUnit4.class)
public class BatchDepartmentMigrationDeviceTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	private String name;

	private MainDatabase room;

	@Before
	public void buildASyncingVersion12Phone() {

		name = StagingFiles.freshName();

		try (OldPhone phone = OldPhone.atVersion6(name)) {

			phone.migrateTo(12, new MigrationContext(IST, LocalDate.of(2026, 3, 1)));

			for (String statement : OutboxTriggers.statements()) {
				phone.exec(statement);
			}
			phone.exec("INSERT OR IGNORE INTO sync_state (id, applying, lastPullAt, lastPushAt) "
					+ "VALUES (1, 0, 0, 0)");
			phone.exec("UPDATE sync_state SET watermark = 'w1' WHERE id = 1");

			phone.professor(1001, "A CS", "a.cs@sies.edu.in", "COMPUTER_SCIENCE", "Professor");
			phone.professor(1002, "B CH", "b.ch@sies.edu.in", "CHEMISTRY", "Professor");
			phone.session(2025, LocalDate.of(2025, 6, 1), LocalDate.of(2026, 5, 31), IST);

			phone.student(2001, "CS ONE", "she/her", null);
			phone.student(2002, "CH ONE", "he/him", null);
			phone.studentYear(2001, 2025, "CS-1", "FY", "BSc_unaided", "COMPUTER_SCIENCE", 1);
			phone.studentYear(2002, 2025, "CH-1", "FY", "BSc", "CHEMISTRY", 1);

			phone.course(3001, 1001, "CS-101", "Programming", "BSc_unaided",
					"COMPUTER_SCIENCE", "FY", 1, "MAJOR");
			phone.course(3002, 1002, "CH-101", "Inorganic", "BSc", "CHEMISTRY", "FY", 1,
					"MAJOR");

			phone.batch(5001, "Linked CS", 2025);
			phone.courseBatch(3001, 5001);
			phone.batchMember(5001, 2002, 2025);

			phone.batch(5002, "Taught CH", 2025);
			ZonedDateTime start = ZonedDateTime.of(2025, 7, 14, 9, 0, 0, 0, IST);
			phone.lecture(4001, 3002, 5002L, start, start.plusHours(1));

			phone.batch(5003, "Chemists", 2025);
			phone.batchMember(5003, 2002, 2025);

			phone.batch(5004, "Both", 2025);
			phone.courseBatch(3001, 5004);
			phone.courseBatch(3002, 5004);

			phone.batch(5005, "Mixed", 2025);
			phone.batchMember(5005, 2001, 2025);
			phone.batchMember(5005, 2002, 2025);

			phone.batch(5006, "Empty", 2025);

			// What the seeding queued is not under test; what the migration queues is.
			phone.exec("DELETE FROM outbox");
		}

		room = MainDatabase.openStaging(StagingFiles.context(), name);
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
	public void aBatchIsPlacedByItsCoursesAndThenItsMembers() {

		assertEquals("its course's, not its member's",
				Department.COMPUTER_SCIENCE, departmentOf(5001));
		assertEquals("a lecture says as much as a link",
				Department.CHEMISTRY, departmentOf(5002));
		assertEquals("with no course, its members' major",
				Department.CHEMISTRY, departmentOf(5003));
		assertNull("two departments' courses", departmentOf(5004));
		assertNull("two majors and no course", departmentOf(5005));
		assertNull("nothing to go on", departmentOf(5006));
	}

	@Test
	public void theGuessIsNotSentAndThePhoneAsksTheServerAgain() {

		assertEquals("nothing queued for the server", 0L, number("SELECT COUNT(*) FROM outbox"));
		assertEquals("and the triggers are on again", 0L,
				number("SELECT applying FROM sync_state WHERE id = 1"));
		assertNull("the next pull is a snapshot",
				room.daoOutbox().stateSync().watermark);
	}

	@Test
	public void anEditAfterTheUpgradeIsQueuedAgain() {

		room.daoBatch().renameBatch(5006, "Empty, renamed", null, Department.PHYSICS);

		assertTrue(number("SELECT COUNT(*) FROM outbox WHERE tableName = 'batch_desc' "
				+ "AND key1 = 5006") > 0);
	}

	private Department departmentOf(long batchID) {
		EntityBatchDesc batch = room.daoBatch().getByIdSync(batchID);
		return batch.department;
	}

	private long number(String sql) {
		SupportSQLiteDatabase db = room.getOpenHelper().getWritableDatabase();
		try (Cursor cursor = db.query(sql)) {
			assertTrue(sql, cursor.moveToFirst());
			return cursor.getLong(0);
		}
	}
}
