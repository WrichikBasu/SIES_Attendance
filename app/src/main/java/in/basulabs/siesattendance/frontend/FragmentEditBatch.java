package in.basulabs.siesattendance.frontend;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.pojo.StudentWithYear;
import in.basulabs.siesattendance.frontend.adapter.AdapterSelectableStudent;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * Edit a batch: its name, description and membership.
 * <p>
 * Membership is shown as the full student list for the academic year with the current members
 * ticked, rather than as separate add and remove screens. Selection is held as a set of IDs
 * independent of the list currently displayed, so filtering by the search box never silently
 * drops a tick for someone who scrolled out of view.
 */
public class FragmentEditBatch extends Fragment {

	private AttendanceViewModel viewModel;

	private AdapterSelectableStudent adapter;

	private TextInputEditText etName, etDescription, etSearch;

	private TextInputLayout tilName;

	private TextView selectedCount;

	private long batchID;

	/** Every student of the academic year, unfiltered. */
	private final List<StudentWithYear> allStudents = new ArrayList<>();

	/** Ordered so the membership written back is stable between saves. */
	private final Set<Long> selected = new LinkedHashSet<>();

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

		etName = view.findViewById(R.id.etBatchName);
		etDescription = view.findViewById(R.id.etBatchDesc);
		etSearch = view.findViewById(R.id.etSearch);
		tilName = view.findViewById(R.id.tilBatchName);
		selectedCount = view.findViewById(R.id.tvSelectedCount);

		RecyclerView recyclerView = view.findViewById(R.id.rvStudents);
		adapter = new AdapterSelectableStudent(this::onStudentToggled);
		recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
		recyclerView.setAdapter(adapter);

		MaterialButton save = view.findViewById(R.id.btnSaveBatch);
		save.setOnClickListener(this::onSave);

		etSearch.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int a, int b, int c) {
			}

			@Override
			public void onTextChanged(CharSequence s, int a, int b, int c) {
			}

			@Override
			public void afterTextChanged(Editable s) {
				applyFilter(s.toString());
			}
		});

		load();
	}

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

				applyFilter(etSearch.getText() == null ? "" : etSearch.getText().toString());
				updateCount();
			});
		});
	}

	private void onStudentToggled(long studentID, boolean isSelected) {
		if (isSelected) {
			selected.add(studentID);
		} else {
			selected.remove(studentID);
		}
		updateCount();
	}

	/**
	 * Filters the visible list without touching the selection set, so a student ticked and then
	 * filtered out of view stays ticked.
	 */
	private void applyFilter(@Nullable String query) {

		String needle = query == null ? "" : query.trim().toLowerCase();
		List<StudentWithYear> visible = new ArrayList<>();

		for (StudentWithYear student : allStudents) {
			if (needle.isEmpty()
					|| student.name.toLowerCase().contains(needle)
					|| student.rollNo.toLowerCase().contains(needle)) {
				visible.add(student);
			}
		}

		adapter.submit(visible, selected);
	}

	private void updateCount() {
		selectedCount.setText(getResources().getQuantityString(
				R.plurals.student_count, selected.size(), selected.size()));
	}

	private void onSave(@NonNull View button) {

		tilName.setError(null);

		String name = etName.getText() == null ? "" : etName.getText().toString().trim();
		if (name.isEmpty()) {
			tilName.setError(getString(R.string.error_required));
			return;
		}

		String description = etDescription.getText() == null
				? "" : etDescription.getText().toString().trim();

		viewModel.updateBatch(batchID, name, description.isEmpty() ? null : description,
				new ArrayList<>(selected),
				() -> requireActivity().runOnUiThread(() -> {
					if (isAdded()) {
						Navigation.findNavController(button).navigateUp();
					}
				}));
	}
}
