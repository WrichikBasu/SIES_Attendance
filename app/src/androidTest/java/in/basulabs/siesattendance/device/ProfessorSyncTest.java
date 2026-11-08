package in.basulabs.siesattendance.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import static in.basulabs.siesattendance.device.support.Fixture.COURSE_CHEM;
import static in.basulabs.siesattendance.device.support.Fixture.COURSE_PHYS;
import static in.basulabs.siesattendance.device.support.Fixture.IST;
import static in.basulabs.siesattendance.device.support.Fixture.LECTURE_CHEM;
import static in.basulabs.siesattendance.device.support.Fixture.LECTURE_PHYS;
import static in.basulabs.siesattendance.device.support.Fixture.STUDENT_CHEM;
import static in.basulabs.siesattendance.device.support.Fixture.STUDENT_PHYS;
import static in.basulabs.siesattendance.device.support.ServerSync.pullAll;
import static in.basulabs.siesattendance.device.support.ServerSync.pushAll;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.List;

import in.basulabs.siesattendance.backend.AccountKind;
import in.basulabs.siesattendance.backend.EntityAttendance;
import in.basulabs.siesattendance.backend.EntityLecture;
import in.basulabs.siesattendance.backend.EntityOutbox;
import in.basulabs.siesattendance.backend.EntityStudent;
import in.basulabs.siesattendance.backend.sync.SyncSignIn;
import in.basulabs.siesattendance.device.support.Fixture;
import in.basulabs.siesattendance.device.support.ServerSync;
import in.basulabs.siesattendance.device.support.ServerSync.Totals;
import in.basulabs.siesattendance.device.support.TwoPhones;

/**
 * Row-level security as a professor's phone meets it: P PHYS, who teaches PH-101.
 * <p>
 * {@link DeviceSyncTest} signs in as an administrator, who may see and write everything, so it
 * shows the wire works and nothing about who may do what. Here the account is an ordinary
 * Physics professor, made through the accounts API the console uses, and the server's policies
 * are what answer. The server's {@code SyncTest} checks the same policies with hand-made JSON;
 * this is the phone's own intents, its own handling of the answer, and its own pulled copy.
 * <p>
 * The keys are {@link Fixture}'s. After the phone is done the script checks the server itself,
 * so a refusal is shown to have kept the row off the server and not only to have been
 * reported.
 */
@RunWith(AndroidJUnit4.class)
public class ProfessorSyncTest extends TwoPhones {

	@BeforeClass
	public static void signInAsAProfessor() throws IOException {

		ServerSync.signIn("siesProfEmail", "siesProfPassword", SyncSignIn.Door.STAFF,
				AccountKind.PROFESSOR);
	}

	@Test
	public void aPullBringsTheirDepartmentAndNothingOfAnother() {

		assertNotNull("their own course", phoneA.daoCourse().getByIdSync(COURSE_PHYS));
		assertNotNull("their own department's roll",
				phoneA.daoStudent().getStudentSync(STUDENT_PHYS));

		assertNull("not Chemistry's course", phoneA.daoCourse().getByIdSync(COURSE_CHEM));
		assertNull("not Chemistry's student", phoneA.daoStudent().getStudentSync(STUDENT_CHEM));
		assertNull("not Chemistry's lecture", phoneA.daoLecture().getByIdSync(LECTURE_CHEM));
		assertTrue("nor its marks",
				phoneA.daoAttendance().getMarkedIdsSync(LECTURE_CHEM).isEmpty());
	}

	@Test
	public void aRegisterForTheirOwnCourseIsAcceptedAndReachesTheirOtherPhone()
			throws IOException {

		ZonedDateTime start = ZonedDateTime.of(2025, 8, 4, 10, 0, 0, 0, IST);
		long lecture = phoneA.daoLecture().insert(
				new EntityLecture(COURSE_PHYS, null, start, start.plusHours(1), tag));
		phoneA.daoAttendance().saveSheet(lecture,
				List.of(new EntityAttendance(lecture, STUDENT_PHYS, true)));

		Totals pushed = pushAll(phoneA);

		assertEquals(pushed.toString(), 0, pushed.refused());
		assertEquals("the lecture and its one mark", 2, pushed.sent);
		assertEquals(0, phoneA.daoOutbox().countPendingSync());

		pullAll(phoneB);
		assertNotNull(phoneB.daoLecture().getByIdSync(lecture));
		assertEquals(List.of(STUDENT_PHYS), phoneB.daoAttendance().getPresentIdsSync(lecture));
	}

	/**
	 * The control for every refused correction elsewhere: the fixture's own lecture, keyed as
	 * a server row is, can be corrected by the account that may. Without this, a colleague's
	 * or the office's refused correction could be the row's key being refused rather than the
	 * policies, and those tests would pass for the wrong reason. Put back afterwards, because
	 * the later stages expect the mark as the fixture made it.
	 */
	@Test
	public void theyCorrectTheirCoursesExistingRegisterAndPutItBack() throws IOException {

		phoneA.daoAttendance().saveSheet(LECTURE_PHYS,
				List.of(new EntityAttendance(LECTURE_PHYS, STUDENT_PHYS, false)));
		Totals corrected = pushAll(phoneA);
		assertEquals(corrected.toString(), 0, corrected.refused());
		assertEquals(1, corrected.sent);

		pullAll(phoneB);
		assertTrue("absent on the server now",
				phoneB.daoAttendance().getPresentIdsSync(LECTURE_PHYS).isEmpty());

		phoneA.daoAttendance().saveSheet(LECTURE_PHYS,
				List.of(new EntityAttendance(LECTURE_PHYS, STUDENT_PHYS, true)));
		Totals restored = pushAll(phoneA);
		assertEquals(restored.toString(), 0, restored.refused());

		pullAll(phoneB);
		assertEquals(List.of(STUDENT_PHYS), phoneB.daoAttendance().getPresentIdsSync(LECTURE_PHYS));
	}

	@Test
	public void aLectureOnAnotherDepartmentsCourseIsHeldBackNotLost() throws IOException {

		Fixture.plantChemistryCourse(phoneA);

		ZonedDateTime start = ZonedDateTime.of(2025, 8, 5, 11, 0, 0, 0, IST);
		long lecture = phoneA.daoLecture().insert(
				new EntityLecture(COURSE_CHEM, null, start, start.plusHours(1), tag));

		Totals pushed = pushAll(phoneA);

		assertEquals("nothing of it landed", 0, pushed.sent);
		assertEquals("refused, but not for good: the server cannot tell 'not yours' from "
				+ "'not arrived yet'", 1, pushed.rejected);
		assertEquals(0, pushed.dropped);

		List<EntityOutbox> left = phoneA.daoOutbox().pendingSync(10);
		assertEquals("kept in the queue rather than lost", 1, left.size());
		assertEquals("lectures", left.get(0).tableName);
		assertEquals(lecture, left.get(0).key1);
		assertTrue("and counted", left.get(0).attempts >= 1);
		assertNotNull("with the reason against it", left.get(0).lastError);
	}

	@Test
	public void theRollIsTheOfficesAndAProfessorsStudentIsRefusedForGood() throws IOException {

		long student = phoneA.daoStudent().insertStudent(
				new EntityStudent("NOT ON THE ROLL " + tag, "they/them", null));

		Totals pushed = pushAll(phoneA);

		assertEquals(0, pushed.sent);
		assertEquals("refused for good: retrying would never empty the queue",
				1, pushed.dropped);
		assertFalse("and the phone has a sentence to show", pushed.refusals.isEmpty());
		assertEquals(0, phoneA.daoOutbox().countPendingSync());

		pullAll(phoneB);
		assertNull("the server does not have it", phoneB.daoStudent().getStudentSync(student));
	}
}
