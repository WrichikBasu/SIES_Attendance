package in.basulabs.siesattendance.frontend;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Map;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.ConstantsAndStatics;
import in.basulabs.siesattendance.backend.util.BackupCrypto;
import in.basulabs.siesattendance.backend.util.DatabaseBackup;
import in.basulabs.siesattendance.backend.util.DatabaseMerger;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * Backup and restore.
 * <p>
 * Both halves go through the system file picker rather than writing to a fixed path, so the user
 * chooses where a backup lands, whether that is a cloud folder, a memory card or wherever they
 * already keep things, and the app needs no storage permission at all.
 * <p>
 * A restore ends by restarting the app. Every screen, DAO and observer in memory is bound to
 * the database file that has just been replaced, and there is no sound way to re-point them
 * mid-flight; a restart is both simpler and easier to reason about than trying.
 */
public class FragmentBackup extends Fragment {

	private AttendanceViewModel viewModel;

	private MaterialSwitch switchEncrypt;

	private View progress;

	private TextView progressLabel;

	/**
	 * Held between choosing a file and the password being typed, because the picker result and
	 * the dialog result arrive in separate callbacks.
	 */
	@Nullable
	private Uri pendingImport;

	/** Chosen before the file picker opens, and reused if a wrong password needs a retry. */
	@NonNull
	private DatabaseBackup.Mode pendingMode = DatabaseBackup.Mode.REPLACE;

	/**
	 * Launchers must be registered before the fragment starts, which is why these are field
	 * initialisers rather than set up in {@code onViewCreated}.
	 */
	private final ActivityResultLauncher<String> exportPicker = registerForActivityResult(
			new ActivityResultContracts.CreateDocument(
					ConstantsAndStatics.BACKUP_MIME_TYPE), uri -> {
				if (uri != null) {
					onExportDestinationChosen(uri);
				}
			});

	private final ActivityResultLauncher<String[]> importPicker = registerForActivityResult(
			new ActivityResultContracts.OpenDocument(), uri -> {
				if (uri != null) {
					onImportSourceChosen(uri);
				}
			});

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
	                         @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_backup, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity()).get(AttendanceViewModel.class);

		switchEncrypt = view.findViewById(R.id.switchEncrypt);
		progress = view.findViewById(R.id.layoutProgress);
		progressLabel = view.findViewById(R.id.tvProgress);

		MaterialButton export = view.findViewById(R.id.btnExport);
		MaterialButton importButton = view.findViewById(R.id.btnImport);

		export.setOnClickListener(v -> exportPicker.launch(
				DatabaseBackup.suggestedFileName(switchEncrypt.isChecked())));

		importButton.setOnClickListener(v -> confirmBeforeImport());

		observe();
	}

	// ── Export ───────────────────────────────────────────────────────────

	/** Runs once the user has picked where the backup goes, and after any password has been set. */
	private void onExportDestinationChosen(@NonNull Uri destination) {

		if (!switchEncrypt.isChecked()) {
			warnAboutPlainBackup(destination);
			return;
		}

		askForNewPassword(password -> {
			progressLabel.setText(R.string.progress_exporting);
			viewModel.exportBackup(destination, password);
		});
	}

	/**
	 * An unencrypted backup is a complete copy of every student's name, email and attendance
	 * record in a file the user is about to put somewhere else. That is a legitimate choice,
	 * but it should be a deliberate one rather than the result of leaving a switch alone.
	 */
	private void warnAboutPlainBackup(@NonNull Uri destination) {

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_plain_backup_title)
				.setMessage(R.string.dialog_plain_backup_message)
				.setNegativeButton(R.string.action_cancel, null)
				.setPositiveButton(R.string.action_export_anyway, (d, w) -> {
					progressLabel.setText(R.string.progress_exporting);
					viewModel.exportBackup(destination, null);
				})
				.show();
	}

	// ── Import ───────────────────────────────────────────────────────────

	/**
	 * Asks how the backup should be brought in before anything else happens.
	 * <p>
	 * The two modes differ enough that picking one afterwards, once a file is already chosen,
	 * would invite the wrong answer: replace throws away current work, merge cannot. It is
	 * asked first, and stated in terms of what happens to the data rather than by name.
	 */
	private void confirmBeforeImport() {

		String[] options = {
				getString(R.string.option_restore_merge),
				getString(R.string.option_restore_replace)
		};

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_restore_mode_title)
				.setItems(options, (d, which) -> {
					pendingMode = which == 0
							? DatabaseBackup.Mode.MERGE
							: DatabaseBackup.Mode.REPLACE;

					if (pendingMode == DatabaseBackup.Mode.REPLACE) {
						warnBeforeReplace();
					} else {
						// Any file type: the picker cannot filter on a custom extension
						// reliably across providers, and a wrong choice is caught by the
						// header check anyway.
						importPicker.launch(new String[]{"*/*"});
					}
				})
				.setNegativeButton(R.string.action_cancel, null)
				.show();
	}

	/**
	 * The extra warning shown only for a replacing restore, which is the one mode that discards
	 * what is currently on the device. A merge needs no such warning: it never deletes anything.
	 */
	private void warnBeforeReplace() {

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_restore_title)
				.setMessage(R.string.dialog_restore_message)
				.setNegativeButton(R.string.action_cancel, null)
				.setPositiveButton(R.string.action_choose_file, (d, w) ->
						importPicker.launch(new String[]{"*/*"}))
				.show();
	}

	/**
	 * Inspects the chosen file and decides what to ask for next.
	 * <p>
	 * The header is readable without the password, so an unusable file is rejected with a
	 * specific message here rather than surfacing later as a decryption failure.
	 */
	private void onImportSourceChosen(@NonNull Uri source) {

		pendingImport = source;

		// The header says whether a password is needed, so an unencrypted backup never
		// prompts for one and an encrypted one never fails halfway for the want of it.
		viewModel.inspectBackup(source, info -> {

			if (!isAdded()) {
				return;
			}

			if (info == null) {
				showMessage(R.string.dialog_restore_failed_title,
						getString(R.string.error_not_a_backup));
				pendingImport = null;
				return;
			}

			if (info.schemaVersion < 0) {
				showMessage(R.string.dialog_restore_failed_title,
						getString(R.string.error_backup_too_new));
				pendingImport = null;
				return;
			}

			if (info.encrypted) {
				askForExistingPassword(info, password -> startRestore(source, password));
			} else {
				confirmRestoreOf(info, () -> startRestore(source, null));
			}
		});
	}

	/** Hands the restore to the ViewModel, which runs it off the main thread. */
	private void startRestore(@NonNull Uri source, @Nullable char[] password) {
		progressLabel.setText(pendingMode == DatabaseBackup.Mode.MERGE
				? R.string.progress_merging
				: R.string.progress_restoring);
		viewModel.importBackup(source, password, pendingMode);
	}

	/**
	 * Last confirmation, showing when the backup was made so the user can catch an accidental
	 * restore of a stale file before it overwrites current work.
	 */
	private void confirmRestoreOf(@NonNull DatabaseBackup.Info info, @NonNull Runnable onGo) {

		boolean merging = pendingMode == DatabaseBackup.Mode.MERGE;

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(merging
						? R.string.dialog_merge_confirm_title
						: R.string.dialog_restore_confirm_title)
				.setMessage(getString(merging
								? R.string.dialog_merge_confirm_message
								: R.string.dialog_restore_confirm_message,
						formatDate(info.createdAt)))
				.setNegativeButton(R.string.action_cancel, null)
				.setPositiveButton(merging ? R.string.action_merge : R.string.action_restore,
						(d, w) -> onGo.run())
				.show();
	}

	// ── Password dialogs ─────────────────────────────────────────────────

	private interface PasswordCallback {
		void onPassword(@NonNull char[] password);
	}

	/**
	 * Asks twice, because a typo in a password that is never stored anywhere makes the backup
	 * permanently unreadable and there is no way to find that out later.
	 */
	private void askForNewPassword(@NonNull PasswordCallback callback) {

		View content = getLayoutInflater().inflate(R.layout.dialog_backup_password, null);

		TextView explainer = content.findViewById(R.id.tvPasswordExplainer);
		TextInputLayout passwordLayout = content.findViewById(R.id.tilPassword);
		TextInputLayout confirmLayout = content.findViewById(R.id.tilConfirm);
		TextInputEditText passwordField = content.findViewById(R.id.etPassword);
		TextInputEditText confirmField = content.findViewById(R.id.etConfirm);

		explainer.setText(R.string.hint_set_backup_password);

		androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(
				requireContext())
				.setTitle(R.string.dialog_set_password_title)
				.setView(content)
				.setNegativeButton(R.string.action_cancel, null)
				.setPositiveButton(R.string.action_export_backup, null)
				.create();

		dialog.show();

		// Wired after show() so a validation failure can leave the dialog open; the builder's
		// own listener always dismisses.
		dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
				.setOnClickListener(v -> {

					char[] password = charsOf(passwordField.getText());
					char[] confirm = charsOf(confirmField.getText());

					if (password.length < MIN_PASSWORD_LENGTH) {
						passwordLayout.setError(getString(R.string.error_password_too_short,
								MIN_PASSWORD_LENGTH));
						BackupCrypto.wipe(password);
						BackupCrypto.wipe(confirm);
						return;
					}

					if (!java.util.Arrays.equals(password, confirm)) {
						confirmLayout.setError(getString(R.string.error_passwords_differ));
						BackupCrypto.wipe(password);
						BackupCrypto.wipe(confirm);
						return;
					}

					BackupCrypto.wipe(confirm);
					passwordLayout.setError(null);
					confirmLayout.setError(null);
					dialog.dismiss();
					callback.onPassword(password);
				});
	}

	/**
	 * Asks for the password of a file being restored. There is no way to check it short of
	 * attempting the decryption, so a wrong one comes back through {@link #onRestoreFailed} and
	 * returns here for another try.
	 */
	private void askForExistingPassword(@NonNull DatabaseBackup.Info info,
	                                    @NonNull PasswordCallback callback) {

		View content = getLayoutInflater().inflate(R.layout.dialog_backup_password, null);

		TextView explainer = content.findViewById(R.id.tvPasswordExplainer);
		TextInputLayout passwordLayout = content.findViewById(R.id.tilPassword);
		TextInputEditText passwordField = content.findViewById(R.id.etPassword);

		// Only one field when unlocking: there is nothing to confirm against.
		content.findViewById(R.id.tilConfirm).setVisibility(View.GONE);
		explainer.setText(getString(R.string.hint_enter_backup_password,
				formatDate(info.createdAt)));

		androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(
				requireContext())
				.setTitle(R.string.dialog_enter_password_title)
				.setView(content)
				.setNegativeButton(R.string.action_cancel, null)
				.setPositiveButton(R.string.action_restore, null)
				.create();

		dialog.show();

		dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
				.setOnClickListener(v -> {

					char[] password = charsOf(passwordField.getText());

					if (password.length == 0) {
						passwordLayout.setError(getString(R.string.error_password_required));
						return;
					}

					passwordLayout.setError(null);
					dialog.dismiss();
					callback.onPassword(password);
				});
	}

	/**
	 * The shortest password accepted for a new backup. A floor rather than a policy: the key
	 * derivation is deliberately slow, but nothing saves a password of three characters.
	 */
	private static final int MIN_PASSWORD_LENGTH = 8;

	/**
	 * Pulls the characters out without going through {@code toString()}. A String would sit in
	 * the heap until collected and cannot be overwritten, which defeats the point of wiping
	 * the buffer afterwards.
	 */
	@NonNull
	private static char[] charsOf(@Nullable Editable editable) {

		if (editable == null) {
			return new char[0];
		}

		char[] out = new char[editable.length()];
		editable.getChars(0, editable.length(), out, 0);
		return out;
	}

	// ── Outcomes ─────────────────────────────────────────────────────────

	private void observe() {

		viewModel.isBusy().observe(getViewLifecycleOwner(), busy -> {
			boolean running = busy != null && busy;
			progress.setVisibility(running ? View.VISIBLE : View.GONE);
			requireView().findViewById(R.id.btnExport).setEnabled(!running);
			requireView().findViewById(R.id.btnImport).setEnabled(!running);
		});

		viewModel.getBackupResult().observe(getViewLifecycleOwner(), result -> {

			if (result == null) {
				return;
			}
			viewModel.clearBackupResult();

			if (result.success) {
				showMessage(R.string.dialog_export_done_title,
						getString(switchEncrypt.isChecked()
								? R.string.message_export_done_encrypted
								: R.string.message_export_done));
			} else {
				showMessage(R.string.dialog_export_failed_title,
						result.detail == null
								? getString(R.string.error_export_generic)
								: result.detail);
			}
		});

		viewModel.getRestoreResult().observe(getViewLifecycleOwner(), result -> {

			if (result == null) {
				return;
			}
			viewModel.clearRestoreResult();

			if (result.success) {
				pendingImport = null;
				announceRestartAfterRestore(result.report);
			} else {
				onRestoreFailed(result);
			}
		});
	}

	/**
	 * Turns a {@link DatabaseBackup.Failure} into something worth reading, and sends a wrong
	 * password back to the prompt rather than making the user start over.
	 */
	private void onRestoreFailed(@NonNull DatabaseBackup.Result result) {

		DatabaseBackup.Failure failure =
				result.failure == null ? DatabaseBackup.Failure.IO : result.failure;

		// A wrong password is the one failure worth retrying in place, since the file itself
		// is fine and the user may simply have mistyped.
		if (failure == DatabaseBackup.Failure.BAD_PASSWORD && pendingImport != null) {

			Uri source = pendingImport;

			new MaterialAlertDialogBuilder(requireContext())
					.setTitle(R.string.dialog_wrong_password_title)
					.setMessage(R.string.dialog_wrong_password_message)
					.setNegativeButton(R.string.action_cancel, (d, w) -> pendingImport = null)
					.setPositiveButton(R.string.action_try_again, (d, w) ->
							onImportSourceChosen(source))
					.show();
			return;
		}

		pendingImport = null;

		int message;

		switch (failure) {
			case NOT_A_BACKUP:
				message = R.string.error_not_a_backup;
				break;
			case FORMAT_TOO_NEW:
			case SCHEMA_TOO_NEW:
				message = R.string.error_backup_too_new;
				break;
			case DAMAGED:
				message = R.string.error_backup_damaged;
				break;
			default:
				message = R.string.error_restore_generic;
		}

		String detail = result.detail == null
				? getString(message)
				: getString(message) + "\n\n" + result.detail;

		showMessage(R.string.dialog_restore_failed_title, detail);
	}

	/**
	 * The restore has already happened by this point, so the dialog cannot be dismissed into
	 * a working app: there is no cancel button, and the only action restarts the process.
	 */
	private void announceRestartAfterRestore(
			@Nullable DatabaseMerger.MergeReport report) {

		String message = report == null
				? getString(R.string.dialog_restore_done_message)
				: describeMerge(report);

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(report == null
						? R.string.dialog_restore_done_title
						: R.string.dialog_merge_done_title)
				.setMessage(message)
				.setCancelable(false)
				.setPositiveButton(R.string.action_restart, (d, w) -> restartApp())
				.show();
	}

	/**
	 * Spells out what the merge actually did, table by table. A merge that changes nothing
	 * looks identical to one that failed unless it says so, and "added 0, updated 0" is a
	 * useful answer when the same backup is applied twice.
	 */
	@NonNull
	private String describeMerge(@NonNull DatabaseMerger.MergeReport report) {

		if (report.isEmpty()) {
			return getString(R.string.message_merge_no_changes);
		}

		StringBuilder out = new StringBuilder();
		out.append(getString(R.string.message_merge_summary,
				report.totalInserted(), report.totalUpdated()));

		for (Map.Entry<String, int[]> entry : report.byTable.entrySet()) {

			int added = entry.getValue()[0];
			int updated = entry.getValue()[1];

			if (added == 0 && updated == 0) {
				continue;
			}

			out.append("\n• ").append(labelFor(entry.getKey()))
					.append(": ").append(getString(R.string.message_merge_row,
							added, updated));
		}

		if (!report.skipped.isEmpty()) {
			out.append("\n\n").append(getString(R.string.message_merge_notes));
			for (String note : report.skipped) {
				out.append("\n• ").append(note);
			}
		}

		out.append("\n\n").append(getString(R.string.dialog_restore_done_message));
		return out.toString();
	}

	/** Table names are internal; these are what the user calls them. */
	@NonNull
	private String labelFor(@NonNull String table) {

		switch (table) {
			case "students":
				return getString(R.string.nav_students);
			case "student_year":
				return getString(R.string.label_merge_enrolments);
			case "professors":
				return getString(R.string.nav_professors);
			case "courses":
				return getString(R.string.label_merge_courses);
			case "batch_desc":
			case "batch_student_map":
				return getString(R.string.label_merge_batches);
			case "course_batch_map":
				return getString(R.string.label_merge_course_batches);
			case "lectures":
				return getString(R.string.label_merge_lectures);
			case "attendance":
				return getString(R.string.label_merge_attendance);
			case "academic_years":
				return getString(R.string.title_sessions);
			default:
				return table;
		}
	}

	/**
	 * Relaunches from scratch and ends the current process.
	 * <p>
	 * Killing the process is what makes this reliable: the Room singleton, every cached cursor
	 * and every LiveData still holding rows from the old database all go with it, so the new
	 * process opens the restored file cleanly and runs any migration it needs.
	 */
	private void restartApp() {

		Intent restart = requireContext().getPackageManager()
				.getLaunchIntentForPackage(requireContext().getPackageName());

		if (restart != null) {
			restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
			startActivity(restart);
		}

		requireActivity().finish();
		Runtime.getRuntime().exit(0);
	}

	/** A plain dialog, used wherever the outcome needs more room than a snackbar allows. */
	private void showMessage(int titleRes, @NonNull String message) {

		if (!isAdded()) {
			return;
		}

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(titleRes)
				.setMessage(message)
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}

	@NonNull
	/** The backup's creation time, so the user can tell which file they are about to restore. */
	private String formatDate(long epochMillis) {

		if (epochMillis <= 0L) {
			return getString(R.string.label_unknown_date);
		}

		return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
				.withZone(ZoneId.systemDefault())
				.format(Instant.ofEpochMilli(epochMillis));
	}
}
