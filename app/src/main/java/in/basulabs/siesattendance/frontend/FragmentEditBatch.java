package in.basulabs.siesattendance.frontend;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AutoCompleteTextView;
import android.widget.TextView;

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
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;
import in.basulabs.siesattendance.backend.pojo.StudentWithYear;
import in.basulabs.siesattendance.frontend.adapter.AdapterHeader;
import in.basulabs.siesattendance.frontend.adapter.AdapterSelectableStudent;
import in.basulabs.siesattendance.frontend.util.DropdownBinder;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * Create or edit a batch. A {@code batchID} argument of -1 means create.
 * <p>
 * A batch is an arbitrary set of students, not a class. Practicals split one class in two, but
 * an open elective or an AEC pulls students from several departments and even several years at
 * once, so the picker lists everyone in the academic year and lets the user narrow by year,
 * stream and department to find them.
 * <p>
 * The important property is that <b>filtering never changes the selection</b>. The chosen
 * students are held as a set of IDs, entirely separate from whichever subset is on screen, so
 * the user can tick some FY BSc students, switch the filters to TY BCom, tick more, and keep
 * both. Tying selection to the visible rows would silently drop anyone scrolled or filtered out
 * of view, and that loss would only surface weeks later as missing attendance.
 */
public class FragmentEditBatch extends Fragment {

	private AttendanceViewModel viewModel;

	private AdapterSelectableStudent adapter;

	private TextInputEditText etName, etDescription, etSearch;

	private TextInputLayout tilName;

	private AutoCompleteTextView actvYear, actvStream, actvDept;

	private TextView selectedCount, emptyMessage;

	private Chip chipShowSelected;

	private MaterialButton save;

	private AdapterHeader headerAdapter;

	private long batchID;

	/** Kept so the header can be filled in if it is created after the load finishes. */
	@Nullable
	private in.basulabs.siesattendance.backend.EntityBatchDesc loadedBatch;

	/** Every student of the academic year, unfiltered. */
	private final List<StudentWithYear> allStudents = new ArrayList<>();

	/** Insertion-ordered so repeated saves write membership in a stable order. */
	private final Set<Long> selected = new LinkedHashSet<>();

	private Year filterYear;

	private Stream filterStream;

	private Department filterDept;

	/** Shown as the first entry of each filter dropdown. */
	private String anyLabel;

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
	                         @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_edit_batch, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity()).get(AttendanceViewModel.class);
		batchID = getArguments() == null ? -1L : getArguments().getLong("batchID", -1L);
		anyLabel = getString(R.string.filter_any);

		RecyclerView recyclerView = view.findViewById(R.id.rvStudents);
		recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

		adapter = new AdapterSelectableStudent(this::onStudentToggled);

		// Bound before the header is wired: onHeaderReady calls updateCount, which enables or
		// disables this button according to how many students are selected.
		save = view.findViewById(R.id.btnSaveBatch);
		save.setText(isCreating() ? R.string.action_create_batch : R.string.action_save_changes);
		save.setOnClickListener(this::onSave);

		// Fields and filters as the list's first item, so the screen scrolls as one piece. The
		// header is wired before the adapter is attached: onHeaderReady calls applyFilter,
		// which notifies the student adapter, and doing that mid-layout throws.
		View header = getLayoutInflater()
				.inflate(R.layout.view_edit_batch_header, recyclerView, false);
		onHeaderReady(header);

		headerAdapter = new AdapterHeader(header);
		recyclerView.setAdapter(new ConcatAdapter(headerAdapter, adapter));

		load();
	}

	/**
	 * Wires the header's controls.
	 * <p>
	 * The list and the save button are not among them: both live in the fragment's own layout
	 * and are set up in onViewCreated. Re-resolving the RecyclerView here would have found
	 * nothing, and reassigning its adapter would have replaced the concatenated pair with the
	 * student adapter alone, taking the header off screen.
	 */
	private void onHeaderReady(@NonNull View header) {

		etName = header.findViewById(R.id.etBatchName);
		etDescription = header.findViewById(R.id.etBatchDesc);
		etSearch = header.findViewById(R.id.etSearch);
		tilName = header.findViewById(R.id.tilBatchName);

		actvYear = header.findViewById(R.id.actvFilterYear);
		actvStream = header.findViewById(R.id.actvFilterStream);
		actvDept = header.findViewById(R.id.actvFilterDept);

		selectedCount = header.findViewById(R.id.tvSelectedCount);
		emptyMessage = header.findViewById(R.id.tvNoMatches);
		chipShowSelected = header.findViewById(R.id.chipShowSelected);

		header.findViewById(R.id.btnSelectShown).setOnClickListener(v -> setAllShown(true));
		header.findViewById(R.id.btnClearShown).setOnClickListener(v -> setAllShown(false));

		chipShowSelected.setOnCheckedChangeListener((v, checked) -> applyFilter());

		if (loadedBatch != null) {
			etName.setText(loadedBatch.batchName);
			etDescription.setText(loadedBatch.batchDesc);
		}

		setupFilters();
		setupSearch();

		// The data may already have loaded before the header existed.
		applyFilter();
		updateCount();
	}

	private boolean isCreating() {
		return batchID <= 0;
	}

	// ── Filters ──────────────────────────────────────────────────────────

	/**
	 * The three filters are linked the same way as everywhere else: a department narrows the
	 * stream list to what it offers. Each has an "Any" entry so a dimension can be relaxed
	 * without disturbing the others.
	 */
	private void setupFilters() {

		DropdownBinder.linkDepartmentAndStream(actvDept, actvStream, actvYear,
				Arrays.asList(Department.values()), anyLabel,
				filterDept, filterStream, filterYear,
				(department, stream, year) -> {
					filterDept = department;
					filterStream = stream;
					filterYear = year;
					applyFilter();
				});
	}

	private void setupSearch() {
		etSearch.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int a, int b, int c) {
			}

			@Override
			public void onTextChanged(CharSequence s, int a, int b, int c) {
			}

			@Override
			public void afterTextChanged(Editable s) {
				applyFilter();
			}
		});
	}

	// ── Loading ──────────────────────────────────────────────────────────

	private void load() {
		viewModel.loadBatchEditor(batchID, (batch, students, memberIDs) -> {
			if (!isAdded()) {
				return;
			}
			requireActivity().runOnUiThread(() -> {
				if (!isAdded()) {
					return;
				}

				if (batch != null && etName != null) {
					etName.setText(batch.batchName);
					etDescription.setText(batch.batchDesc);
				}
				loadedBatch = batch;

				allStudents.clear();
				allStudents.addAll(students);

				selected.clear();
				selected.addAll(memberIDs);

				applyFilter();
				updateCount();
			});
		});
	}

	// ── Selection ────────────────────────────────────────────────────────

	private void onStudentToggled(long studentID, boolean isSelected) {
		if (isSelected) {
			selected.add(studentID);
		} else {
			selected.remove(studentID);
		}
		updateCount();

		// Rows disappear as they are unticked while the selected-only view is on, so the list
		// has to be rebuilt; otherwise the tick can just be left where it is.
		if (chipShowSelected != null && chipShowSelected.isChecked()) {
			applyFilter();
		}
	}

	/**
	 * Applies to the currently visible students only, which is what makes "narrow to SY BSc CS,
	 * select all, then narrow to something else and select those too" work.
	 */
	private void setAllShown(boolean select) {

		for (StudentWithYear student : visibleStudents()) {
			if (select) {
				selected.add(student.studentID);
			} else {
				selected.remove(student.studentID);
			}
		}

		applyFilter();
		updateCount();
	}

	private List<StudentWithYear> visibleStudents() {

		if (etSearch == null) {
			return new ArrayList<>(allStudents);
		}

		String needle = etSearch.getText() == null
				? "" : etSearch.getText().toString().trim().toLowerCase();

		boolean selectedOnly = chipShowSelected != null && chipShowSelected.isChecked();
		List<StudentWithYear> visible = new ArrayList<>();

		for (StudentWithYear student : allStudents) {

			if (filterYear != null && student.year != filterYear) {
				continue;
			}
			if (filterStream != null && student.stream != filterStream) {
				continue;
			}
			if (filterDept != null && student.majorDept != filterDept) {
				continue;
			}
			if (selectedOnly && !selected.contains(student.studentID)) {
				continue;
			}
			if (!needle.isEmpty()
					&& !student.name.toLowerCase().contains(needle)
					&& !student.rollNo.toLowerCase().contains(needle)) {
				continue;
			}

			visible.add(student);
		}

		return visible;
	}

	private void applyFilter() {
		List<StudentWithYear> visible = visibleStudents();
		adapter.submit(visible, selected);
		if (emptyMessage != null) {
			emptyMessage.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
		}
	}

	private void updateCount() {
		save.setEnabled(!selected.isEmpty());
		if (selectedCount == null) {
			return;
		}
		selectedCount.setText(getResources().getQuantityString(
				R.plurals.student_count, selected.size(), selected.size()));
	}

	// ── Save ─────────────────────────────────────────────────────────────

	private void onSave(@NonNull View button) {

		if (tilName != null) {
			tilName.setError(null);
		}

		if (etName == null) {
			return;
		}

		String name = etName.getText() == null ? "" : etName.getText().toString().trim();
		if (name.isEmpty()) {
			tilName.setError(getString(R.string.error_required));
			return;
		}

		if (selected.isEmpty()) {
			return;
		}

		String description = etDescription.getText() == null
				? "" : etDescription.getText().toString().trim();
		String desc = description.isEmpty() ? null : description;

		List<Long> ids = new ArrayList<>(selected);

		Runnable navigateBack = () -> requireActivity().runOnUiThread(() -> {
			if (isAdded()) {
				Navigation.findNavController(button).navigateUp();
			}
		});

		if (isCreating()) {
			viewModel.createBatch(name, desc, ids, navigateBack);
		} else {
			viewModel.updateBatch(batchID, name, desc, ids, navigateBack);
		}
	}
}
