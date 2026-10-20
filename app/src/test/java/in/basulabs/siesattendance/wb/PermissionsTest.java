package in.basulabs.siesattendance.wb;

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
import java.util.stream.Stream;

import in.basulabs.siesattendance.backend.AccountKind;
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
			"FragmentAddCourse.java", "COURSES");

	@Test
	public void aStudentChangesNoneOfTheCollegesRecords() {

		for (Permissions.Capability what : Permissions.Capability.values()) {
			assertFalse("a class representative must not be offered " + what,
					Permissions.may(AccountKind.STUDENT, what));
		}
	}

	@Test
	public void aProfessorAndTheOfficeAreNotStoppedHere() {

		for (Permissions.Capability what : Permissions.Capability.values()) {

			assertTrue(what + " is a professor's to change as far as this app is concerned; "
							+ "the policies on the table are what refuse one that is not theirs",
					Permissions.may(AccountKind.PROFESSOR, what));

			assertTrue("the office keeps the roll: " + what,
					Permissions.may(AccountKind.STAFF, what));
		}
	}

	@Test
	public void anUnknownKindIsPermitted() {

		// An install that signed in before the kind was recorded, and a build with no server
		// to sign in to at all. Refusing would leave an app that can do nothing, which is the
		// same trade SignInGuard makes for the same reason.
		for (Permissions.Capability what : Permissions.Capability.values()) {
			assertTrue("an unknown kind must not lock the app: " + what,
					Permissions.may(null, what));
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
					|| source.contains("_to_editStudent");

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
