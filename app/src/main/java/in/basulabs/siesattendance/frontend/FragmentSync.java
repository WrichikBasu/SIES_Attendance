package in.basulabs.siesattendance.frontend;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.EntitySyncState;
import in.basulabs.siesattendance.backend.sync.SyncClient;
import in.basulabs.siesattendance.frontend.util.PasswordChange;
import in.basulabs.siesattendance.frontend.util.SyncAge;
import in.basulabs.siesattendance.frontend.util.Ui;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * Signing in to the server, and what syncing is doing.
 * <p>
 * The last piece of the transport, and the only part of it a person sees. Everything else —
 * queueing a change, draining it, applying what comes back — happens without being asked; this
 * screen exists to get a session in the first place, and then to answer "has my attendance
 * reached the server".
 *
 * <h3>Two doors, and the screen has to say which</h3>
 * {@code app_account} is unique per door, so one address may hold a staff account and a student
 * account. The server will not guess, and neither does this: the choice is on the form, with a
 * sentence saying what it means, because "Staff or Student" without one is a question a professor
 * should not have to think about.
 *
 * <h3>It is honest about doing nothing</h3>
 * A build with no server address — which is every release build today — says so and offers no
 * form. An app that showed a sign-in box with nowhere to send it would be worse than one that
 * admits it is local.
 */
public class FragmentSync extends Fragment {

	private AttendanceViewModel viewModel;

	private TextView tvState, tvUp, tvDown, tvError, tvMustChange;

	private View groupSignedIn, groupSyncing;

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
	                         @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_sync, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {

		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity()).get(AttendanceViewModel.class);

		tvState = view.findViewById(R.id.tvSyncState);
		tvUp = view.findViewById(R.id.tvSyncUp);
		tvDown = view.findViewById(R.id.tvSyncDown);
		groupSyncing = view.findViewById(R.id.groupSyncing);
		tvError = view.findViewById(R.id.tvSyncError);
		tvMustChange = view.findViewById(R.id.tvMustChangePassword);
		groupSignedIn = view.findViewById(R.id.groupSignedIn);

		view.<MaterialButton>findViewById(R.id.btnSyncNow)
				.setOnClickListener(v -> viewModel.syncNow());

		view.<MaterialButton>findViewById(R.id.btnChangePassword)
				.setOnClickListener(v -> askForNewPassword());
		// Out means out: the whole graph is popped and the door is what is left. Nothing of
		// what was on the stack can be resumed by whoever is handed the phone next, and the
		// rows stay on the device for the next person who signs in -- see signOut.
		view.<MaterialButton>findViewById(R.id.btnSignOut).setOnClickListener(v ->
				viewModel.signOut(() -> Ui.onMain(this, () ->
						Navigation.findNavController(requireView())
								.navigate(R.id.action_global_signIn))));

		// Both directions, both live. The queue count is a Room query and the state row is
		// another, so the two lines keep themselves current while a sync is running rather
		// than only when somebody returns to this screen.
		viewModel.getPendingChanges().observe(getViewLifecycleOwner(), pending -> {

			int waiting = pending == null ? 0 : pending;

			tvUp.setText(waiting == 0
					? getString(R.string.sync_up_nothing)
					: getResources().getQuantityString(R.plurals.sync_changes_waiting, waiting,
							waiting));
		});

		viewModel.getSyncState().observe(getViewLifecycleOwner(), this::showWhatCameDown);

		viewModel.getSyncRunning().observe(getViewLifecycleOwner(), running ->
				groupSyncing.setVisibility(Boolean.TRUE.equals(running)
						? View.VISIBLE : View.GONE));

		// Nothing observes a message here. Signing in and out report through the ViewModel's
		// toast channel, which the activity shows wherever the user has got to by then --
		// signing in takes a few seconds and they may well have walked away from this screen.
		refresh();
	}

	@Override
	public void onResume() {
		super.onResume();
		// The worker may have run while another screen was in front, so the state is read
		// again rather than observed: it changes a few times a day, and a LiveData for it would
		// be a LiveData over SharedPreferences.
		refresh();
	}

	/**
	 * The second direction: what the server has sent this phone, and when.
	 * <p>
	 * Three states, and the difference between the last two is the one that matters. No
	 * watermark means the server's copy has never arrived — a phone that has pushed happily for
	 * a week can still be in that state, which is exactly the confusion the old single line
	 * caused. A watermark with nothing new means this phone is in step. A watermark with rows
	 * means the last run brought those down.
	 */
	private void showWhatCameDown(@Nullable EntitySyncState state) {

		if (state == null || state.watermark == null || state.lastPullAt <= 0) {
			tvDown.setText(R.string.sync_down_never);
			return;
		}

		String when = describe(SyncAge.of(state.lastPullAt, System.currentTimeMillis()));
		int received = viewModel.lastReceived();

		tvDown.setText(received == 0
				? getString(R.string.sync_down_nothing_new, when)
				: getResources().getQuantityString(R.plurals.sync_down_received, received,
						received, when));
	}

	/** One bucket from {@link SyncAge}, in words. */
	@NonNull
	private String describe(@NonNull SyncAge.Age age) {

		return switch (age.scale()) {
			case NEVER, MOMENTS -> getString(R.string.age_moments);
			case MINUTES -> getResources().getQuantityString(R.plurals.age_minutes,
					age.quantity(), age.quantity());
			case HOURS -> getResources().getQuantityString(R.plurals.age_hours,
					age.quantity(), age.quantity());
			case DAYS -> getResources().getQuantityString(R.plurals.age_days,
					age.quantity(), age.quantity());
		};
	}

	/** Shows what is true now: signed in or not, and what went wrong if anything did. */
	private void refresh() {

		boolean configured = new SyncClient(requireContext()).isConfigured();
		boolean signedIn = configured && viewModel.isSignedIn();

		// There is no sign-in form here any more: the app does not open without a session, so
		// this screen is only ever reached by somebody who has one. The exception is a build
		// with no server at all, where there is nothing to sign in to and nothing to show.
		groupSignedIn.setVisibility(signedIn ? View.VISIBLE : View.GONE);

		if (!configured) {
			tvState.setText(R.string.sync_no_server);
			tvUp.setVisibility(View.GONE);
			tvDown.setVisibility(View.GONE);
			tvError.setVisibility(View.GONE);
			return;
		}

		tvUp.setVisibility(View.VISIBLE);
		tvDown.setVisibility(View.VISIBLE);

		// Who, not which device. The node is the key-issuing number and belongs to the sync
		// machinery, not to the person holding the phone.
		String who = viewModel.signedInAs();

		tvState.setText(!signedIn
				? getString(R.string.sync_not_signed_in)
				: who == null || who.isBlank()
						? getString(R.string.sync_signed_in)
						: getString(R.string.sync_signed_in_as, who));

		String error = viewModel.lastSyncError();

		tvError.setVisibility(error == null ? View.GONE : View.VISIBLE);
		tvError.setText(error == null ? "" : error);

		tvMustChange.setVisibility(signedIn && viewModel.passwordChangeRequired()
				? View.VISIBLE : View.GONE);
	}

	/**
	 * Asks for the current password and the new one twice.
	 * <p>
	 * Three boxes rather than two, because the server never sees the third: a new password
	 * typed differently in the two boxes would otherwise be set to whichever was in the first
	 * one, and the person would find out at the next sign-in. {@code PasswordChange} is where
	 * that rule and the other three this side can see are written down.
	 * <p>
	 * The buttons are wired after {@code show()}, as everywhere else in this app: the builder's
	 * own listener always dismisses, and a refusal has to be able to leave the dialog open with
	 * the error on the field it is about.
	 */
	private void askForNewPassword() {

		View content = getLayoutInflater().inflate(R.layout.dialog_change_password, null);

		TextInputLayout currentLayout = content.findViewById(R.id.tilCurrentPassword);
		TextInputLayout newLayout = content.findViewById(R.id.tilNewPassword);
		TextInputLayout confirmLayout = content.findViewById(R.id.tilConfirmPassword);
		TextInputEditText currentField = content.findViewById(R.id.etCurrentPassword);
		TextInputEditText newField = content.findViewById(R.id.etNewPassword);
		TextInputEditText confirmField = content.findViewById(R.id.etConfirmPassword);

		content.<TextView>findViewById(R.id.tvChangePasswordExplainer).setText(
				getString(R.string.change_password_explained,
						PasswordChange.MINIMUM_LENGTH));

		AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_change_password_title)
				.setView(content)
				.setNegativeButton(R.string.action_cancel, null)
				.setPositiveButton(R.string.action_change_password, null)
				.create();

		dialog.show();

		dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {

			String current = textOf(currentField);
			String next = textOf(newField);
			String confirm = textOf(confirmField);

			currentLayout.setError(null);
			newLayout.setError(null);
			confirmLayout.setError(null);

			PasswordChange.Problem problem = PasswordChange.check(current, next, confirm);

			if (problem != null) {
				show(problem, currentLayout, newLayout, confirmLayout);
				return;
			}

			viewModel.changePassword(current.toCharArray(), next.toCharArray(),
					changed -> Ui.onMain(this, () -> {

						// Left open on a refusal, with the server's own sentence already on
						// its way to the toast: the current password is the likely mistake and
						// retyping it is the whole of the fix.
						if (Boolean.TRUE.equals(changed)) {
							dialog.dismiss();
						}
						refresh();
					}));
		});
	}

	/** The problem on the field it is about, so the error is where somebody is looking. */
	private void show(@NonNull PasswordChange.Problem problem,
	                  @NonNull TextInputLayout currentLayout,
	                  @NonNull TextInputLayout newLayout,
	                  @NonNull TextInputLayout confirmLayout) {

		switch (problem) {
			case NO_CURRENT -> currentLayout.setError(
					getString(R.string.error_current_password_required));
			case NO_NEW -> newLayout.setError(
					getString(R.string.error_new_password_required));
			case TOO_SHORT -> newLayout.setError(getString(R.string.error_new_password_short,
					PasswordChange.MINIMUM_LENGTH));
			case ONLY_SPACES -> newLayout.setError(
					getString(R.string.error_new_password_blank));
			case SAME_AS_CURRENT -> newLayout.setError(
					getString(R.string.error_new_password_same));
			case MISMATCH -> confirmLayout.setError(
					getString(R.string.error_new_password_mismatch));
		}
	}

	@NonNull
	private static String textOf(@NonNull TextInputEditText field) {
		return field.getText() == null ? "" : field.getText().toString();
	}
}
