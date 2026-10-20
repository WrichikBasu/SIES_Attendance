package in.basulabs.siesattendance.frontend.util;

/**
 * Whether this phone may be used at all, and the one place that decides it.
 * <p>
 * The app is behind a sign-in. Not one screen of it — all of it: a register, a roll, a student's
 * record and a term's statistics are the college's data, and a phone that has been signed out, or
 * never signed in, shows the sign-in screen and nothing else. Marks are attributed on the server
 * to the account that sent them, and that attribution is the point of the audit trail: "who
 * recorded this" has to have an answer that is not "a phone that was lying around".
 *
 * <h3>What this is not</h3>
 * It is not a connectivity check, and it must never become one. A session is a token on the
 * device; holding one is what this asks about. Marking a register in a basement laboratory with
 * no signal works exactly as it did before — that is the requirement the whole design is built
 * around, and a gate that asked the server anything would break it. The token outlives a
 * fortnight of no signal, and nothing here shortens that.
 *
 * <h3>A build with no server is not locked out</h3>
 * {@code sync_base_url} is empty in a release build until somebody configures one, and then there
 * is no account anywhere to sign in to. A locked screen with no way past it is not a secured app,
 * it is a broken one, so the rule has two halves and both matter: <em>ask only when there is
 * somewhere to ask about</em>. That is one boolean, in {@link #gate}, and tightening it to "no
 * server means no app" is a one-line change in a place a reader will find.
 *
 * <h3>Where it is enforced</h3>
 * {@code ActivityHome} picks the navigation graph's start destination from {@link #gate} before
 * the first fragment exists, and asks again in {@code onStart}, which is what catches a session
 * that ended while the app was in the background. Enforcing it at the host rather than at each
 * screen is deliberate: there are two dozen destinations and a per-screen check is a check
 * somebody will forget to add to the twenty-fifth.
 */
public final class SignInGuard {

	/** Where the app may start. */
	public enum Gate {

		/** The sign-in screen, and nothing else is reachable. */
		SIGN_IN,

		/** The app proper. */
		THE_APP
	}

	private SignInGuard() {
	}

	/**
	 * The rule, as a function of two facts and nothing else — no Android in it, so it can be
	 * tested.
	 *
	 * @param hasServer whether this build has a server address at all
	 * @param signedIn  whether this device holds a session
	 */
	public static Gate gate(boolean hasServer, boolean signedIn) {
		return mustSignIn(hasServer, signedIn) ? Gate.SIGN_IN : Gate.THE_APP;
	}

	/** Whether somebody has to sign in before this phone can be used. */
	public static boolean mustSignIn(boolean hasServer, boolean signedIn) {
		return hasServer && !signedIn;
	}
}
