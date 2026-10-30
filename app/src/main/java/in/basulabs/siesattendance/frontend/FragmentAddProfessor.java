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
import in.basulabs.siesattendance.backend.Designations;
import in.basulabs.siesattendance.backend.EntityProfessor;
import in.basulabs.siesattendance.frontend.util.DropdownBinder;
import in.basulabs.siesattendance.frontend.util.Ui;
import in.basulabs.siesattendance.frontend.util.Permissions;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * Add or edit a professor. Passing a {@code profID} argument switches the form into edit mode.
 * <p>
 * The same screen serves both, because the fields and the validation are identical and the only
 * difference is whether the save inserts or updates. {@link #editingProfID} is what separates
 * the two.
 */
public class FragmentAddProfessor extends Fragment {

	private AttendanceViewModel viewModel;

	/** The professor being edited, or -1 when adding a new one. */
	private long editingProfID = -1L;

	private TextInputEditText etName, etEmail;

	private TextInputLayout tilName, tilEmail;

	private AutoCompleteTextView actvDepartment, actvDesignation;

	@Nullable
	private Department chosenDepartment;

	/**
	 * One of {@link Designations#ALL}, and nothing else: HOD makes a professor head of their
	 * department when their account is created, so the field is a fixed choice rather than
	 * text. A professor on file under the old "Head of Department" opens as HOD; one under a
	 * title no longer on the list opens with the field empty, to be chosen before saving. A new
	 * professor starts at the first, as on the console.
	 */
	@Nullable
	private String chosenDesignation = Designations.ALL.get(0);

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

		if (!viewModel.may(Permissions.Capability.PROFESSORS)) {
			// On the screen itself, as well as at the controls that lead here. The controls
			// are the courtesy; this is the one check that cannot be forgotten when somebody
			// adds a fourth way in -- which is exactly what happened to the sign-in gate.
			viewModel.sayNotPermitted();
			Navigation.findNavController(view).popBackStack();
			return;
		}

		editingProfID = getArguments() == null ? -1L : getArguments().getLong("profID", -1L);

		etName = view.findViewById(R.id.etName);
		etEmail = view.findViewById(R.id.etEmail);
		tilName = view.findViewById(R.id.tilName);
		tilEmail = view.findViewById(R.id.tilEmail);
		actvDepartment = view.findViewById(R.id.actvDepartment);
		actvDesignation = view.findViewById(R.id.actvDesignation);

		DropdownBinder.bind(actvDepartment, Arrays.asList(Department.values()), null,
				chosenDepartment, value -> chosenDepartment = value);
		bindDesignations();

		MaterialButton save = view.findViewById(R.id.btnSaveProfessor);
		save.setOnClickListener(this::onSave);

		if (editingProfID > 0) {
			save.setText(R.string.action_save_changes);
			viewModel.loadProfessorSync(editingProfID, professor -> {
				if (professor == null || !isAdded()) {
					return;
				}
				Ui.onMain(this, () -> {
					etName.setText(professor.name);
					etEmail.setText(professor.email);
					chosenDepartment = professor.department;
					DropdownBinder.setSelection(actvDepartment, professor.department, null);
					chosenDesignation = Designations.normalise(professor.designation);
					bindDesignations();
				});
			});
		}
	}

	/** Fills the designation field with the fixed list and the current choice. */
	private void bindDesignations() {

		actvDesignation.setAdapter(new ArrayAdapter<>(requireContext(),
				android.R.layout.simple_list_item_1, Designations.ALL));
		actvDesignation.setText(chosenDesignation == null ? "" : chosenDesignation, false);
		actvDesignation.setOnItemClickListener((parent, v, position, id) -> {
			chosenDesignation = Designations.ALL.get(position);
			((TextInputLayout) requireView().findViewById(R.id.tilDesignation)).setError(null);
		});
	}

	/**
	 * Validates and saves, then navigates back.
	 * <p>
	 * The write happens on a background thread and the navigation runs in its callback, so the
	 * screen stays put if the insert fails on the unique email index. Both callbacks check
	 * {@code isAdded()} first, since the user is free to leave before the write completes.
	 */
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

		String designation = chosenDesignation;

		if (designation == null) {
			((TextInputLayout) requireView().findViewById(R.id.tilDesignation))
					.setError(getString(R.string.error_required));
			return;
		}

		EntityProfessor professor =
				new EntityProfessor(name, email, department, designation);

		Runnable navigateBack = () -> Ui.onMain(this, () -> {
			Navigation.findNavController(button).navigateUp();
		});

		if (editingProfID > 0) {
			professor.profID = editingProfID;
			viewModel.updateProfessor(professor, navigateBack);
		} else {
			viewModel.addProfessor(professor, navigateBack);
		}
	}
}
