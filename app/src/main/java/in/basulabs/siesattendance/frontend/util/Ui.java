package in.basulabs.siesattendance.frontend.util;

import android.app.Activity;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

/**
 * Getting back onto the main thread from a background callback, safely.
 * <p>
 * Every {@code ...Sync} method on the ViewModel hands its result to a callback running on a
 * database thread, so a fragment receiving one has to post to the main thread before touching
 * a view, and has to check it is still attached once it gets there. Written out by hand that
 * came to {@code requireActivity().runOnUiThread(() -> { if (isAdded()) … })}, which has a
 * trap in the first call: {@code requireActivity()} throws {@link IllegalStateException} when
 * the fragment has been detached, and the user navigating away mid-query is the ordinary case
 * rather than a rare one.
 * <p>
 * Where the ViewModel invoked that callback inside its own {@code try}, the throw was caught
 * as though the write had failed, and the user was told their attendance had not been recorded
 * when it had. Where it was not, it would have gone uncaught out of the executor and taken the
 * process with it. Both come from the same line, so it is written once here: nothing to throw,
 * and nothing runs against a view that has gone.
 */
public final class Ui {

	private Ui() {
	}

	/**
	 * Runs {@code action} on the main thread if, and only if, {@code fragment} is still
	 * attached by the time it gets there. Safe to call from any thread, and a no-op once the
	 * fragment has gone.
	 */
	public static void onMain(@NonNull Fragment fragment, @NonNull Runnable action) {

		Activity activity = fragment.getActivity();

		if (activity == null) {
			return;
		}

		activity.runOnUiThread(() -> {
			if (fragment.isAdded()) {
				action.run();
			}
		});
	}
}
