package in.basulabs.siesattendance.frontend.util;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * Whether a register may be marked on this phone, and the one place that decides it.
 * <p>
 * A lecture and its marks are attributed on the server to the account that sent them, and that
 * attribution is the whole point of the audit trail: "who recorded this" has to have an answer
 * that is not "a phone". So a build that has a server to answer to asks for a sign-in before the
 * first register, rather than collecting attendance it will later have to refuse.
 *
 * <h3>What this is not</h3>
 * It is not a connectivity check, and it must never become one. A session is a token on the
 * device; having one is what this asks about. Marking a register in a basement laboratory with
 * no signal works exactly as it did before — that is the requirement the whole design is built
 * around, and a guard that asked the server anything would break it.
 *
 * <h3>A build with no server is not blocked</h3>
 * {@code sync_base_url} is empty in a release build until somebody configures one, and there is
 * nothing to sign in to. Blocking attendance on a build where signing in is impossible would
 * turn the app off, so the rule has two halves and both matter: ask only when there is somewhere
 * to ask about. {@link #mustSignIn} is that rule on its own, with no Android in it, so it can be
 * tested.
 */
public final class SignInGuard {

	private SignInGuard() {
	}

	/**
	 * The rule, as a function of two facts.
	 *
	 * @param hasServer whether this build has a server address at all
	 * @param signedIn  whether this device holds a session
	 * @return whether the person has to sign in before taking attendance
	 */
	public static boolean mustSignIn(boolean hasServer, boolean signedIn) {
		return hasServer && !signedIn;
	}

	/**
	 * Runs the action, or explains why it cannot run and offers the way to fix that.
	 * <p>
	 * Every route into taking attendance goes through here, and
	 * {@code FragmentTakeAttendance} checks again on arrival: a screen that can be reached three
	 * ways is a screen that will be reached a fourth way eventually.
	 *
	 * @param navigation the controller the caller navigates with, which is not always its own —
	 *                   a fragment inside a hub navigates with the hub's
	 */
	public static void ifAllowed(@NonNull Fragment fragment,
	                             @NonNull AttendanceViewModel viewModel,
	                             @NonNull NavController navigation,
	                             @NonNull Runnable action) {

		if (!mustSignIn(viewModel.hasServer(), viewModel.isSignedIn())) {
			action.run();
			return;
		}

		new MaterialAlertDialogBuilder(fragment.requireContext())
				.setTitle(R.string.title_sign_in_required)
				.setMessage(R.string.message_sign_in_required)
				.setPositiveButton(R.string.action_sign_in,
						(dialog, which) -> navigation.navigate(R.id.action_global_sync))
				.setNegativeButton(R.string.action_not_now, null)
				.show();
	}
}
