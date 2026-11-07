package in.basulabs.siesattendance.frontend;

import android.graphics.Rect;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
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
import in.basulabs.siesattendance.backend.EntityBatchDesc;
import in.basulabs.siesattendance.backend.EntityCourse;
import in.basulabs.siesattendance.backend.EntityProfessor;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;
import in.basulabs.siesattendance.frontend.util.BatchPicker;
import in.basulabs.siesattendance.frontend.util.DropdownBinder;
import in.basulabs.siesattendance.frontend.util.Ui;
import in.basulabs.siesattendance.frontend.util.Permissions;
import in.basulabs.siesattendance.frontend.util.RepresentativePicker;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * Add or edit a course. Passing a {@code courseID} argument switches the form into edit mode.
 * <p>
 * The academic fields are linked through {@link DropdownBinder}, so choosing a department
 * narrows the streams and choosing a stream narrows the years. The course type is independent of
 * all of them. None of these decides who is on the course's roll: that is the batches linked
 * to it.
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

	/** Whether this account may say which batches sit the course: professors only. */
	private boolean mayAssignBatches;

	private TextInputEditText etBatches;

	/** The batches the field offers, and the ones ticked, by ID. */
	private final List<EntityBatchDesc> batchOptions = new ArrayList<>();

	private final Set<Long> batchIDs = new LinkedHashSet<>();

	/**
	 * Whether the course's current links have arrived. Until they have, {@link #batchIDs} is
	 * empty because nothing is known, not because nothing is linked, and saving it would remove
	 * every link the course has.
	 */
	private boolean batchesLoaded;

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

		if (!viewModel.may(Permissions.Capability.COURSES)) {
			// On the screen itself, as well as at the controls that lead here. The controls
			// are the courtesy; this is the one check that cannot be forgotten when somebody
			// adds a fourth way in -- which is exactly what happened to the sign-in gate.
			viewModel.sayNotPermitted();
			Navigation.findNavController(view).popBackStack();
			return;
		}

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

		mayAssignBatches = viewModel.may(Permissions.Capability.BATCHES);
		etBatches = view.findViewById(R.id.etCourseBatches);

		if (mayAssignBatches) {
			etBatches.setOnClickListener(v -> chooseBatches());
			loadBatches();
		} else {
			view.findViewById(R.id.tilCourseBatches).setVisibility(View.GONE);
		}

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
				// The list offers an IKS or VEC course to an administrator alone; this is
				// the check for any other way in (V46).
				if (!viewModel.mayKeepCourseOfType(course.courseType)) {
					viewModel.sayNotPermitted();
					Navigation.findNavController(requireView()).popBackStack();
					return;
				}

				// Appointed on the server, with the roll it keeps, so not part of the save:
				// only for a course the server has and lists as this account's to appoint for.
				viewModel.checkMayAppointFor(course.courseID, may -> Ui.onMain(this, () -> {
					if (may) {
						View representatives =
								requireView().findViewById(R.id.btnRepresentatives);
						representatives.setVisibility(View.VISIBLE);
						representatives.setOnClickListener(v ->
								RepresentativePicker.open(this, viewModel, course));
					}
				}));

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

	/** The batches this course may be linked to and is linked to, for the Batches field. */
	private void loadBatches() {
		viewModel.loadCourseBatchOptions(editingCourseID, null, (options, linked) ->
				Ui.onMain(this, () -> {
					batchOptions.clear();
					batchOptions.addAll(options);

					batchIDs.clear();
					batchIDs.addAll(linked);
					batchesLoaded = true;

					showBatchSummary();
				}));
	}

	/** Whether the type chosen above takes any department's batches (V52). */
	private boolean takesAnyDepartmentsBatches() {
		return chosenType != null && chosenType.takesAnyDepartmentsBatches();
	}

	/**
	 * Opens the batch picker. For an OE, AEC, IKS or VEC course the server is asked for the
	 * session's batches first, since this phone holds only its own department's.
	 */
	private void chooseBatches() {

		boolean anyDepartment = takesAnyDepartmentsBatches();
		boolean keeps = chosenDept != null && viewModel.mayKeepBatchesOf(chosenDept);

		java.util.function.Consumer<List<EntityBatchDesc>> show = options ->
				BatchPicker.show(requireContext(), options, batchIDs, chosenDept,
						anyDepartment, keeps, viewModel.academicYearValue(), picked -> {
							batchIDs.clear();
							batchIDs.addAll(picked);
							showBatchSummary();
						});

		if (!anyDepartment) {
			show.accept(offeredBatches());
			return;
		}

		viewModel.addServerBatches(offeredBatches(), merged -> Ui.onMain(this, () -> {
			// Kept, so the summary can name a server batch once it is ticked.
			for (EntityBatchDesc batch : merged) {
				if (!isListed(batch.batchID)) {
					batchOptions.add(batch);
				}
			}
			show.accept(merged);
		}));
	}

	/**
	 * What the Batches field offers: the session's batches of the department chosen above,
	 * when this account keeps that department's batches, and whatever is linked already so it
	 * can be unticked. A batch is linked only to its own department's courses (V31), except
	 * that an OE, AEC, IKS or VEC course takes anybody's (V52), and is offered every one.
	 */
	@NonNull
	private List<EntityBatchDesc> offeredBatches() {

		boolean anyDepartment = takesAnyDepartmentsBatches();
		boolean keeps = chosenDept != null && viewModel.mayKeepBatchesOf(chosenDept);
		List<EntityBatchDesc> offered = new ArrayList<>();

		for (EntityBatchDesc batch : batchOptions) {
			if (anyDepartment || (keeps && batch.department == chosenDept)
					|| batchIDs.contains(batch.batchID)) {
				offered.add(batch);
			}
		}
		return offered;
	}

	private void showBatchSummary() {

		List<String> names = new ArrayList<>();

		for (EntityBatchDesc batch : batchOptions) {
			if (batchIDs.contains(batch.batchID)) {
				names.add(BatchPicker.describe(batch));
			}
		}

		etBatches.setText(names.isEmpty()
				? getString(R.string.label_no_linked_batches) : String.join(", ", names));
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
	 * Links the academic fields, with one difference from the student form: whether the offering
	 * department decides the stream depends on the course type.
	 * <p>
	 * For a major or a DSE it does, exactly as on the student form — a Mass Media course can
	 * only be BAMMC, because the roll is the students majoring in Mass Media and BAMMC is the
	 * only stream they can be in. For a minor, an open elective, or any of the types a whole
	 * year takes regardless of department, the offering department is somebody else's and the
	 * stream is the class being taught, so every stream stays on offer. Mathematics is studied
	 * as BSc; narrowing on it would make a Mathematics minor read to a BSc_unaided Computer
	 * Science class impossible to enter. See
	 * {@link CourseType#followsOfferingDepartment()}.
	 * <p>
	 * Because the answer hangs on a field of its own, the type dropdown has to rebuild the
	 * linkage when it changes, which also reconciles a now-impossible stream back out.
	 */
	private void bindDropdowns() {

		DropdownBinder.Rebuildable academicFields = DropdownBinder.linkAcademicFields(
				actvDept, actvStream, actvYear, actvSemester,
				Arrays.asList(Department.values()), null,
				chosenDept, chosenStream, chosenYear, chosenSemester,
				() -> chosenType == null || chosenType.followsOfferingDepartment(),
				(department, stream, year, semester) -> {
					chosenDept = department;
					chosenStream = stream;
					chosenYear = year;
					chosenSemester = semester;
				});

		// IKS and VEC only for an administrator (V46), who is the only one the server lets
		// make or change one.
		List<CourseType> types = new ArrayList<>();
		for (CourseType type : CourseType.values()) {
			if (viewModel.mayKeepCourseOfType(type)) {
				types.add(type);
			}
		}

		DropdownBinder.bind(actvType, types, null,
				chosenType, value -> {
					chosenType = value;
					academicFields.rebuild();
					showCoordinatorChoices(true);
				});

		// Professors are loaded off the main thread, then the dropdown is populated back on it.
		viewModel.loadProfessorsSync(list -> Ui.onMain(this, () -> {
			professors = list;
			showCoordinatorChoices(false);
			applyPendingProfessor();
			showInstructorSummary();
		}));

		// Changing the coordinator changes who the picker may offer and who the summary may
		// name, so both are redrawn rather than left showing the previous choice.
		actvProfessor.setOnItemClickListener((parent, view, position, id) -> {
			((TextInputLayout) requireView().findViewById(R.id.tilProfessor)).setError(null);
			showInstructorSummary();
		});
	}

	/**
	 * Fills the coordinator dropdown with the professors who may coordinate a course of the
	 * chosen type: for an AEC, those of English, Hindi or Marathi (V50), and for every other
	 * type, everyone.
	 * <p>
	 * {@link #professors} stays the whole list, because the co-instructor picker offers anyone:
	 * the rule is on the coordinator alone.
	 * <p>
	 * When the type is changed by hand, a coordinator who may not coordinate the new type is
	 * cleared, so the form does not show a choice it will refuse. When the form loads it is
	 * left alone: a course recorded before V50 with a coordinator of another department keeps
	 * showing who that is, and the save says why it has to change.
	 */
	private void showCoordinatorChoices(boolean clearRefused) {

		List<String> labels = new ArrayList<>();
		for (EntityProfessor professor : professors) {
			if (AcademicRules.validateCoordinator(chosenType, professor.department) == null) {
				labels.add(professor.name + " (" + professor.department + ")");
			}
		}
		actvProfessor.setAdapter(new ArrayAdapter<>(requireContext(),
				android.R.layout.simple_list_item_1, labels));

		if (clearRefused) {
			int index = indexOfSelectedProfessor();
			if (index >= 0 && AcademicRules.validateCoordinator(chosenType,
					professors.get(index).department) != null) {
				actvProfessor.setText("", false);
				showInstructorSummary();
			}
		}

		// Said rather than left as an empty dropdown, which looks broken.
		List<Department> offering = chosenType == null ? null : chosenType.offeringDepartments();
		TextInputLayout layout = requireView().findViewById(R.id.tilProfessor);
		layout.setHelperText(offering != null && labels.isEmpty() && !professors.isEmpty()
				? getString(R.string.helper_no_coordinator_for_type,
						AcademicRules.joinDepartments(offering))
				: null);
	}

	/**
	 * Validates and saves.
	 * <p>
	 * {@link AcademicRules#validateCourse} runs here even though the dropdowns already steer
	 * towards valid combinations. Narrowing is one-directional, so a value chosen before the
	 * field above it was changed can survive as a contradiction, and this is the only place that
	 * catches it. The course form uses the course-type-aware check rather than the plain
	 * {@link AcademicRules#validate}, for the reason given on {@link #bindDropdowns()}.
	 */
	private void onSave(@NonNull View button) {

		for (int id : new int[]{R.id.tilCourseCode, R.id.tilCourseTopic, R.id.tilStream,
				R.id.tilDepartment, R.id.tilYear, R.id.tilSemester, R.id.tilProfessor,
				R.id.tilCourseType}) {
			((TextInputLayout) requireView().findViewById(id)).setError(null);
		}

		String code = etCode.getText() == null ? "" : etCode.getText().toString().trim();
		String topic = etTopic.getText() == null ? "" : etTopic.getText().toString().trim();

		if (code.isEmpty()) {
			refuse(R.id.tilCourseCode);
			return;
		}
		if (topic.isEmpty()) {
			refuse(R.id.tilCourseTopic);
			return;
		}

		Stream stream = chosenStream;
		Department dept = chosenDept;
		Year year = chosenYear;
		CourseType type = chosenType;

		// Each of these used to return without a word, which reads as a Save button that
		// does nothing.
		if (stream == null) {
			refuse(R.id.tilStream);
			return;
		}
		if (dept == null) {
			refuse(R.id.tilDepartment);
			return;
		}
		if (year == null) {
			refuse(R.id.tilYear);
			return;
		}
		if (chosenSemester == null) {
			refuse(R.id.tilSemester);
			return;
		}
		if (type == null) {
			refuse(R.id.tilCourseType);
			return;
		}

		int semester = chosenSemester;

		String problem = AcademicRules.validateCourse(dept, stream, year, semester, type);
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
			refuse(R.id.tilProfessor);
			return;
		}
		course.profID = professors.get(index).profID;

		String coordinatorProblem = AcademicRules.validateCoordinator(type,
				professors.get(index).department);
		if (coordinatorProblem != null) {
			new MaterialAlertDialogBuilder(requireContext())
					.setTitle(R.string.dialog_invalid_combination)
					.setMessage(coordinatorProblem)
					.setPositiveButton(android.R.string.ok, null)
					.show();
			return;
		}

		// Passed as-is, coordinator and all. setInstructors folds them in and drops the
		// duplicate, so the form does not have to work out which of the two fields a professor
		// arrived from.
		List<Long> instructorIDs = new ArrayList<>(otherInstructorIDs);

		// Null leaves the links alone: the office edits a course without touching batches, and
		// a save made before the links have loaded must not write an empty set over them.
		List<Long> chosenBatches = null;

		if (mayAssignBatches && batchesLoaded) {

			// A batch of another department cannot stay linked once the department or the
			// type above has changed: the server would refuse the link. One with no
			// department yet is left as it was, and an OE, AEC, IKS or VEC course keeps
			// every one (V52).
			chosenBatches = new ArrayList<>();
			for (EntityBatchDesc batch : batchOptions) {
				if (batchIDs.contains(batch.batchID)
						&& type.mayLinkBatchOf(chosenDept, batch.department)) {
					chosenBatches.add(batch.batchID);
				}
			}
			for (long batchID : batchIDs) {
				if (!chosenBatches.contains(batchID) && !isListed(batchID)) {
					chosenBatches.add(batchID);
				}
			}
		}

		Runnable navigateBack = () -> Ui.onMain(this, () -> {
			Navigation.findNavController(button).navigateUp();
		});

		if (editingCourseID > 0) {
			course.courseID = editingCourseID;
			viewModel.updateCourse(course, instructorIDs, chosenBatches, navigateBack);
		} else {
			viewModel.addCourse(course, instructorIDs, chosenBatches, navigateBack);
		}
	}

	private boolean isListed(long batchID) {
		for (EntityBatchDesc batch : batchOptions) {
			if (batch.batchID == batchID) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Where the currently chosen professor sits in {@link #professors}, or -1 when none is
	 * chosen. Used to preselect the entry in the picker dialog.
	 */
	/**
	 * Marks a field as required and brings it into view.
	 * <p>
	 * The error alone was not enough. Save is at the foot of a long form, so the field it
	 * complained about, the coordinator most often, was scrolled out of sight and the button
	 * looked as if it did nothing.
	 */
	private void refuse(int fieldId) {

		View root = requireView();
		TextInputLayout field = root.findViewById(fieldId);
		field.setError(getString(R.string.error_required));

		if (root instanceof NestedScrollView scroll) {
			scroll.post(() -> {
				Rect where = new Rect();
				field.getDrawingRect(where);
				scroll.offsetDescendantRectToMyCoords(field, where);
				int margin = getResources().getDimensionPixelSize(R.dimen.activity_vertical_margin);
				scroll.smoothScrollTo(0, Math.max(0, where.top - margin));
			});
		}
	}

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
