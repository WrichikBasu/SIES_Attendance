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
import java.util.stream.Stream;

import in.basulabs.siesattendance.frontend.util.SignInGuard;

/**
 * A register cannot be marked without a session, and every route to one says so.
 *
 * <h3>The rule, and the half of it that is easy to lose</h3>
 * "No session, no attendance" on its own would turn off every build that has no server to sign
 * in to, which is every release build until somebody configures one. So the rule is the
 * conjunction, and the truth table below is what stops a later simplification from dropping the
 * first half.
 *
 * <h3>Why the routes are checked by reading the source</h3>
 * Three screens start a register and nothing in the type system says a fourth has to ask
 * permission first. {@code FragmentTakeAttendance} checks on arrival, which is the backstop, but
 * a screen that navigates straight into a register and gets bounced out again is a worse
 * experience than one that explains itself. This test holds the callers to the explaining
 * version, the same way {@code RepositorySeamTest} holds the fragments off the database.
 */
public class SignInGuardTest {

	private static final String FRONTEND = "app/src/main/java/in/basulabs/siesattendance/frontend";

	@Test
	public void aSessionIsRequiredOnlyWhenThereIsSomewhereToSignIn() {

		assertTrue("a build with a server and no session has to ask",
				SignInGuard.mustSignIn(true, false));

		assertFalse("a signed-in device is not asked again",
				SignInGuard.mustSignIn(true, true));

		assertFalse("a build with no server cannot sign in, so blocking it would turn the "
						+ "app off: attendance on a local-only build must keep working",
				SignInGuard.mustSignIn(false, false));

		assertFalse("and the same build is not asked because of a stale token either",
				SignInGuard.mustSignIn(false, true));
	}

	@Test
	public void everyScreenThatStartsARegisterAsksFirst() throws IOException {

		List<String> unguarded = new ArrayList<>();

		for (Path file : javaFilesUnder(FRONTEND)) {

			String name = file.getFileName().toString();

			if (name.equals("FragmentTakeAttendance.java")) {
				// The destination itself, which checks on arrival rather than before.
				continue;
			}

			String source = withoutComments(Files.readString(file, StandardCharsets.UTF_8));

			if (source.contains("to_takeAttendance") && !source.contains("SignInGuard")) {
				unguarded.add(name);
			}
		}

		assertTrue("these navigate into taking attendance without asking for a session "
						+ "first; wrap the navigation in SignInGuard.ifAllowed: " + unguarded,
				unguarded.isEmpty());
	}

	@Test
	public void theDestinationChecksOnArrivalToo() throws IOException {

		String source = withoutComments(read(FRONTEND + "/FragmentTakeAttendance.java"));

		assertTrue("FragmentTakeAttendance has to check for itself: it is reachable three ways "
						+ "today, and the check on the screen is the one that cannot be "
						+ "forgotten by whoever adds the fourth",
				source.contains("SignInGuard.mustSignIn"));
		assertTrue("and it has to leave rather than render an unusable form",
				source.contains("popBackStack"));
	}

	// ── Helpers, the same shape as RepositorySeamTest's ──────────────────

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
