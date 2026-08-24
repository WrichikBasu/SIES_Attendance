package in.basulabs.siesattendance.frontend;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavOptions;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.pojo.AttendanceRow;
import in.basulabs.siesattendance.backend.pojo.LectureDetails;
import in.basulabs.siesattendance.frontend.adapter.AdapterAttendance;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * Step two: mark each student present or absent.
 * <p>
 * The sheet opens with everyone present and the professor taps the absentees, so it can be
 * saved at any point, and an untouched sheet means a full house. That is the common case, and
 * making it the default is what keeps marking a roll to a few taps.
 * <p>
 * Marks are held in the ViewModel rather than here, so rotating the device or stepping away to
 * check something does not lose a partly marked sheet.
 */
public class FragmentAttendance extends Fragment {

	private AttendanceViewModel viewModel;

	private AdapterAttendance adapter;

	private TextView courseName, lectureDetails;

	private Chip chipPresent, chipAbsent;

	private long lectureID;

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
	                         @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_attendance, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity()).get(AttendanceViewModel.class);

		lectureID = getArguments() == null ? -1L : getArguments().getLong("lectureID", -1L);

		courseName = view.findViewById(R.id.tvAttCourseName);
		lectureDetails = view.findViewById(R.id.tvAttLectureDetails);
		chipPresent = view.findViewById(R.id.chipPresent);
		chipAbsent = view.findViewById(R.id.chipAbsent);

		RecyclerView recyclerView = view.findViewById(R.id.rvAttendance);

		adapter = new AdapterAttendance((studentID, present) -> {
			viewModel.setPresence(studentID, present);
		});

		recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
		recyclerView.setAdapter(adapter);

		view.findViewById(R.id.btnMarkAllPresent)
				.setOnClickListener(v -> viewModel.setAllPresence(true));
		view.findViewById(R.id.btnMarkAllAbsent)
				.setOnClickListener(v -> viewModel.setAllPresence(false));

		MaterialButton save = view.findViewById(R.id.btnSaveAttendance);
		save.setOnClickListener(v -> viewModel.saveAttendance(
				() -> requireActivity().runOnUiThread(() ->
						Navigation.findNavController(v).navigateUp())));

		viewModel.getAttendanceRows().observe(getViewLifecycleOwner(), this::onRowsChanged);

		if (lectureID > 0) {
			viewModel.loadAttendanceSheet(lectureID);
		}

		setupMenu();
	}

	/**
	 * The overflow: duplicate this slot, clear the marks, or delete the lecture outright. Bound
	 * to the view lifecycle so the items go when the screen does.
	 */
	private void setupMenu() {
		requireActivity().addMenuProvider(new MenuProvider() {

			@Override
			public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
				inflater.inflate(R.menu.menu_attendance_record, menu);
			}

			@Override
			public boolean onMenuItemSelected(@NonNull MenuItem item) {
				if (item.getItemId() == R.id.action_edit_lecture) {
					Bundle args = new Bundle();
					args.putLong("lectureID", lectureID);
					Navigation.findNavController(requireView())
							.navigate(R.id.action_attendance_to_editLecture, args);
					return true;
				}
				if (item.getItemId() == R.id.action_duplicate_attendance) {
					askDuplicateSlot();
					return true;
				}
				if (item.getItemId() == R.id.action_delete_attendance) {
					askHowToDelete();
					return true;
				}
				return false;
			}
		}, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
	}

	/**
	 * Two different things get called "deleting the attendance", and they are not
	 * interchangeable: one throws away who was present, the other says the lecture never
	 * happened. Statistics treat them differently, since a cleared lecture still counts as a
	 * session that was held, so the choice is put to the user rather than assumed.
	 */
	private void askHowToDelete() {

		if (lectureID <= 0) {
			return;
		}

		String[] options = {
				getString(R.string.option_clear_marks),
				getString(R.string.option_delete_lecture_too)
		};

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_delete_attendance_title)
				.setItems(options, (d, which) -> {
					if (which == 0) {
						confirmClearMarks();
					} else {
						confirmDeleteLecture();
					}
				})
				.setNegativeButton(R.string.action_cancel, null)
				.show();
	}

	/**
	 * Clearing keeps the lecture and discards its sheet, which is what is wanted after marking
	 * the wrong roll. The lecture itself stays, so it still counts as having been held.
	 */
	private void confirmClearMarks() {

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_clear_marks_title)
				.setMessage(R.string.dialog_clear_marks_message)
				.setNegativeButton(R.string.action_cancel, null)
				.setPositiveButton(R.string.action_delete, (d, w) ->
						viewModel.clearAttendanceForLecture(lectureID, this::leaveScreen))
				.show();
	}

	/** Deleting removes the lecture and its sheet together, as though the session never happened. */
	private void confirmDeleteLecture() {

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_delete_lecture_title)
				.setMessage(R.string.dialog_delete_lecture_and_marks)
				.setNegativeButton(R.string.action_cancel, null)
				.setPositiveButton(R.string.action_delete, (d, w) ->
						viewModel.deleteLecture(lectureID, this::leaveScreen))
				.show();
	}

	/** The lecture this screen was showing has gone, so there is nothing left to display. */
	private void leaveScreen() {
		requireActivity().runOnUiThread(() -> {
			if (isAdded()) {
				Navigation.findNavController(requireView()).navigateUp();
			}
		});
	}

	/** Pushes the roll into the adapter and keeps the present and absent counts in step with it. */
	private void onRowsChanged(@Nullable List<AttendanceRow> rows) {

		adapter.submitRows(rows);
		updateHeader();

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

		chipPresent.setText(getString(R.string.chip_present_count, present));
		chipAbsent.setText(getString(R.string.chip_absent_count, absent));
	}

	/** Fills in the course, batch and time strip above the list. */
	private void updateHeader() {
		LectureDetails details = viewModel.getCurrentLecture();
		if (details == null) {
			return;
		}

		courseName.setText(details.courseCode + " · " + details.courseTopic);

		DateTimeFormatter dayFormat = DateTimeFormatter.ofPattern("d MMM yyyy");
		DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("HH:mm");

		String batchLabel = details.isWholeClass()
				? getString(R.string.label_whole_class)
				: details.batchName;

		lectureDetails.setText(batchLabel + " · "
				+ details.startDateTime.format(dayFormat) + " · "
				+ details.startDateTime.format(timeFormat) + " – "
				+ details.endDateTime.format(timeFormat));
	}

	// ── Duplicating ──────────────────────────────────────────────────────

	/**
	 * Asks when the copy should sit, then makes it.
	 * <p>
	 * Prefilled with the slot straight after the lecture being copied, of the same length,
	 * which is the double period this exists for. Everything else about the lecture comes
	 * across untouched, so the common case is two taps.
	 */
	private void askDuplicateSlot() {

		LectureDetails source = viewModel.getCurrentLecture();

		if (source == null || lectureID <= 0) {
			return;
		}

		long minutes = Duration.between(source.startDateTime, source.endDateTime).toMinutes();

		final LocalDate[] date = {source.startDateTime.toLocalDate()};
		final LocalTime[] start = {source.endDateTime.toLocalTime()};
		final LocalTime[] end = {start[0].plusMinutes(minutes)};

		View content = getLayoutInflater().inflate(R.layout.dialog_duplicate_lecture, null);

		TextView explainer = content.findViewById(R.id.tvDuplicateExplainer);
		TextInputEditText dateField = content.findViewById(R.id.etDupDate);
		TextInputEditText startField = content.findViewById(R.id.etDupStart);
		TextInputEditText endField = content.findViewById(R.id.etDupEnd);

		explainer.setText(getString(R.string.hint_duplicate_explainer,
				source.courseCode,
				source.batchName == null
						? getString(R.string.label_whole_class)
						: source.batchName));

		dateField.setText(date[0].format(DUP_DATE));
		startField.setText(start[0].format(DUP_TIME));
		endField.setText(end[0].format(DUP_TIME));

		dateField.setOnClickListener(v -> {
			MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
					.setTitleText(R.string.hint_date)
					.setSelection(date[0].atStartOfDay(ZoneId.of("UTC"))
							.toInstant().toEpochMilli())
					.build();
			picker.addOnPositiveButtonClickListener(selection -> {
				date[0] = Instant.ofEpochMilli(selection)
						.atZone(ZoneId.of("UTC")).toLocalDate();
				dateField.setText(date[0].format(DUP_DATE));
			});
			picker.show(getParentFragmentManager(), "dupDate");
		});

		startField.setOnClickListener(v -> pickTime(start[0], chosen -> {
			start[0] = chosen;
			startField.setText(chosen.format(DUP_TIME));
		}, "dupStart", R.string.hint_start_time));

		endField.setOnClickListener(v -> pickTime(end[0], chosen -> {
			end[0] = chosen;
			endField.setText(chosen.format(DUP_TIME));
		}, "dupEnd", R.string.hint_end_time));

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_duplicate_title)
				.setView(content)
				.setNegativeButton(R.string.action_cancel, null)
				.setPositiveButton(R.string.action_duplicate, (d, w) ->
						viewModel.duplicateLecture(lectureID, date[0], start[0], end[0],
								this::openCopy))
				.show();
	}

	/**
	 * Opens the clock picker, defaulting to the time already set. Uses the 24-hour format
	 * throughout, matching how a timetable is written.
	 */
	private void pickTime(@NonNull LocalTime current,
	                      @NonNull java.util.function.Consumer<LocalTime> onPicked,
	                      @NonNull String tag, int titleRes) {

		MaterialTimePicker picker = new MaterialTimePicker.Builder()
				.setTimeFormat(TimeFormat.CLOCK_24H)
				.setHour(current.getHour())
				.setMinute(current.getMinute())
				.setTitleText(titleRes)
				.build();

		picker.addOnPositiveButtonClickListener(v ->
				onPicked.accept(LocalTime.of(picker.getHour(), picker.getMinute())));

		picker.show(getParentFragmentManager(), tag);
	}

	/**
	 * Opens the copy so it can be corrected and saved.
	 * <p>
	 * Replaces this screen in the back stack rather than stacking on top of it, so going back
	 * from the copy returns to the list instead of to the lecture it was copied from, which
	 * would look like the duplicate had failed.
	 */
	private void openCopy(@Nullable Long newLectureID) {

		if (newLectureID == null) {
			return;
		}

		requireActivity().runOnUiThread(() -> {
			if (!isAdded()) {
				return;
			}

			Bundle args = new Bundle();
			args.putLong("lectureID", newLectureID);

			NavOptions options = new NavOptions.Builder()
					.setPopUpTo(R.id.fragmentAttendance, true)
					.build();

			Navigation.findNavController(requireView())
					.navigate(R.id.fragmentAttendance, args, options);
		});
	}

	private static final DateTimeFormatter DUP_DATE =
			DateTimeFormatter.ofPattern("EEE, d MMM yyyy");

	private static final DateTimeFormatter DUP_TIME = DateTimeFormatter.ofPattern("HH:mm");
}
