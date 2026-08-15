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
import java.util.List;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.CourseType;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.EntityCourse;
import in.basulabs.siesattendance.backend.EntityProfessor;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * Add or edit a course. Passing a {@code courseID} argument switches the form into edit mode.
 */
public class FragmentAddCourse extends Fragment {

	private AttendanceViewModel viewModel;

	private long editingCourseID = -1L;

	private TextInputEditText etCode, etTopic, etRemarks;

	private TextInputLayout tilCode, tilTopic;

	private AutoCompleteTextView actvStream, actvDept, actvYear, actvSemester,
			actvType, actvProfessor;

	private List<EntityProfessor> professors = new ArrayList<>();

	/** Professor to select once the dropdown finishes loading, used when editing. */
	private long pendingProfID = -1L;

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
			requireActivity().runOnUiThread(() -> {
				if (!isAdded()) {
					return;
				}
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

	private void prefillFromExisting() {
		viewModel.loadCourseSync(editingCourseID, course -> {
			if (course == null || !isAdded()) {
				return;
			}
			requireActivity().runOnUiThread(() -> {
				etCode.setText(course.courseCode);
				etTopic.setText(course.courseTopic);
				etRemarks.setText(course.remarks);
				actvStream.setText(course.stream.name(), false);
				actvDept.setText(course.offeringDepartment.name(), false);
				actvYear.setText(course.year.name(), false);
				actvSemester.setText(String.valueOf(course.semester), false);
				actvType.setText(course.courseType.name(), false);
				pendingProfID = course.profID;
				applyPendingProfessor();
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
						professor.name + " (" + professor.department.name() + ")", false);
				return;
			}
		}
	}

	private void bindDropdowns() {

		actvStream.setAdapter(names(Stream.values()));
		actvDept.setAdapter(names(Department.values()));
		actvYear.setAdapter(names(Year.values()));
		actvType.setAdapter(names(CourseType.values()));

		List<String> semesters = new ArrayList<>();
		for (int i = 1; i <= 6; i++) {
			semesters.add(String.valueOf(i));
		}
		actvSemester.setAdapter(new ArrayAdapter<>(requireContext(),
				android.R.layout.simple_list_item_1, semesters));

		// Professors are loaded off the main thread, then the dropdown is populated back on it.
		viewModel.loadProfessorsSync(list -> requireActivity().runOnUiThread(() -> {
			professors = list;
			List<String> labels = new ArrayList<>();
			for (EntityProfessor professor : list) {
				labels.add(professor.name + " (" + professor.department.name() + ")");
			}
			actvProfessor.setAdapter(new ArrayAdapter<>(requireContext(),
					android.R.layout.simple_list_item_1, labels));
			applyPendingProfessor();
		}));
	}

	private <E extends Enum<E>> ArrayAdapter<String> names(E[] values) {
		List<String> items = new ArrayList<>();
		for (E value : values) {
			items.add(value.name());
		}
		return new ArrayAdapter<>(requireContext(),
				android.R.layout.simple_list_item_1, items);
	}

	private void onSave(@NonNull View button) {

		tilCode.setError(null);
		tilTopic.setError(null);

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

		Stream stream = pick(Stream.class, actvStream.getText().toString());
		Department dept = pick(Department.class, actvDept.getText().toString());
		Year year = pick(Year.class, actvYear.getText().toString());
		CourseType type = pick(CourseType.class, actvType.getText().toString());

		if (stream == null || dept == null || year == null || type == null) {
			return;
		}

		int semester;
		try {
			semester = Integer.parseInt(actvSemester.getText().toString().trim());
		} catch (NumberFormatException e) {
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

		Runnable navigateBack = () -> requireActivity().runOnUiThread(() -> {
			if (isAdded()) {
				Navigation.findNavController(button).navigateUp();
			}
		});

		if (editingCourseID > 0) {
			course.courseID = editingCourseID;
			viewModel.updateCourse(course, navigateBack);
		} else {
			viewModel.addCourse(course, navigateBack);
		}
	}

	private int indexOfSelectedProfessor() {
		String selected = actvProfessor.getText().toString().trim();
		for (int i = 0; i < professors.size(); i++) {
			EntityProfessor professor = professors.get(i);
			String label = professor.name + " (" + professor.department.name() + ")";
			if (label.equals(selected)) {
				return i;
			}
		}
		return -1;
	}

	@Nullable
	private <E extends Enum<E>> E pick(@NonNull Class<E> type, @Nullable String raw) {
		if (raw == null || raw.trim().isEmpty()) {
			return null;
		}
		for (E constant : type.getEnumConstants()) {
			if (constant.name().equals(raw.trim())) {
				return constant;
			}
		}
		return null;
	}
}
