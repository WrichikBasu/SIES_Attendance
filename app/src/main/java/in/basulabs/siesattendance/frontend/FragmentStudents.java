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
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.AcademicRules;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.EntityAcademicYear;
import in.basulabs.siesattendance.backend.EntityBatchDesc;
import in.basulabs.siesattendance.backend.pojo.ClassKey;
import in.basulabs.siesattendance.backend.pojo.StudentWithYear;
import in.basulabs.siesattendance.frontend.adapter.AdapterHeader;
import in.basulabs.siesattendance.frontend.adapter.AdapterStudent;
import in.basulabs.siesattendance.frontend.util.DropdownBinder;
import in.basulabs.siesattendance.frontend.util.Ui;
import in.basulabs.siesattendance.frontend.util.Permissions;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel.StudentFilter;

/**
 * The students screen. Holds three entry points: manual add, CSV import, and the class filter
 * that turns "SY BSc CS" into a student list.
 * <p>
 * The filters and the class chips live in an {@link AdapterHeader} in front of the list, so the
 * whole screen scrolls as one piece while the rows still recycle. The header is created
 * asynchronously, which is why the most recent results are kept in the {@code last} fields and
 * replayed into it once it exists.
 * <p>
 * The class chips are built from the students actually enrolled rather than from every possible
 * combination, so the screen only ever offers classes that exist.
 */
public class FragmentStudents extends Fragment {

	private AttendanceViewModel viewModel;

	private AdapterStudent adapter;

	private AdapterHeader headerAdapter;

	private RecyclerView recyclerView;

	private View emptyState;

	private TextView emptySubtitle;

	private ChipGroup classChips;

	private TextView resultCount, pastSessionNotice, filtersActive;

	private AutoCompleteTextView actvSession, actvYear, actvStream, actvDivision, actvMajor,
			actvMinor, actvOe, actvBatch;

	private View filterPanel;

	private MaterialButton toggleFilters;

	private List<EntityAcademicYear> sessions = new ArrayList<>();

	private List<EntityBatchDesc> batches = new ArrayList<>();

	private String anyLabel;

	/** Suppresses re-queries while dropdowns are populated in code. */
	private boolean binding;

	/**
	 * Whether the session dropdown has been filled in yet.
	 * <p>
	 * {@link #bindPendingState()} runs on every student result, and it used to rebind the
	 * sessions each time to cover the case where the header is built after they arrive.
	 * Rebinding ends by reloading the batch list, so a database round trip was fired for every
	 * keystroke in the search box. It only has to happen once, and then again whenever the
	 * sessions themselves change.
	 */
	private boolean sessionsBound;

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
		recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

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

				// A long press is the way into the editor. A class representative reads this
				// list to mark a roll and does not correct it; the office does.
				if (!viewModel.may(Permissions.Capability.STUDENTS)) {
					viewModel.sayNotPermitted();
					return;
				}

				Bundle args = new Bundle();
				args.putLong("studentID", student.studentID);
				Navigation.findNavController(view)
						.navigate(R.id.action_students_to_addStudent, args);
			}
		});

		// The controls are the list's first item, so the screen scrolls as one piece while the
		// student rows still recycle. The header is inflated and wired here, before the adapter
		// is attached: wiring it later, from inside onCreateViewHolder, would run during a
		// layout pass and any resulting notify would throw.
		View header = getLayoutInflater()
				.inflate(R.layout.view_students_header, recyclerView, false);
		onHeaderReady(header);

		headerAdapter = new AdapterHeader(header);
		recyclerView.setAdapter(new ConcatAdapter(headerAdapter, adapter));

		View add = view.findViewById(R.id.fabAddStudent);

		// Gone rather than disabled: a greyed-out button is a promise that something could be
		// pressed, and nothing a class representative does will ever make this pressable.
		if (viewModel.may(Permissions.Capability.STUDENTS)) {
			add.setOnClickListener(v ->
					Navigation.findNavController(v).navigate(R.id.action_students_to_addStudent));
		} else {
			add.setVisibility(View.GONE);
		}

		observe();
		viewModel.refreshStudents();
	}

	/**
	 * Wires the header's controls. Called once, when the RecyclerView creates it.
	 */
	private void onHeaderReady(@NonNull View header) {
		emptyState = header.findViewById(R.id.layoutEmptyState);
		emptySubtitle = header.findViewById(R.id.tvEmptySubtitle);
		classChips = header.findViewById(R.id.chipGroupClasses);
		resultCount = header.findViewById(R.id.tvResultCount);
		pastSessionNotice = header.findViewById(R.id.tvPastSessionNotice);

		actvSession = header.findViewById(R.id.actvSession);
		actvYear = header.findViewById(R.id.actvYear);
		actvStream = header.findViewById(R.id.actvStream);
		actvDivision = header.findViewById(R.id.actvDivision);
		actvMajor = header.findViewById(R.id.actvMajorDept);
		actvMinor = header.findViewById(R.id.actvMinorDept);
		actvOe = header.findViewById(R.id.actvOeDept);
		actvBatch = header.findViewById(R.id.actvBatch);

		filterPanel = header.findViewById(R.id.scrollFilters);
		toggleFilters = header.findViewById(R.id.btnToggleFilters);
		filtersActive = header.findViewById(R.id.tvFiltersActive);

		setupSearchIn(header);
		setupButtonsIn(header);
		setupFilterDropdowns();

		// The list may already have loaded before the header existed.
		bindPendingState();
	}


	/** Wires the search box. Search composes with the filters rather than replacing them. */
	private void setupSearchIn(@NonNull View view) {
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
	 * Wires the detailed filters.
	 * <p>
	 * Major department, stream and year move together: choosing a department narrows the stream
	 * list to what that department actually offers, fixes it outright when there is only one,
	 * and drops the master's years for departments running no postgraduate course. Minor and
	 * open elective are plain department choices, since a student's minor has no bearing on the
	 * stream they are enrolled under.
	 */
	private void setupFilterDropdowns() {

		binding = true;

		StudentFilter f = viewModel.studentFilter();

		DropdownBinder.linkDepartmentAndStream(actvMajor, actvStream, actvYear,
				Arrays.asList(Department.values()), anyLabel,
				f.majorDept, f.stream, f.year,
				(department, stream, year) -> {
					StudentFilter filter = viewModel.studentFilter();
					filter.majorDept = department;
					filter.stream = stream;
					filter.year = year;

					// A division belongs to one stream, so narrowing to a stream that has none
					// drops it rather than leaving a filter no student can match.
					filter.division =
							AcademicRules.reconcileDivision(stream, filter.division);
					bindDivisionFilter();

					classChips.clearCheck();
					rerun();
				});

		bindDivisionFilter();

		DropdownBinder.bind(actvMinor, Arrays.asList(Department.values()), anyLabel,
				f.minorDept, value -> {
					viewModel.studentFilter().minorDept = value;
					rerun();
				});

		DropdownBinder.bind(actvOe, Arrays.asList(Department.values()), anyLabel,
				f.oeDept, value -> {
					viewModel.studentFilter().oeDept = value;
					rerun();
				});

		binding = false;
	}

	/**
	 * Fills the division filter for the stream currently filtered on. With no stream chosen
	 * every division is offered, since a student of any divided stream could be in one; a
	 * stream that has no divisions leaves the field empty and disabled.
	 */
	private void bindDivisionFilter() {
		DropdownBinder.bindDivisions(actvDivision, viewModel.studentFilter().stream, anyLabel,
				viewModel.studentFilter().division, value -> {
					viewModel.studentFilter().division = value;
					classChips.clearCheck();
					rerun();
				});
	}

	/**
	 * Fills the session dropdown. Switching session changes which enrolments are in view, so it
	 * re-runs the query and rebuilds the class chips.
	 */
	private void bindSessions(@NonNull List<EntityAcademicYear> loaded) {

		binding = true;
		sessionsBound = true;
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

	/** Loads the batches for the chosen session, so membership can be used as a filter. */
	private void loadBatches() {
		viewModel.loadBatchesForStudentFilter(loaded -> {
			if (!isAdded()) {
				return;
			}
			Ui.onMain(this, () -> {
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

	/** Re-runs the student query with the current filters. A no-op while {@link #binding} is set. */
	private void rerun() {
		if (!binding) {
			updateFiltersActive();
			viewModel.refreshStudents();
		}
	}

	/** Puts every filter control back to "Any" after a clear. */
	private void resetFilterControls() {
		binding = true;
		for (AutoCompleteTextView field : new AutoCompleteTextView[]{
				actvYear, actvStream, actvDivision, actvMajor, actvMinor, actvOe, actvBatch}) {
			field.setText(anyLabel, false);
		}

		// The division list depends on the stream, which has just been cleared, so the field
		// goes back to offering every division rather than the last stream's.
		bindDivisionFilter();

		binding = false;
	}

	/**
	 * Wires the header's buttons. The add-student FAB is deliberately not among them: it lives
	 * in the fragment's own layout, floating above the list rather than scrolling with it, and
	 * is wired in onViewCreated.
	 */
	private void setupButtonsIn(@NonNull View view) {

		// The CSV adds and updates students wholesale, so it belongs to whoever may do that
		// one at a time.
		View importCsv = view.findViewById(R.id.btnImportCsv);

		if (!viewModel.may(Permissions.Capability.STUDENTS)) {
			importCsv.setVisibility(View.GONE);
		}

		importCsv.setOnClickListener(v ->
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

	/** Latest result, kept so the header can catch up if it is created after the first load. */
	@Nullable
	private List<StudentWithYear> lastResult;

	private void observe() {

		viewModel.getFilteredStudents().observe(getViewLifecycleOwner(), students -> {
			lastResult = students;
			adapter.submitList(students);
			bindPendingState();
		});

		viewModel.getClasses().observe(getViewLifecycleOwner(), classes -> {
			lastClasses = classes;
			if (classChips != null) {
				buildClassChips(classes);
			}
		});

		viewModel.getAcademicYears().observe(getViewLifecycleOwner(), years -> {
			lastSessions = years;
			if (actvSession != null && years != null && !years.isEmpty()) {
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

	@Nullable
	private List<ClassKey> lastClasses;

	@Nullable
	private List<EntityAcademicYear> lastSessions;

	/**
	 * Applies whatever has already been loaded to the header. The list and the header are
	 * created independently, so either can arrive first.
	 */
	private void bindPendingState() {

		if (resultCount == null) {
			return;
		}

		List<StudentWithYear> students = lastResult;
		boolean empty = students == null || students.isEmpty();

		emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);

		if (empty) {
			resultCount.setText("");
			emptySubtitle.setText(hasActiveFilter()
					? R.string.empty_students_filtered
					: R.string.empty_students);
		} else {
			resultCount.setText(getResources().getQuantityString(
					R.plurals.student_count, students.size(), students.size()));
		}

		updateFiltersActive();

		if (lastClasses != null) {
			buildClassChips(lastClasses);
		}

		// Only to catch sessions that arrived before the header existed. The observer rebinds
		// whenever they actually change, so doing it again per student result would reload the
		// batch list on every keystroke.
		if (!sessionsBound && lastSessions != null && !lastSessions.isEmpty()) {
			bindSessions(lastSessions);
		}
	}

	/**
	 * Shows the red line whenever the list is narrowed, including by the dimensions the class
	 * chips do not cover and while the filter panel is closed.
	 * <p>
	 * Wider than {@link #hasActiveFilter()}, which answers a different question: that one drives
	 * the "all classes" chip and so only counts the three dimensions a chip stands for. A list
	 * narrowed to one batch is still a narrowed list.
	 */
	private void updateFiltersActive() {

		if (filtersActive == null) {
			return;
		}

		StudentFilter filter = viewModel.studentFilter();
		boolean narrowed = filter.hasActiveFilters() || !filter.query.isEmpty();

		filtersActive.setVisibility(narrowed ? View.VISIBLE : View.GONE);
	}

	/** Whether anything beyond the session is narrowing the list, which the clear button follows. */
	private boolean hasActiveFilter() {
		return viewModel.getFilterYear() != null
				|| viewModel.getFilterStream() != null
				|| viewModel.getFilterDept() != null
				|| viewModel.getFilterDivision() != null;
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
			viewModel.setClassFilter(null, null, null, null);
			binding = true;
			DropdownBinder.setSelection(actvYear, null, anyLabel);
			DropdownBinder.setSelection(actvStream, null, anyLabel);
			DropdownBinder.setSelection(actvMajor, null, anyLabel);
			bindDivisionFilter();
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
					&& key.majorDept.equals(viewModel.getFilterDept())
					// Compared by identity so that a chip for an undivided class, which carries
					// no division, reads as checked when the filter carries none either.
					&& key.division == viewModel.getFilterDivision());

			chip.setOnClickListener(v -> {
				viewModel.setClassFilter(key.year, key.stream, key.majorDept, key.division);
				binding = true;
				DropdownBinder.setSelection(actvYear, key.year, anyLabel);
				DropdownBinder.setSelection(actvStream, key.stream, anyLabel);
				DropdownBinder.setSelection(actvMajor, key.majorDept, anyLabel);
				bindDivisionFilter();
				binding = false;
				buildClassChips(classes);
			});
			classChips.addView(chip);
		}
	}

	// ── CSV import ───────────────────────────────────────────────────────

	/**
	 * Confirms before importing, since an import writes into the chosen session and a file picked
	 * by mistake is easier to prevent than to unpick.
	 */
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

	/** Explains the expected columns and offers the template, which is the usual first question. */
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
			Ui.onMain(this, () -> {
				viewModel.setBatchSeed(ids);

				Bundle args = new Bundle();
				args.putLong("batchID", -1L);
				Navigation.findNavController(requireView())
						.navigate(R.id.action_students_to_editBatch, args);
			});
		});
	}
}
