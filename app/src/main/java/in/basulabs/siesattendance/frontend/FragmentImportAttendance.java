package in.basulabs.siesattendance.frontend;

import android.net.Uri;
import android.os.Bundle;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.List;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.EntityAcademicYear;
import in.basulabs.siesattendance.backend.util.AttendanceCsvParser;
import in.basulabs.siesattendance.backend.util.AttendanceRecordImporter;
import in.basulabs.siesattendance.frontend.adapter.AdapterImportLecture;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * Imports a register kept elsewhere: one column per class session, one row per student.
 * <p>
 * The screen is a two-step affair on purpose. Choosing a file only reads it and shows what it
 * would create; nothing is written until the button at the bottom is pressed and the
 * confirmation accepted. That is a heavier ceremony than the roster import gets, and it is
 * warranted, because this creates lectures. A file's column says who was present and who was
 * absent but not which group sat the session, so the app infers the batch from the pattern of
 * empty cells, and an inference that only shows up in the numbers weeks later is one the user
 * should get to look at first. {@code BatchMatcher} explains what the inference costs when it
 * goes wrong.
 * <p>
 * The plan lives in the ViewModel rather than here, so what is written is necessarily what was
 * reviewed even across a rotation.
 */
public class FragmentImportAttendance extends Fragment {

	private AttendanceViewModel viewModel;

	private AdapterImportLecture adapter;

	private TextView fileErrors, summary, warnings;

	private MaterialButton save;

	private LinearProgressIndicator progress;

	/**
	 * Registered at construction time, as {@code registerForActivityResult} requires. The
	 * spreadsheet types are offered alongside the CSV ones because a file exported from Excel
	 * or Google Sheets is frequently reported by the provider under its own type even when it
	 * has been saved as CSV.
	 */
	private final ActivityResultLauncher<String[]> picker =
			registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
				if (uri != null) {
					viewModel.planAttendanceImport(uri);
				}
			});

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
	                         @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_import_attendance, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity()).get(AttendanceViewModel.class);

		fileErrors = view.findViewById(R.id.tvImportFileErrors);
		summary = view.findViewById(R.id.tvImportSummary);
		warnings = view.findViewById(R.id.tvImportWarnings);
		save = view.findViewById(R.id.btnSaveImport);
		progress = view.findViewById(R.id.progressImportAttendance);

		RecyclerView list = view.findViewById(R.id.rvImportLectures);
		adapter = new AdapterImportLecture();
		list.setLayoutManager(new LinearLayoutManager(requireContext()));
		list.setAdapter(adapter);

		view.findViewById(R.id.btnChooseAttendanceFile).setOnClickListener(v ->
				picker.launch(new String[]{"text/csv", "text/comma-separated-values",
						"text/plain", "application/vnd.ms-excel"}));

		view.findViewById(R.id.btnAttendanceCsvFormat)
				.setOnClickListener(v -> showFormatHelp());

		save.setOnClickListener(v -> confirmImport());

		observe();
	}

	// ── Showing the plan ─────────────────────────────────────────────────

	private void observe() {

		viewModel.getAttendancePlan().observe(getViewLifecycleOwner(), this::render);

		viewModel.getAttendanceImportResult().observe(getViewLifecycleOwner(), result -> {
			if (result != null) {
				showResult(result);
				viewModel.clearAttendanceImportResult();
			}
		});

		viewModel.isBusy().observe(getViewLifecycleOwner(), busy -> {
			boolean working = busy != null && busy;
			progress.setVisibility(working ? View.VISIBLE : View.GONE);
			save.setEnabled(!working);
		});
	}

	/** Puts a plan on screen, or clears everything when there is none. */
	private void render(@Nullable AttendanceRecordImporter.Plan plan) {

		if (plan == null) {
			adapter.submit(new ArrayList<>());
			fileErrors.setVisibility(View.GONE);
			summary.setVisibility(View.GONE);
			warnings.setVisibility(View.GONE);
			save.setVisibility(View.GONE);
			return;
		}

		boolean readable = plan.errors.isEmpty();

		fileErrors.setText(String.join("\n", plan.errors));
		fileErrors.setVisibility(readable ? View.GONE : View.VISIBLE);

		// A file that could not be read as a grid has nothing worth listing: whatever columns
		// were recovered before it went wrong describe a sheet nobody should act on.
		adapter.submit(readable ? plan.lectures
				: new ArrayList<AttendanceRecordImporter.LecturePlan>());

		summary.setText(describe(plan));
		summary.setVisibility(readable ? View.VISIBLE : View.GONE);

		warnings.setText(String.join("\n", plan.warnings));
		warnings.setVisibility(readable && !plan.warnings.isEmpty()
				? View.VISIBLE : View.GONE);

		save.setVisibility(plan.canImport() ? View.VISIBLE : View.GONE);
		save.setText(getResources().getQuantityString(R.plurals.action_import_sessions,
				plan.usableCount(), plan.usableCount()));
	}

	/** The headline above the list: what would go in, and what would not. */
	@NonNull
	private String describe(@NonNull AttendanceRecordImporter.Plan plan) {

		if (!plan.errors.isEmpty()) {
			return "";
		}

		List<String> parts = new ArrayList<>();

		parts.add(getResources().getQuantityString(R.plurals.label_import_sessions_found,
				plan.usableCount(), plan.usableCount(), plan.markCount()));

		if (!plan.academicYears().isEmpty()) {
			List<String> labels = new ArrayList<>();
			for (Integer year : plan.academicYears()) {
				labels.add(EntityAcademicYear.labelFor(year));
			}
			parts.add(getString(R.string.label_import_session_years,
					String.join(", ", labels)));
		}

		if (plan.replacedCount() > 0) {
			parts.add(getResources().getQuantityString(R.plurals.label_import_replaces,
					plan.replacedCount(), plan.replacedCount()));
		}

		if (plan.skippedCount() > 0) {
			parts.add(getResources().getQuantityString(R.plurals.label_import_skipped,
					plan.skippedCount(), plan.skippedCount()));
		}

		return String.join("\n", parts);
	}

	// ── Confirming and writing ───────────────────────────────────────────

	/**
	 * The confirmation. It restates the same figures the summary already shows, deliberately:
	 * this is the last point at which the batch the app chose for each session can be rejected,
	 * and after it there are lectures on record.
	 */
	private void confirmImport() {

		AttendanceRecordImporter.Plan plan = viewModel.getAttendancePlan().getValue();

		if (plan == null || !plan.canImport()) {
			return;
		}

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_import_attendance_title)
				.setMessage(describe(plan) + "\n\n"
						+ getString(R.string.dialog_import_attendance_message))
				.setPositiveButton(R.string.action_import,
						(d, w) -> viewModel.commitAttendanceImport())
				.setNegativeButton(R.string.action_cancel, null)
				.show();
	}

	private void showResult(@NonNull AttendanceRecordImporter.Result result) {

		String message;

		if (result.isFailure()) {
			// The write is one transaction, so a failure means nothing at all went in and the
			// message should not leave that in doubt.
			message = getString(R.string.message_import_attendance_failed, result.error);
		} else {
			List<String> parts = new ArrayList<>();

			parts.add(getResources().getQuantityString(R.plurals.message_import_created,
					result.created, result.created));

			if (result.replaced > 0) {
				parts.add(getResources().getQuantityString(R.plurals.message_import_replaced,
						result.replaced, result.replaced));
			}

			parts.add(getResources().getQuantityString(R.plurals.message_import_marks,
					result.marks, result.marks));

			if (result.skipped > 0) {
				parts.add(getResources().getQuantityString(R.plurals.label_import_skipped,
						result.skipped, result.skipped));
			}

			message = String.join("\n", parts);
		}

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_import_result_title)
				.setMessage(message)
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}

	/** Explains the layout and offers an example, which is the usual first question. */
	private void showFormatHelp() {
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_attendance_format_title)
				.setMessage(getString(R.string.dialog_attendance_format_message,
						AttendanceCsvParser.formatHelp(), AttendanceCsvParser.templateCsv()))
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}
}
