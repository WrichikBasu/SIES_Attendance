package in.basulabs.siesattendance.frontend.util;

import android.net.Uri;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.function.BiFunction;
import java.util.function.Function;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.util.MarkSheetExporter;
import in.basulabs.siesattendance.backend.util.MarkSheetExporter.Format;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * Exporting one lecture's or one day's marks, from asking how to where to save it.
 * <p>
 * Shared by the day screen and the lecture screen, which differ only in what they export. Each
 * holds one of these as a field, which is what registers its save dialogs early enough: a result
 * launcher has to be registered before its fragment is created, and a field initialiser runs
 * then.
 * <p>
 * Two launchers rather than one, because the save dialog is told the file's type when the
 * launcher is registered, not when it is launched. One per format keeps the dialog offering
 * the right extension and filtering to the right kind of file.
 * <p>
 * The chosen request goes into the activity-scoped ViewModel while the save dialog is up, not
 * into this object. The dialog is another activity; rotating the device under it rebuilds the
 * fragment and this with it, and the result arrives at the new instance.
 */
public final class MarkExportFlow {

	@NonNull
	private final Fragment fragment;

	@NonNull
	private final ActivityResultLauncher<String> odsPicker;

	@NonNull
	private final ActivityResultLauncher<String> csvPicker;

	public MarkExportFlow(@NonNull Fragment fragment) {
		this.fragment = fragment;
		odsPicker = fragment.registerForActivityResult(
				new ActivityResultContracts.CreateDocument(Format.ODS.mimeType),
				this::onDestinationChosen);
		csvPicker = fragment.registerForActivityResult(
				new ActivityResultContracts.CreateDocument(Format.CSV.mimeType),
				this::onDestinationChosen);
	}

	/**
	 * Asks for the format and whether to highlight, then where to save, then exports.
	 *
	 * @param fileName the name to suggest in the save dialog, for the format chosen
	 * @param request  what to export, given the format and whether to highlight
	 */
	public void start(@NonNull Function<Format, String> fileName,
	                  @NonNull BiFunction<Format, Boolean, MarkSheetExporter.Request> request) {

		View view = fragment.getLayoutInflater().inflate(R.layout.dialog_export_marks, null);

		MaterialButtonToggleGroup formats = view.findViewById(R.id.toggleExportFormat);
		MaterialSwitch highlight = view.findViewById(R.id.switchHighlightAbsentees);
		TextView hint = view.findViewById(R.id.tvHighlightHint);

		// A CSV cannot hold colour. The switch is disabled rather than hidden, and the hint
		// says why, so choosing CSV does not look like it quietly dropped the setting.
		formats.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
			if (!isChecked) {
				return;
			}
			boolean csv = checkedId == R.id.btnFormatCsv;
			highlight.setEnabled(!csv);
			hint.setText(csv ? R.string.hint_highlight_csv : R.string.hint_highlight_absentees);
		});

		new MaterialAlertDialogBuilder(fragment.requireContext())
				.setTitle(R.string.title_export_marks)
				.setView(view)
				.setNegativeButton(R.string.action_cancel, null)
				.setPositiveButton(R.string.action_export, (d, w) -> {

					Format format = formats.getCheckedButtonId() == R.id.btnFormatCsv
							? Format.CSV : Format.ODS;

					viewModel().setPendingMarkExport(
							request.apply(format, highlight.isChecked()));

					(format == Format.CSV ? csvPicker : odsPicker)
							.launch(fileName.apply(format));
				})
				.show();
	}

	private void onDestinationChosen(@Nullable Uri destination) {

		if (destination == null) {
			viewModel().clearPendingMarkExport();
			return;
		}
		viewModel().runPendingMarkExport(destination);
	}

	@NonNull
	private AttendanceViewModel viewModel() {
		return new ViewModelProvider(fragment.requireActivity()).get(AttendanceViewModel.class);
	}
}
