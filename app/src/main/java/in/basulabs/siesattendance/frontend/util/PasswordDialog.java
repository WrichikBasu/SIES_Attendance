package in.basulabs.siesattendance.frontend.util;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * The change-password dialog, from the Account and sync screen and straight after signing in.
 * <p>
 * The second is the one that matters: a new account has to change the password it was given
 * before the server will do anything else, and the dialog used to be reachable only from a
 * button on a screen nobody had been sent to.
 */
public final class PasswordDialog {

	private PasswordDialog() {
	}

	/**
	 * @param known       the current password when it was typed a moment ago, at sign-in: its
	 *                    field is then left out rather than asked for twice, and the dialog
	 *                    cannot be dismissed except by {@code onCancelled}. Not wiped here
	 * @param onChanged   run on the main thread once the server has accepted the new password
	 * @param onCancelled run on the main thread if the person gives up; null to allow plain
	 *                    cancelling
	 */
	public static void show(@NonNull Fragment fragment, @NonNull AttendanceViewModel viewModel,
	                        @Nullable char[] known, @NonNull Runnable onChanged,
	                        @Nullable Runnable onCancelled) {

		View content = fragment.getLayoutInflater().inflate(R.layout.dialog_change_password,
				null);

		TextInputLayout currentLayout = content.findViewById(R.id.tilCurrentPassword);
		TextInputLayout newLayout = content.findViewById(R.id.tilNewPassword);
		TextInputLayout confirmLayout = content.findViewById(R.id.tilConfirmPassword);
		TextInputEditText currentField = content.findViewById(R.id.etCurrentPassword);
		TextInputEditText newField = content.findViewById(R.id.etNewPassword);
		TextInputEditText confirmField = content.findViewById(R.id.etConfirmPassword);

		String explained = fragment.getString(R.string.change_password_explained,
				PasswordChange.MINIMUM_LENGTH);

		content.<TextView>findViewById(R.id.tvChangePasswordExplainer).setText(known == null
				? explained
				: fragment.getString(R.string.change_password_required) + "\n\n" + explained);

		if (known != null) {
			currentLayout.setVisibility(View.GONE);
		}

		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
				fragment.requireContext())
				.setTitle(R.string.dialog_change_password_title)
				.setView(content)
				.setPositiveButton(R.string.action_change_password, null);

		if (onCancelled == null) {
			builder.setNegativeButton(R.string.action_cancel, null);
		} else {
			builder.setNegativeButton(R.string.action_sign_out, (d, w) -> onCancelled.run())
					.setCancelable(false);
		}

		AlertDialog dialog = builder.create();
		dialog.show();

		dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {

			String current = known == null ? textOf(currentField) : new String(known);
			String next = textOf(newField);
			String confirm = textOf(confirmField);

			currentLayout.setError(null);
			newLayout.setError(null);
			confirmLayout.setError(null);

			PasswordChange.Problem problem = PasswordChange.check(current, next, confirm);

			if (problem != null) {
				show(fragment, problem, currentLayout, newLayout, confirmLayout);
				return;
			}

			viewModel.changePassword(current.toCharArray(), next.toCharArray(),
					changed -> Ui.onMain(fragment, () -> {

						// Left open on a refusal, with the server's own sentence already on
						// its way to the toast: the current password is the likely mistake and
						// retyping it is the whole of the fix.
						if (Boolean.TRUE.equals(changed)) {
							dialog.dismiss();
							onChanged.run();
						}
					}));
		});
	}

	/** The problem on the field it is about, so the error is where somebody is looking. */
	private static void show(@NonNull Fragment fragment, @NonNull PasswordChange.Problem problem,
	                         @NonNull TextInputLayout currentLayout,
	                         @NonNull TextInputLayout newLayout,
	                         @NonNull TextInputLayout confirmLayout) {

		switch (problem) {
			case NO_CURRENT -> currentLayout.setError(
					fragment.getString(R.string.error_current_password_required));
			case NO_NEW -> newLayout.setError(
					fragment.getString(R.string.error_new_password_required));
			case TOO_SHORT -> newLayout.setError(fragment.getString(
					R.string.error_new_password_short, PasswordChange.MINIMUM_LENGTH));
			case ONLY_SPACES -> newLayout.setError(
					fragment.getString(R.string.error_new_password_blank));
			case SAME_AS_CURRENT -> newLayout.setError(
					fragment.getString(R.string.error_new_password_same));
			case MISMATCH -> confirmLayout.setError(
					fragment.getString(R.string.error_new_password_mismatch));
		}
	}

	@NonNull
	private static String textOf(@NonNull TextInputEditText field) {
		return field.getText() == null ? "" : field.getText().toString();
	}
}
