package in.basulabs.siesattendance.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import static in.basulabs.siesattendance.device.support.Fixture.SESSION;
import static in.basulabs.siesattendance.device.support.Fixture.STUDENT_CHEM;
import static in.basulabs.siesattendance.device.support.Fixture.STUDENT_PHYS;
import static in.basulabs.siesattendance.device.support.ServerSync.pullAll;
import static in.basulabs.siesattendance.device.support.ServerSync.pushAll;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

import in.basulabs.siesattendance.backend.AccountKind;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Division;
import in.basulabs.siesattendance.backend.EntityStudent;
import in.basulabs.siesattendance.backend.EntityStudentYear;
import in.basulabs.siesattendance.backend.MainDatabase;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;
import in.basulabs.siesattendance.backend.sync.SyncSignIn;
import in.basulabs.siesattendance.device.support.ServerSync;
import in.basulabs.siesattendance.device.support.ServerSync.Totals;
import in.basulabs.siesattendance.device.support.TwoPhones;

/**
 * The head of Physics, H PHYS, whose headship comes from their designation when the console
 * makes their account: the server grants HOD of their own department for that, and nothing is
 * granted by hand.
 * <p>
 * A head keeps their department's roll (V20): they add students to it and take them off it,
 * where a plain professor only reads it. The authority is the department, and the enrolment is
 * what says whose student somebody is, so the head of Physics may neither put somebody on
 * Chemistry's roll nor take Chemistry's student off it. The server's {@code SyncTest} has the
 * same rules as JSON; the script checks the server afterwards.
 */
@RunWith(AndroidJUnit4.class)
public class HeadOfDepartmentSyncTest extends TwoPhones {

	@BeforeClass
	public static void signInAsTheHeadOfPhysics() throws IOException {

		ServerSync.signIn("siesHeadEmail", "siesHeadPassword", SyncSignIn.Door.STAFF,
				AccountKind.PROFESSOR);
	}

	@Test
	public void aPullBringsTheirDepartmentsRollAndNotAnothers() {
		assertNotNull(phoneA.daoStudent().getStudentSync(STUDENT_PHYS));
		assertNull(phoneA.daoStudent().getStudentSync(STUDENT_CHEM));
	}

	@Test
	public void theHeadPutsAStudentOnTheirRollAndItReachesTheirOtherPhone()
			throws IOException {

		long student = enrol(phoneA, "HEAD ADDS " + tag, "HA-" + tag, Department.PHYSICS);

		Totals pushed = pushAll(phoneA);

		assertEquals(pushed.toString(), 0, pushed.refused());
		assertEquals("the student and their enrolment", 2, pushed.sent);

		pullAll(phoneB);
		assertNotNull(phoneB.daoStudent().getStudentSync(student));
		assertEquals("HA-" + tag,
				phoneB.daoStudent().getEnrolmentSync(student, SESSION).rollNo);
	}

	@Test
	public void theHeadTakesAStudentOffTheirRoll() throws IOException {

		long student = enrol(phoneA, "HEAD REMOVES " + tag, "HR-" + tag, Department.PHYSICS);
		assertEquals(0, pushAll(phoneA).refused());

		// Enrolment first, which is the order the foreign key wants.
		phoneA.daoStudent().deleteEnrolment(student, SESSION);
		delete(phoneA, "students", "studentID", student);

		Totals pushed = pushAll(phoneA);
		assertEquals(pushed.toString(), 0, pushed.refused());
		assertEquals(2, pushed.sent);

		pullAll(phoneB);
		assertNull("gone from the server", phoneB.daoStudent().getStudentSync(student));
	}

	@Test
	public void theHeadOfPhysicsCannotPutSomebodyOnChemistrysRoll() throws IOException {

		long student = enrol(phoneA, "HEAD CHEM " + tag, "HC-" + tag, Department.CHEMISTRY);

		Totals pushed = pushAll(phoneA);

		// A person with no enrolment is on nobody's roll, so the bare row is admitted; the
		// enrolment that would put them on Chemistry's is not.
		assertEquals(pushed.toString(), 1, pushed.sent);
		assertEquals(pushed.toString(), 1, pushed.refused());

		pullAll(phoneB);
		assertNull("no Chemistry enrolment reached the server",
				phoneB.daoStudent().getEnrolmentSync(student, SESSION));
	}

	@Test
	public void theHeadOfPhysicsCannotTakeChemistrysStudentOff() throws IOException {

		// Chemistry's student was never sent to this phone; put it here unqueued and delete
		// it, as a stale phone might.
		ServerSync.withoutQueueing(phoneA, () -> {
			EntityStudent chemist = new EntityStudent("CHEM ONE", "he/him", null);
			chemist.studentID = STUDENT_CHEM;
			phoneA.daoStudent().insertStudent(chemist);
		});
		delete(phoneA, "students", "studentID", STUDENT_CHEM);

		Totals pushed = pushAll(phoneA);

		assertEquals(pushed.toString(), 0, pushed.sent);
		assertEquals(pushed.toString(), 1, pushed.refused());
	}

	private static long enrol(@NonNull MainDatabase db, @NonNull String name,
	                          @NonNull String roll, @NonNull Department major) {

		long id = db.daoStudent().insertStudent(new EntityStudent(name, "they/them", null));
		db.daoStudent().insertStudentYear(new EntityStudentYear(id, SESSION, roll, Year.FY,
				Stream.BSc, major, null, null, 1, Division.A));
		return id;
	}
}
