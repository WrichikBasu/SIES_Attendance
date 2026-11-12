package in.basulabs.siesattendance.device.support;

import static org.junit.Assert.assertNull;

import androidx.annotation.NonNull;

import java.time.ZoneId;

import in.basulabs.siesattendance.backend.CourseType;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.EntityCourse;
import in.basulabs.siesattendance.backend.EntityProfessor;
import in.basulabs.siesattendance.backend.MainDatabase;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.StructuredId;
import in.basulabs.siesattendance.backend.Year;

/**
 * The keys of the fixture {@code server/tools/verify-device-sync.sh} loads on the server before
 * any phone signs in. The script is where it is written; this is the phone's copy of its keys,
 * and the two must agree. The numbers below are the rows' names; their keys are
 * {@link #key}s of them.
 * <pre>
 *   professors  1001 P PHYS  PHYSICS    Assistant Professor  teaches PH-101
 *               1002 C CHEM  CHEMISTRY  Assistant Professor  teaches CH-101; the office
 *                                                             makes them HOD from a phone
 *               1003 Q PHYS  PHYSICS    Assistant Professor  teaches nothing
 *               1004 H PHYS  PHYSICS    HOD                  heads Physics
 *   students    2001 PHYSICS major, on PH-101's roll through batch 5001, and its
 *                    class representative for 2025
 *               2002 CHEMISTRY major
 *               2003 PHYSICS major on PH-101's roll, appointed and then revoked
 *               2004 PHYSICS major on PH-101's roll, appointed and then expired
 *   courses     3001 PH-101 (PHYSICS), 3002 CH-101 (CHEMISTRY)
 *   batches     5001 FY PH, linked to PH-101
 *   lectures    4001 on PH-101, 2001 present
 *               4002 on CH-101, 2002 present
 *   session     2025: 1 June 2025 to 31 May 2026
 * </pre>
 */
public final class Fixture {

	public static final int SESSION = 2025;

	public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	public static final long PROF_PHYS = key(1001);

	public static final long PROF_CHEM = key(1002);

	public static final long PROF_COLLEAGUE = key(1003);

	public static final long PROF_HEAD = key(1004);

	public static final long STUDENT_PHYS = key(2001);

	public static final long STUDENT_CHEM = key(2002);

	public static final long STUDENT_REVOKED = key(2003);

	public static final long STUDENT_EXPIRED = key(2004);

	public static final long COURSE_PHYS = key(3001);

	public static final long COURSE_CHEM = key(3002);

	public static final long LECTURE_PHYS = key(4001);

	public static final long LECTURE_CHEM = key(4002);

	public static final long BATCH_PHYS = key(5001);

	private Fixture() {
	}

	/**
	 * Fixture row {@code n}'s key: a structured key under the server's own node, as a row a
	 * backup import or roster CSV brought would have. The script computes the same, as
	 * 2^55 + n * 2^16. A plain {@code n} would be a key from before the ID scheme, which the
	 * server refuses to amend from a phone, so every edit of a fixture row would fail on its
	 * key before the policies were asked -- and a test expecting a refusal would pass for the
	 * wrong reason.
	 */
	private static long key(long n) {
		return StructuredId.compose(StructuredId.MIN_COUNTER + n, StructuredId.SERVER_NODE);
	}

	/**
	 * Puts Chemistry's CH-101 on a phone whose account was never sent it, without queueing it,
	 * so a lecture can be written against it locally and offered to the server.
	 */
	public static void plantChemistryCourse(@NonNull MainDatabase db) {
		plantCourse(db, COURSE_CHEM, "CH-101", "Inorganic", Department.CHEMISTRY,
				PROF_CHEM, "C CHEM", "c.chem@sies.edu.in");
	}

	/** The same for Physics' PH-101, for an account that can no longer read it. */
	public static void plantPhysicsCourse(@NonNull MainDatabase db) {
		plantCourse(db, COURSE_PHYS, "PH-101", "Mechanics", Department.PHYSICS,
				PROF_PHYS, "P PHYS", "p.phys@sies.edu.in");
	}

	private static void plantCourse(@NonNull MainDatabase db, long courseId,
	                                @NonNull String code, @NonNull String topic,
	                                @NonNull Department department, long profId,
	                                @NonNull String profName, @NonNull String profEmail) {

		ServerSync.withoutQueueing(db, () -> {

			// Professors may be shared scaffolding that a pull already brought.
			if (db.daoProfessor().getByIdSync(profId) == null) {
				EntityProfessor professor = new EntityProfessor(profName, profEmail,
						department, "Assistant Professor");
				professor.profID = profId;
				db.daoProfessor().insert(professor);
			}

			assertNull("the pull must not have brought " + code,
					db.daoCourse().getByIdSync(courseId));

			EntityCourse course = new EntityCourse(code, topic, Stream.BSc, department,
					Year.FY, 1, CourseType.MAJOR, null);
			course.courseID = courseId;
			course.profID = profId;
			db.daoCourse().insert(course);
		});
	}
}
