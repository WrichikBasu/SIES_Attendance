package in.basulabs.siesattendance.frontend;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Arrays;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.EntityProfessor;
import in.basulabs.siesattendance.frontend.util.DropdownBinder;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * Add or edit a professor. Passing a {@code profID} argument switches the form into edit mode.
 */
public class FragmentAddProfessor extends Fragment {

	private AttendanceViewModel viewModel;

	private long editingProfID = -1L;

	private TextInputEditText etName, etEmail;

	private TextInputLayout tilName, tilEmail;

	private AutoCompleteTextView actvDepartment, actvDesignation;

	@Nullable
	private Department chosenDepartment;

	private static final String[] DESIGNATIONS = {
			"Professor", "Associate Professor", "Assistant Professor",
			"Visiting Faculty", "Lab Assistant"
	};

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
	                         @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_add_professor, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity()).get(AttendanceViewModel.class);

		editingProfID = getArguments() == null ? -1L : getArguments().getLong("profID", -1L);

		etName = view.findViewById(R.id.etName);
		etEmail = view.findViewById(R.id.etEmail);
		tilName = view.findViewById(R.id.tilName);
		tilEmail = view.findViewById(R.id.tilEmail);
		actvDepartment = view.findViewById(R.id.actvDepartment);
		actvDesignation = view.findViewById(R.id.actvDesignation);

		DropdownBinder.bind(actvDepartment, Arrays.asList(Department.values()), null,
				chosenDepartment, value -> chosenDepartment = value);
		actvDesignation.setAdapter(new ArrayAdapter<>(requireContext(),
				android.R.layout.simple_list_item_1, DESIGNATIONS));

		MaterialButton save = view.findViewById(R.id.btnSaveProfessor);
		save.setOnClickListener(this::onSave);

		if (editingProfID > 0) {
			save.setText(R.string.action_save_changes);
			viewModel.loadProfessorSync(editingProfID, professor -> {
				if (professor == null || !isAdded()) {
					return;
				}
				requireActivity().runOnUiThread(() -> {
					etName.setText(professor.name);
					etEmail.setText(professor.email);
					chosenDepartment = professor.department;
					DropdownBinder.setSelection(actvDepartment, professor.department, null);
					actvDesignation.setText(professor.designation, false);
				});
			});
		}
	}

	private void onSave(@NonNull View button) {

		tilName.setError(null);
		tilEmail.setError(null);

		String name = etName.getText() == null ? "" : etName.getText().toString().trim();
		String email = etEmail.getText() == null ? "" : etEmail.getText().toString().trim();

		if (name.isEmpty()) {
			tilName.setError(getString(R.string.error_required));
			return;
		}
		if (email.isEmpty()) {
			tilEmail.setError(getString(R.string.error_required));
			return;
		}

		Department department = chosenDepartment;

		if (department == null) {
			((TextInputLayout) requireView().findViewById(R.id.tilDepartment))
					.setError(getString(R.string.error_required));
			return;
		}

		String designation = actvDesignation.getText().toString().trim();
		if (designation.isEmpty()) {
			designation = "Professor";
		}

		EntityProfessor professor =
				new EntityProfessor(name, email, department, designation);

		Runnable navigateBack = () -> requireActivity().runOnUiThread(() -> {
			if (isAdded()) {
				Navigation.findNavController(button).navigateUp();
			}
		});

		if (editingProfID > 0) {
			professor.profID = editingProfID;
			viewModel.updateProfessor(professor, navigateBack);
		} else {
			viewModel.addProfessor(professor, navigateBack);
		}
	}
}
