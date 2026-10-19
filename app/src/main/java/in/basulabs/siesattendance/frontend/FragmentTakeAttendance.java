package in.basulabs.siesattendance.frontend;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.TextView;
import android.widget.Toast;

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
import in.basulabs.siesattendance.frontend.util.SignInGuard;
import in.basulabs.siesattendance.frontend.util.Ui;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * Take attendance for a lecture that just happened.
 * <p>
 * There is no separate scheduling step. Selection is two stages: pick a course, then pick the
 * batch the session was for. Only once a batch is chosen does the roll load. Showing a roll
 * immediately would be wrong for a practical, and the user would have no way to tell that the
 * list in front of them was the wrong one.
 * <p>
 * A batch is required; there is no whole-class option. The database still allows a lecture with
 * no batch and older records use it, but nothing recorded from here produces one. The reason is
 * what a null batch costs in the reports: the export counts a lecture towards a student when it
 * was whole-class or one of their batches, so a session filed against no batch lands in the
 * denominator of everybody in the year, including the students who were never in the room. If
 * the class genuinely is everybody, it needs a batch holding everybody.
 * <p>
 * The lecture row is created when the sheet is saved, so a lecture in the database always
 * represents a session that actually took place.
 * <p>
 * This is the longest screen in the app, and most of its length comes from three things worth
 * knowing about before reading it:
 * <ul>
 *     <li>The course and batch lists load asynchronously, so a selection restored from saved
 *     instance state cannot be applied until they arrive. The {@code pending} fields hold it in
 *     the meantime.</li>
 *     <li>Changing the course or batch after marking has begun would discard the marks, so
 *     {@link #rollTouched} decides whether that needs confirming.</li>
 *     <li>Saving can roll straight on to the next period through
 *     {@link #carryForwardToNextPeriod()}, which is what makes marking consecutive lectures
 *     bearable.</li>
 * </ul>
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

	/**
	 * The batch the session is being recorded for. Null means nothing has been chosen yet, which
	 * is the only thing null means now that there is no whole-class option: the roll stays empty
	 * and the sheet cannot be saved until it is set.
	 */
	private Long selectedBatchID;

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

	/**
	 * Navigation argument: the day the sheet should open on, as an epoch day.
	 * <p>
	 * Set by the callers that are already looking at one particular day, so the sheet starts on
	 * the day being browsed rather than on today. Absent means today, which is what the entry
	 * points with no day of their own want.
	 * <p>
	 * Distinct from {@link #KEY_DATE}: that one is the working date saved across a rotation,
	 * and it wins over this, since by then the user may have moved it.
	 */
	public static final String ARG_DATE = "initialDate";

	private static final String KEY_COURSE = "courseID";
	private static final String KEY_BATCH = "batchID";
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

		if (SignInGuard.mustSignIn(viewModel.hasServer(), viewModel.isSignedIn())) {
			// Checked here as well as at every route in. Three screens start a register today
			// and a fourth will be added by somebody who has not read SignInGuard; this is the
			// one check that cannot be forgotten, because it is on the screen itself. It also
			// covers a session that ended while this screen was open behind another.
			Toast.makeText(requireContext(), R.string.title_sign_in_required,
					Toast.LENGTH_LONG).show();
			Navigation.findNavController(view).popBackStack();
			return;
		}

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

		// Opened from a day being browsed, the sheet starts on that day. Landing on today and
		// leaving the date to be corrected by hand is how a session gets filed under the wrong
		// date, which is not something the saved sheet shows afterwards. Only on a fresh
		// visit: after a rotation, restoreFrom below puts back whatever was actually set.
		Bundle args = getArguments();
		if (savedInstanceState == null && args != null && args.containsKey(ARG_DATE)) {
			date = LocalDate.ofEpochDay(args.getLong(ARG_DATE));
		}

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

	/**
	 * Fills the course dropdown from the active courses, then applies any restored selection.
	 */
	private void loadCourses() {
		viewModel.loadActiveCoursesSync(list -> {
			if (!isAdded()) {
				return;
			}
			Ui.onMain(this, () -> {
				courses = list;
				List<String> labels = new ArrayList<>();
				for (EntityCourse course : list) {
					labels.add(course.courseCode + " – " + course.courseTopic);
				}
				actvCourse.setAdapter(new ArrayAdapter<>(requireContext(),
						android.R.layout.simple_list_item_1, labels));

				actvCourse.setOnItemClickListener((parent, v, position, id) ->
						requestCourseChange(courses.get(position)));

				applyPendingCourse(labels);
			});
		});
	}

	/**
	 * Changing the course keeps whatever has been marked.
	 * <p>
	 * The group is what decides who is in the room, and it is left alone here: the same
	 * students are sitting there whether the hour goes down as theory or practicals. The roll
	 * is re-read because a different course can reach a slightly different set of students,
	 * and every mark already made is carried across it.
	 * <p>
	 * Only changing the group discards anything, which is the one case where the people
	 * themselves differ.
	 */
	private void requestCourseChange(@NonNull EntityCourse course) {

		if (selectedCourse != null && selectedCourse.courseID == course.courseID) {
			return;
		}
		onCourseChosen(course);
	}

	/**
	 * Asks before a change that would throw away marks already entered. Only reached when
	 * {@link #rollTouched} is set, so an untouched sheet switches without interruption.
	 */
	private void confirmGroupChange(long newBatchID, @NonNull List<String> labels) {

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_discard_roll_title)
				.setMessage(R.string.dialog_discard_roll_message)
				.setNegativeButton(R.string.action_keep_marking, (d, w) -> {
					// The dropdown already shows the batch that was tapped, so putting the
					// old one back is part of cancelling. Only reachable with a batch already
					// chosen, since there is nothing to discard before that.
					int previous = indexOfBatch(selectedBatchID);
					actvBatch.setText(previous < 0 ? "" : labels.get(previous), false);
				})
				.setPositiveButton(R.string.action_discard_and_switch, (d, w) ->
						applyGroupChange(newBatchID))
				.show();
	}

	/**
	 * The batch's position in {@link #batches}, or -1 when it is not among them.
	 */
	private int indexOfBatch(@Nullable Long batchID) {

		if (batchID == null) {
			return -1;
		}

		for (int i = 0; i < batches.size(); i++) {
			if (batches.get(i).batchID == batchID) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Commits a batch change and reloads the roll for it, discarding whatever was marked.
	 */
	private void applyGroupChange(long newBatchID) {
		selectedBatchID = newBatchID;
		rollTouched = false;
		tilBatch.setError(null);
		onGroupChosen();
	}


	/**
	 * Picking a course reloads the group list and, if a group is already chosen, the roll for
	 * it. The roll stays empty until a group is chosen.
	 */
	private void onCourseChosen(@NonNull EntityCourse course) {

		boolean hadGroup = selectedBatchID != null;

		selectedCourse = course;
		tilCourse.setError(null);
		tilBatch.setError(null);

		// The batch survives the course change. Batches are listed per session rather than
		// per course, so the one already chosen is still a valid choice for the new course.
		loadBatchOptions(course);

		if (hadGroup) {
			viewModel.loadRoll(course.courseID, selectedBatchID, true);
		}
		updateRollVisibility();
	}

	/**
	 * Loads the batches attendance can be taken for: every batch in the current academic year,
	 * and nothing else.
	 * <p>
	 * The list is not narrowed by the course. A batch is a named group of students for a
	 * session, not a property of a course, and whoever is taking attendance knows which batch
	 * is sitting in front of them.
	 * <p>
	 * An empty list is a dead end rather than a detail: with no whole-class option there is
	 * nothing to record a session against, so the helper text says what to do about it instead
	 * of merely noting the absence.
	 */
	private void loadBatchOptions(@NonNull EntityCourse course) {
		viewModel.loadBatchesForSession(list -> {
			if (!isAdded()) {
				return;
			}
			Ui.onMain(this, () -> {
				batches = list;

				List<String> labels = new ArrayList<>();
				for (EntityBatchDesc batch : list) {
					labels.add(batch.batchName);
				}

				actvBatch.setAdapter(new ArrayAdapter<>(requireContext(),
						android.R.layout.simple_list_item_1, labels));

				actvBatch.setOnItemClickListener((parent, v, position, id) -> {

					long newBatchID = batches.get(position).batchID;

					if (selectedBatchID != null && selectedBatchID == newBatchID) {
						return;
					}

					// A different batch means different people, so the marks cannot follow.
					// This is the only place on the screen that throws work away, and it asks
					// first once there is work to lose.
					if (selectedBatchID != null && rollTouched) {
						confirmGroupChange(newBatchID, labels);
					} else {
						applyGroupChange(newBatchID);
					}
				});

				tilBatch.setHelperText(list.isEmpty()
						? getString(R.string.helper_no_batches_for_session)
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
		if (selectedCourse == null || selectedBatchID == null) {
			return;
		}

		rollHeader.setText(getString(R.string.roll_header_for,
				actvBatch.getText().toString()));
		viewModel.loadRoll(selectedCourse.courseID, selectedBatchID);
	}

	// ── Date and time ────────────────────────────────────────────────────

	/**
	 * Opens the calendar, defaulting to the date already set. The selection comes back as a UTC
	 * midnight and is converted in the same zone, since reading it locally lands a day early
	 * anywhere east of Greenwich.
	 */
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

	/**
	 * Follows the roll held by the ViewModel, which survives rotation and navigation away.
	 */
	private void observe() {
		viewModel.getAttendanceRows().observe(getViewLifecycleOwner(), rows -> {
			adapter.submitRows(rows);
			updateCounts(rows);
			updateRollVisibility();
		});
	}

	/**
	 * Keeps the present and absent tallies above the list in step with the marks.
	 */
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

	/**
	 * Shows the roll only once a group has been chosen, and the empty message only when a chosen
	 * group turns out to have nobody in it. The two states look alike and mean very different
	 * things, so they are kept apart deliberately.
	 */
	private void updateRollVisibility() {
		List<AttendanceRow> rows = viewModel.getAttendanceRows().getValue();
		boolean hasRows = rows != null && !rows.isEmpty();
		boolean batchChosen = selectedBatchID != null;

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
	 * instead of leaving the screen
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
		if (selectedBatchID == null) {
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
				() -> Ui.onMain(this, () -> {
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

		if (selectedBatchID != null) {
			outState.putLong(KEY_BATCH, selectedBatchID);
		}

		outState.putBoolean(KEY_TOUCHED, rollTouched);
		outState.putLong(KEY_DATE, date.toEpochDay());

		if (startTime != null) {
			outState.putInt(KEY_START, startTime.toSecondOfDay());
		}
		if (endTime != null) {
			outState.putInt(KEY_END, endTime.toSecondOfDay());
		}
	}

	/**
	 * Reads back the date, times and selections after the view is recreated. The course and batch
	 * are only recorded as pending here; the dropdown lists they refer to may not have loaded yet.
	 */
	private void restoreFrom(@NonNull Bundle state) {

		pendingCourseID = state.getLong(KEY_COURSE, -1L);
		pendingBatchID = state.containsKey(KEY_BATCH) ? state.getLong(KEY_BATCH) : null;
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

	/**
	 * The batch half of the same restore, once the batch list is in hand.
	 * <p>
	 * A batch that is no longer in the list leaves the selection empty rather than falling back
	 * to the first one, which would hand the marks being restored to a different set of people.
	 */
	private void applyPendingBatch(@NonNull List<String> labels) {

		if (pendingBatchID == null) {
			return;
		}

		int index = indexOfBatch(pendingBatchID);
		pendingBatchID = null;

		if (index < 0) {
			return;
		}

		selectedBatchID = batches.get(index).batchID;
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
