package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import in.basulabs.siesattendance.backend.AccountKind;
import in.basulabs.siesattendance.backend.CourseType;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.EntityBatchDesc;
import in.basulabs.siesattendance.backend.EntityCourse;
import in.basulabs.siesattendance.backend.Year;
import in.basulabs.siesattendance.frontend.util.Permissions;

/**
 * A student account marks registers and changes nothing else.
 *
 * <h3>Why the screens are checked by reading the source</h3>
 * The rule is one function and the hard part is remembering to ask it. There are three editors
 * and five controls that lead to them today, and nothing in the type system says the sixth will
 * ask — which is exactly how a fourth route into taking attendance appeared without a sign-in
 * check. So the controls are swept here, the same way {@code SignInGuardTest} sweeps the gate and
 * {@code RepositorySeamTest} keeps fragments off the database.
 */
public class PermissionsTest {

	private static final String FRONTEND =
			"app/src/main/java/in/basulabs/siesattendance/frontend";

	/** Which capability each editor is the editor for. */
	private static final Map<String, String> EDITORS = Map.of(
			"FragmentAddStudent.java", "STUDENTS",
			"FragmentAddProfessor.java", "PROFESSORS",
			"FragmentAddCourse.java", "COURSES",
			"FragmentEditBatch.java", "BATCHES");

	/** The capabilities that are the roll, and so follow the server's mayKeepARoll answer. */
	private static final List<Permissions.Capability> ROSTER = List.of(
			Permissions.Capability.STUDENTS, Permissions.Capability.PROFESSORS,
			Permissions.Capability.COURSES);

	@Test
	public void aStudentChangesNoneOfTheCollegesRecords() {

		for (Permissions.Capability what : Permissions.Capability.values()) {

			// Both ways: a student holds no role that keeps a roll, so the kind decides it
			// without needing the server's answer -- which is the half that still works when
			// the server has never been reached.
			assertFalse("a class representative must not be offered " + what,
					Permissions.may(AccountKind.STUDENT, false, what));

			assertFalse("not even if something said they may keep a roll: " + what,
					Permissions.may(AccountKind.STUDENT, true, what));
		}
	}

	@Test
	public void aStudentSeesOnlyTheCoursesTheyRepresent() {

		List<EntityCourse> all = List.of(course(3001, "CS-101"), course(3002, "CS-102"),
				course(3003, "CS-103"));

		assertEquals(List.of("CS-101", "CS-103"), codes(Permissions.visibleCourses(
						AccountKind.STUDENT, Set.of(3001L, 3003L), all)));

		assertEquals("every appointment ended is no course at all, not every course",
				List.of(), codes(Permissions.visibleCourses(AccountKind.STUDENT, Set.of(), all)));

		assertEquals("and so is a student whose appointments were never recorded",
				List.of(), codes(Permissions.visibleCourses(AccountKind.STUDENT, null, all)));

		assertEquals("a professor is not narrowed by appointments, which are a student's",
				3, Permissions.visibleCourses(AccountKind.PROFESSOR, Set.of(3001L), all).size());

		assertEquals("nor is an install that never recorded who signed in",
				3, Permissions.visibleCourses(null, null, all).size());
	}

	@Test
	public void aStudentSeesOnlyTheBatchesOfTheirCourses() {

		List<EntityBatchDesc> all = List.of(batch(5001, "B1"), batch(5002, "B2"),
				batch(5003, "Club"));

		assertEquals(List.of("B1", "B2"), names(Permissions.visibleBatches(
				AccountKind.STUDENT, Set.of(5001L, 5002L), all)));

		assertEquals("no appointment recorded is no batch, not every batch",
				List.of(), names(Permissions.visibleBatches(AccountKind.STUDENT, null, all)));

		assertEquals("a professor sees every batch",
				3, Permissions.visibleBatches(AccountKind.PROFESSOR, Set.of(), all).size());
	}

	private static EntityBatchDesc batch(long id, String name) {
		EntityBatchDesc batch = new EntityBatchDesc(name, 2026, null);
		batch.batchID = id;
		return batch;
	}

	private static List<String> names(List<EntityBatchDesc> batches) {
		return batches.stream().map(batch -> batch.batchName).toList();
	}

	@Test
	public void everyCourseListTheScreensAreGivenIsNarrowed() throws IOException {

		// The same sweep as the editors': the rule is one function and the hard part is a
		// seventh course list that forgets to ask it. Every read of the course lists in the
		// view model has to pass through visible().
		String source = withoutComments(read(
				"app/src/main/java/in/basulabs/siesattendance/frontend/viewmodel/"
						+ "AttendanceViewModel.java"));

		Matcher read = Pattern.compile(
				"repository\\.(coursesSync|activeCoursesSync|observeCourses|observeCourseCount)"
						+ "\\(\\)").matcher(source);

		int seen = 0;

		while (read.find()) {
			seen++;

			String before = source.substring(Math.max(0, read.start() - 40), read.start());

			assertTrue("AttendanceViewModel reads " + read.group(1) + " without narrowing it "
							+ "to the courses this account is shown: ..." + before + read.group(),
					before.contains("visible(") || before.contains("Transformations.map("));
		}

		assertTrue("the sweep found nothing, so it is no longer looking at the right file",
				seen >= 4);
	}

	private static EntityCourse course(long id, String code) {

		EntityCourse course = new EntityCourse(code, "Topic", in.basulabs.siesattendance.backend
				.Stream.BSc_unaided, Department.COMPUTER_SCIENCE, Year.FY, 1, CourseType.MAJOR,
				null);
		course.courseID = id;
		return course;
	}

	private static List<String> codes(List<EntityCourse> courses) {
		return courses.stream().map(course -> course.courseCode).toList();
	}

	@Test
	public void batchesAreAProfessorsAndNobodyElses() {

		Permissions.Capability batches = Permissions.Capability.BATCHES;

		assertTrue("any professor, whether or not they keep the roll",
				Permissions.may(AccountKind.PROFESSOR, false, batches));
		assertTrue(Permissions.may(AccountKind.PROFESSOR, true, batches));

		assertFalse("not a class representative",
				Permissions.may(AccountKind.STUDENT, true, batches));
		assertFalse("and not the office or an administrator, who keep the roll but not batches",
				Permissions.may(AccountKind.STAFF, true, batches));
	}

	@Test
	public void onlyAnAdministratorAddsASession() {

		Permissions.Capability add = Permissions.Capability.NEW_SESSION;
		Permissions.Capability edit = Permissions.Capability.SESSIONS;

		assertTrue(Permissions.may(AccountKind.STAFF, true, Set.of("ADMIN"), add));
		assertFalse("the office corrects a session and does not open one",
				Permissions.may(AccountKind.STAFF, true, Set.of("OFFICE"), add));
		assertTrue(Permissions.may(AccountKind.STAFF, true, Set.of("OFFICE"), edit));

		assertFalse("a head of department keeps the roll, not the calendar",
				Permissions.may(AccountKind.PROFESSOR, true, Set.of("HOD"), add));
		assertFalse(Permissions.may(AccountKind.PROFESSOR, true, Set.of("HOD"), edit));

		assertFalse("roles never recorded is no role, for an account that is known",
				Permissions.may(AccountKind.STAFF, true, null, add));

		assertTrue("a build with no server has nobody else to set up its calendar",
				Permissions.may(null, true, null, add));
	}

	@Test
	public void whoKeepsTheRollIsTheServersAnswer() {

		for (Permissions.Capability what : ROSTER) {

			// actor_may_keep_a_roll(): the office, an administrator, or the head of a
			// department. A professor who heads one is offered the screens; one who does not
			// is not, because the server would refuse the push and the correction would live
			// on one phone and nowhere else.
			assertTrue("a head of department keeps their department's roll: " + what,
					Permissions.may(AccountKind.PROFESSOR, true, what));

			assertFalse("a professor who heads nothing does not: " + what,
					Permissions.may(AccountKind.PROFESSOR, false, what));

			assertTrue("the office keeps the roll: " + what,
					Permissions.may(AccountKind.STAFF, true, what));
		}
	}

	@Test
	public void anInstallThatHasNeverAskedIsPermitted() {

		// An install that signed in before any of this was recorded, and a build with no
		// server to sign in to at all. Refusing would leave an app that can do nothing, which
		// is the same trade SignInGuard makes for the same reason -- and DeviceIdentity's
		// default for the flag is true for exactly this case.
		for (Permissions.Capability what : Permissions.Capability.values()) {
			assertTrue("an unknown account must not lock the app: " + what,
					Permissions.may(null, true, what));
		}
	}

	/**
	 * Attendance, which is the whole reason a class representative has an account.
	 * <p>
	 * Taking a register, correcting one, editing the lecture it was taken in, duplicating a
	 * slot, clearing or deleting a record, importing marks from a file: a student does all of
	 * it. This is a list of screens that must stay ungated, held here because the easy mistake
	 * when adding a capability is to reach for one screen too many — and the one screen too
	 * many is the one that turns a CR's account back into something that cannot do its job.
	 */
	@Test
	public void aStudentKeepsEveryPartOfAttendance() throws IOException {

		List<String> gated = new ArrayList<>();

		for (String screen : List.of(
				"FragmentTakeAttendance.java",     // calling the roll
				"FragmentAttendance.java",         // one register: edit, duplicate, clear, delete
				"FragmentAttendancePicker.java",   // a day at a time
				"FragmentEditLecture.java",        // the lecture a register belongs to
				"FragmentLectureList.java",        // and the way into a new one
				"FragmentImportAttendance.java")) {

			if (withoutComments(read(FRONTEND + "/" + screen)).contains("Permissions")) {
				gated.add(screen);
			}
		}

		assertTrue("these are attendance, and attendance is what a student account is for: "
						+ gated, gated.isEmpty());
	}

	@Test
	public void everyEditorRefusesOnArrival() throws IOException {

		for (Map.Entry<String, String> editor : EDITORS.entrySet()) {

			String source = withoutComments(read(FRONTEND + "/" + editor.getKey()));

			assertTrue(editor.getKey() + " has to check for itself: it is the one check that "
							+ "cannot be forgotten when somebody adds another way in",
					source.contains("Permissions.Capability." + editor.getValue()));

			assertTrue(editor.getKey() + " has to leave rather than show a form whose save "
							+ "would be refused",
					source.contains("popBackStack"));
		}
	}

	@Test
	public void everyControlThatLeadsToAnEditorAsksFirst() throws IOException {

		List<String> unguarded = new ArrayList<>();

		for (Path file : javaFilesUnder(FRONTEND)) {

			String name = file.getFileName().toString();

			if (EDITORS.containsKey(name)) {
				// The destinations themselves, covered above.
				continue;
			}

			String source = withoutComments(Files.readString(file, StandardCharsets.UTF_8));

			boolean leadsToAnEditor = source.contains("_to_addStudent")
					|| source.contains("_to_addProfessor")
					|| source.contains("_to_addCourse")
					|| source.contains("_to_editStudent")
					|| source.contains("_to_editBatch");

			if (leadsToAnEditor && !source.contains("Permissions.Capability")) {
				unguarded.add(name);
			}
		}

		assertTrue("these offer a way into an editor without asking whether this account may "
						+ "use it; ask viewModel.may(...) first: " + unguarded,
				unguarded.isEmpty());
	}

	// ── Helpers, the same shape as SignInGuardTest's ─────────────────────

	private static String withoutComments(String source) {
		return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
	}

	private static List<Path> javaFilesUnder(String relative) throws IOException {

		try (Stream<Path> files = Files.walk(repositoryRoot().resolve(relative))) {
			return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
		}
	}

	private static String read(String relative) throws IOException {
		return Files.readString(repositoryRoot().resolve(relative), StandardCharsets.UTF_8);
	}

	private static Path repositoryRoot() throws IOException {

		File directory = new File("").getAbsoluteFile();

		for (int depth = 0; depth < 5 && directory != null; depth++) {

			if (new File(directory, "app/src/main/java").isDirectory()) {
				return directory.toPath();
			}
			directory = directory.getParentFile();
		}
		throw new IOException("no app module above " + new File("").getAbsolutePath());
	}
}
