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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
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

	private TextView rollHeader, rollEmpty, presentCount, absentCount;

	private View rollSection, bulkActions;

	private MaterialButton save, saveAndNext;

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

	/**
	 * True once the roll has been touched: a student toggled, or a bulk action used.
	 * <p>
	 * Needed because every row carries an answer from the moment it loads, so the marks alone
	 * cannot say whether any thought has gone into them. Without this, changing course would
	 * either always warn or never warn, and both are wrong.
	 */
	private boolean rollTouched;

	/**
	 * Selections read back from a saved instance state, applied once the asynchronous course
	 * and batch loads return. The lists are not in hand at the moment the state is restored, so
	 * the choice has to wait for them.
	 */
	private long pendingCourseID = -1L;

	private Long pendingBatchID;

	private boolean pendingBatchChosen;

	private static final String KEY_COURSE = "courseID";
	private static final String KEY_BATCH = "batchID";
	private static final String KEY_HAS_BATCH = "hasBatch";
	private static final String KEY_BATCH_CHOSEN = "batchChosen";
	private static final String KEY_DATE = "date";
	private static final String KEY_START = "start";
	private static final String KEY_END = "end";
	private static final String KEY_TOUCHED = "rollTouched";

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

		RecyclerView recyclerView = view.findViewById(R.id.rvRoll);

		adapter = new AdapterAttendance((studentID, present) -> {
			rollTouched = true;
			viewModel.setPresence(studentID, present);
		});
		recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
		recyclerView.setNestedScrollingEnabled(false);
		recyclerView.setAdapter(adapter);

		save = view.findViewById(R.id.btnSaveAttendance);
		save.setOnClickListener(v -> onSave(v, false));

		saveAndNext = view.findViewById(R.id.btnSaveAndNext);
		saveAndNext.setOnClickListener(v -> onSave(v, true));

		view.findViewById(R.id.btnMarkAllPresent).setOnClickListener(v -> {
			rollTouched = true;
			viewModel.setAllPresence(true);
		});
		view.findViewById(R.id.btnMarkAllAbsent).setOnClickListener(v -> {
			rollTouched = true;
			viewModel.setAllPresence(false);
		});

		etDate.setText(date.format(DATE_LABEL));
		etDate.setOnClickListener(v -> pickDate());
		etStart.setOnClickListener(v -> pickTime(true));
		etEnd.setOnClickListener(v -> pickTime(false));

		// Only on a fresh visit. The roll lives in the activity-scoped ViewModel precisely so
		// it survives a rotation; clearing it here unconditionally threw away the sheet every
		// time the screen was rebuilt.
		if (savedInstanceState == null) {
			viewModel.clearRoll();
		} else {
			restoreFrom(savedInstanceState);
		}

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
						requestCourseChange(courses.get(position), labels));

				applyPendingCourse(labels);
			});
		});
	}

	/**
	 * Guards the course dropdown, because changing course throws the roll away.
	 * <p>
	 * Asked only when the roll has actually been worked on. Choosing a course, seeing it was
	 * the wrong one and picking another is the common case and must not need a confirmation;
	 * losing twenty minutes of marking to a mis-tap is what this is here to stop.
	 */
	private void requestCourseChange(@NonNull EntityCourse course,
	                                 @NonNull List<String> labels) {

		if (selectedCourse != null && selectedCourse.courseID == course.courseID) {
			return;
		}

		if (!rollTouched || viewModel.getAttendanceRows().getValue() == null
				|| viewModel.getAttendanceRows().getValue().isEmpty()) {
			onCourseChosen(course);
			return;
		}

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_discard_roll_title)
				.setMessage(R.string.dialog_discard_roll_message)
				.setNegativeButton(R.string.action_keep_marking, (d, w) -> {
					// The dropdown already shows the course that was tapped, so putting the
					// old one back is part of cancelling.
					int index = indexOf(selectedCourse);
					if (index >= 0) {
						actvCourse.setText(labels.get(index), false);
					}
				})
				.setPositiveButton(R.string.action_discard_and_switch, (d, w) ->
						onCourseChosen(course))
				.show();
	}

	private int indexOf(@Nullable EntityCourse course) {

		if (course == null) {
			return -1;
		}

		for (int i = 0; i < courses.size(); i++) {
			if (courses.get(i).courseID == course.courseID) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Picking a course resets the group selection and loads the groups available for it. The
	 * roll deliberately stays empty until a group is chosen.
	 */
	private void onCourseChosen(@NonNull EntityCourse course) {
		selectedCourse = course;
		selectedBatchID = null;
		batchChosen = false;
		rollTouched = false;

		tilCourse.setError(null);
		tilBatch.setError(null);

		actvBatch.setText("", false);
		viewModel.clearRoll();

		loadBatchOptions(course);
		updateRollVisibility();
	}

	/**
	 * Loads the groups attendance can be taken for: the course's whole class, plus every batch
	 * in the current academic year.
	 * <p>
	 * The batch list is not narrowed by the course. A batch is a named group of students for a
	 * session, not a property of a course, and whoever is taking attendance knows which batch
	 * is sitting in front of them.
	 */
	private void loadBatchOptions(@NonNull EntityCourse course) {
		viewModel.loadBatchesForSession(list -> {
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

				// Restores the group after a rotation. Deliberately not onGroupChosen(),
				// which reloads the roll from the database and would overwrite the marks
				// being restored.
				applyPendingBatch(labels);
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

		int present = 0, absent = 0;
		for (AttendanceRow row : rows) {
			if (row.present) {
				present++;
			} else {
				absent++;
			}
		}

		presentCount.setText(getString(R.string.chip_present_count, present));
		absentCount.setText(getString(R.string.chip_absent_count, absent));
	}

	private void updateRollVisibility() {
		List<AttendanceRow> rows = viewModel.getAttendanceRows().getValue();
		boolean hasRows = rows != null && !rows.isEmpty();

		tilBatch.setVisibility(selectedCourse == null ? View.GONE : View.VISIBLE);

		rollSection.setVisibility(batchChosen ? View.VISIBLE : View.GONE);
		bulkActions.setVisibility(hasRows ? View.VISIBLE : View.GONE);
		rollEmpty.setVisibility(batchChosen && !hasRows ? View.VISIBLE : View.GONE);
		save.setEnabled(hasRows);
		saveAndNext.setEnabled(hasRows);
	}

	// ── Save ─────────────────────────────────────────────────────────────

	/**
	 * @param thenDuplicate keep the marks and roll the clock forward for the next period
	 *                      instead of leaving the screen
	 */
	private void onSave(@NonNull View button, boolean thenDuplicate) {

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
					if (!isAdded()) {
						return;
					}
					if (thenDuplicate) {
						carryForwardToNextPeriod();
					} else {
						Navigation.findNavController(button).navigateUp();
					}
				}));
	}



	/**
	 * Sets the sheet up for the second hour of a double period.
	 * <p>
	 * The marks stay exactly as they are, which is the point: the same students are in the
	 * room, and the professor amends the handful who left rather than calling the roll again.
	 * The clock rolls forward by the length of the period just saved, so a 10:00-11:00 lecture
	 * offers 11:00-12:00 next, and the notes are cleared because they described the hour that
	 * has already been filed.
	 */
	private void carryForwardToNextPeriod() {

		LocalTime previousStart = startTime;
		LocalTime previousEnd = endTime;

		if (previousStart == null || previousEnd == null) {
			return;
		}

		long minutes = java.time.Duration.between(previousStart, previousEnd).toMinutes();

		startTime = previousEnd;
		endTime = previousEnd.plusMinutes(minutes);

		etStart.setText(startTime.format(TIME_LABEL));
		etEnd.setText(endTime.format(TIME_LABEL));
		etNotes.setText("");

		tilStart.setError(null);
		tilEnd.setError(null);

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_next_period_title)
				.setMessage(getString(R.string.dialog_next_period_message,
						startTime.format(TIME_LABEL), endTime.format(TIME_LABEL)))
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}

	// ── Surviving a rotation ─────────────────────────────────────────────

	/**
	 * The roll itself needs no saving: it lives in the activity-scoped ViewModel and outlives
	 * the view. What is saved here is the surrounding choice, which was held in plain fields
	 * and therefore died with the fragment.
	 */
	@Override
	public void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);

		if (selectedCourse != null) {
			outState.putLong(KEY_COURSE, selectedCourse.courseID);
		}

		outState.putBoolean(KEY_HAS_BATCH, selectedBatchID != null);
		if (selectedBatchID != null) {
			outState.putLong(KEY_BATCH, selectedBatchID);
		}

		outState.putBoolean(KEY_BATCH_CHOSEN, batchChosen);
		outState.putBoolean(KEY_TOUCHED, rollTouched);
		outState.putLong(KEY_DATE, date.toEpochDay());

		if (startTime != null) {
			outState.putInt(KEY_START, startTime.toSecondOfDay());
		}
		if (endTime != null) {
			outState.putInt(KEY_END, endTime.toSecondOfDay());
		}
	}

	private void restoreFrom(@NonNull Bundle state) {

		pendingCourseID = state.getLong(KEY_COURSE, -1L);
		pendingBatchID = state.getBoolean(KEY_HAS_BATCH) ? state.getLong(KEY_BATCH) : null;
		pendingBatchChosen = state.getBoolean(KEY_BATCH_CHOSEN);
		rollTouched = state.getBoolean(KEY_TOUCHED);

		date = LocalDate.ofEpochDay(state.getLong(KEY_DATE, date.toEpochDay()));

		if (state.containsKey(KEY_START)) {
			startTime = LocalTime.ofSecondOfDay(state.getInt(KEY_START));
			etStart.setText(startTime.format(TIME_LABEL));
		}
		if (state.containsKey(KEY_END)) {
			endTime = LocalTime.ofSecondOfDay(state.getInt(KEY_END));
			etEnd.setText(endTime.format(TIME_LABEL));
		}
		etDate.setText(date.format(DATE_LABEL));
	}

	/**
	 * Re-selects the saved course once the course list has loaded.
	 * <p>
	 * Goes around {@link #onCourseChosen}, which exists to start a fresh roll and would clear
	 * the very sheet being restored.
	 */
	private void applyPendingCourse(@NonNull List<String> labels) {

		if (pendingCourseID <= 0) {
			return;
		}

		int index = -1;
		for (int i = 0; i < courses.size(); i++) {
			if (courses.get(i).courseID == pendingCourseID) {
				index = i;
				break;
			}
		}

		pendingCourseID = -1L;

		if (index < 0) {
			return;
		}

		selectedCourse = courses.get(index);
		actvCourse.setText(labels.get(index), false);
		loadBatchOptions(selectedCourse);
		updateRollVisibility();
	}

	/** The batch half of the same restore, once the batch list is in hand. */
	private void applyPendingBatch(@NonNull List<String> labels) {

		if (!pendingBatchChosen) {
			return;
		}

		pendingBatchChosen = false;

		int index = 0;
		if (pendingBatchID != null) {
			for (int i = 0; i < batches.size(); i++) {
				if (batches.get(i).batchID == pendingBatchID) {
					index = i + 1;
					break;
				}
			}
		}

		selectedBatchID = pendingBatchID;
		pendingBatchID = null;
		batchChosen = true;
		actvBatch.setText(labels.get(index), false);
		updateRollVisibility();
	}

	@Override
	public void onDestroyView() {
		// Cleared only when the screen is genuinely being left, not when the view is rebuilt
		// underneath it. A rotation destroys the view too, and wiping the roll here was what
		// lost the sheet.
		if (isRemoving() && !requireActivity().isChangingConfigurations()) {
			viewModel.clearRoll();
		}
		super.onDestroyView();
	}
}
