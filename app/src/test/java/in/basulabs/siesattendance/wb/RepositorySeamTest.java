package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * That the one seam is actually a seam.
 * <p>
 * Phase 6 moves the attendance database to the server, and the whole plan for doing it without
 * rewriting the app rests on a single claim: there is exactly one place that reads and writes
 * data, and everything above it goes through an interface. That claim is easy to make and easy to
 * break — one convenient {@code MainDatabase.getInstance(…)} in a fragment, one DAO call left
 * behind in the ViewModel, and the seam leaks in a way nothing else would notice until the
 * transport was already in place.
 * <p>
 * So it is checked here, over the source, the same way {@code WireTest} checks that nothing but
 * {@code ActorSession} sets the database actor. These tests need no device and no database: they
 * are about the shape of the code, which is the thing being promised.
 */
public class RepositorySeamTest {

	private static final String VIEW_MODEL =
			"app/src/main/java/in/basulabs/siesattendance/frontend/viewmodel/"
					+ "AttendanceViewModel.java";

	private static final String ROOM_REPOSITORY =
			"app/src/main/java/in/basulabs/siesattendance/backend/repo/"
					+ "RoomAttendanceRepository.java";

	private static final String INTERFACE =
			"app/src/main/java/in/basulabs/siesattendance/backend/repo/"
					+ "AttendanceRepository.java";

	private static final String FRONTEND = "app/src/main/java/in/basulabs/siesattendance/frontend";

	@Test
	public void theViewModelReachesTheDatabaseOnlyThroughTheRepository() throws IOException {

		String source = withoutComments(read(VIEW_MODEL));

		assertEquals("The ViewModel must not call a DAO: every read and write goes through "
						+ "AttendanceRepository, which is what lets the implementation behind it "
						+ "change.",
				0, countOf(source, Pattern.compile("\\bdb\\s*\\.\\s*dao")));

		assertEquals("The ViewModel must not run its own transaction: a transaction is two or "
						+ "more writes that have to travel together, which is exactly what the "
						+ "repository has to be able to send as one intent.",
				0, countOf(source, Pattern.compile("runInTransaction")));

		// One mention, in the constructor, where the implementation is chosen. That line is the
		// thing Phase 6 changes.
		assertEquals("MainDatabase should appear in the ViewModel only to build the repository "
						+ "and to name the query executor.",
				1, countOf(source, Pattern.compile("MainDatabase\\s*\\.\\s*getInstance")));
	}

	@Test
	public void noScreenTouchesTheDatabase() throws IOException {

		List<String> offenders = new ArrayList<>();

		for (Path file : javaFilesUnder(FRONTEND)) {

			if (file.toString().contains("viewmodel")) {
				continue;
			}

			String source = withoutComments(Files.readString(file, StandardCharsets.UTF_8));

			if (source.contains("MainDatabase") || source.contains("daoStudent")
					|| source.contains("daoLecture") || source.contains("daoCourse")
					|| source.contains("daoAttendance") || source.contains("daoBatch")
					|| source.contains("daoProfessor") || source.contains("daoAcademicYear")) {
				offenders.add(file.getFileName().toString());
			}
		}

		assertEquals("A fragment that reaches the database directly is a fragment the transport "
						+ "change would have to touch, and there are twenty-five of them.",
				List.of(), offenders);
	}

	/**
	 * Every method of the Room implementation is one DAO call.
	 * <p>
	 * This is what makes extracting the interface provably inert rather than merely intended to
	 * be: if each method is the call the ViewModel used to make, in the same order with the same
	 * arguments, then no behaviour moved. The two exceptions are the two the ViewModel already
	 * ran inside {@code runInTransaction}, and they are named here so that adding a third
	 * multi-statement method is a decision somebody has to come and make rather than something
	 * that happens quietly.
	 */
	@Test
	public void theRoomRepositoryIsOneDaoCallPerMethod() throws IOException {

		String source = withoutComments(read(ROOM_REPOSITORY));

		List<String> transactional = List.of("recordLectureWithMarks", "duplicateLecture");

		Matcher matcher = Pattern.compile(
				"\\n\\tpublic [^\\n]*?\\b([a-zA-Z0-9_]+)\\((?:[^{]*?)\\) \\{(.*?)\\n\\t\\}",
				Pattern.DOTALL).matcher(source);

		int checked = 0;

		while (matcher.find()) {

			String name = matcher.group(1);
			String body = matcher.group(2);

			// The constructor and the one escape hatch, which is documented where it is
			// declared: the whole-file operations work on the database file itself.
			if (name.equals("RoomAttendanceRepository") || name.equals("database")
					|| transactional.contains(name)) {
				continue;
			}

			int calls = countOf(body, Pattern.compile("\\bdb\\s*\\.\\s*dao"));

			if (calls != 1) {
				fail(name + " makes " + calls + " DAO calls. Every method here is meant to be "
						+ "exactly the call the ViewModel used to make; a method that is not is "
						+ "a behaviour change hiding in a refactor.");
			}
			checked++;
		}

		assertTrue("the method bodies could not be read: got " + checked, checked > 60);
	}

	/** The interface names no Room type, or it would not be an interface worth having. */
	@Test
	public void theInterfaceDoesNotMentionRoom() throws IOException {

		String source = read(INTERFACE);

		for (String room : List.of("MainDatabase", "androidx.room", "RoomDatabase")) {
			assertTrue("AttendanceRepository must not mention " + room
							+ ": the point of it is that what is behind it can be something else.",
					!source.contains(room));
		}

		// LiveData is deliberately allowed. It is the one piece of the storage layer that does
		// reach the screens, because a self-updating query is what keeps the UI correct while
		// a background sync writes to the same tables -- and that is a decision the plan makes
		// explicitly rather than an accident of Room being underneath.
		assertTrue("LiveData is expected here", source.contains("LiveData"));
	}

	// ── Reading the source ───────────────────────────────────────────────

	private static int countOf(String source, Pattern pattern) {

		Matcher matcher = pattern.matcher(source);
		int found = 0;

		while (matcher.find()) {
			found++;
		}
		return found;
	}

	/**
	 * Java source with its comments removed, so that a note explaining why something must not
	 * happen does not read as that thing happening.
	 */
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

	/**
	 * The working tree's root, found by walking up until the app module is visible. The unit
	 * tests run with the module directory as the working directory, and the schema drift test
	 * does the same thing for the same reason.
	 */
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
