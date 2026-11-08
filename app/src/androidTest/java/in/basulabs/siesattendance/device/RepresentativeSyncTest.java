package in.basulabs.siesattendance.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import static in.basulabs.siesattendance.device.support.Fixture.COURSE_CHEM;
import static in.basulabs.siesattendance.device.support.Fixture.COURSE_PHYS;
import static in.basulabs.siesattendance.device.support.Fixture.IST;
import static in.basulabs.siesattendance.device.support.Fixture.LECTURE_CHEM;
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
import in.basulabs.siesattendance.backend.EntityStudent;
import in.basulabs.siesattendance.backend.sync.SyncSignIn;
import in.basulabs.siesattendance.device.support.Fixture;
import in.basulabs.siesattendance.device.support.ServerSync;
import in.basulabs.siesattendance.device.support.ServerSync.Totals;
import in.basulabs.siesattendance.device.support.TwoPhones;

/**
 * A class representative: student 2001, signed in through the student door, appointed by
 * PH-101's professor through the course's representatives endpoint for the 2025 session.
 * <p>
 * A representative takes the register for the course they represent, in the session they were
 * appointed for, and that is all: not another course, not another session, not the roll.
 * The script checks the server afterwards.
 */
@RunWith(AndroidJUnit4.class)
public class RepresentativeSyncTest extends TwoPhones {

	@BeforeClass
	public static void signInAsTheRepresentative() throws IOException {

		ServerSync.signIn("siesRepEmail", "siesRepPassword", SyncSignIn.Door.STUDENT,
				AccountKind.STUDENT);
	}

	@Test
	public void aPullBringsTheCourseTheyRepresentAndNothingOfAnother() {

		assertNotNull(phoneA.daoCourse().getByIdSync(COURSE_PHYS));
		assertNull(phoneA.daoCourse().getByIdSync(COURSE_CHEM));
		assertNull(phoneA.daoLecture().getByIdSync(LECTURE_CHEM));
	}

	@Test
	public void theyTakeTheRegisterForTheirCourseAndItLands() throws IOException {

		ZonedDateTime start = ZonedDateTime.of(2025, 9, 1, 9, 0, 0, 0, IST);
		long lecture = phoneA.daoLecture().insert(
				new EntityLecture(COURSE_PHYS, null, start, start.plusHours(1), tag));
		phoneA.daoAttendance().saveSheet(lecture,
				List.of(new EntityAttendance(lecture, STUDENT_PHYS, true)));

		Totals pushed = pushAll(phoneA);

		assertEquals(pushed.toString(), 0, pushed.refused());
		assertEquals("the lecture and its one mark", 2, pushed.sent);

		pullAll(phoneB);
		assertEquals(List.of(STUDENT_PHYS), phoneB.daoAttendance().getPresentIdsSync(lecture));
	}

	@Test
	public void notInASessionTheyWereNotAppointedFor() throws IOException {

		// August 2026: the 2026 session. The appointment is for 2025.
		ZonedDateTime start = ZonedDateTime.of(2026, 8, 3, 9, 0, 0, 0, IST);
		phoneA.daoLecture().insert(
				new EntityLecture(COURSE_PHYS, null, start, start.plusHours(1), tag));

		Totals pushed = pushAll(phoneA);

		assertEquals(pushed.toString(), 0, pushed.sent);
		assertEquals(pushed.toString(), 1, pushed.refused());
	}

	@Test
	public void notForACourseTheyDoNotRepresent() throws IOException {

		Fixture.plantChemistryCourse(phoneA);

		ZonedDateTime start = ZonedDateTime.of(2025, 9, 2, 9, 0, 0, 0, IST);
		phoneA.daoLecture().insert(
				new EntityLecture(COURSE_CHEM, null, start, start.plusHours(1), tag));

		Totals pushed = pushAll(phoneA);

		assertEquals(pushed.toString(), 0, pushed.sent);
		assertEquals(pushed.toString(), 1, pushed.refused());
	}

	@Test
	public void notTheRoll() throws IOException {

		phoneA.daoStudent().insertStudent(
				new EntityStudent("REP MADE " + tag, "they/them", null));

		Totals pushed = pushAll(phoneA);

		assertEquals(pushed.toString(), 0, pushed.sent);
		assertEquals(pushed.toString(), 1, pushed.refused());
	}
}
