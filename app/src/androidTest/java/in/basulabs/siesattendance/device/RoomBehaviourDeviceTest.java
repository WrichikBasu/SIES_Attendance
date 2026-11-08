package in.basulabs.siesattendance.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;

import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import in.basulabs.siesattendance.backend.CourseType;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Division;
import in.basulabs.siesattendance.backend.EntityAcademicYear;
import in.basulabs.siesattendance.backend.EntityAttendance;
import in.basulabs.siesattendance.backend.EntityBatchDesc;
import in.basulabs.siesattendance.backend.EntityCourse;
import in.basulabs.siesattendance.backend.EntityLecture;
import in.basulabs.siesattendance.backend.EntityProfessor;
import in.basulabs.siesattendance.backend.EntityStudent;
import in.basulabs.siesattendance.backend.EntityStudentYear;
import in.basulabs.siesattendance.backend.IdGenerator;
import in.basulabs.siesattendance.backend.MainDatabase;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;
import in.basulabs.siesattendance.backend.pojo.LectureDetails;
import in.basulabs.siesattendance.backend.pojo.LectureMark;
import in.basulabs.siesattendance.device.support.StagingFiles;

/**
 * What Room and the phone's SQLite do with the app's writes, on a fresh current-schema file.
 * <p>
 * Each of these depends on something the JVM tests cannot have: foreign keys as Room switches
 * them on, the outbox triggers firing inside Room's transactions, {@code @Transaction} rolling
 * back on a real constraint failure, WAL and its checkpoint, and {@link IdGenerator} issuing
 * keys against the phone's own clock.
 */
@RunWith(AndroidJUnit4.class)
public class RoomBehaviourDeviceTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	private static final int SESSION = 2025;

	private static final ZonedDateTime START = ZonedDateTime.of(2025, 7, 14, 9, 0, 0, 0, IST);

	private String name;

	private MainDatabase room;

	private long alice;

	private long bob;

	private long course;

	private long batch;

	private long lecture;

	@Before
	public void seed() {

		name = StagingFiles.freshName();
		room = MainDatabase.openStaging(StagingFiles.context(), name);

		room.daoAcademicYear().upsert(EntityAcademicYear.of(SESSION,
				LocalDate.of(2025, 6, 1), LocalDate.of(2026, 5, 31)));

		long prof = room.daoProfessor().insert(new EntityProfessor("Prof P", "p@sies.edu.in",
				Department.PHYSICS, "Professor"));

		EntityCourse c = new EntityCourse("PH-101", "Mechanics", Stream.BSc,
				Department.PHYSICS, Year.FY, 1, CourseType.MAJOR, null);
		c.profID = prof;
		course = room.daoCourse().insert(c);

		alice = enrol("ALICE", "PH-1");
		bob = enrol("BOB", "PH-2");

		batch = room.daoBatch().createBatchFromStudents(
				new EntityBatchDesc("PH A", SESSION, null, Department.PHYSICS),
				List.of(alice, bob));

		lecture = room.daoLecture().insert(
				new EntityLecture(course, batch, START, START.plusHours(1), null));

		room.daoAttendance().saveSheet(lecture, List.of(
				new EntityAttendance(lecture, alice, true),
				new EntityAttendance(lecture, bob, false)));
	}

	private long enrol(String student, String roll) {

		long id = room.daoStudent().insertStudent(new EntityStudent(student, "they/them", null));
		room.daoStudent().insertStudentYear(new EntityStudentYear(id, SESSION, roll, Year.FY,
				Stream.BSc, Department.PHYSICS, null, null, 1, Division.A));
		return id;
	}

	@After
	public void throwItAway() {
		if (room != null) {
			room.close();
		}
		StagingFiles.delete(name);
	}

	// ── Foreign keys and transactions ───────────────────────────────────

	@Test
	public void aStudentWithAttendanceCannotBeDeletedOutFromUnderIt() {

		assertThrows(SQLiteConstraintException.class, () -> sqlite().execSQL(
				"DELETE FROM students WHERE studentID = ?", new Object[]{alice}));

		assertNotNull(room.daoStudent().getStudentSync(alice));
	}

	@Test
	public void aFailedSaveSheetLeavesTheOldSheetExactlyAsItWas() {

		long nobody = IdGenerator.newID();

		assertThrows(SQLiteConstraintException.class, () -> room.daoAttendance().saveSheet(
				lecture, List.of(new EntityAttendance(lecture, alice, false),
						new EntityAttendance(lecture, nobody, true))));

		// clearForLecture ran before the failing insert; @Transaction must have undone it.
		assertEquals(List.of(alice), room.daoAttendance().getPresentIdsSync(lecture));
		assertEquals(2, room.daoAttendance().getMarkedIdsSync(lecture).size());
	}

	@Test
	public void aBatchWithLecturesRefusesToBeDeletedAndKeepsItsMembers() {

		assertThrows(IllegalStateException.class,
				() -> room.daoBatch().deleteBatchChecked(batch));

		assertNotNull(room.daoBatch().getByIdSync(batch));
		assertEquals(2, room.daoBatch().countStudentsSync(batch));
	}

	// ── The outbox triggers ─────────────────────────────────────────────

	@Test
	public void repeatedEditsToOneRowQueueOneIntent() {

		EntityStudent s = room.daoStudent().getStudentSync(alice);
		s.name = "ALICE B";
		room.daoStudent().updateStudent(s);
		s.name = "ALICE C";
		room.daoStudent().updateStudent(s);

		assertEquals(1L, number("SELECT COUNT(*) FROM outbox WHERE tableName = 'students' "
				+ "AND key1 = " + alice));
		assertEquals("UPSERT", text("SELECT op FROM outbox WHERE tableName = 'students' "
				+ "AND key1 = " + alice));
	}

	@Test
	public void aDeleteReplacesThePendingUpsert() {

		long gone = room.daoStudent().insertStudent(new EntityStudent("GONE", "he/him", null));
		sqlite().execSQL("DELETE FROM students WHERE studentID = ?", new Object[]{gone});

		assertEquals(1L, number("SELECT COUNT(*) FROM outbox WHERE tableName = 'students' "
				+ "AND key1 = " + gone));
		assertEquals("DELETE", text("SELECT op FROM outbox WHERE tableName = 'students' "
				+ "AND key1 = " + gone));
	}

	@Test
	public void compositeKeysLandInTheRightSlots() {

		assertEquals(1L, number("SELECT COUNT(*) FROM outbox WHERE tableName = 'attendance' "
				+ "AND key1 = " + lecture + " AND key2 = " + bob + " AND key3 = 0"));
	}

	@Test
	public void nothingIsQueuedWhileAPullIsBeingApplied() {

		room.daoOutbox().setApplyingSync(1);
		long quiet;
		try {
			quiet = room.daoStudent().insertStudent(new EntityStudent("QUIET", "she/her", null));
		} finally {
			room.daoOutbox().setApplyingSync(0);
		}

		assertEquals(0L, number("SELECT COUNT(*) FROM outbox WHERE tableName = 'students' "
				+ "AND key1 = " + quiet));
	}

	// ── Queries the device's SQLite has to agree with ───────────────────

	@Test
	public void aStudentWhoLeftIsNotOnTheMarkSheet() {

		room.daoStudent().setEnrolmentDates(bob, SESSION, null, LocalDate.of(2025, 8, 1));

		List<LectureMark> marks = room.daoAttendance().getMarksSync(List.of(lecture));

		assertEquals(1, marks.size());
		assertEquals(alice, marks.get(0).studentID);
		assertEquals("PH-1", marks.get(0).rollNo);
	}

	@Test
	public void theLectureWindowIsInclusiveAtBothEnds() {

		long epoch = START.toEpochSecond();

		assertEquals(1, room.daoLecture().getDetailsBetweenSync(epoch, epoch).size());
		assertEquals(0, room.daoLecture().getDetailsBetweenSync(epoch + 1, epoch + 10).size());
		assertEquals(0, room.daoLecture().getDetailsBetweenSync(epoch - 10, epoch - 1).size());

		LectureDetails details = room.daoLecture().getDetailsBetweenSync(epoch, epoch).get(0);
		assertEquals(2, details.markedCount);
		assertEquals(1, details.presentCount);
	}

	@Test
	public void timesKeepTheirNanosecondsAndTheirZone() {

		ZonedDateTime odd = ZonedDateTime.of(2025, 11, 2, 1, 30, 0, 123_456_789,
				ZoneId.of("America/New_York"));

		long id = room.daoLecture().insert(
				new EntityLecture(course, null, odd, odd.plusMinutes(50), "DST night"));

		EntityLecture back = room.daoLecture().getByIdSync(id);
		assertEquals(odd, back.startDateTime);
		assertEquals(odd.getZone(), back.startDateTime.getZone());
	}

	@Test
	public void courseCodesMatchWithoutRegardToCase() {
		EntityCourse found = room.daoCourse().findByCodeSync("ph-101");
		assertNotNull(found);
		assertEquals(course, found.courseID);
	}

	@Test
	public void unicodeNamesSurviveTheRoundTrip() {

		String name = "Ånanyā Çhatterjee — অনন্যা";
		long id = room.daoStudent().insertStudent(new EntityStudent(name, "she/her", null));

		assertEquals(name, room.daoStudent().getStudentSync(id).name);
	}

	// ── Keys and the file ───────────────────────────────────────────────

	@Test
	public void aBurstOfInsertsGetsDistinctKeysFromThisPhonesClock() {

		Set<Long> seen = new HashSet<>();

		room.runInTransaction(() -> {
			for (int i = 0; i < 500; i++) {
				long id = room.daoStudent().insertStudent(new EntityStudent(
						String.format(Locale.ROOT, "BURST %03d", i), "they/them", null));
				assertTrue("duplicate key " + id, seen.add(id));
				assertEquals(IdGenerator.nodeID(), IdGenerator.nodeOf(id));
			}
		});

		assertEquals(500, seen.size());
	}

	@Test
	public void aCheckpointFoldsTheWholeLogBackIntoTheFile() {

		assumeTrue("Room chose WAL on this device",
				"wal".equalsIgnoreCase(text("PRAGMA journal_mode")));

		File wal = new File(StagingFiles.file(name).getPath() + "-wal");
		assertTrue("writes have gone to the log", wal.length() > 0);

		assertTrue(MainDatabase.checkpoint(sqlite()));
		assertEquals("TRUNCATE leaves an empty log", 0L, wal.length());
	}

	// ── helpers ──────────────────────────────────────────────────────────

	private SupportSQLiteDatabase sqlite() {
		return room.getOpenHelper().getWritableDatabase();
	}

	private long number(String sql) {
		try (Cursor cursor = sqlite().query(sql)) {
			assertTrue(sql, cursor.moveToFirst());
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
