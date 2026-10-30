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

import in.basulabs.siesattendance.frontend.util.SignInGuard;

/**
 * The app does not open without a session, and the gate is in one place.
 *
 * <h3>The rule, and the half of it that is easy to lose</h3>
 * "No session, no app" on its own would turn off every build that has no server to sign in to,
 * which is every release build until somebody configures one — a locked screen with no way past
 * it. So the rule is a conjunction, and the truth table below is what stops a later
 * simplification from dropping the first half.
 *
 * <h3>Why the host is checked by reading the source</h3>
 * There are two dozen destinations and nothing in the type system says a new one has to ask
 * permission. That is exactly why the gate moved from the screens to {@code ActivityHome}: it is
 * chosen before the first fragment exists and asked again on every {@code onStart}. These tests
 * hold that arrangement in place, the same way {@code RepositorySeamTest} holds the fragments off
 * the database — including the one deliberate exemption, so nobody tidies it away without reading
 * why it is there.
 */
public class SignInGuardTest {

	private static final String FRONTEND =
			"app/src/main/java/in/basulabs/siesattendance/frontend";

	@Test
	public void aSessionIsRequiredOnlyWhenThereIsSomewhereToSignIn() {

		assertTrue("a build with a server and no session has to ask",
				SignInGuard.mustSignIn(true, false));

		assertFalse("a signed-in device is not asked again",
				SignInGuard.mustSignIn(true, true));

		assertFalse("a build with no server cannot sign in, so locking it would turn the app "
						+ "off rather than secure it",
				SignInGuard.mustSignIn(false, false));

		assertFalse("and the same build is not asked because of a stale token either",
				SignInGuard.mustSignIn(false, true));
	}

	@Test
	public void theGateIsTheSameRuleSaidAsADestination() {

		assertEquals(SignInGuard.Gate.SIGN_IN, SignInGuard.gate(true, false));
		assertEquals(SignInGuard.Gate.THE_APP, SignInGuard.gate(true, true));
		assertEquals(SignInGuard.Gate.THE_APP, SignInGuard.gate(false, false));
		assertEquals(SignInGuard.Gate.THE_APP, SignInGuard.gate(false, true));
	}

	@Test
	public void theHostPicksTheStartDestinationAndAsksAgainOnEveryStart() throws IOException {

		String source = withoutComments(read(FRONTEND + "/ActivityHome.java"));

		assertTrue("the start destination has to come from the gate, before any fragment "
						+ "exists: a host that inflated the graph first would show the "
						+ "dashboard and then navigate away from it",
				source.contains("SignInGuard.gate") && source.contains("setStartDestination"));

		assertTrue("and onStart has to ask again, for a session that ended while the app was "
						+ "in the background",
				source.contains("protected void onStart")
						&& source.contains("action_global_signIn"));
	}

	@Test
	public void aRegisterBeingMarkedIsNotInterrupted() throws IOException {

		// The one exemption, and it is not a small one: the sheet is in memory until it is
		// saved, so ejecting somebody mid-roll would throw away attendance already taken --
		// the one outcome this whole design refuses. The lock arrives when they leave.
		String host = withoutComments(read(FRONTEND + "/ActivityHome.java"));

		assertTrue("ActivityHome.onStart must not eject somebody from a half-marked register",
				host.contains("fragmentTakeAttendance"));

		// Which is why the screen itself checks on the way in, where there is nothing to lose.
		String register = withoutComments(read(FRONTEND + "/FragmentTakeAttendance.java"));

		assertTrue("the register screen checks on arrival instead",
				register.contains("SignInGuard.mustSignIn"));
		assertTrue("and sends the person to the door rather than back into a locked app",
				register.contains("action_global_signIn"));
	}

	@Test
	public void theDefaultInTheGraphIsTheLockedOne() throws IOException {

		String graph = read("app/src/main/res/navigation/nav_graph.xml");

		// The host overrides this every launch; it matters anyway, because it is what happens
		// if that code is ever wrong. Of the two possible mistakes, showing the door to
		// somebody who holds a session is the harmless one.
		assertTrue("the graph's own start destination is the sign-in screen: "
						+ "a mistake should show the door, not open it",
				graph.contains("app:startDestination=\"@id/fragmentSignIn\""));

		assertTrue("and signing out pops the whole graph, so nothing on the stack can be "
						+ "resumed by whoever is handed the phone next",
				graph.contains("app:popUpTo=\"@id/nav_graph\"")
						&& graph.contains("app:popUpToInclusive=\"true\""));
	}

	@Test
	public void goingThroughTheDoorLeavesNothingToComeBackTo() throws IOException {

		String source = withoutComments(read(FRONTEND + "/FragmentSignIn.java"));

		assertTrue("signing in pops the sign-in screen itself, so back from the dashboard "
						+ "leaves the app instead of returning to a satisfied door",
				source.contains("setPopUpTo(R.id.fragmentSignIn, true)"));
	}

	@Test
	public void nothingPromptsForASignInFromInsideTheAppAnyMore() throws IOException {

		// The dialogs at the four routes into a register are gone: they could not fire, since
		// the app does not open without a session. A new one would mean the gate had been
		// weakened somewhere.
		for (Path file : Files.walk(repositoryRoot().resolve(FRONTEND))
				.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {

			String source = withoutComments(Files.readString(file, StandardCharsets.UTF_8));

			assertFalse(file.getFileName() + " still asks for a sign-in mid-app; the gate is "
							+ "at ActivityHome now",
					source.contains("SignInGuard.ifAllowed"));
		}
	}

	// ── Helpers, the same shape as RepositorySeamTest's ──────────────────

	private static String withoutComments(String source) {
		return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
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
