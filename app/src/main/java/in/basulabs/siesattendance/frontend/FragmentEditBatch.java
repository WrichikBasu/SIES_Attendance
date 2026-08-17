package in.basulabs.siesattendance.frontend;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
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
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;
import in.basulabs.siesattendance.backend.pojo.StudentWithYear;
import in.basulabs.siesattendance.frontend.adapter.AdapterSelectableStudent;
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

	private long batchID;

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

		etName = view.findViewById(R.id.etBatchName);
		etDescription = view.findViewById(R.id.etBatchDesc);
		etSearch = view.findViewById(R.id.etSearch);
		tilName = view.findViewById(R.id.tilBatchName);

		actvYear = view.findViewById(R.id.actvFilterYear);
		actvStream = view.findViewById(R.id.actvFilterStream);
		actvDept = view.findViewById(R.id.actvFilterDept);

		selectedCount = view.findViewById(R.id.tvSelectedCount);
		emptyMessage = view.findViewById(R.id.tvNoMatches);
		chipShowSelected = view.findViewById(R.id.chipShowSelected);

		RecyclerView recyclerView = view.findViewById(R.id.rvStudents);
		adapter = new AdapterSelectableStudent(this::onStudentToggled);
		recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
		recyclerView.setAdapter(adapter);

		save = view.findViewById(R.id.btnSaveBatch);
		save.setText(isCreating() ? R.string.action_create_batch : R.string.action_save_changes);
		save.setOnClickListener(this::onSave);

		view.findViewById(R.id.btnSelectShown).setOnClickListener(v -> setAllShown(true));
		view.findViewById(R.id.btnClearShown).setOnClickListener(v -> setAllShown(false));

		chipShowSelected.setOnCheckedChangeListener((v, checked) -> applyFilter());

		setupFilters();
		setupSearch();
		load();
	}

	private boolean isCreating() {
		return batchID <= 0;
	}

	// ── Filters ──────────────────────────────────────────────────────────

	private void setupFilters() {

		actvYear.setAdapter(labelledAdapter(Year.values()));
		actvStream.setAdapter(labelledAdapter(Stream.values()));
		actvDept.setAdapter(labelledAdapter(Department.values()));

		actvYear.setText(anyLabel, false);
		actvStream.setText(anyLabel, false);
		actvDept.setText(anyLabel, false);

		actvYear.setOnItemClickListener((p, v, position, id) -> {
			filterYear = position == 0 ? null : Year.values()[position - 1];
			applyFilter();
		});

		actvStream.setOnItemClickListener((p, v, position, id) -> {
			filterStream = position == 0 ? null : Stream.values()[position - 1];
			applyFilter();
		});

		actvDept.setOnItemClickListener((p, v, position, id) -> {
			filterDept = position == 0 ? null : Department.values()[position - 1];
			applyFilter();
		});
	}

	/**
	 * Builds a dropdown whose first entry clears that dimension, so each filter can be relaxed
	 * without clearing the others.
	 */
	private <E extends Enum<E>> ArrayAdapter<String> labelledAdapter(E[] values) {
		List<String> items = new ArrayList<>();
		items.add(anyLabel);
		for (E value : values) {
			items.add(value.name());
		}
		return new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, items);
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

				if (batch != null) {
					etName.setText(batch.batchName);
					etDescription.setText(batch.batchDesc);
				}

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
		if (chipShowSelected.isChecked()) {
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

		String needle = etSearch.getText() == null
				? "" : etSearch.getText().toString().trim().toLowerCase();

		boolean selectedOnly = chipShowSelected.isChecked();
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
		emptyMessage.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
	}

	private void updateCount() {
		selectedCount.setText(getResources().getQuantityString(
				R.plurals.student_count, selected.size(), selected.size()));
		save.setEnabled(!selected.isEmpty());
	}

	// ── Save ─────────────────────────────────────────────────────────────

	private void onSave(@NonNull View button) {

		tilName.setError(null);

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
