package in.basulabs.siesattendance.frontend.util;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.function.Consumer;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.sync.ServerAddress;
import in.basulabs.siesattendance.backend.sync.SyncClient;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * Asks for the server's address, from the Account and sync screen and from the sign-in screen.
 * <p>
 * The second is not optional. A phone with a server cannot be used without signing in to it, so
 * an address with a typo in it, or one for a server that has moved, would leave somebody on a
 * sign-in screen with no way back to the screen that sets it.
 * <p>
 * Asks the server before it answers. An address is handed on only once this server's API has
 * answered at it — see {@code SyncClient.probe} — because the screens act on it at once: the
 * Account and sync screen signs out for it, and an address nobody is listening at would be
 * discovered only on the sign-in screen that follows, with the old one already gone.
 * <p>
 * What a change means — signing out first, when somebody is signed in — is the caller's, because
 * the two screens are in different positions to do it.
 */
public final class ServerAddressDialog {

	private ServerAddressDialog() {
	}

	/**
	 * The address in use, as a line on a screen: whose it is, as well as what it is.
	 */
	@NonNull
	public static String describe(@NonNull Context context) {

		String current = ServerAddress.current(context);

		if (current.isEmpty()) {
			return context.getString(R.string.server_none);
		}
		return ServerAddress.isChosen(context)
				? current
				: context.getString(R.string.server_from_build, current);
	}

	/**
	 * @param onChosen run on the main thread with the new address, already normalised and
	 *                 answered by the server, or with null for the build's own; only when it
	 *                 differs from the one in use
	 */
	public static void show(@NonNull Fragment fragment, @NonNull AttendanceViewModel viewModel,
	                        @NonNull Consumer<String> onChosen) {

		Context context = fragment.requireContext();
		View content = fragment.getLayoutInflater().inflate(R.layout.dialog_server_address,
				null);

		TextInputLayout layout = content.findViewById(R.id.tilServerAddress);
		TextInputEditText field = content.findViewById(R.id.etServerAddress);

		if (ServerAddress.isChosen(context)) {
			field.setText(ServerAddress.current(context));
		}
		layout.setPlaceholderText(ServerAddress.builtIn(context));

		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
				.setTitle(R.string.dialog_server_title)
				.setView(content)
				.setPositiveButton(R.string.action_save, null)
				.setNegativeButton(R.string.action_cancel, null);

		if (ServerAddress.isChosen(context)) {
			builder.setNeutralButton(R.string.action_use_built_in, null);
		}

		AlertDialog dialog = builder.create();
		dialog.show();

		// Wired after show(), as everywhere else in this app: the builder's own listener always
		// dismisses, and a refusal has to leave the dialog open with the error on the field.
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {

			String typed = field.getText() == null ? "" : field.getText().toString().trim();
			layout.setError(null);

			if (typed.isEmpty()) {
				useBuiltIn(fragment, viewModel, dialog, layout, field, onChosen);
				return;
			}

			ServerAddress.Problem problem = ServerAddress.problemWith(typed);

			if (problem != null) {
				layout.setError(context.getString(switch (problem) {
					case NOT_AN_ADDRESS -> R.string.error_server_not_an_address;
					case NOT_HTTP -> R.string.error_server_not_http;
					case CLEARTEXT_REFUSED -> R.string.error_server_cleartext;
				}));
				return;
			}

			String address = ServerAddress.normalise(typed);

			if (address.equals(ServerAddress.current(context))) {
				dialog.dismiss();
				return;
			}

			check(fragment, viewModel, dialog, layout, field, address, onChosen);
		});

		if (ServerAddress.isChosen(context)) {
			dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
				layout.setError(null);
				useBuiltIn(fragment, viewModel, dialog, layout, field, onChosen);
			});
		}
	}

	/** Goes back to the build's address, checking it first when there is one to check. */
	private static void useBuiltIn(@NonNull Fragment fragment,
	                               @NonNull AttendanceViewModel viewModel,
	                               @NonNull AlertDialog dialog, @NonNull TextInputLayout layout,
	                               @NonNull TextInputEditText field,
	                               @NonNull Consumer<String> onChosen) {

		Context context = fragment.requireContext();

		if (!ServerAddress.isChosen(context)) {
			dialog.dismiss();
			return;
		}

		String builtIn = ServerAddress.builtIn(context);

		if (builtIn.isEmpty()) {
			// No server at all: there is nothing to ask, and working on its own is always
			// available.
			dialog.dismiss();
			onChosen.accept(null);
			return;
		}

		check(fragment, viewModel, dialog, layout, field, builtIn,
				address -> onChosen.accept(null));
	}

	/**
	 * Asks the server, with the dialog held still while it does, and hands the address on only
	 * if this server's API answered at it. Anything else leaves the dialog open with the reason
	 * on the field, where the address can be corrected.
	 */
	private static void check(@NonNull Fragment fragment, @NonNull AttendanceViewModel viewModel,
	                          @NonNull AlertDialog dialog, @NonNull TextInputLayout layout,
	                          @NonNull TextInputEditText field, @NonNull String address,
	                          @NonNull Consumer<String> onChosen) {

		setChecking(fragment, dialog, layout, field, true);

		viewModel.checkServer(address, answer -> Ui.onMain(fragment, () -> {

			setChecking(fragment, dialog, layout, field, false);

			if (answer == SyncClient.Probe.SIES) {
				dialog.dismiss();
				onChosen.accept(address);
				return;
			}

			layout.setError(fragment.getString(switch (answer) {
				case NO_SUCH_HOST -> R.string.error_server_no_such_host;
				case NO_ANSWER -> R.string.error_server_no_answer;
				case CERTIFICATE -> R.string.error_server_certificate;
				case NOT_SIES, SIES -> R.string.error_server_not_sies;
			}));
		}));
	}

	/** Nothing can be pressed or typed while the server is being asked, and it says so. */
	private static void setChecking(@NonNull Fragment fragment, @NonNull AlertDialog dialog,
	                                @NonNull TextInputLayout layout,
	                                @NonNull TextInputEditText field, boolean checking) {

		field.setEnabled(!checking);
		layout.setHelperText(checking ? fragment.getString(R.string.server_checking) : null);
		dialog.setCancelable(!checking);

		for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE,
				AlertDialog.BUTTON_NEUTRAL}) {

			if (dialog.getButton(which) != null) {
				dialog.getButton(which).setEnabled(!checking);
			}
		}
	}
}
