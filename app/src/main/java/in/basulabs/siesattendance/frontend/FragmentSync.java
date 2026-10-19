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
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Arrays;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.sync.SyncClient;
import in.basulabs.siesattendance.backend.sync.SyncSignIn;
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

	private TextView tvState, tvPending, tvError;

	private View groupSignIn, groupSignedIn;

	private TextInputEditText etEmail, etPassword;

	private TextInputLayout tilEmail, tilPassword;

	private AutoCompleteTextView actvDoor;

	@NonNull
	private SyncSignIn.Door door = SyncSignIn.Door.STAFF;

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
		tvPending = view.findViewById(R.id.tvPendingChanges);
		tvError = view.findViewById(R.id.tvSyncError);
		groupSignIn = view.findViewById(R.id.groupSignIn);
		groupSignedIn = view.findViewById(R.id.groupSignedIn);
		etEmail = view.findViewById(R.id.etSyncEmail);
		etPassword = view.findViewById(R.id.etSyncPassword);
		tilEmail = view.findViewById(R.id.tilSyncEmail);
		tilPassword = view.findViewById(R.id.tilSyncPassword);
		actvDoor = view.findViewById(R.id.actvSyncDoor);

		bindDoor();

		view.<MaterialButton>findViewById(R.id.btnSignIn).setOnClickListener(v -> signIn());
		view.<MaterialButton>findViewById(R.id.btnSyncNow)
				.setOnClickListener(v -> viewModel.syncNow());
		view.<MaterialButton>findViewById(R.id.btnSignOut).setOnClickListener(v ->
				viewModel.signOut(() -> Ui.onMain(this, this::refresh)));

		viewModel.getPendingChanges().observe(getViewLifecycleOwner(), pending -> {

			int waiting = pending == null ? 0 : pending;

			tvPending.setText(waiting == 0
					? getString(R.string.sync_nothing_waiting)
					: getResources().getQuantityString(R.plurals.sync_changes_waiting, waiting,
							waiting));
		});

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

	/** Shows what is true now: signed in or not, and what went wrong if anything did. */
	private void refresh() {

		boolean configured = new SyncClient(requireContext()).isConfigured();
		boolean signedIn = configured && viewModel.isSignedIn();

		groupSignIn.setVisibility(configured && !signedIn ? View.VISIBLE : View.GONE);
		groupSignedIn.setVisibility(signedIn ? View.VISIBLE : View.GONE);

		if (!configured) {
			tvState.setText(R.string.sync_no_server);
			tvPending.setVisibility(View.GONE);
			tvError.setVisibility(View.GONE);
			return;
		}

		tvPending.setVisibility(View.VISIBLE);

		tvState.setText(signedIn
				? getString(R.string.sync_signed_in, viewModel.deviceNode())
				: getString(R.string.sync_not_signed_in));

		String error = viewModel.lastSyncError();

		tvError.setVisibility(error == null ? View.GONE : View.VISIBLE);
		tvError.setText(error == null ? "" : error);
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

		viewModel.signIn(email, door, characters, displayName ->
				Ui.onMain(this, () -> {

					if (displayName != null) {
						Arrays.fill(characters, '\0');
					}
					refresh();
				}));
	}
}
