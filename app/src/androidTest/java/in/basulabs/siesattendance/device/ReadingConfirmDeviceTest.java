package in.basulabs.siesattendance.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileWriter;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import in.basulabs.siesattendance.backend.CourseType;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.EntityAcademicYear;
import in.basulabs.siesattendance.backend.EntityAttendance;
import in.basulabs.siesattendance.backend.EntityBatchDesc;
import in.basulabs.siesattendance.backend.EntityCourse;
import in.basulabs.siesattendance.backend.EntityLecture;
import in.basulabs.siesattendance.backend.EntityProfessor;
import in.basulabs.siesattendance.backend.EntityStudent;
import in.basulabs.siesattendance.backend.EntityStudentYear;
import in.basulabs.siesattendance.backend.MainDatabase;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;
import in.basulabs.siesattendance.backend.pojo.AttendanceRow;
import in.basulabs.siesattendance.backend.pojo.StudentCourseStat;
import in.basulabs.siesattendance.backend.pojo.StudentWithYear;
import in.basulabs.siesattendance.backend.repo.RoomAttendanceRepository;
import in.basulabs.siesattendance.backend.stats.StatsFilter;
import in.basulabs.siesattendance.backend.stats.StatsQueryBuilder;
import in.basulabs.siesattendance.backend.util.AttendanceRecordImporter;
import in.basulabs.siesattendance.backend.util.CsvImporter;
import in.basulabs.siesattendance.device.support.StagingFiles;

/**
 * Probes for findings about the phone's own database code that had only been read. Each test
 * states the behaviour that would be right, so a failure here is the finding confirmed.
 * Numbers are the review's.
 * <p>
 * Most run on a throwaway database. The two importers only know the app's own database, so
 * their tests use that: run this class on an emulator or a phone whose debug install holds
 * nothing worth keeping.
 */
@RunWith(AndroidJUnit4.class)
public class ReadingConfirmDeviceTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	private static final int SESSION = 2025;

	private String name;

	private MainDatabase room;

	private RoomAttendanceRepository repository;

	private long course, batch, alice, bob;

	@Before
	public void seed() {
		name = StagingFiles.freshName();
		room = MainDatabase.openStaging(StagingFiles.context(), name);
		repository = new RoomAttendanceRepository(room);

		seedInto(room);
	}

	private void seedInto(MainDatabase db) {

		db.daoAcademicYear().upsert(EntityAcademicYear.of(SESSION,
				LocalDate.of(SESSION, 6, 1), LocalDate.of(SESSION + 1, 5, 31)));

		long prof = db.daoProfessor().insert(new EntityProfessor("Prof P", "p@sies.edu.in",
				Department.PHYSICS, "Professor"));

		EntityCourse c = new EntityCourse("PH201", "Optics", Stream.BSc, Department.PHYSICS,
				Year.SY, 3, CourseType.MAJOR, null);
		c.profID = prof;
		course = db.daoCourse().insert(c);

		alice = enrol(db, "ALICE", "PH1", "alice@sies.edu.in");
		bob = enrol(db, "BOB", "PH2", null);

		batch = db.daoBatch().createBatchFromStudents(
				new EntityBatchDesc("PH SY", SESSION, null, Department.PHYSICS),
				List.of(alice, bob));
		db.daoBatch().setCourseLinks(batch, List.of(course));
	}

	private static long enrol(MainDatabase db, String student, String roll, String email) {
		long id = db.daoStudent().insertStudent(new EntityStudent(student, "they/them", email));
		db.daoStudent().insertStudentYear(new EntityStudentYear(id, SESSION, roll, Year.SY,
				Stream.BSc, Department.PHYSICS, Department.CHEMISTRY, null, 3, null));
		return id;
	}

	@After
	public void throwItAway() {
		if (room != null) {
			room.close();
		}
		StagingFiles.delete(name);
	}

	private static ZonedDateTime at(int month, int day) {
		return ZonedDateTime.of(SESSION, month, day, 11, 0, 0, 0, IST);
	}

	private long marksOf(long lecture, long student) {
		return StagingFilesCount.count(room, "SELECT COUNT(*) FROM attendance WHERE lectureID = "
				+ lecture + " AND studentID = " + student);
	}

	// ── R29 ──────────────────────────────────────────────────────────────

	@Test
	public void r29_takingAttendanceAgainForASlotKeepsAMarkItDoesNotMention() {

		ZonedDateTime start = at(7, 14);

		long first = repository.recordLectureWithMarks(course, batch, start, start.plusHours(1),
				null, List.of(new AttendanceRow(alice, "ALICE", "PH-1", true),
						new AttendanceRow(bob, "BOB", "PH-2", false)));

		// Bob leaves the batch. Somebody takes attendance for the same slot again, from the
		// take-attendance screen, whose roll no longer has him.
		long second = repository.recordLectureWithMarks(course, batch, start,
				start.plusHours(1), null,
				List.of(new AttendanceRow(alice, "ALICE", "PH-1", true)));

		assertEquals("the same slot is the same lecture", first, second);
		assertEquals("Bob's mark was deleted by a sheet that never listed him", 1,
				marksOf(first, bob));
	}

	// ── R16 ──────────────────────────────────────────────────────────────

	@Test
	public void r16_theRollUsedToFindStrandedMarksStillHasAStudentWhoLeft() {

		ZonedDateTime start = at(7, 14);
		long lecture = room.daoLecture().insert(
				new EntityLecture(course, batch, start, start.plusHours(1), null));
		room.daoAttendance().saveSheet(lecture, List.of(
				new EntityAttendance(lecture, alice, true),
				new EntityAttendance(lecture, bob, true)));

		// Bob leaves the college in August, after the lecture.
		room.getOpenHelper().getWritableDatabase().execSQL(
				"UPDATE student_year SET leaveDate = " + LocalDate.of(SESSION, 8, 1).toEpochDay()
						+ " WHERE studentID = " + bob);

		// What "Edit lecture" does with "drop stranded marks": keep the marks of whoever is
		// on the roll of the batch, delete the rest. The batch has not changed.
		List<Long> keep = new ArrayList<>();
		for (StudentWithYear student : repository.rollForBatchSync(batch, SESSION)) {
			keep.add(student.studentID);
		}
		repository.deleteAttendanceExcept(lecture, keep);

		assertEquals("Bob sat this lecture with this batch before he left, and his mark was "
				+ "dropped as stranded", 1, marksOf(lecture, bob));
	}

	// ── R38 ──────────────────────────────────────────────────────────────

	@Test
	public void r38_deletingASessionDoesNotLeaveItsEnrolmentsAndLecturesBehind() {

		ZonedDateTime start = at(7, 14);
		room.daoLecture().insert(
				new EntityLecture(course, batch, start, start.plusHours(1), null));

		boolean refused = false;
		try {
			repository.deleteAcademicYear(EntityAcademicYear.of(SESSION,
					LocalDate.of(SESSION, 6, 1), LocalDate.of(SESSION + 1, 5, 31)));
		} catch (Exception e) {
			refused = true;
		}

		long sessions = StagingFilesCount.count(room,
				"SELECT COUNT(*) FROM academic_years WHERE academicYear = " + SESSION);
		long enrolments = StagingFilesCount.count(room,
				"SELECT COUNT(*) FROM student_year WHERE academicYear = " + SESSION);
		long lectures = StagingFilesCount.count(room, "SELECT COUNT(*) FROM lectures");

		assertTrue("the session is gone (" + sessions + " left) and " + enrolments
						+ " enrolments and " + lectures + " lecture of it are still stored",
				refused || sessions == 1 || (enrolments == 0 && lectures == 0));
	}

	// ── R34 ──────────────────────────────────────────────────────────────

	@Test
	public void r34_addingAStudentUnderARollNumberInUseDoesNotRewriteItsHolder() {

		// What the "Add student" form calls on Save, with a roll number mistyped as Alice's.
		try {
			repository.upsertStudentWithYear(new EntityStudent("CAROL", "she/her", null),
					SESSION, "PH-1", Year.SY, Stream.BSc, Department.PHYSICS, null, null, 3,
					null, null, null);
		} catch (Exception refused) {
			// Right: the form should be told the roll number is taken.
		}

		assertEquals("Alice was renamed by somebody adding a new student", "ALICE",
				StagingFilesCount.text(room,
						"SELECT name FROM students WHERE studentID = " + alice));
	}

	// ── R32 ──────────────────────────────────────────────────────────────

	@Test
	public void r32_theBreakdownAndTheDashboardHonourTheJoinDateAsTheStatisticsDo()
			throws Exception {

		// Bob joined on 1 August. A lecture in July has an absence against him all the same.
		room.getOpenHelper().getWritableDatabase().execSQL(
				"UPDATE student_year SET joinDate = " + LocalDate.of(SESSION, 8, 1).toEpochDay()
						+ " WHERE studentID = " + bob);

		long july = room.daoLecture().insert(
				new EntityLecture(course, batch, at(7, 14), at(7, 14).plusHours(1), null));
		long august = room.daoLecture().insert(
				new EntityLecture(course, batch, at(8, 10), at(8, 10).plusHours(1), null));

		room.daoAttendance().saveSheet(july, List.of(
				new EntityAttendance(july, alice, true),
				new EntityAttendance(july, bob, false)));
		room.daoAttendance().saveSheet(august, List.of(
				new EntityAttendance(august, alice, true),
				new EntityAttendance(august, bob, true)));

		StatsFilter filter = new StatsFilter(SESSION);
		filter.studentID = bob;
		int statistics = room.daoStats().runSummary(StatsQueryBuilder.summary(filter)).total;
		assertEquals("the statistics count Bob from the day he joined", 1, statistics);

		List<StudentCourseStat> breakdown = await(room.daoStudent().getCourseBreakdown(bob,
				null, null, true, List.of(), true, List.of()));
		Double overall = await(room.daoAttendance().getOverallPercentage());

		assertEquals("the student's own breakdown counts the lecture before he joined", 1,
				breakdown.get(0).totalLectures);
		assertEquals("the dashboard counts the absence before he joined", 100.0, overall,
				0.01);
	}

	private static <T> T await(LiveData<T> data) throws Exception {

		Object[] box = new Object[1];
		CountDownLatch seen = new CountDownLatch(1);
		Observer<T> observer = value -> {
			box[0] = value;
			seen.countDown();
		};

		InstrumentationRegistry.getInstrumentation().runOnMainSync(
				() -> data.observeForever(observer));
		assertTrue("no value arrived", seen.await(10, TimeUnit.SECONDS));
		InstrumentationRegistry.getInstrumentation().runOnMainSync(
				() -> data.removeObserver(observer));

		@SuppressWarnings("unchecked")
		T value = (T) box[0];
		return value;
	}

	// ── R17, R18, R20, R14: the importers, which use the app's own database ──

	private static Uri file(Context context, String name, String text) throws Exception {
		File out = new File(context.getCacheDir(), name);
		try (FileWriter writer = new FileWriter(out)) {
			writer.write(text);
		}
		return Uri.fromFile(out);
	}

	private MainDatabase appDatabase(Context context) {
		MainDatabase db = MainDatabase.getInstance(context);
		db.clearAllTables();
		seedInto(db);
		return db;
	}

	@Test
	public void r17_r20_aNewRollNumberWithAnotherStudentsEmailIsNotThatStudent()
			throws Exception {

		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		MainDatabase db = appDatabase(context);

		// Alice's sibling, who shares the family address, under a roll number nobody holds.
		CsvImporter.Result result = CsvImporter.importStudents(context, file(context, "r17.csv",
				"rollNo,name,pronouns,year,stream,majorDept,semester,email\n"
						+ "PH-9,ANITA,she/her,SY,BSc,PHYSICS,3,alice@sies.edu.in\n"),
				SESSION, null);

		String aliceNow = StagingFilesCount.text(db, "SELECT s.name || '|' || y.rollNo "
				+ "FROM students s JOIN student_year y ON y.studentID = s.studentID "
				+ "WHERE s.studentID = " + alice);

		assertEquals("R17: Alice was renamed and moved to the new roll number (summary said "
						+ result.created + " created, " + result.updated + " updated, errors "
						+ result.errors + ")", "ALICE|PH1", aliceNow);
	}

	@Test
	public void r20_aRowThatLandedOnAnExistingStudentIsNotCountedAsCreated() throws Exception {

		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		MainDatabase db = appDatabase(context);
		long before = StagingFilesCount.count(db, "SELECT COUNT(*) FROM students");

		CsvImporter.Result result = CsvImporter.importStudents(context, file(context, "r20.csv",
				"rollNo,name,pronouns,year,stream,majorDept,semester,email\n"
						+ "PH-9,ANITA,she/her,SY,BSc,PHYSICS,3,alice@sies.edu.in\n"),
				SESSION, null);

		long after = StagingFilesCount.count(db, "SELECT COUNT(*) FROM students");

		assertEquals("the summary says " + result.created + " created, and the number of "
				+ "students went from " + before + " to " + after, after - before,
				result.created);
	}

	@Test
	public void r18_aFileWithNoEmailOrMinorColumnDoesNotEraseWhatIsStored() throws Exception {

		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		MainDatabase db = appDatabase(context);

		CsvImporter.importStudents(context, file(context, "r18.csv",
				"rollNo,name,pronouns,year,stream,majorDept,semester\n"
						+ "PH-1,ALICE,they/them,SY,BSc,PHYSICS,3\n"), SESSION, null);

		assertEquals("the email and the minor on record are gone",
				"alice@sies.edu.in|CHEMISTRY", StagingFilesCount.text(db,
						"SELECT COALESCE(s.email, '(none)') || '|' || COALESCE(y.minorDept, "
								+ "'(none)') FROM students s JOIN student_year y "
								+ "ON y.studentID = s.studentID WHERE s.studentID = " + alice));
	}

	@Test
	public void r14_aSheetIsReadInTheCollegesTimeWhateverThePhoneIsSetTo() throws Exception {

		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		appDatabase(context);

		TimeZone before = TimeZone.getDefault();
		TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));

		try {
			AttendanceRecordImporter.Plan plan = AttendanceRecordImporter.plan(context,
					file(context, "r14.csv", "Course code,PH-201\nDate,14/07/2025\n"
							+ "Start time,09:00\nEnd time,10:00\nPH-1,P\nPH-2,AB\n"), null);

			assertTrue("the sheet was not planned: " + plan.errors, !plan.lectures.isEmpty());
			ZonedDateTime start = plan.lectures.get(0).start;
			assertNotNull("no start time: " + plan.lectures.get(0).problems, start);

			// The server reads the same file as 09:00 in Asia/Kolkata.
			assertEquals("the phone and the server would file this class at different "
							+ "instants", ZonedDateTime.of(2025, 7, 14, 9, 0, 0, 0, IST).toInstant(),
					start.toInstant());
		} finally {
			TimeZone.setDefault(before);
		}
	}

	/** Small reads straight off a Room database, for the assertions above. */
	private static final class StagingFilesCount {

		static long count(MainDatabase db, String sql) {
			try (android.database.Cursor cursor = db.getOpenHelper().getReadableDatabase()
					.query(sql)) {
				cursor.moveToFirst();
				return cursor.getLong(0);
			}
		}

		static String text(MainDatabase db, String sql) {
			try (android.database.Cursor cursor = db.getOpenHelper().getReadableDatabase()
					.query(sql)) {
				return cursor.moveToFirst() ? cursor.getString(0) : "(no row)";
			}
		}
	}
}
