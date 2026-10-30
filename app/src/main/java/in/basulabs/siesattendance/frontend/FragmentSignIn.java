package in.basulabs.siesattendance.frontend;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavOptions;
import androidx.navigation.Navigation;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Arrays;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.sync.SyncSignIn;
import in.basulabs.siesattendance.frontend.util.PasswordDialog;
import in.basulabs.siesattendance.frontend.util.Ui;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * The door. Nothing else in the app is reachable until this screen is satisfied.
 * <p>
 * The navigation graph starts here whenever {@code SignInGuard.gate} says so, and
 * {@code ActivityHome} hides the toolbar and the bottom bar while it is showing: a tab bar on a
 * locked screen offers somewhere to go that is not there.
 *
 * <h3>Two doors, and the screen has to say which</h3>
 * {@code app_account} is unique per door, so one address may hold a staff account and a student
 * account. The server will not guess and neither does this: the choice is on the form, with a
 * sentence saying what it means, because "Staff or Student" without one is a question a professor
 * should not have to think about.
 *
 * <h3>Once, not every morning</h3>
 * A session is a token kept on the device, so this screen is seen on a new install, after signing
 * out, and after a session is revoked — not daily, and never merely because there is no signal. A
 * professor in a basement laboratory marks a register exactly as before; that is the requirement
 * the whole design is built around.
 *
 * <h3>A build with no server</h3>
 * {@code sync_base_url} is empty until somebody configures one, and then there is no account
 * anywhere to sign in to. This screen says so and offers the way through, because a locked screen
 * with no way past it is a broken app rather than a secured one. See {@code SignInGuard}.
 */
public class FragmentSignIn extends Fragment {

	private AttendanceViewModel viewModel;

	private TextInputEditText etEmail, etPassword;

	private TextInputLayout tilEmail, tilPassword;

	private AutoCompleteTextView actvDoor;

	private TextView tvError;

	@NonNull
	private SyncSignIn.Door door = SyncSignIn.Door.STAFF;

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
	                         @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_sign_in, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {

		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity()).get(AttendanceViewModel.class);

		// This screen is the only one with no app bar above it, so it is the only one that
		// has to inset itself. ActivityHome pads the bars rather than the container; with the
		// bar hidden there is nothing between this content and the clock, which on Android 15
		// -- where edge-to-edge is forced for apps targeting SDK 35 -- means the headline
		// under the status bar and the button under the gesture handle.
		ViewCompat.setOnApplyWindowInsetsListener(view, (padded, windowInsets) -> {

			Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
					| WindowInsetsCompat.Type.displayCutout());

			padded.setPadding(bars.left, bars.top, bars.right, bars.bottom);
			return windowInsets;
		});

		etEmail = view.findViewById(R.id.etSignInEmail);
		etPassword = view.findViewById(R.id.etSignInPassword);
		tilEmail = view.findViewById(R.id.tilSignInEmail);
		tilPassword = view.findViewById(R.id.tilSignInPassword);
		actvDoor = view.findViewById(R.id.actvSignInDoor);
		tvError = view.findViewById(R.id.tvSignInError);

		boolean hasServer = viewModel.hasServer();

		view.findViewById(R.id.groupCredentials)
				.setVisibility(hasServer ? View.VISIBLE : View.GONE);
		view.findViewById(R.id.groupNoServer)
				.setVisibility(hasServer ? View.GONE : View.VISIBLE);

		view.<TextView>findViewById(R.id.tvSignInHeadline).setText(hasServer
				? R.string.sign_in_headline
				: R.string.sign_in_headline_no_server);

		bindDoor();

		view.<MaterialButton>findViewById(R.id.btnDoSignIn).setOnClickListener(v -> signIn());

		// The way through on a build with nowhere to sign in to. Deliberately not a silent
		// skip: somebody should see that this phone is answering to nobody.
		view.<MaterialButton>findViewById(R.id.btnContinueLocally)
				.setOnClickListener(v -> enterTheApp());

		// The last refusal, kept so it is still on the screen after a rotation.
		String refusal = viewModel.lastSyncError();

		tvError.setVisibility(refusal == null ? View.GONE : View.VISIBLE);
		tvError.setText(refusal == null ? "" : refusal);
	}

	private void bindDoor() {

		actvDoor.setAdapter(new ArrayAdapter<>(requireContext(),
				android.R.layout.simple_list_item_1,
				new String[]{getString(R.string.door_staff), getString(R.string.door_student)}));

		actvDoor.setText(getString(R.string.door_staff), false);

		actvDoor.setOnItemClickListener((parent, view, position, id) ->
				door = position == 0 ? SyncSignIn.Door.STAFF : SyncSignIn.Door.STUDENT);
	}

	private void signIn() {

		String email = etEmail.getText() == null ? "" : etEmail.getText().toString().trim();
		String password = etPassword.getText() == null ? "" : etPassword.getText().toString();

		tilEmail.setError(email.isEmpty() ? getString(R.string.error_email_required) : null);
		tilPassword.setError(password.isEmpty()
				? getString(R.string.error_sync_password_required) : null);

		if (email.isEmpty() || password.isEmpty()) {
			return;
		}

		char[] characters = password.toCharArray();

		// Cleared here as well as inside SyncSignIn. The field's own text is a String and
		// cannot be wiped at all, which is the limit of what a form can do: it is why the
		// password is never kept anywhere after this and why the token is what gets stored.
		etPassword.setText("");

		signIn(email, characters, false);
	}

	/**
	 * Signs in with a copy of the password, keeping this one until the answer is in: the
	 * question about another account's unsent changes needs it a second time, and asking for
	 * it again would only be asking the user to retype what they have just typed.
	 */
	private void signIn(@NonNull String email, @NonNull char[] characters,
	                    boolean discardUnsent) {

		viewModel.signIn(email, door, characters.clone(), discardUnsent,
				(unsent, previous) -> Ui.onMain(this, () ->
						askToDiscard(email, characters, unsent, previous)),
				displayName -> Ui.onMain(this, () -> {

					if (displayName == null) {
						// The server's own sentence is already on its way to the snackbar;
						// the screen stays where it is, with the address still typed.
						Arrays.fill(characters, '\0');
						return;
					}

					if (!viewModel.passwordChangeRequired()) {
						Arrays.fill(characters, '\0');
						enterTheApp();
						return;
					}

					// A new account, or one whose password was reset: the server refuses
					// everything else until it is changed, so it is asked for here rather
					// than leaving somebody in an app that cannot sync.
					PasswordDialog.show(this, viewModel, characters,
							() -> {
								Arrays.fill(characters, '\0');
								enterTheApp();
							},
							() -> {
								Arrays.fill(characters, '\0');
								viewModel.abandonSignIn();
							});
				}));
	}

	/**
	 * The previous account left changes that never reached the server, and this sign-in would
	 * delete them along with the rest of its data. The same question signing out asks.
	 */
	private void askToDiscard(@NonNull String email, @NonNull char[] characters, int unsent,
	                          @Nullable String previous) {

		String who = previous == null ? getString(R.string.the_previous_account) : previous;

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_sign_out_unsent_title)
				.setMessage(getResources().getQuantityString(
						R.plurals.dialog_sign_in_unsent_message, unsent, unsent, who))
				.setPositiveButton(R.string.action_sign_in_anyway,
						(d, w) -> signIn(email, characters, true))
				.setNegativeButton(R.string.action_cancel,
						(d, w) -> Arrays.fill(characters, '\0'))
				.setOnCancelListener(d -> Arrays.fill(characters, '\0'))
				.show();
	}

	/**
	 * Goes through, and leaves nothing behind to come back to.
	 * <p>
	 * {@code popUpTo} this destination inclusively, so the back button from the dashboard
	 * leaves the app rather than returning to a sign-in screen that has already been
	 * satisfied — and so that nothing in the back stack can be resumed by somebody who was
	 * handed the phone.
	 */
	private void enterTheApp() {

		Navigation.findNavController(requireView()).navigate(R.id.fragmentDashboard,
				null,
				new NavOptions.Builder()
						.setPopUpTo(R.id.fragmentSignIn, true)
						.build());
	}
}
