package in.basulabs.siesattendance.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import static in.basulabs.siesattendance.device.support.Fixture.COURSE_PHYS;
import static in.basulabs.siesattendance.device.support.Fixture.IST;
import static in.basulabs.siesattendance.device.support.Fixture.LECTURE_PHYS;
import static in.basulabs.siesattendance.device.support.ServerSync.pushAll;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.time.ZonedDateTime;

import in.basulabs.siesattendance.backend.AccountKind;
import in.basulabs.siesattendance.backend.EntityLecture;
import in.basulabs.siesattendance.backend.sync.SyncSignIn;
import in.basulabs.siesattendance.device.support.Fixture;
import in.basulabs.siesattendance.device.support.ServerSync;
import in.basulabs.siesattendance.device.support.ServerSync.Totals;
import in.basulabs.siesattendance.device.support.TwoPhones;

/**
 * A student whose appointment as PH-101's representative has ended.
 * <p>
 * Run twice by the script with different accounts: student 2003, whose appointment PH-101's
 * professor revoked through the representatives endpoint, and student 2004, whose appointment
 * expired. Either way {@code actor_is_cr_of} and {@code actor_represents} stop answering yes,
 * and with them goes everything the appointment gave: reading the course, its lectures and
 * its marks, and taking its register. A phone that still holds the course from before keeps
 * offering the register, and the server keeps refusing it. The script checks the server wrote
 * nothing for either.
 */
@RunWith(AndroidJUnit4.class)
public class FormerRepresentativeSyncTest extends TwoPhones {

	@BeforeClass
	public static void signInAsAFormerRepresentative() throws IOException {
		ServerSync.signIn("siesFormerEmail", "siesFormerPassword", SyncSignIn.Door.STUDENT,
				AccountKind.STUDENT);
	}

	@Test
	public void aPullNoLongerBringsTheCourseOrItsRegister() {

		assertNull("not the course", phoneA.daoCourse().getByIdSync(COURSE_PHYS));
		assertNull("not its lecture", phoneA.daoLecture().getByIdSync(LECTURE_PHYS));
		assertTrue("not its marks",
				phoneA.daoAttendance().getMarkedIdsSync(LECTURE_PHYS).isEmpty());
	}

	@Test
	public void aRegisterFromBeforeTheAppointmentEndedIsRefused() throws IOException {

		// The phone still has the course from when they represented it.
		Fixture.plantPhysicsCourse(phoneA);

		ZonedDateTime start = ZonedDateTime.of(2025, 9, 4, 9, 0, 0, 0, IST);
		phoneA.daoLecture().insert(
				new EntityLecture(COURSE_PHYS, null, start, start.plusHours(1), tag));

		Totals pushed = pushAll(phoneA);

		assertEquals(pushed.toString(), 0, pushed.sent);
		assertEquals(pushed.toString(), 1, pushed.refused());
	}
}
