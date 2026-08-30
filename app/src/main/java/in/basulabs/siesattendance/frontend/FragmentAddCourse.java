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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.AcademicRules;
import in.basulabs.siesattendance.backend.CourseType;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.EntityCourse;
import in.basulabs.siesattendance.backend.EntityProfessor;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;
import in.basulabs.siesattendance.frontend.util.DropdownBinder;
import in.basulabs.siesattendance.frontend.util.Ui;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * Add or edit a course. Passing a {@code courseID} argument switches the form into edit mode.
 * <p>
 * The academic fields are linked through {@link DropdownBinder}, so choosing a department
 * narrows the streams and choosing a stream narrows the years. The course type is independent of
 * all of them, and is what decides who ends up on the course's roll; see the roster query in
 * {@code DaoStudent}.
 * <p>
 * A course cannot be saved without a coordinator, which is why the form warns up front when no
 * professors have been entered yet rather than failing at save time.
 * <p>
 * The coordinator is one field and the rest of the teaching staff is another. A course can be
 * team-taught, and a practical batch can have a demonstrator who never gives a lecture, so
 * {@code courses.profID} names only the person answerable for the course and
 * {@code course_instructor} holds everyone who teaches it. The second field edits that table;
 * the coordinator is added to it for free, which is why they are excluded from the picker
 * rather than shown already ticked and un-tickable.
 */
public class FragmentAddCourse extends Fragment {

	private AttendanceViewModel viewModel;

	private long editingCourseID = -1L;

	private TextInputEditText etCode, etTopic, etRemarks;

	private TextInputLayout tilCode, tilTopic;

	private AutoCompleteTextView actvStream, actvDept, actvYear, actvSemester,
			actvType, actvProfessor;

	private TextInputEditText etOtherInstructors;

	/**
	 * Loaded asynchronously to fill the professor dropdown. Empty until the load returns, which
	 * is what {@link #pendingProfID} exists to deal with.
	 */
	private List<EntityProfessor> professors = new ArrayList<>();

	/** Professor to select once the dropdown finishes loading, used when editing. */
	private long pendingProfID = -1L;

	/**
	 * The co-instructors currently ticked. Ordered so the summary line does not reshuffle
	 * itself every time the dialog is reopened.
	 * <p>
	 * The coordinator is deliberately absent: they are added by the save, and holding them here
	 * as well would mean two places could disagree about whether they are on the course.
	 */
	private final Set<Long> otherInstructorIDs = new LinkedHashSet<>();

	// Tracked as values because the stream list depends on the department and "BSc" alone
	// cannot say whether the aided or unaided constant is meant.
	@Nullable
	private Department chosenDept;

	@Nullable
	private Stream chosenStream;

	@Nullable
	private Year chosenYear;

	@Nullable
	private CourseType chosenType;

	// Semester is bound to the year: FY covers 1 and 2, SY 3 and 4, TY 5 and 6, with a
	// master's course restarting the count. Tracked as a value for the same reason as the
	// others, since the dropdown's contents depend on the year.
	@Nullable
	private Integer chosenSemester;

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
	                         @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_add_course, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity()).get(AttendanceViewModel.class);

		editingCourseID = getArguments() == null ? -1L : getArguments().getLong("courseID", -1L);

		etCode = view.findViewById(R.id.etCourseCode);
		etTopic = view.findViewById(R.id.etCourseTopic);
		etRemarks = view.findViewById(R.id.etRemarks);
		tilCode = view.findViewById(R.id.tilCourseCode);
		tilTopic = view.findViewById(R.id.tilCourseTopic);

		actvStream = view.findViewById(R.id.actvStream);
		actvDept = view.findViewById(R.id.actvDepartment);
		actvYear = view.findViewById(R.id.actvYear);
		actvSemester = view.findViewById(R.id.actvSemester);
		actvType = view.findViewById(R.id.actvCourseType);
		actvProfessor = view.findViewById(R.id.actvProfessor);
		etOtherInstructors = view.findViewById(R.id.etOtherInstructors);
		etOtherInstructors.setOnClickListener(v -> showInstructorPicker());

		bindDropdowns();

		MaterialButton save = view.findViewById(R.id.btnSaveCourse);
		save.setOnClickListener(this::onSave);

		if (editingCourseID > 0) {
			save.setText(R.string.action_save_changes);
			prefillFromExisting();
		} else {
			warnIfNoProfessors();
		}
	}

	/**
	 * A course cannot exist without a professor, so rather than letting the user fill in the
	 * whole form and fail on save, the dead end is surfaced immediately with a way out.
	 */
	private void warnIfNoProfessors() {
		viewModel.countProfessors(count -> {
			if (count > 0 || !isAdded()) {
				return;
			}
			Ui.onMain(this, () -> {
				new MaterialAlertDialogBuilder(requireContext())
						.setTitle(R.string.dialog_no_professors_title)
						.setMessage(R.string.dialog_no_professors_message)
						.setPositiveButton(R.string.action_add_professor, (d, w) ->
								Navigation.findNavController(requireView())
										.navigate(R.id.fragmentAddProfessor))
						.setNegativeButton(R.string.action_cancel, (d, w) ->
								Navigation.findNavController(requireView()).navigateUp())
						.setCancelable(false)
						.show();
			});
		});
	}

	/**
	 * Loads the course being edited and fills the form in.
	 * <p>
	 * The professor is recorded in {@link #pendingProfID} rather than selected here, since the
	 * professor list may not have arrived yet.
	 */
	private void prefillFromExisting() {
		viewModel.loadCourseSync(editingCourseID, course -> {
			if (course == null || !isAdded()) {
				return;
			}
			Ui.onMain(this, () -> {
				etCode.setText(course.courseCode);
				etTopic.setText(course.courseTopic);
				etRemarks.setText(course.remarks);
				chosenDept = course.offeringDepartment;
				chosenStream = course.stream;
				chosenYear = course.year;
				chosenSemester = course.semester;
				chosenType = course.courseType;

				// Rebuilt rather than just filled in, so each list matches the loaded values
				// before the selections are applied.
				bindDropdowns();

				pendingProfID = course.profID;
				applyPendingProfessor();
			});
		});

		// Read separately from the course row, and every instructor is loaded rather than only
		// the co-instructors: the coordinator is among them in the database, and which one they
		// are is not known here until the course row arrives. They are filtered out when the
		// summary is drawn instead, where the answer is available.
		viewModel.loadCourseInstructorIdsSync(editingCourseID, ids -> {
			if (!isAdded()) {
				return;
			}
			Ui.onMain(this, () -> {
				otherInstructorIDs.clear();
				otherInstructorIDs.addAll(ids);
				showInstructorSummary();
			});
		});
	}

	/**
	 * The professor list and the course record load independently, so whichever arrives second
	 * triggers the selection.
	 */
	private void applyPendingProfessor() {
		if (pendingProfID <= 0 || professors.isEmpty() || actvProfessor == null) {
			return;
		}
		for (EntityProfessor professor : professors) {
			if (professor.profID == pendingProfID) {
				actvProfessor.setText(
						professor.name + " (" + professor.department + ")", false);
				// The summary names people, so it can only be written once the names are here.
				showInstructorSummary();
				return;
			}
		}
	}

	/**
	 * The multiple-choice picker for everyone teaching the course besides the coordinator.
	 * <p>
	 * The coordinator is left out of the list entirely. They are on the course by definition,
	 * so offering them as a choice would only let someone untick a box that does not do
	 * anything.
	 */
	private void showInstructorPicker() {

		if (professors.isEmpty()) {
			return;
		}

		long coordinator = coordinatorProfID();

		List<EntityProfessor> choices = new ArrayList<>();
		for (EntityProfessor professor : professors) {
			if (professor.profID != coordinator) {
				choices.add(professor);
			}
		}

		if (choices.isEmpty()) {
			return;
		}

		String[] labels = new String[choices.size()];
		boolean[] checked = new boolean[choices.size()];

		for (int i = 0; i < choices.size(); i++) {
			EntityProfessor professor = choices.get(i);
			labels[i] = professor.name + " (" + professor.department + ")";
			checked[i] = otherInstructorIDs.contains(professor.profID);
		}

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_other_instructors_title)
				.setMultiChoiceItems(labels, checked, (dialog, which, isChecked) ->
						checked[which] = isChecked)
				.setPositiveButton(android.R.string.ok, (dialog, which) -> {
					otherInstructorIDs.clear();
					for (int i = 0; i < choices.size(); i++) {
						if (checked[i]) {
							otherInstructorIDs.add(choices.get(i).profID);
						}
					}
					showInstructorSummary();
				})
				.setNegativeButton(R.string.action_cancel, null)
				.show();
	}

	/**
	 * Writes the co-instructors into the field as a comma-separated list of names.
	 * <p>
	 * The coordinator is skipped even if they are in the set, which they are immediately after
	 * an edit form loads: the read that fills it returns every instructor, and the coordinator
	 * is one of them.
	 */
	private void showInstructorSummary() {

		if (etOtherInstructors == null) {
			return;
		}

		long coordinator = coordinatorProfID();
		StringBuilder names = new StringBuilder();

		for (EntityProfessor professor : professors) {
			if (professor.profID == coordinator
					|| !otherInstructorIDs.contains(professor.profID)) {
				continue;
			}
			if (names.length() > 0) {
				names.append(", ");
			}
			names.append(professor.name);
		}

		etOtherInstructors.setText(names.length() == 0
				? getString(R.string.hint_other_instructors_none)
				: names.toString());
	}

	/**
	 * The professor currently chosen as coordinator, or -1 before one has been. Read from the
	 * dropdown rather than held in a field, because the dropdown is what the user is editing.
	 */
	private long coordinatorProfID() {
		int index = indexOfSelectedProfessor();
		return index < 0 ? -1L : professors.get(index).profID;
	}

	/**
	 * The offering department decides the stream, so the two are linked here exactly as they
	 * are on the student form: a Mass Media course can only be BAMMC.
	 */
	private void bindDropdowns() {

		DropdownBinder.linkAcademicFields(actvDept, actvStream, actvYear, actvSemester,
				Arrays.asList(Department.values()), null,
				chosenDept, chosenStream, chosenYear, chosenSemester,
				(department, stream, year, semester) -> {
					chosenDept = department;
					chosenStream = stream;
					chosenYear = year;
					chosenSemester = semester;
				});

		DropdownBinder.bind(actvType, Arrays.asList(CourseType.values()), null,
				chosenType, value -> chosenType = value);

		// Professors are loaded off the main thread, then the dropdown is populated back on it.
		viewModel.loadProfessorsSync(list -> Ui.onMain(this, () -> {
			professors = list;
			List<String> labels = new ArrayList<>();
			for (EntityProfessor professor : list) {
				labels.add(professor.name + " (" + professor.department + ")");
			}
			actvProfessor.setAdapter(new ArrayAdapter<>(requireContext(),
					android.R.layout.simple_list_item_1, labels));
			applyPendingProfessor();
			showInstructorSummary();
		}));

		// Changing the coordinator changes who the picker may offer and who the summary may
		// name, so both are redrawn rather than left showing the previous choice.
		actvProfessor.setOnItemClickListener((parent, view, position, id) ->
				showInstructorSummary());
	}

	/**
	 * Validates and saves.
	 * <p>
	 * {@link AcademicRules#validate} runs here even though the dropdowns already steer towards
	 * valid combinations. Narrowing is one-directional, so a value chosen before the field above
	 * it was changed can survive as a contradiction, and this is the only place that catches it.
	 */
	private void onSave(@NonNull View button) {

		tilCode.setError(null);
		tilTopic.setError(null);
		((TextInputLayout) requireView().findViewById(R.id.tilSemester)).setError(null);

		String code = etCode.getText() == null ? "" : etCode.getText().toString().trim();
		String topic = etTopic.getText() == null ? "" : etTopic.getText().toString().trim();

		if (code.isEmpty()) {
			tilCode.setError(getString(R.string.error_required));
			return;
		}
		if (topic.isEmpty()) {
			tilTopic.setError(getString(R.string.error_required));
			return;
		}

		Stream stream = chosenStream;
		Department dept = chosenDept;
		Year year = chosenYear;
		CourseType type = chosenType;

		if (stream == null || dept == null || year == null || type == null) {
			return;
		}

		if (chosenSemester == null) {
			((TextInputLayout) requireView().findViewById(R.id.tilSemester))
					.setError(getString(R.string.error_required));
			return;
		}

		int semester = chosenSemester;

		String problem = AcademicRules.validate(dept, stream, year, semester);
		if (problem != null) {
			new MaterialAlertDialogBuilder(requireContext())
					.setTitle(R.string.dialog_invalid_combination)
					.setMessage(problem)
					.setPositiveButton(android.R.string.ok, null)
					.show();
			return;
		}

		String remarks = etRemarks.getText() == null ? "" :
				etRemarks.getText().toString().trim();

		EntityCourse course = new EntityCourse(code, topic, stream, dept, year, semester,
				type, remarks.isEmpty() ? null : remarks);

		// EntityCourse carries a profID foreign key, so a professor must be chosen.
		int index = indexOfSelectedProfessor();
		if (index < 0) {
			((TextInputLayout) requireView().findViewById(R.id.tilProfessor))
					.setError(getString(R.string.error_required));
			return;
		}
		course.profID = professors.get(index).profID;

		// Passed as-is, coordinator and all. setInstructors folds them in and drops the
		// duplicate, so the form does not have to work out which of the two fields a professor
		// arrived from.
		List<Long> instructorIDs = new ArrayList<>(otherInstructorIDs);

		Runnable navigateBack = () -> Ui.onMain(this, () -> {
			Navigation.findNavController(button).navigateUp();
		});

		if (editingCourseID > 0) {
			course.courseID = editingCourseID;
			viewModel.updateCourse(course, instructorIDs, navigateBack);
		} else {
			viewModel.addCourse(course, instructorIDs, navigateBack);
		}
	}

	/**
	 * Where the currently chosen professor sits in {@link #professors}, or -1 when none is
	 * chosen. Used to preselect the entry in the picker dialog.
	 */
	private int indexOfSelectedProfessor() {
		String selected = actvProfessor.getText().toString().trim();
		for (int i = 0; i < professors.size(); i++) {
			EntityProfessor professor = professors.get(i);
			String label = professor.name + " (" + professor.department + ")";
			if (label.equals(selected)) {
				return i;
			}
		}
		return -1;
	}

}
