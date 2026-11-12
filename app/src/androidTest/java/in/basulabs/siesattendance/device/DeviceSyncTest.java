package in.basulabs.siesattendance.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import in.basulabs.siesattendance.backend.CourseType;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Division;
import in.basulabs.siesattendance.backend.EntityAcademicYear;
import in.basulabs.siesattendance.backend.EntityAttendance;
import in.basulabs.siesattendance.backend.EntityBatchDesc;
import in.basulabs.siesattendance.backend.EntityCourse;
import in.basulabs.siesattendance.backend.EntityLecture;
import in.basulabs.siesattendance.backend.EntityOutbox;
import in.basulabs.siesattendance.backend.EntityProfessor;
import in.basulabs.siesattendance.backend.EntityStudent;
import in.basulabs.siesattendance.backend.EntityStudentYear;
import in.basulabs.siesattendance.backend.IdGenerator;
import in.basulabs.siesattendance.backend.MainDatabase;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.StructuredId;
import in.basulabs.siesattendance.backend.Year;
import in.basulabs.siesattendance.backend.sync.SyncClient;
import in.basulabs.siesattendance.backend.sync.SyncSignIn;
import in.basulabs.siesattendance.backend.util.CanonicalCode;
import in.basulabs.siesattendance.device.support.ServerSync;
import in.basulabs.siesattendance.device.support.ServerSync.Totals;
import in.basulabs.siesattendance.device.support.StagingFiles;

import static in.basulabs.siesattendance.device.support.ServerSync.pullAll;
import static in.basulabs.siesattendance.device.support.ServerSync.pushAll;

/**
 * {@link OutboxDrain} on a phone, talking over the network to a real server.
 * <p>
 * The server's {@code SyncTest} posts hand-made JSON to the controllers; the app's JVM tests
 * check the drain's halves on their own. Neither has the phone's own HTTP stack, its JSON as
 * {@link SyncClient} writes it, the outbox triggers producing the intents, or a pull applied by
 * the phone's SQLite. This is those joined, which {@code verify-sync.sh} says is otherwise
 * only done by hand.
 * <p>
 * Two staging databases stand for two phones of the same account: what one pushes, the other
 * pulls. Both live beside the app's own database and are deleted afterwards. The sign-in is
 * real and process-wide (the token in the Keystore, the node {@link IdGenerator} issues keys
 * under), which is fine in the debug app this runs in and is thrown away with it.
 * <p>
 * Run through {@code server/tools/verify-device-sync.sh}, which starts a throwaway database
 * and server, makes the account, forwards the port with {@code adb reverse} and passes these
 * as instrumentation arguments. Without them every test here is skipped, and the script treats
 * a skip as a failure.
 */
@RunWith(AndroidJUnit4.class)
public class DeviceSyncTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	private static final int SESSION = 2025;

	private static Context context;

	private String nameA;

	private String nameB;

	private MainDatabase phoneA;

	private MainDatabase phoneB;

	/** Unique per test, so rows from one test never collide with another's on the server. */
	private String tag;

	@BeforeClass
	public static void signInToTheServer() throws IOException {

		context = StagingFiles.context();
		ServerSync.signIn("siesEmail", "siesPassword", SyncSignIn.Door.STAFF, null);
	}

	@Before
	public void twoPhones() {

		ServerSync.assumeSignedIn();

		tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);

		nameA = StagingFiles.freshName();
		nameB = StagingFiles.freshName();
		phoneA = MainDatabase.openStaging(context, nameA);
		phoneB = MainDatabase.openStaging(context, nameB);
	}

	@After
	public void throwThemAway() {
		if (phoneA != null) {
			phoneA.close();
		}
		if (phoneB != null) {
			phoneB.close();
		}
		StagingFiles.delete(nameA);
		StagingFiles.delete(nameB);
	}

	// ── The tests ────────────────────────────────────────────────────────

	@Test
	public void aSessionMadeOnOnePhoneArrivesWholeOnAnother() throws IOException {

		Seed seed = seed(phoneA);

		Totals pushed = pushAll(phoneA);

		assertTrue("something was sent", pushed.sent > 0);
		assertEquals("nothing was refused: " + pushed.refusals, 0,
				pushed.rejected + pushed.dropped);
		assertEquals("the queue is empty", 0, phoneA.daoOutbox().countPendingSync());

		pullAll(phoneB);

		EntityLecture lecture = phoneB.daoLecture().getByIdSync(seed.lecture);
		assertNotNull("the lecture arrived", lecture);
		assertEquals(seed.start, lecture.startDateTime);
		assertEquals("and in the zone it was taken in", IST, lecture.startDateTime.getZone());
		assertEquals(Long.valueOf(seed.batch), lecture.batchID);

		assertEquals(List.of(seed.alice), phoneB.daoAttendance().getPresentIdsSync(seed.lecture));
		assertEquals(2, phoneB.daoAttendance().getMarkedIdsSync(seed.lecture).size());

		assertEquals("ALICE " + tag, phoneB.daoStudent().getStudentSync(seed.alice).name);
		// The one field that does not arrive as it was sent: the server canonicalises every
		// roll number written to student_year (V57), and this fixture writes one as typed.
		// See enrol().
		assertEquals(CanonicalCode.of("A-" + tag), phoneB.daoStudent()
				.getEnrolmentSync(seed.alice, SESSION).rollNo);
		assertEquals(2, phoneB.daoBatch().countStudentsSync(seed.batch));
		assertEquals(List.of(seed.course), phoneB.daoBatch().getLinkedCourseIdsSync(seed.batch));
		assertTrue("the coordinator teaches it",
				phoneB.daoCourse().getInstructorIdsSync(seed.course).contains(seed.professor));

		assertEquals("pulling writes nothing to the second phone's queue",
				0, phoneB.daoOutbox().countPendingSync());
	}

	@Test
	public void aCorrectionAndThenADeleteFollowToTheOtherPhone() throws IOException {

		Seed seed = seed(phoneA);
		pushAll(phoneA);
		pullAll(phoneB);

		// Bob was marked absent; he was there.
		phoneA.daoAttendance().saveSheet(seed.lecture, List.of(
				new EntityAttendance(seed.lecture, seed.alice, true),
				new EntityAttendance(seed.lecture, seed.bob, true)));
		Totals corrected = pushAll(phoneA);
		assertEquals("refused: " + corrected.refusals, 0,
				corrected.rejected + corrected.dropped);

		pullAll(phoneB);
		assertEquals(new HashSet<>(List.of(seed.alice, seed.bob)),
				new HashSet<>(phoneB.daoAttendance().getPresentIdsSync(seed.lecture)));

		phoneA.daoLecture().deleteLectureCascade(phoneA.daoLecture().getByIdSync(seed.lecture));
		Totals deleted = pushAll(phoneA);
		assertEquals("refused: " + deleted.refusals, 0, deleted.rejected + deleted.dropped);

		pullAll(phoneB);
		assertNull("the lecture is gone from the other phone",
				phoneB.daoLecture().getByIdSync(seed.lecture));
		assertTrue("and its marks with it",
				phoneB.daoAttendance().getMarkedIdsSync(seed.lecture).isEmpty());

		assertEquals("a pull with nothing new applies nothing", 0, pullAll(phoneB));
	}

	@Test
	public void aKeyClaimingAnotherPhoneIsRefusedForGoodAndTheRestStillLands()
			throws IOException {

		Seed seed = seed(phoneA);

		// The same counter, another node: a row this phone could not have made.
		long ours = IdGenerator.newID();
		int otherNode = (IdGenerator.nodeID() + 7) % (IdGenerator.MAX_ASSIGNED_NODE_ID + 1);
		long forged = StructuredId.compose(StructuredId.counterOf(ours), otherNode);

		EntityStudent impostor = new EntityStudent("FORGED " + tag, "they/them", null);
		impostor.studentID = forged;
		phoneA.daoStudent().insertStudent(impostor);

		Totals pushed = pushAll(phoneA);

		assertTrue("the honest rows went: " + pushed.refusals, pushed.sent > 0);
		assertEquals("one row, refused for good", 1, pushed.dropped);
		assertEquals(0, pushed.rejected);
		assertFalse("and said why", pushed.refusals.isEmpty());
		assertEquals("a permanent refusal leaves the queue", 0,
				phoneA.daoOutbox().countPendingSync());

		pullAll(phoneB);
		assertNull("the server never stored it", phoneB.daoStudent().getStudentSync(forged));
		assertNotNull("but it stored the rest", phoneB.daoLecture().getByIdSync(seed.lecture));
	}

	@Test
	public void withNoServerToReachTheQueueIsKeptAndCountedUntilThereIs() throws IOException {

		seed(phoneA);
		int queued = phoneA.daoOutbox().countPendingSync();
		assertTrue(queued > 0);

		// A client built while the address points at a port nothing on the phone listens on.
		SyncClient unreachable = ServerSync.clientFor("http://127.0.0.1:9/api/v1");

		assertThrows(IOException.class, () -> new in.basulabs.siesattendance.backend.sync.OutboxDrain(phoneA, unreachable).push());

		assertEquals("nothing was lost", queued, phoneA.daoOutbox().countPendingSync());
		List<EntityOutbox> tried = phoneA.daoOutbox().pendingSync(queued);
		for (EntityOutbox entry : tried) {
			if (entry.attempts > 0) {
				assertNotNull("a failure says what went wrong", entry.lastError);
			}
		}
		assertTrue("the batch it tried is counted",
				tried.stream().anyMatch(entry -> entry.attempts == 1));

		Totals later = pushAll(phoneA);
		assertEquals("refused: " + later.refusals, 0, later.rejected + later.dropped);
		assertEquals("once the server is back the queue empties",
				0, phoneA.daoOutbox().countPendingSync());
	}

	// ── Seeding ──────────────────────────────────────────────────────────

	/** What {@link #seed} made, by key. */
	private record Seed(long professor, long course, long alice, long bob, long batch,
	                    long lecture, ZonedDateTime start) {
	}

	/**
	 * A small, complete session through the app's own DAOs, so the triggers queue it the way
	 * they queue a teacher's work.
	 */
	@NonNull
	private Seed seed(@NonNull MainDatabase db) {

		db.daoAcademicYear().upsert(EntityAcademicYear.of(SESSION,
				LocalDate.of(SESSION, 6, 1), LocalDate.of(SESSION + 1, 5, 31)));

		long professor = db.daoProfessor().insert(new EntityProfessor("PROF " + tag,
				"prof." + tag.toLowerCase(Locale.ROOT) + "@sies.edu.in", Department.PHYSICS,
				"Professor"));

		EntityCourse c = new EntityCourse("PH-" + tag, "Optics " + tag, Stream.BSc,
				Department.PHYSICS, Year.SY, 3, CourseType.MAJOR, null);
		c.profID = professor;
		long course = db.daoCourse().insert(c);

		long alice = enrol(db, "ALICE " + tag, "A-" + tag);
		long bob = enrol(db, "BOB " + tag, "B-" + tag);

		long batch = db.daoBatch().createBatchFromStudents(
				new EntityBatchDesc("BATCH " + tag, SESSION, null, Department.PHYSICS),
				List.of(alice, bob));
		db.daoBatch().setCourseLinks(batch, List.of(course));

		ZonedDateTime start = ZonedDateTime.of(SESSION, 7, 14, 9, 0, 0, 0, IST);
		long lecture = db.daoLecture().insert(
				new EntityLecture(course, batch, start, start.plusHours(1), "via the server"));

		db.daoAttendance().saveSheet(lecture, List.of(
				new EntityAttendance(lecture, alice, true),
				new EntityAttendance(lecture, bob, false)));

		return new Seed(professor, course, alice, bob, batch, lecture, start);
	}

	/**
	 * A student and their enrolment, written straight to the tables.
	 * <p>
	 * Not through {@code DaoStudent}'s upsert, which canonicalises the roll number before it
	 * stores it, so the roll goes out over the wire as it was typed — the shape a phone on a
	 * build older than V57 pushes, and the one path that proves the server fixes the spelling
	 * for every device rather than only for the one that typed it. The assertion above
	 * therefore expects {@link CanonicalCode#of(String)} of what was written here. The course
	 * code in {@link #seed(MainDatabase)} is canonicalised the same way, by the trigger on
	 * {@code courses}.
	 */
	private static long enrol(@NonNull MainDatabase db, String name, String roll) {

		long id = db.daoStudent().insertStudent(new EntityStudent(name, "they/them", null));
		db.daoStudent().insertStudentYear(new EntityStudentYear(id, SESSION, roll, Year.SY,
				Stream.BSc, Department.PHYSICS, null, null, 3, Division.A));
		return id;
	}
}
