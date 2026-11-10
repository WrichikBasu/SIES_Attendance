package in.basulabs.siesattendance.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import static in.basulabs.siesattendance.device.support.Fixture.COURSE_CHEM;
import static in.basulabs.siesattendance.device.support.Fixture.COURSE_PHYS;
import static in.basulabs.siesattendance.device.support.Fixture.IST;
import static in.basulabs.siesattendance.device.support.Fixture.LECTURE_CHEM;
import static in.basulabs.siesattendance.device.support.Fixture.LECTURE_PHYS;
import static in.basulabs.siesattendance.device.support.Fixture.PROF_CHEM;
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
import java.time.ZonedDateTime;
import java.util.List;

import in.basulabs.siesattendance.backend.AccountKind;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Designations;
import in.basulabs.siesattendance.backend.Division;
import in.basulabs.siesattendance.backend.EntityAttendance;
import in.basulabs.siesattendance.backend.EntityCourse;
import in.basulabs.siesattendance.backend.EntityLecture;
import in.basulabs.siesattendance.backend.EntityProfessor;
import in.basulabs.siesattendance.backend.EntityStudent;
import in.basulabs.siesattendance.backend.EntityStudentYear;
import in.basulabs.siesattendance.backend.MainDatabase;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;
import in.basulabs.siesattendance.backend.sync.SyncSignIn;
import in.basulabs.siesattendance.backend.util.CanonicalCode;
import in.basulabs.siesattendance.device.support.ServerSync;
import in.basulabs.siesattendance.device.support.ServerSync.Totals;
import in.basulabs.siesattendance.device.support.TwoPhones;

/**
 * The office: a staff account holding the OFFICE role, made through the accounts API.
 * <p>
 * The office keeps the college's records — every department's roll, the professors and
 * their designations, the courses — and reads everything. What it does not do is take
 * attendance: lectures and marks are read-only to it ("office and auditor read everything and
 * change nothing", V6). The server's {@code SyncTest} has the same rules as JSON; the script
 * checks the server afterwards, including that setting a professor's designation to HOD on a
 * phone made them head of their department.
 */
@RunWith(AndroidJUnit4.class)
public class OfficeSyncTest extends TwoPhones {

	@BeforeClass
	public static void signInAsTheOffice() throws IOException {
		ServerSync.signIn("siesOfficeEmail", "siesOfficePassword", SyncSignIn.Door.STAFF,
				AccountKind.STAFF);
	}

	@Test
	public void aPullBringsEveryDepartment() {

		assertNotNull(phoneA.daoStudent().getStudentSync(STUDENT_PHYS));
		assertNotNull(phoneA.daoStudent().getStudentSync(STUDENT_CHEM));
		assertNotNull(phoneA.daoCourse().getByIdSync(COURSE_PHYS));
		assertNotNull(phoneA.daoCourse().getByIdSync(COURSE_CHEM));
		assertEquals(List.of(STUDENT_PHYS), phoneA.daoAttendance().getPresentIdsSync(LECTURE_PHYS));
		assertEquals(List.of(STUDENT_CHEM), phoneA.daoAttendance().getPresentIdsSync(LECTURE_CHEM));
	}

	@Test
	public void theOfficePutsAStudentOnAnyDepartmentsRollAndTakesOneOff() throws IOException {

		String keptRoll = "OA-" + tag;
		long kept = enrol(phoneA, "OFFICE ADDS " + tag, keptRoll, Department.CHEMISTRY);
		long removed = enrol(phoneA, "OFFICE REMOVES " + tag, "OR-" + tag, Department.PHYSICS);

		Totals added = pushAll(phoneA);
		assertEquals(added.toString(), 0, added.refused());
		assertEquals("two students and two enrolments", 4, added.sent);

		phoneA.daoStudent().deleteEnrolment(removed, SESSION);
		delete(phoneA, "students", "studentID", removed);

		Totals taken = pushAll(phoneA);
		assertEquals(taken.toString(), 0, taken.refused());
		assertEquals(2, taken.sent);

		pullAll(phoneB);
		// Canonicalised on the way: the server does that to every roll number written to
		// student_year (V57), and this fixture writes one as typed. See enrol().
		assertEquals(CanonicalCode.of(keptRoll),
				phoneB.daoStudent().getEnrolmentSync(kept, SESSION).rollNo);
		assertNull(phoneB.daoStudent().getStudentSync(removed));
	}

	@Test
	public void theOfficeMakesAProfessorHeadByTheirDesignation() throws IOException {

		EntityProfessor chemist = phoneA.daoProfessor().getByIdSync(PROF_CHEM);
		assertNotNull(chemist);
		chemist.designation = Designations.HOD;
		phoneA.daoProfessor().update(chemist);

		Totals pushed = pushAll(phoneA);

		assertEquals(pushed.toString(), 0, pushed.refused());
		assertEquals(1, pushed.sent);
		// That C CHEM now heads Chemistry is the server's to say: the script checks user_role.
	}

	@Test
	public void theOfficeEditsACourse() throws IOException {

		EntityCourse course = phoneA.daoCourse().getByIdSync(COURSE_PHYS);
		course.remarks = "office " + tag;
		phoneA.daoCourse().update(course);

		Totals pushed = pushAll(phoneA);
		assertEquals(pushed.toString(), 0, pushed.refused());

		pullAll(phoneB);
		assertEquals("office " + tag, phoneB.daoCourse().getByIdSync(COURSE_PHYS).remarks);
	}

	@Test
	public void theOfficeDoesNotTakeARegister() throws IOException {

		ZonedDateTime start = ZonedDateTime.of(2025, 9, 3, 9, 0, 0, 0, IST);
		long lecture = phoneA.daoLecture().insert(
				new EntityLecture(COURSE_PHYS, null, start, start.plusHours(1), tag));

		Totals pushed = pushAll(phoneA);

		assertEquals(pushed.toString(), 0, pushed.sent);
		assertEquals(pushed.toString(), 1, pushed.refused());

		pullAll(phoneB);
		assertNull(phoneB.daoLecture().getByIdSync(lecture));
	}

	@Test
	public void theOfficeDoesNotCorrectAMark() throws IOException {

		phoneA.daoAttendance().saveSheet(LECTURE_PHYS,
				List.of(new EntityAttendance(LECTURE_PHYS, STUDENT_PHYS, false)));

		Totals pushed = pushAll(phoneA);

		assertEquals(pushed.toString(), 0, pushed.sent);
		assertEquals(pushed.toString(), 1, pushed.refused());

		pullAll(phoneB);
		assertEquals("still present on the server", List.of(STUDENT_PHYS),
				phoneB.daoAttendance().getPresentIdsSync(LECTURE_PHYS));
	}

	/**
	 * A student and their enrolment, written straight to the tables.
	 * <p>
	 * Not through {@code DaoStudent}'s upsert, which canonicalises the roll number before it
	 * stores it, so the roll goes out over the wire as it was typed — the shape a phone on a
	 * build older than V57 pushes. The server canonicalises it on arrival and the other phone
	 * pulls that spelling, which is why the assertion above expects
	 * {@link CanonicalCode#of(String)} of what was written here and not the string itself.
	 */
	private static long enrol(@NonNull MainDatabase db, @NonNull String name,
	                          @NonNull String roll, @NonNull Department major) {

		long id = db.daoStudent().insertStudent(new EntityStudent(name, "they/them", null));
		db.daoStudent().insertStudentYear(new EntityStudentYear(id, SESSION, roll, Year.FY,
				Stream.BSc, major, null, null, 1, Division.A));
		return id;
	}
}
