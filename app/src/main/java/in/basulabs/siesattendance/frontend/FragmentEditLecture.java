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

import com.google.android.material.button.MaterialButton;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
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
import in.basulabs.siesattendance.backend.EntityLecture;
import in.basulabs.siesattendance.frontend.util.Ui;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * Corrects a lecture that has already been recorded: its course, batch, date, times or notes.
 * <p>
 * Mistakes here are ordinary: attendance taken against yesterday's date, or against the
 * practicals batch when it was the whole class, noticed only after saving. Without this the only
 * remedy was to delete the record and call the roll again from memory.
 * <p>
 * Changing the course or batch is the one edit that is not purely cosmetic, because it changes
 * who is on the roll. Marks belonging to students who are not on the new roll are counted and
 * put to the user before anything is written, rather than being silently kept or silently
 * dropped.
 */
public class FragmentEditLecture extends Fragment {

	private AttendanceViewModel viewModel;

	private long lectureID;

	private AutoCompleteTextView actvCourse, actvBatch;

	private TextInputLayout tilCourse, tilDate, tilStart, tilEnd;

	private TextInputEditText etDate, etStart, etEnd, etNotes;

	private TextView rollWarning;

	private List<EntityCourse> courses = new ArrayList<>();

	private List<EntityBatchDesc> batches = new ArrayList<>();

	@Nullable
	private EntityCourse selectedCourse;

	@Nullable
	private Long selectedBatchID;

	@Nullable
	private LocalDate date;

	@Nullable
	private LocalTime startTime, endTime;

	/** What the lecture pointed at when the screen opened, to tell a real change from a no-op. */
	private long originalCourseID;

	@Nullable
	private Long originalBatchID;

	private static final DateTimeFormatter DATE_LABEL =
			DateTimeFormatter.ofPattern("EEE, d MMM yyyy");

	private static final DateTimeFormatter TIME_LABEL = DateTimeFormatter.ofPattern("HH:mm");

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
	                         @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_edit_lecture, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity()).get(AttendanceViewModel.class);

		lectureID = getArguments() == null ? -1L : getArguments().getLong("lectureID", -1L);

		tilCourse = view.findViewById(R.id.tilEditCourse);
		tilDate = view.findViewById(R.id.tilEditDate);
		tilStart = view.findViewById(R.id.tilEditStart);
		tilEnd = view.findViewById(R.id.tilEditEnd);

		actvCourse = view.findViewById(R.id.actvEditCourse);
		actvBatch = view.findViewById(R.id.actvEditBatch);
		etDate = view.findViewById(R.id.etEditDate);
		etStart = view.findViewById(R.id.etEditStart);
		etEnd = view.findViewById(R.id.etEditEnd);
		etNotes = view.findViewById(R.id.etEditNotes);
		rollWarning = view.findViewById(R.id.tvRollWarning);

		etDate.setOnClickListener(v -> pickDate());
		etStart.setOnClickListener(v -> pickTime(true));
		etEnd.setOnClickListener(v -> pickTime(false));

		MaterialButton save = view.findViewById(R.id.btnSaveLecture);
		save.setOnClickListener(this::onSave);

		if (lectureID > 0) {
			load();
		}
	}

	// ── Loading ──────────────────────────────────────────────────────────

	/** Loads the lecture along with the course and batch lists the dropdowns need. */
	private void load() {
		viewModel.loadLectureForEdit(lectureID, lecture -> {

			if (lecture == null || !isAdded()) {
				return;
			}

			Ui.onMain(this, () -> {
				prefill(lecture);
			});
		});
	}

	/**
	 * Fills the form from the loaded lecture and records the original course and batch, which is
	 * what {@link #groupChanged()} later compares against.
	 */
	private void prefill(@NonNull EntityLecture lecture) {

		originalCourseID = lecture.courseID;
		originalBatchID = lecture.batchID;
		selectedBatchID = lecture.batchID;

		ZonedDateTime start = lecture.startDateTime;
		ZonedDateTime end = lecture.endDateTime;

		date = start.toLocalDate();
		startTime = start.toLocalTime();
		endTime = end.toLocalTime();

		etDate.setText(date.format(DATE_LABEL));
		etStart.setText(startTime.format(TIME_LABEL));
		etEnd.setText(endTime.format(TIME_LABEL));
		etNotes.setText(lecture.notes == null ? "" : lecture.notes);

		bindCourses();
		bindBatches();
	}

	/**
	 * Fills the course dropdown. Changing the course changes the roll, so it triggers a
	 * recheck.
	 * <p>
	 * Only courses still being offered are listed, so an edit cannot move a lecture onto a
	 * retired course. The lecture's own course is kept in the list whatever its state, since
	 * the form has to show what is actually recorded.
	 */
	private void bindCourses() {
		viewModel.loadCoursesForEdit(originalCourseID, list -> {

			if (list == null || !isAdded()) {
				return;
			}

			Ui.onMain(this, () -> {
				courses = list;

				List<String> labels = new ArrayList<>();
				for (EntityCourse course : list) {
					labels.add(course.courseCode + " \u2013 " + course.courseTopic);
				}

				actvCourse.setAdapter(new ArrayAdapter<>(requireContext(),
						android.R.layout.simple_list_item_1, labels));

				// Pre-select whatever the lecture is currently filed under, so the field
				// shows the truth rather than an empty box the user has to guess at.
				for (int i = 0; i < list.size(); i++) {
					if (list.get(i).courseID == originalCourseID) {
						selectedCourse = list.get(i);
						actvCourse.setText(labels.get(i), false);
						break;
					}
				}

				actvCourse.setOnItemClickListener((parent, v, position, id) -> {
					selectedCourse = courses.get(position);
					tilCourse.setError(null);
					refreshRollWarning();
				});
			});
		});
	}

	/**
	 * Fills the batch dropdown, with a leading entry for the whole class. Every batch in the
	 * session is offered rather than only those linked to the course; see the note at the foot of
	 * {@code DaoCourse}.
	 * <p>
	 * The lecture's own batch is included even when it belongs to an earlier session, since
	 * batches do not carry forward and the field has to show what is actually recorded.
	 */
	private void bindBatches() {
		viewModel.loadBatchesForEdit(selectedBatchID, list -> {

			if (!isAdded()) {
				return;
			}

			Ui.onMain(this, () -> {
				batches = list;

				List<String> labels = new ArrayList<>();
				labels.add(getString(R.string.label_whole_class));
				for (EntityBatchDesc batch : list) {
					labels.add(batch.batchName);
				}

				actvBatch.setAdapter(new ArrayAdapter<>(requireContext(),
						android.R.layout.simple_list_item_1, labels));

				int selected = 0;
				for (int i = 0; i < list.size(); i++) {
					if (selectedBatchID != null && list.get(i).batchID == selectedBatchID) {
						selected = i + 1;
						break;
					}
				}
				actvBatch.setText(labels.get(selected), false);

				actvBatch.setOnItemClickListener((parent, v, position, id) -> {
					selectedBatchID = position == 0 ? null : batches.get(position - 1).batchID;
					refreshRollWarning();
				});
			});
		});
	}

	// ── Pickers ──────────────────────────────────────────────────────────

	private void pickDate() {

		MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
				.setTitleText(R.string.hint_date)
				.setSelection(date == null
						? MaterialDatePicker.todayInUtcMilliseconds()
						: date.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
				.build();

		picker.addOnPositiveButtonClickListener(selection -> {
			date = Instant.ofEpochMilli(selection).atZone(ZoneId.of("UTC")).toLocalDate();
			etDate.setText(date.format(DATE_LABEL));
			tilDate.setError(null);
		});

		picker.show(getParentFragmentManager(), "editDate");
	}

	private void pickTime(boolean isStart) {

		LocalTime current = isStart ? startTime : endTime;

		MaterialTimePicker picker = new MaterialTimePicker.Builder()
				.setTimeFormat(TimeFormat.CLOCK_24H)
				.setHour(current == null ? 9 : current.getHour())
				.setMinute(current == null ? 0 : current.getMinute())
				.setTitleText(isStart ? R.string.hint_start_time : R.string.hint_end_time)
				.build();

		picker.addOnPositiveButtonClickListener(v -> {
			LocalTime chosen = LocalTime.of(picker.getHour(), picker.getMinute());

			if (isStart) {
				startTime = chosen;
				etStart.setText(chosen.format(TIME_LABEL));
				tilStart.setError(null);
			} else {
				endTime = chosen;
				etEnd.setText(chosen.format(TIME_LABEL));
				tilEnd.setError(null);
			}
		});

		picker.show(getParentFragmentManager(), isStart ? "editStart" : "editEnd");
	}

	// ── Roll warning ─────────────────────────────────────────────────────

	/**
	 * Whether the course or batch differs from what the lecture was opened with. Only then can
	 * marks be left stranded, so only then is the roll recomputed.
	 */
	private boolean groupChanged() {
		long courseID = selectedCourse == null ? originalCourseID : selectedCourse.courseID;
		boolean sameBatch = selectedBatchID == null
				? originalBatchID == null
				: selectedBatchID.equals(originalBatchID);
		return courseID != originalCourseID || !sameBatch;
	}

	/**
	 * Shows how many existing marks the current selection would strand, while there is still
	 * time to reconsider.
	 * <p>
	 * The count is taken against the date and time currently in the form rather than the ones
	 * the lecture was loaded with, because moving it into another session changes which roll
	 * it is measured against. Those fields are filled in by {@link #prefill}, so they are only
	 * null if the lecture failed to load, in which case there is nothing to warn about.
	 */
	private void refreshRollWarning() {

		if (!groupChanged() || selectedCourse == null || date == null || startTime == null) {
			rollWarning.setVisibility(View.GONE);
			return;
		}

		viewModel.countStrandedMarks(lectureID, selectedCourse.courseID, selectedBatchID,
				ZonedDateTime.of(date, startTime, ZoneId.systemDefault()),
				stranded -> {

					if (!isAdded()) {
						return;
					}

					Ui.onMain(this, () -> {
						if (stranded == null || stranded == 0) {
							rollWarning.setVisibility(View.GONE);
							return;
						}

						rollWarning.setVisibility(View.VISIBLE);
						rollWarning.setText(getResources().getQuantityString(
								R.plurals.warn_stranded_marks, stranded, stranded));
					});
				});
	}

	// ── Saving ───────────────────────────────────────────────────────────

	private void onSave(@NonNull View button) {

		if (selectedCourse == null) {
			tilCourse.setError(getString(R.string.error_required));
			return;
		}

		if (date == null || startTime == null || endTime == null) {
			tilDate.setError(getString(R.string.error_required));
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

		if (!groupChanged()) {
			commit(button, start, end, notes, false);
			return;
		}

		// The roll is about to change, so ask what should happen to marks that will no longer
		// belong to anyone on it before writing anything.
		viewModel.countStrandedMarks(lectureID, selectedCourse.courseID, selectedBatchID,
				start,
				stranded -> {

					if (!isAdded()) {
						return;
					}

					Ui.onMain(this, () -> {
						if (stranded == null || stranded == 0) {
							commit(button, start, end, notes, false);
						} else {
							askAboutStranded(button, start, end, notes, stranded);
						}
					});
				});
	}

	/**
	 * Puts the stranded marks to the user before anything is written. Keeping them would leave
	 * students counted against a course they do not take; dropping them silently would discard
	 * attendance the user may not realise is at stake.
	 * <p>
	 * The two choices are buttons rather than a list. A dialog cannot show both: AppCompat's
	 * {@code AlertController} only swaps its scroll view for the item list when no message has
	 * been set, so a builder given both renders the message and silently drops every option,
	 * leaving the user nothing to press but Cancel. The count is what makes this decision
	 * answerable, so the message stays and the options become buttons.
	 */
	private void askAboutStranded(@NonNull View button, @NonNull ZonedDateTime start,
	                              @NonNull ZonedDateTime end, @NonNull String notes,
	                              int stranded) {

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_stranded_title)
				.setMessage(getResources().getQuantityString(
						R.plurals.dialog_stranded_message, stranded, stranded))
				.setNeutralButton(R.string.action_cancel, null)
				.setNegativeButton(R.string.option_keep_stranded, (d, w) ->
						commit(button, start, end, notes, false))
				.setPositiveButton(R.string.option_drop_stranded, (d, w) ->
						commit(button, start, end, notes, true))
				.show();
	}

	/** Writes the edit, having settled what happens to any marks that no longer belong. */
	private void commit(@NonNull View button, @NonNull ZonedDateTime start,
	                    @NonNull ZonedDateTime end, @NonNull String notes,
	                    boolean dropStranded) {

		if (selectedCourse == null) {
			return;
		}

		viewModel.updateLecture(lectureID, selectedCourse.courseID, selectedBatchID,
				start, end, notes.isEmpty() ? null : notes, dropStranded,
				() -> Ui.onMain(this, () -> {
					Navigation.findNavController(button).navigateUp();
				}));
	}
}
