package in.basulabs.siesattendance.frontend;

import android.os.Bundle;
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

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Arrays;

import in.basulabs.siesattendance.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import in.basulabs.siesattendance.backend.AcademicRules;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Division;
import in.basulabs.siesattendance.backend.EntityStudent;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;
import in.basulabs.siesattendance.frontend.util.DropdownBinder;
import in.basulabs.siesattendance.frontend.util.Ui;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * Manual single-student entry, for the cases the CSV import does not cover: a late admission, a
 * correction, a transfer.
 * <p>
 * Doubles as the edit form. Passing a {@code studentID} argument loads that student and turns
 * the save button into an update, so there is only one field layout to keep in step.
 * <p>
 * A student carries three departments, of which only the major is required. Which of them
 * matters depends on the course being looked at, so all three are offered here rather than being
 * inferred; the two optional ones take a "none" entry that stores null.
 * <p>
 * The division field follows the stream rather than the department: most streams are not split
 * into divisions, and on those the field is empty and disabled rather than absent, so the form
 * keeps the same shape whichever class is being entered.
 * <p>
 * Cross-field problems are reported as they arise through {@link #showCombinationProblem()},
 * rather than only at save time, so an impossible pairing is visible while the user is still
 * looking at the field that caused it.
 */
public class FragmentAddStudent extends Fragment {

	private AttendanceViewModel viewModel;

	/** -1 means "creating a new student"; anything else is an edit. */
	private long editingStudentID = -1L;

	/**
	 * The session this form reads from and writes back to. Held rather than looked up each
	 * time so a load and the save that follows it cannot land in different years.
	 */
	private int academicYear;


	private TextInputEditText etName, etPronouns, etEmail, etRollNo;

	private TextInputLayout tilName, tilRollNo;

	private AutoCompleteTextView actvYear, actvStream, actvSemester, actvDivision, actvMajor,
			actvMinor, actvOe;

	/** Shows the first cross-check failure as soon as one exists, rather than at save time. */
	private TextView combinationWarning;

	/** Sentinel shown at the top of the optional department dropdowns. */
	private static final String NONE = "— none —";

	// Selections are tracked as values rather than read back from the fields, because the
	// stream field's contents depend on the department and a label alone can be ambiguous:
	// "BSc" means the aided stream for Physics and the unaided one for Computer Science.
	@Nullable
	private Department chosenMajor, chosenMinor, chosenOe;

	@Nullable
	private Stream chosenStream;

	@Nullable
	private Year chosenYear;

	@Nullable
	private Integer chosenSemester;

	@Nullable
	private Division chosenDivision;

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
	                         @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_add_student, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity()).get(AttendanceViewModel.class);

		editingStudentID = getArguments() == null
				? -1L
				: getArguments().getLong("studentID", -1L);

		// The session to read from and write back to. Absent when adding, and when the
		// students list opened this; the detail screen passes the one it is showing.
		int requested = getArguments() == null ? 0 : getArguments().getInt("academicYear", 0);
		academicYear = requested > 0 ? requested : viewModel.studentFilter().academicYear;

		etName = view.findViewById(R.id.etName);
		etPronouns = view.findViewById(R.id.etPronouns);
		etEmail = view.findViewById(R.id.etEmail);
		etRollNo = view.findViewById(R.id.etRollNo);

		tilName = view.findViewById(R.id.tilName);
		tilRollNo = view.findViewById(R.id.tilRollNo);

		actvYear = view.findViewById(R.id.actvYear);
		actvStream = view.findViewById(R.id.actvStream);
		actvSemester = view.findViewById(R.id.actvSemester);
		actvDivision = view.findViewById(R.id.actvDivision);
		combinationWarning = view.findViewById(R.id.tvCombinationWarning);
		actvMajor = view.findViewById(R.id.actvMajorDept);
		actvMinor = view.findViewById(R.id.actvMinorDept);
		actvOe = view.findViewById(R.id.actvOeDept);

		bindDropdowns();

		MaterialButton save = view.findViewById(R.id.btnSaveStudent);
		save.setOnClickListener(this::onSave);

		if (isEditing()) {
			save.setText(R.string.action_save_changes);
			prefillFromExisting();
		}
	}

	/** True when the form was opened against an existing student. */
	private boolean isEditing() {
		return editingStudentID > 0;
	}

	/**
	 * Loads the current values into the form. Roll number is editable here because a correction
	 * to a mistyped roll number is exactly the case this screen exists for.
	 */
	/**
	 * Loads the student being edited and fills every field in, including the linked dropdowns,
	 * which have to be rebound so their lists match the loaded values.
	 */
	private void prefillFromExisting() {
		viewModel.loadStudentSync(editingStudentID, academicYear, student -> {
			if (student == null || !isAdded()) {
				return;
			}
			Ui.onMain(this, () -> {
				etName.setText(student.name);
				etPronouns.setText(student.pronouns);
				etEmail.setText(student.email);
				etRollNo.setText(student.rollNo);

				chosenMajor = student.majorDept;
				chosenStream = student.stream;
				chosenYear = student.year;
				chosenSemester = student.semester;
				chosenDivision = student.division;
				chosenMinor = student.minorDept;
				chosenOe = student.oeDept;

				// Rebuilt rather than just filled in, so the stream list matches the loaded
				// department before the selection is applied.
				bindDropdowns();
			});
		});
	}

	/**
	 * Wires the dropdowns. The major department drives the stream and year fields: a Commerce
	 * student can only be BCom, so the stream is filled in and locked rather than offered as a
	 * choice, and departments with no master's course do not list the master's years.
	 */
	private void bindDropdowns() {

		// The four academic fields move together: department narrows stream, stream narrows
		// year, year narrows semester. Using the full linkage rather than the three-field
		// variant is what makes the year and semester agree by construction, so the only way
		// to reach an impossible pair is to change a field above one already chosen.
		DropdownBinder.linkAcademicFields(actvMajor, actvStream, actvYear, actvSemester,
				Arrays.asList(Department.values()), null,
				chosenMajor, chosenStream, chosenYear, chosenSemester,
				(department, stream, year, semester) -> {
					chosenMajor = department;
					chosenStream = stream;
					chosenYear = year;
					chosenSemester = semester;

					// The division belongs to the stream, so a change of stream can leave the
					// chosen one on a class that has no divisions at all. Dropped rather than
					// kept, then the field is rebuilt for whatever the stream now is.
					chosenDivision = AcademicRules.reconcileDivision(stream, chosenDivision);
					bindDivisionField();

					showCombinationProblem();
				});

		bindDivisionField();

		DropdownBinder.bind(actvMinor, Arrays.asList(Department.values()), NONE,
				chosenMinor, value -> {
					chosenMinor = value;
					showCombinationProblem();
				});

		DropdownBinder.bind(actvOe, Arrays.asList(Department.values()), NONE,
				chosenOe, value -> {
					chosenOe = value;
					showCombinationProblem();
				});

		showCombinationProblem();
	}

	/**
	 * Fills the division field for the stream currently chosen, which is what decides whether
	 * there is anything in it: only the aided BSc and BA are split into divisions, and on every
	 * other stream the field is shown empty and disabled.
	 */
	private void bindDivisionField() {
		DropdownBinder.bindDivisions(actvDivision, chosenStream, NONE, chosenDivision,
				value -> {
					chosenDivision = value;
					showCombinationProblem();
				});
	}

	/**
	 * Describes the first cross-check failure in the current selection, or null when it is
	 * sound.
	 * <p>
	 * {@link AcademicRules#validate} covers department against stream, year against both,
	 * semester against year, and division against stream. The minor and open-elective checks are here rather than in
	 * AcademicRules because they are about this form specifically: a student cannot take their
	 * own major department as a minor or an open elective, which is a rule about one student
	 * rather than about what the college offers.
	 */
	@Nullable
	private String combinationProblem() {

		String problem = AcademicRules.validate(chosenMajor, chosenStream, chosenYear,
				chosenSemester, chosenDivision);

		if (problem != null) {
			return problem;
		}

		if (chosenMajor != null && chosenMajor == chosenMinor) {
			return getString(R.string.error_minor_same_as_major);
		}

		if (chosenMajor != null && chosenMajor == chosenOe) {
			return getString(R.string.error_oe_same_as_major);
		}

		if (chosenMinor != null && chosenMinor == chosenOe) {
			return getString(R.string.error_oe_same_as_minor);
		}

		return null;
	}

	/** Puts {@link #combinationProblem()} on screen, or hides the banner when there is none. */
	private void showCombinationProblem() {

		if (combinationWarning == null) {
			return;
		}

		String problem = combinationProblem();

		combinationWarning.setText(problem);
		combinationWarning.setVisibility(problem == null ? View.GONE : View.VISIBLE);
	}

	/**
	 * Validates and saves, then navigates back.
	 * <p>
	 * The roll number is what identifies a student within a session, so a blank one is refused
	 * outright. The academic combination is checked here as well as on screen, since the banner
	 * is advisory and the user can reach the button with it showing.
	 */
	private void onSave(@NonNull View button) {

		tilName.setError(null);
		tilRollNo.setError(null);

		String name = text(etName);
		String rollNo = text(etRollNo);

		if (name.isEmpty()) {
			tilName.setError(getString(R.string.error_required));
			return;
		}
		if (rollNo.isEmpty()) {
			tilRollNo.setError(getString(R.string.error_required));
			return;
		}

		Year year = chosenYear;
		Stream stream = chosenStream;
		Department major = chosenMajor;

		if (year == null) {
			((TextInputLayout) requireView().findViewById(R.id.tilYear))
					.setError(getString(R.string.error_required));
			return;
		}
		if (stream == null) {
			((TextInputLayout) requireView().findViewById(R.id.tilStream))
					.setError(getString(R.string.error_required));
			return;
		}
		if (major == null) {
			((TextInputLayout) requireView().findViewById(R.id.tilMajorDept))
					.setError(getString(R.string.error_required));
			return;
		}

		Department minor = chosenMinor;
		Department oe = chosenOe;

		// The dropdowns narrow one way only, so a stale value can survive a change made above
		// it. The banner has been showing this since the moment it became true; the dialog is
		// the backstop that stops it being stored anyway.
		String problem = combinationProblem();
		if (problem != null) {
			showCombinationProblem();
			new MaterialAlertDialogBuilder(requireContext())
					.setTitle(R.string.dialog_invalid_combination)
					.setMessage(problem)
					.setPositiveButton(android.R.string.ok, null)
					.show();
			return;
		}

		String pronouns = text(etPronouns);
		if (pronouns.isEmpty()) {
			pronouns = "they/them";
		}

		String email = text(etEmail);

		EntityStudent student =
				new EntityStudent(name, pronouns, email.isEmpty() ? null : email);

		Runnable navigateBack = () -> Ui.onMain(this, () -> {
			Navigation.findNavController(button).navigateUp();
		});

		if (isEditing()) {
			student.studentID = editingStudentID;
			viewModel.updateStudent(student, academicYear, rollNo, year, stream, major,
					minor, oe,
					chosenSemester, chosenDivision, navigateBack);
		} else {
			viewModel.addStudent(student, rollNo, year, stream, major, minor, oe,
					chosenSemester, chosenDivision, navigateBack);
		}
	}

	@NonNull
	/** A field's trimmed contents, treating a null editable as empty. */
	private String text(@NonNull TextInputEditText field) {
		return field.getText() == null ? "" : field.getText().toString().trim();
	}

}
