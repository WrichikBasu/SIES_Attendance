package in.basulabs.siesattendance.frontend.util;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.function.BooleanSupplier;

import in.basulabs.siesattendance.R;

/**
 * Asks before Back leaves a register with marks that have not been saved.
 * <p>
 * Neither attendance screen used to listen for Back, so a professor who changed a few marks and
 * pressed it lost them without a word. The marks live only in the ViewModel until Save, and the
 * next visit starts afresh from what was saved, so there was nothing to come back to either.
 * <p>
 * The toolbar's up arrow goes through the same dispatcher (see {@code ActivityHome}), so this
 * covers both. Leaving by saving does not: those paths navigate directly, after the write.
 */
public final class UnsavedMarksGuard {

	private UnsavedMarksGuard() {
	}

	/**
	 * Installs the guard for as long as the fragment's view lives.
	 *
	 * @param fragment the attendance screen
	 * @param unsaved  whether leaving now would lose marks; asked at the moment Back is pressed
	 */
	public static void install(@NonNull Fragment fragment, @NonNull BooleanSupplier unsaved) {

		OnBackPressedCallback callback = new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {

				if (!unsaved.getAsBoolean()) {
					leave(this, fragment);
					return;
				}

				new MaterialAlertDialogBuilder(fragment.requireContext())
						.setTitle(R.string.dialog_leave_unsaved_title)
						.setMessage(R.string.dialog_leave_unsaved_message)
						.setNegativeButton(R.string.action_keep_marking, null)
						.setPositiveButton(R.string.action_discard_marks,
								(d, w) -> leave(this, fragment))
						.show();
			}
		};

		fragment.requireActivity().getOnBackPressedDispatcher()
				.addCallback(fragment.getViewLifecycleOwner(), callback);
	}

	/** Steps aside and lets the Back that was intercepted happen. */
	private static void leave(@NonNull OnBackPressedCallback callback, @NonNull Fragment fragment) {

		if (!fragment.isAdded()) {
			return;
		}
		callback.setEnabled(false);
		fragment.requireActivity().getOnBackPressedDispatcher().onBackPressed();
	}
}
