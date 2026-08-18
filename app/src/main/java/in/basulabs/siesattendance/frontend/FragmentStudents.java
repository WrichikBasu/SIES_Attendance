package in.basulabs.siesattendance.frontend;

import android.app.AlertDialog;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
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

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.EntityAcademicYear;
import in.basulabs.siesattendance.backend.EntityBatchDesc;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;
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

	private TextView resultCount, pastSessionNotice;

	private AutoCompleteTextView actvSession, actvYear, actvStream, actvMajor, actvMinor,
			actvOe, actvBatch;

	private View filterPanel;

	private MaterialButton toggleFilters;

	private List<EntityAcademicYear> sessions = new ArrayList<>();

	private List<EntityBatchDesc> batches = new ArrayList<>();

	private String anyLabel;

	/** Suppresses re-queries while dropdowns are populated in code. */
	private boolean binding;

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

		anyLabel = getString(R.string.filter_any);

		recyclerView = view.findViewById(R.id.rvStudents);
		emptyState = view.findViewById(R.id.layoutEmptyState);
		emptySubtitle = view.findViewById(R.id.tvEmptySubtitle);
		classChips = view.findViewById(R.id.chipGroupClasses);
		resultCount = view.findViewById(R.id.tvResultCount);
		pastSessionNotice = view.findViewById(R.id.tvPastSessionNotice);

		actvSession = view.findViewById(R.id.actvSession);
		actvYear = view.findViewById(R.id.actvYear);
		actvStream = view.findViewById(R.id.actvStream);
		actvMajor = view.findViewById(R.id.actvMajorDept);
		actvMinor = view.findViewById(R.id.actvMinorDept);
		actvOe = view.findViewById(R.id.actvOeDept);
		actvBatch = view.findViewById(R.id.actvBatch);

		filterPanel = view.findViewById(R.id.scrollFilters);
		toggleFilters = view.findViewById(R.id.btnToggleFilters);

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
				// Straight to edit: there is no delete, so a chooser would have one entry.
				Bundle args = new Bundle();
				args.putLong("studentID", student.studentID);
				Navigation.findNavController(view)
						.navigate(R.id.action_students_to_addStudent, args);
			}
		});

		recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
		recyclerView.setAdapter(adapter);

		setupSearch(view);
		setupButtons(view);
		setupFilterDropdowns();
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

	/**
	 * Wires the detailed filters. Each dropdown's first entry is "Any", which clears that one
	 * dimension without disturbing the others.
	 */
	private void setupFilterDropdowns() {

		binding = true;

		bindEnum(actvYear, Year.values(),
				value -> viewModel.studentFilter().year = (Year) value);
		bindEnum(actvStream, Stream.values(),
				value -> viewModel.studentFilter().stream = (Stream) value);
		bindEnum(actvMajor, Department.values(),
				value -> viewModel.studentFilter().majorDept = (Department) value);
		bindEnum(actvMinor, Department.values(),
				value -> viewModel.studentFilter().minorDept = (Department) value);
		bindEnum(actvOe, Department.values(),
				value -> viewModel.studentFilter().oeDept = (Department) value);

		binding = false;
	}

	private interface EnumSetter {
		void set(@Nullable Enum<?> value);
	}

	private <E extends Enum<E>> void bindEnum(@NonNull AutoCompleteTextView field,
	                                          @NonNull E[] values, @NonNull EnumSetter setter) {
		List<String> items = new ArrayList<>();
		items.add(anyLabel);
		for (E value : values) {
			items.add(value.name());
		}

		field.setAdapter(new ArrayAdapter<>(requireContext(),
				android.R.layout.simple_list_item_1, items));
		field.setText(anyLabel, false);

		field.setOnItemClickListener((p, v, position, id) -> {
			setter.set(position == 0 ? null : values[position - 1]);
			// The chips express a class as one unit, so a dimension changed here would leave
			// them showing a selection that no longer matches.
			syncChipsToFilter();
			rerun();
		});
	}

	/**
	 * Fills the session dropdown and keeps the notice in step. Changing session reloads the
	 * batch list too, because a batch belongs to exactly one session.
	 */
	private void bindSessions(@NonNull List<EntityAcademicYear> loaded) {

		binding = true;
		sessions = loaded;

		List<String> labels = new ArrayList<>();
		for (EntityAcademicYear session : loaded) {
			labels.add(session.label());
		}

		actvSession.setAdapter(new ArrayAdapter<>(requireContext(),
				android.R.layout.simple_list_item_1, labels));

		int selected = viewModel.studentFilter().academicYear;
		for (EntityAcademicYear session : loaded) {
			if (session.academicYear == selected) {
				actvSession.setText(session.label(), false);
				break;
			}
		}

		actvSession.setOnItemClickListener((p, v, position, id) -> {
			viewModel.setStudentAcademicYear(sessions.get(position).academicYear);
			actvBatch.setText(anyLabel, false);
			loadBatches();
			updateSessionNotice();
		});

		binding = false;
		updateSessionNotice();
		loadBatches();
	}

	/**
	 * A past session is easy to forget you are in, and additions land there, so it is stated
	 * rather than left implicit.
	 */
	private void updateSessionNotice() {

		int viewing = viewModel.studentFilter().academicYear;
		boolean isCurrent = viewing == viewModel.academicYearValue();

		pastSessionNotice.setVisibility(isCurrent ? View.GONE : View.VISIBLE);

		if (!isCurrent) {
			String label = viewing + "-" + String.format("%02d", (viewing + 1) % 100);
			pastSessionNotice.setText(getString(R.string.notice_viewing_session, label));
		}
	}

	private void loadBatches() {
		viewModel.loadBatchesForStudentFilter(loaded -> {
			if (!isAdded()) {
				return;
			}
			requireActivity().runOnUiThread(() -> {
				if (!isAdded()) {
					return;
				}

				binding = true;
				batches = loaded;

				List<String> labels = new ArrayList<>();
				labels.add(anyLabel);
				for (EntityBatchDesc batch : loaded) {
					labels.add(batch.batchName);
				}

				actvBatch.setAdapter(new ArrayAdapter<>(requireContext(),
						android.R.layout.simple_list_item_1, labels));

				actvBatch.setOnItemClickListener((p, v, position, id) -> {
					viewModel.studentFilter().batchID =
							position == 0 ? null : batches.get(position - 1).batchID;
					rerun();
				});

				binding = false;
			});
		});
	}

	private void rerun() {
		if (!binding) {
			viewModel.refreshStudents();
		}
	}

	/** Puts every filter control back to "Any" after a clear. */
	private void resetFilterControls() {
		binding = true;
		for (AutoCompleteTextView field : new AutoCompleteTextView[]{
				actvYear, actvStream, actvMajor, actvMinor, actvOe, actvBatch}) {
			field.setText(anyLabel, false);
		}
		binding = false;
	}

	/**
	 * Reflects the year, stream and department dropdowns onto the class chips, since the two
	 * controls describe the same three dimensions.
	 */
	private void syncChipsToFilter() {
		classChips.clearCheck();
	}

	private void setupButtons(@NonNull View view) {

		FloatingActionButton fab = view.findViewById(R.id.fabAddStudent);
		fab.setOnClickListener(v -> Navigation.findNavController(v)
				.navigate(R.id.action_students_to_addStudent));

		view.findViewById(R.id.btnImportCsv).setOnClickListener(v ->
				csvPicker.launch(new String[]{"text/csv", "text/comma-separated-values",
						"text/plain", "application/vnd.ms-excel"}));

		view.findViewById(R.id.btnCsvFormat).setOnClickListener(v -> showFormatHelp());

		view.findViewById(R.id.btnMakeBatch).setOnClickListener(v -> seedBatchFromFilter());

		toggleFilters.setOnClickListener(v -> {
			boolean showing = filterPanel.getVisibility() == View.VISIBLE;
			filterPanel.setVisibility(showing ? View.GONE : View.VISIBLE);
			toggleFilters.setText(showing ? R.string.action_show_filters
					: R.string.action_hide_filters);
		});

		view.findViewById(R.id.btnClearFilters).setOnClickListener(v -> {
			viewModel.clearFilters();
			resetFilterControls();
			classChips.clearCheck();
		});
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

		viewModel.getAcademicYears().observe(getViewLifecycleOwner(), years -> {
			if (years != null && !years.isEmpty()) {
				bindSessions(years);
			}
		});

		viewModel.getImportResult().observe(getViewLifecycleOwner(), result -> {
			if (result != null) {
				showImportSummary(result);
				viewModel.clearImportResult();
			}
		});
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
			binding = true;
			actvYear.setText(anyLabel, false);
			actvStream.setText(anyLabel, false);
			actvMajor.setText(anyLabel, false);
			binding = false;
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
				binding = true;
				actvYear.setText(key.year.name(), false);
				actvStream.setText(key.stream.name(), false);
				actvMajor.setText(key.majorDept.name(), false);
				binding = false;
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
						viewModel.studentFilter().academicYear,
						(viewModel.studentFilter().academicYear + 1) % 100))
				.setPositiveButton(R.string.action_import, (d, w) -> viewModel.importCsv(uri))
				.setNegativeButton(R.string.action_cancel, null)
				.show();
	}

	private void showImportSummary(
			@NonNull in.basulabs.siesattendance.backend.util.CsvImporter.Result result) {

		StringBuilder message = new StringBuilder();

		if (result.created > 0) {
			message.append(getResources().getQuantityString(R.plurals.import_created,
					result.created, result.created));
		}

		if (result.updated > 0) {
			if (message.length() > 0) {
				message.append('\n');
			}
			message.append(getResources().getQuantityString(R.plurals.import_updated,
					result.updated, result.updated));
		}

		if (message.length() == 0) {
			message.append(getString(R.string.import_nothing));
		}

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
	 * Opens the batch editor with everyone matching the current class filter already ticked.
	 * This keeps the "SY BSc CS becomes a batch" shortcut while leaving the user free to add
	 * students from other classes before saving, which a filter-bound create could not do.
	 */
	private void seedBatchFromFilter() {
		viewModel.loadFilteredStudentIds(ids -> {
			if (!isAdded()) {
				return;
			}
			requireActivity().runOnUiThread(() -> {
				if (!isAdded()) {
					return;
				}
				viewModel.setBatchSeed(ids);

				Bundle args = new Bundle();
				args.putLong("batchID", -1L);
				Navigation.findNavController(requireView())
						.navigate(R.id.action_students_to_editBatch, args);
			});
		});
	}
}
