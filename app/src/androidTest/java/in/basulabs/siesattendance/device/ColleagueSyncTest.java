package in.basulabs.siesattendance.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import static in.basulabs.siesattendance.device.support.Fixture.COURSE_CHEM;
import static in.basulabs.siesattendance.device.support.Fixture.COURSE_PHYS;
import static in.basulabs.siesattendance.device.support.Fixture.IST;
import static in.basulabs.siesattendance.device.support.Fixture.LECTURE_PHYS;
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
import in.basulabs.siesattendance.backend.sync.SyncSignIn;
import in.basulabs.siesattendance.device.support.ServerSync;
import in.basulabs.siesattendance.device.support.ServerSync.Totals;
import in.basulabs.siesattendance.device.support.TwoPhones;

/**
 * Q PHYS: a Physics professor who teaches nothing.
 * <p>
 * A professor reads their whole department — its courses, its lectures and their registers —
 * because a department's attendance is the department's business. Reading is all: changing a
 * course's lectures or marks is for the professors who teach it. The server's
 * {@code SyncTest} has the same rule as JSON; the script checks the server afterwards.
 */
@RunWith(AndroidJUnit4.class)
public class ColleagueSyncTest extends TwoPhones {

	@BeforeClass
	public static void signInAsAColleagueWhoTeachesNothing() throws IOException {

		ServerSync.signIn("siesColleagueEmail", "siesColleaguePassword",
				SyncSignIn.Door.STAFF, AccountKind.PROFESSOR);
	}

	@Test
	public void theyReadTheirDepartmentsCourseItsLecturesAndItsRegister() {

		assertNotNull("a colleague's course in their department",
				phoneA.daoCourse().getByIdSync(COURSE_PHYS));
		assertNotNull("its lecture", phoneA.daoLecture().getByIdSync(LECTURE_PHYS));
		assertEquals("and its register", List.of(STUDENT_PHYS),
				phoneA.daoAttendance().getPresentIdsSync(LECTURE_PHYS));

		assertNull("but not another department's",
				phoneA.daoCourse().getByIdSync(COURSE_CHEM));
	}

	@Test
	public void theyCannotCorrectAColleaguesRegister() throws IOException {

		phoneA.daoAttendance().saveSheet(LECTURE_PHYS,
				List.of(new EntityAttendance(LECTURE_PHYS, STUDENT_PHYS, false)));

		Totals pushed = pushAll(phoneA);

		assertEquals(pushed.toString(), 0, pushed.sent);
		assertEquals(pushed.toString(), 1, pushed.refused());

		pullAll(phoneB);
		assertEquals("the server still has them present", List.of(STUDENT_PHYS),
				phoneB.daoAttendance().getPresentIdsSync(LECTURE_PHYS));
	}

	@Test
	public void theyCannotTakeARegisterOnACourseTheyDoNotTeach() throws IOException {

		ZonedDateTime start = ZonedDateTime.of(2025, 8, 6, 12, 0, 0, 0, IST);
		long lecture = phoneA.daoLecture().insert(
				new EntityLecture(COURSE_PHYS, null, start, start.plusHours(1), tag));

		Totals pushed = pushAll(phoneA);

		assertEquals(pushed.toString(), 0, pushed.sent);
		assertEquals(pushed.toString(), 1, pushed.refused());

		pullAll(phoneB);
		assertNull(phoneB.daoLecture().getByIdSync(lecture));
	}
}
