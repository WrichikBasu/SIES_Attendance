package in.basulabs.siesattendance.frontend;

import android.os.Bundle;
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
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.EntityBatchDesc;
import in.basulabs.siesattendance.backend.EntityCourse;
import in.basulabs.siesattendance.backend.pojo.AttendanceRow;
import in.basulabs.siesattendance.frontend.adapter.AdapterAttendance;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * Take attendance for a lecture that just happened.
 * <p>
 * There is no separate scheduling step. Selection is two stages: pick a course, then pick which
 * group of its students the session was for. Only once a group is chosen does the roll load —
 * showing the whole class immediately would be wrong for a practical, and the user would have
 * no way to tell that the list in front of them was the wrong one.
 * <p>
 * The lecture row is created when the sheet is saved, so a lecture in the database always
 * represents a session that actually took place.
 */
public class FragmentTakeAttendance extends Fragment {

	private static final DateTimeFormatter DATE_LABEL =
			DateTimeFormatter.ofPattern("d MMM yyyy");

	private static final DateTimeFormatter TIME_LABEL = DateTimeFormatter.ofPattern("HH:mm");

	private AttendanceViewModel viewModel;

	private AdapterAttendance adapter;

	private AutoCompleteTextView actvCourse, actvBatch;

	private TextInputEditText etDate, etStart, etEnd, etNotes;

	private TextInputLayout tilCourse, tilBatch, tilDate, tilStart, tilEnd;

	private TextView rollHeader, rollEmpty, presentCount, absentCount, unmarkedCount;

	private View rollSection, bulkActions;

	private MaterialButton save;

	private List<EntityCourse> courses = new ArrayList<>();

	private List<EntityBatchDesc> batches = new ArrayList<>();

	private EntityCourse selectedCourse;

	/** Null means the whole class rather than one batch. */
	private Long selectedBatchID;

	/**
	 * Distinguishes "whole class was chosen" from "nothing chosen yet", since both leave
	 * {@link #selectedBatchID} null.
	 */
	private boolean batchChosen;

	private LocalDate date = LocalDate.now();

	private LocalTime startTime, endTime;

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
	                         @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_take_attendance, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity()).get(AttendanceViewModel.class);

		actvCourse = view.findViewById(R.id.actvCourse);
		actvBatch = view.findViewById(R.id.actvBatch);
		etDate = view.findViewById(R.id.etDate);
		etStart = view.findViewById(R.id.etStartTime);
		etEnd = view.findViewById(R.id.etEndTime);
		etNotes = view.findViewById(R.id.etNotes);

		tilCourse = view.findViewById(R.id.tilCourse);
		tilBatch = view.findViewById(R.id.tilBatch);
		tilDate = view.findViewById(R.id.tilDate);
		tilStart = view.findViewById(R.id.tilStartTime);
		tilEnd = view.findViewById(R.id.tilEndTime);

		rollHeader = view.findViewById(R.id.tvRollHeader);
		rollEmpty = view.findViewById(R.id.tvRollEmpty);
		rollSection = view.findViewById(R.id.layoutRollSection);
		bulkActions = view.findViewById(R.id.layoutBulkActions);
		presentCount = view.findViewById(R.id.tvPresentCount);
		absentCount = view.findViewById(R.id.tvAbsentCount);
		unmarkedCount = view.findViewById(R.id.tvUnmarkedCount);

		RecyclerView recyclerView = view.findViewById(R.id.rvRoll);
		adapter = new AdapterAttendance((studentID, present) ->
				viewModel.setPresence(studentID, present));
		recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
		recyclerView.setNestedScrollingEnabled(false);
		recyclerView.setAdapter(adapter);

		save = view.findViewById(R.id.btnSaveAttendance);
		save.setOnClickListener(this::onSave);

		view.findViewById(R.id.btnMarkAllPresent)
				.setOnClickListener(v -> viewModel.setAllPresence(true));
		view.findViewById(R.id.btnMarkAllAbsent)
				.setOnClickListener(v -> viewModel.setAllPresence(false));

		etDate.setText(date.format(DATE_LABEL));
		etDate.setOnClickListener(v -> pickDate());
		etStart.setOnClickListener(v -> pickTime(true));
		etEnd.setOnClickListener(v -> pickTime(false));

		// The roll belongs to whichever course is picked next, so anything left over from a
		// previous visit is discarded before the screen is shown.
		viewModel.clearRoll();

		loadCourses();
		observe();
		updateRollVisibility();
	}

	// ── Course and batch selection ───────────────────────────────────────

	private void loadCourses() {
		viewModel.loadCoursesSync(list -> {
			if (!isAdded()) {
				return;
			}
			requireActivity().runOnUiThread(() -> {
				courses = list;
				List<String> labels = new ArrayList<>();
				for (EntityCourse course : list) {
					labels.add(course.courseCode + " – " + course.courseTopic);
				}
				actvCourse.setAdapter(new ArrayAdapter<>(requireContext(),
						android.R.layout.simple_list_item_1, labels));

				actvCourse.setOnItemClickListener((parent, v, position, id) ->
						onCourseChosen(courses.get(position)));
			});
		});
	}

	/**
	 * Picking a course resets the group selection and loads the groups available for it. The
	 * roll deliberately stays empty until a group is chosen.
	 */
	private void onCourseChosen(@NonNull EntityCourse course) {
		selectedCourse = course;
		selectedBatchID = null;
		batchChosen = false;

		tilCourse.setError(null);
		tilBatch.setError(null);

		actvBatch.setText("", false);
		viewModel.clearRoll();

		loadBatchOptions(course);
		updateRollVisibility();
	}

	/**
	 * Loads the groups this course can be taken with: its whole class, plus any batch that
	 * overlaps the course's roll in the current academic year. Batches belonging to other
	 * classes of the same year are excluded by the query, so the list only ever offers groups
	 * that make sense for this course.
	 */
	private void loadBatchOptions(@NonNull EntityCourse course) {
		viewModel.loadBatchesForCourse(course.courseID, list -> {
			if (!isAdded()) {
				return;
			}
			requireActivity().runOnUiThread(() -> {
				if (!isAdded()) {
					return;
				}

				batches = list;

				List<String> labels = new ArrayList<>();
				labels.add(getString(R.string.label_whole_class));
				for (EntityBatchDesc batch : list) {
					labels.add(batch.batchName);
				}

				actvBatch.setAdapter(new ArrayAdapter<>(requireContext(),
						android.R.layout.simple_list_item_1, labels));

				actvBatch.setOnItemClickListener((parent, v, position, id) -> {
					selectedBatchID = position == 0 ? null : batches.get(position - 1).batchID;
					batchChosen = true;
					tilBatch.setError(null);
					onGroupChosen();
				});

				tilBatch.setHelperText(list.isEmpty()
						? getString(R.string.helper_no_batches_for_course)
						: null);
			});
		});
	}

	/**
	 * Loads the roll for the chosen group and labels it, so it is obvious whose names are on
	 * screen before anyone starts tapping.
	 */
	private void onGroupChosen() {
		if (selectedCourse == null) {
			return;
		}

		String groupLabel = selectedBatchID == null
				? selectedCourse.year + " " + selectedCourse.stream + " "
				+ selectedCourse.offeringDepartment
				: actvBatch.getText().toString();

		rollHeader.setText(getString(R.string.roll_header_for, groupLabel));
		viewModel.loadRoll(selectedCourse.courseID, selectedBatchID);
	}

	// ── Date and time ────────────────────────────────────────────────────

	private void pickDate() {
		MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
				.setTitleText(R.string.hint_date)
				.setSelection(date.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
				.build();

		picker.addOnPositiveButtonClickListener(selection -> {
			// The picker hands back UTC midnight, so reading it back in UTC avoids landing on
			// the previous day for anyone east or west of Greenwich.
			date = Instant.ofEpochMilli(selection).atZone(ZoneId.of("UTC")).toLocalDate();
			etDate.setText(date.format(DATE_LABEL));
			tilDate.setError(null);
		});

		picker.show(getParentFragmentManager(), "date");
	}

	private void pickTime(boolean isStart) {
		LocalTime initial = isStart
				? (startTime == null ? LocalTime.of(10, 0) : startTime)
				: (endTime == null
				? (startTime == null ? LocalTime.of(11, 0) : startTime.plusHours(1))
				: endTime);

		MaterialTimePicker picker = new MaterialTimePicker.Builder()
				.setTimeFormat(TimeFormat.CLOCK_24H)
				.setHour(initial.getHour())
				.setMinute(initial.getMinute())
				.setTitleText(isStart ? R.string.hint_start_time : R.string.hint_end_time)
				.build();

		picker.addOnPositiveButtonClickListener(v -> {
			LocalTime picked = LocalTime.of(picker.getHour(), picker.getMinute());
			if (isStart) {
				startTime = picked;
				etStart.setText(picked.format(TIME_LABEL));
				tilStart.setError(null);

				// Most lectures run an hour; filling the end in saves a second picker without
				// preventing the user from changing it.
				if (endTime == null) {
					endTime = picked.plusHours(1);
					etEnd.setText(endTime.format(TIME_LABEL));
				}
			} else {
				endTime = picked;
				etEnd.setText(picked.format(TIME_LABEL));
				tilEnd.setError(null);
			}
		});

		picker.show(getParentFragmentManager(), isStart ? "start" : "end");
	}

	// ── Roll state ───────────────────────────────────────────────────────

	private void observe() {
		viewModel.getAttendanceRows().observe(getViewLifecycleOwner(), rows -> {
			adapter.submitRows(rows);
			updateCounts(rows);
			updateRollVisibility();
		});
	}

	private void updateCounts(@Nullable List<AttendanceRow> rows) {
		if (rows == null) {
			return;
		}

		int present = 0, absent = 0, unmarked = 0;
		for (AttendanceRow row : rows) {
			if (row.present == null) {
				unmarked++;
			} else if (row.present) {
				present++;
			} else {
				absent++;
			}
		}

		presentCount.setText(getString(R.string.chip_present_count, present));
		absentCount.setText(getString(R.string.chip_absent_count, absent));
		unmarkedCount.setVisibility(unmarked > 0 ? View.VISIBLE : View.GONE);
		unmarkedCount.setText(getString(R.string.label_unmarked_count, unmarked));
	}

	private void updateRollVisibility() {
		List<AttendanceRow> rows = viewModel.getAttendanceRows().getValue();
		boolean hasRows = rows != null && !rows.isEmpty();

		tilBatch.setVisibility(selectedCourse == null ? View.GONE : View.VISIBLE);

		rollSection.setVisibility(batchChosen ? View.VISIBLE : View.GONE);
		bulkActions.setVisibility(hasRows ? View.VISIBLE : View.GONE);
		rollEmpty.setVisibility(batchChosen && !hasRows ? View.VISIBLE : View.GONE);
		save.setEnabled(hasRows);
	}

	// ── Save ─────────────────────────────────────────────────────────────

	private void onSave(@NonNull View button) {

		tilCourse.setError(null);
		tilDate.setError(null);
		tilStart.setError(null);
		tilEnd.setError(null);

		tilBatch.setError(null);

		if (selectedCourse == null) {
			tilCourse.setError(getString(R.string.error_required));
			return;
		}
		if (!batchChosen) {
			tilBatch.setError(getString(R.string.error_required));
			return;
		}
		if (startTime == null) {
			tilStart.setError(getString(R.string.error_required));
			return;
		}
		if (endTime == null) {
			tilEnd.setError(getString(R.string.error_required));
			return;
		}

		ZoneId zone = ZoneId.systemDefault();
		ZonedDateTime start = ZonedDateTime.of(date, startTime, zone);
		ZonedDateTime end = ZonedDateTime.of(date, endTime, zone);

		if (!end.isAfter(start)) {
			tilEnd.setError(getString(R.string.error_end_before_start));
			return;
		}

		String notes = etNotes.getText() == null ? "" : etNotes.getText().toString().trim();

		viewModel.recordLectureWithAttendance(selectedCourse.courseID, selectedBatchID,
				start, end, notes.isEmpty() ? null : notes,
				() -> requireActivity().runOnUiThread(() -> {
					if (isAdded()) {
						Navigation.findNavController(button).navigateUp();
					}
				}));
	}

	@Override
	public void onDestroyView() {
		// The roll lives in the shared ViewModel, so it is cleared on the way out rather than
		// leaking into the edit-an-existing-lecture screen.
		viewModel.clearRoll();
		super.onDestroyView();
	}
}
