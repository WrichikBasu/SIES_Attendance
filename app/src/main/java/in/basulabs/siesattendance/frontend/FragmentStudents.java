package in.basulabs.siesattendance.frontend;

import android.app.AlertDialog;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.List;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.pojo.ClassKey;
import in.basulabs.siesattendance.backend.pojo.StudentWithYear;
import in.basulabs.siesattendance.frontend.adapter.AdapterStudent;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * The students screen. Holds three entry points: manual add, CSV import, and the class filter
 * that turns "SY BSc CS" into a student list.
 */
public class FragmentStudents extends Fragment {

	private AttendanceViewModel viewModel;

	private AdapterStudent adapter;

	private RecyclerView recyclerView;

	private View emptyState;

	private TextView emptySubtitle;

	private ChipGroup classChips;

	private TextView resultCount;

	/**
	 * Launcher for the system file picker. Registered at construction time because
	 * {@code registerForActivityResult} must not be called after the fragment is started.
	 */
	private final ActivityResultLauncher<String[]> csvPicker =
			registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
				if (uri != null) {
					confirmImport(uri);
				}
			});

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
	                         @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_students, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity()).get(AttendanceViewModel.class);

		recyclerView = view.findViewById(R.id.rvStudents);
		emptyState = view.findViewById(R.id.layoutEmptyState);
		emptySubtitle = view.findViewById(R.id.tvEmptySubtitle);
		classChips = view.findViewById(R.id.chipGroupClasses);
		resultCount = view.findViewById(R.id.tvResultCount);

		adapter = new AdapterStudent(new AdapterStudent.OnClick() {

			@Override
			public void onStudentClicked(StudentWithYear student) {
				Bundle args = new Bundle();
				args.putLong("studentID", student.studentID);
				Navigation.findNavController(view)
						.navigate(R.id.action_students_to_studentDetail, args);
			}

			@Override
			public void onStudentLongPressed(StudentWithYear student) {
				showStudentActions(student);
			}
		});

		recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
		recyclerView.setAdapter(adapter);

		setupSearch(view);
		setupButtons(view);
		observe();

		viewModel.refreshStudents();
	}

	private void setupSearch(@NonNull View view) {
		TextInputEditText search = view.findViewById(R.id.etSearch);

		search.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int a, int b, int c) {
			}

			@Override
			public void onTextChanged(CharSequence s, int a, int b, int c) {
			}

			@Override
			public void afterTextChanged(Editable s) {
				viewModel.setSearchQuery(s.toString());
			}
		});
	}

	private void setupButtons(@NonNull View view) {

		FloatingActionButton fab = view.findViewById(R.id.fabAddStudent);
		fab.setOnClickListener(v -> Navigation.findNavController(v)
				.navigate(R.id.action_students_to_addStudent));

		view.findViewById(R.id.btnImportCsv).setOnClickListener(v ->
				csvPicker.launch(new String[]{"text/csv", "text/comma-separated-values",
						"text/plain", "application/vnd.ms-excel"}));

		view.findViewById(R.id.btnCsvFormat).setOnClickListener(v -> showFormatHelp());

		view.findViewById(R.id.btnMakeBatch).setOnClickListener(v -> promptCreateBatch());
	}

	private void observe() {

		viewModel.getFilteredStudents().observe(getViewLifecycleOwner(), students -> {
			adapter.submitList(students);

			boolean empty = students == null || students.isEmpty();
			emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
			recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);

			if (!empty) {
				resultCount.setVisibility(View.VISIBLE);
				resultCount.setText(getResources().getQuantityString(
						R.plurals.student_count, students.size(), students.size()));
			} else {
				resultCount.setVisibility(View.GONE);
				emptySubtitle.setText(hasActiveFilter()
						? R.string.empty_students_filtered
						: R.string.empty_students);
			}
		});

		viewModel.getClasses().observe(getViewLifecycleOwner(), this::buildClassChips);

		viewModel.getImportResult().observe(getViewLifecycleOwner(), result -> {
			if (result != null) {
				showImportSummary(result);
				viewModel.clearImportResult();
			}
		});
	}

	/**
	 * Long-pressing a row offers edit and delete without needing to open the detail screen
	 * first, which matters when correcting a batch of freshly imported rows.
	 */
	private void showStudentActions(@NonNull StudentWithYear student) {
		new AlertDialog.Builder(requireContext())
				.setTitle(student.displayName())
				.setItems(new CharSequence[]{
						getString(R.string.action_edit),
						getString(R.string.action_delete)
				}, (dialog, which) -> {
					if (which == 0) {
						Bundle args = new Bundle();
						args.putLong("studentID", student.studentID);
						Navigation.findNavController(requireView())
								.navigate(R.id.action_students_to_addStudent, args);
					} else {
						confirmDeleteStudent(student);
					}
				})
				.show();
	}

	private void confirmDeleteStudent(@NonNull StudentWithYear student) {
		new AlertDialog.Builder(requireContext())
				.setTitle(R.string.dialog_delete_student_title)
				.setMessage(R.string.dialog_delete_student_message)
				.setPositiveButton(R.string.action_delete, (d, w) ->
						viewModel.deleteStudent(student.studentID, () -> {
						}))
				.setNegativeButton(R.string.action_cancel, null)
				.show();
	}

	private boolean hasActiveFilter() {
		return viewModel.getFilterYear() != null
				|| viewModel.getFilterStream() != null
				|| viewModel.getFilterDept() != null;
	}

	/**
	 * One chip per distinct class found in the database, plus an "all" chip. Building these
	 * from the data rather than from the enums means the user only ever sees classes that
	 * actually have students in them.
	 */
	private void buildClassChips(@Nullable List<ClassKey> classes) {

		classChips.removeAllViews();

		Chip allChip = new Chip(requireContext());
		allChip.setText(R.string.filter_all_classes);
		allChip.setCheckable(true);
		allChip.setChecked(!hasActiveFilter());
		allChip.setOnClickListener(v -> {
			viewModel.setClassFilter(null, null, null);
			buildClassChips(classes);
		});
		classChips.addView(allChip);

		if (classes == null) {
			return;
		}

		for (ClassKey key : classes) {
			Chip chip = new Chip(requireContext());
			chip.setText(getString(R.string.class_chip_label, key.label(), key.studentCount));
			chip.setCheckable(true);
			chip.setChecked(key.year.equals(viewModel.getFilterYear())
					&& key.stream.equals(viewModel.getFilterStream())
					&& key.majorDept.equals(viewModel.getFilterDept()));

			chip.setOnClickListener(v -> {
				viewModel.setClassFilter(key.year, key.stream, key.majorDept);
				buildClassChips(classes);
			});
			classChips.addView(chip);
		}
	}

	// ── CSV import ───────────────────────────────────────────────────────

	private void confirmImport(@NonNull Uri uri) {
		new AlertDialog.Builder(requireContext())
				.setTitle(R.string.dialog_import_title)
				.setMessage(getString(R.string.dialog_import_message,
						viewModel.academicYearValue(),
						viewModel.academicYearValue() + 1))
				.setPositiveButton(R.string.action_import, (d, w) -> viewModel.importCsv(uri))
				.setNegativeButton(R.string.action_cancel, null)
				.show();
	}

	private void showImportSummary(
			@NonNull in.basulabs.siesattendance.backend.util.CsvImporter.Result result) {

		StringBuilder message = new StringBuilder();
		message.append(getResources().getQuantityString(R.plurals.import_success,
				result.imported, result.imported));

		if (result.hasErrors()) {
			message.append("\n\n").append(getResources().getQuantityString(
					R.plurals.import_failed, result.failed, result.failed));
			message.append("\n\n");

			int shown = Math.min(result.errors.size(), 10);
			for (int i = 0; i < shown; i++) {
				message.append("• ").append(result.errors.get(i)).append('\n');
			}
			if (result.errors.size() > shown) {
				message.append(getString(R.string.import_more_errors,
						result.errors.size() - shown));
			}
		}

		new AlertDialog.Builder(requireContext())
				.setTitle(R.string.dialog_import_result_title)
				.setMessage(message.toString())
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}

	private void showFormatHelp() {
		new AlertDialog.Builder(requireContext())
				.setTitle(R.string.dialog_csv_format_title)
				.setMessage(getString(R.string.dialog_csv_format_message,
						in.basulabs.siesattendance.backend.util.CsvImporter.templateCsv()))
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}

	// ── Batch creation from the current filter ───────────────────────────

	/**
	 * Turns whatever the class filter currently matches into a batch, which is how a lecture
	 * eventually gets a roll to mark.
	 */
	private void promptCreateBatch() {

		if (!hasActiveFilter()) {
			new AlertDialog.Builder(requireContext())
					.setMessage(R.string.warn_select_class_first)
					.setPositiveButton(android.R.string.ok, null)
					.show();
			return;
		}

		View content = LayoutInflater.from(requireContext())
				.inflate(R.layout.dialog_create_batch, null, false);

		EditText nameField = content.findViewById(R.id.etDialogBatchName);
		EditText descField = content.findViewById(R.id.etDialogBatchDesc);

		String suggested = (viewModel.getFilterYear() == null ? "" :
				viewModel.getFilterYear().name())
				+ " " + (viewModel.getFilterStream() == null ? "" :
				viewModel.getFilterStream().name())
				+ " " + (viewModel.getFilterDept() == null ? "" :
				viewModel.getFilterDept().name());
		nameField.setText(suggested.trim());

		new AlertDialog.Builder(requireContext())
				.setTitle(R.string.dialog_create_batch_title)
				.setView(content)
				.setPositiveButton(R.string.action_create_batch, (d, w) -> {
					String name = nameField.getText().toString().trim();
					if (name.isEmpty()) {
						return;
					}
					String desc = descField.getText().toString().trim();
					viewModel.createBatchFromCurrentFilter(name,
							desc.isEmpty() ? null : desc, () -> {
							});
				})
				.setNegativeButton(R.string.action_cancel, null)
				.show();
	}
}
