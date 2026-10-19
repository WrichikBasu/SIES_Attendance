package in.basulabs.siesattendance.frontend;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.datepicker.MaterialDatePicker;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.pojo.LectureDetails;
import in.basulabs.siesattendance.backend.util.MarkSheetExporter;
import in.basulabs.siesattendance.frontend.adapter.AdapterLecture;
import in.basulabs.siesattendance.frontend.util.MarkExportFlow;
import in.basulabs.siesattendance.frontend.util.SignInGuard;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * Step one of marking attendance: choose which lecture. Defaults to today, since that is what
 * a professor opening the app mid-teaching-day almost always wants.
 */
public class FragmentAttendancePicker extends Fragment {

	private AttendanceViewModel viewModel;

	private AdapterLecture adapter;

	private TextView dateLabel;

	private View emptyState;

	private LocalDate selectedDate = LocalDate.now();

	/**
	 * The LiveData currently feeding the list. Held so it can be detached when the date changes;
	 * see {@link #observeForDate()}.
	 */
	private LiveData<List<LectureDetails>> currentSource;

	/** The day's lectures as last shown, so an export can be refused before a file is made. */
	@Nullable
	private List<LectureDetails> shownLectures;

	/** Exporting the day's marks; a field so its save dialogs are registered in time. */
	private final MarkExportFlow exportFlow = new MarkExportFlow(this);

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
	                         @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_attendance_picker, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity()).get(AttendanceViewModel.class);

		dateLabel = view.findViewById(R.id.tvSelectedDate);
		emptyState = view.findViewById(R.id.layoutEmptyState);

		RecyclerView recyclerView = view.findViewById(R.id.rvLecturesForAttendance);

		adapter = new AdapterLecture(lecture -> {
			Bundle args = new Bundle();
			args.putLong("lectureID", lecture.lectureID);
			Navigation.findNavController(view)
					.navigate(R.id.action_attendancePicker_to_attendance, args);
		});

		recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
		recyclerView.setAdapter(adapter);

		view.findViewById(R.id.btnPickDate).setOnClickListener(v -> pickDate());

		// The day being browsed travels with the tap. This screen exists to look at one day at
		// a time, so a sheet started from it means that day, not today; read at click time
		// rather than captured, since the arrows and the calendar move it.
		view.findViewById(R.id.fabTakeAttendance).setOnClickListener(v ->
				SignInGuard.ifAllowed(this, viewModel, Navigation.findNavController(v), () -> {

					Bundle args = new Bundle();
					args.putLong(FragmentTakeAttendance.ARG_DATE, selectedDate.toEpochDay());
					Navigation.findNavController(v)
							.navigate(R.id.action_attendancePicker_to_takeAttendance, args);
				}));

		MaterialButton prev = view.findViewById(R.id.btnPrevDay);
		MaterialButton next = view.findViewById(R.id.btnNextDay);
		prev.setOnClickListener(v -> shiftDate(-1));
		next.setOnClickListener(v -> shiftDate(1));

		setupImportMenu();
		observeForDate();
	}

	/**
	 * The register import and the day's export live in the overflow rather than on the screen.
	 * <p>
	 * This is the screen they belong to — the import produces lectures with their sheets already
	 * filled in, and the export is the day on screen — but neither is what a professor opens
	 * the app to do every day, and they should not compete with it for space.
	 */
	private void setupImportMenu() {
		requireActivity().addMenuProvider(new MenuProvider() {

			@Override
			public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
				inflater.inflate(R.menu.menu_attendance_picker, menu);
			}

			@Override
			public boolean onMenuItemSelected(@NonNull MenuItem item) {
				if (item.getItemId() == R.id.action_import_attendance) {
					Navigation.findNavController(requireView())
							.navigate(R.id.action_attendancePicker_to_importAttendance);
					return true;
				}
				if (item.getItemId() == R.id.action_export_day) {
					exportDay();
					return true;
				}
				return false;
			}
		}, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
	}

	/**
	 * Exports the marks of every lecture on the day being shown.
	 * <p>
	 * Refused up front when there is nothing to put in it, before the save dialog creates a
	 * file: an empty export is a file someone has to open to learn it is empty. A day where
	 * some lectures are marked and some are not is exported, with the unmarked ones named in
	 * the sheet's heading and counted in the message afterwards.
	 */
	private void exportDay() {

		List<LectureDetails> lectures = shownLectures;

		if (lectures == null || lectures.isEmpty()) {
			Toast.makeText(requireContext(), R.string.message_no_lectures_on_day,
					Toast.LENGTH_LONG).show();
			return;
		}

		boolean anyMarked = false;
		for (LectureDetails lecture : lectures) {
			if (lecture.markedCount > 0) {
				anyMarked = true;
				break;
			}
		}

		if (!anyMarked) {
			Toast.makeText(requireContext(), R.string.message_day_not_marked,
					Toast.LENGTH_LONG).show();
			return;
		}

		// Captured now: the arrows can move the screen to another day while the dialogs are
		// up, and the export should be of the day it was asked for.
		LocalDate day = selectedDate;

		exportFlow.start(format -> MarkSheetExporter.dayFileName(day, format),
				(format, highlight) -> MarkSheetExporter.Request.forDay(day, format, highlight));
	}

	private void shiftDate(int days) {
		selectedDate = selectedDate.plusDays(days);
		observeForDate();
	}

	/**
	 * Opens the calendar picker.
	 * <p>
	 * The selection comes back as UTC midnight, which is what the Material picker documents, so
	 * it is converted at UTC rather than at the device zone. Using the local zone here shifts the
	 * date back a day for anyone east of Greenwich, which includes everyone using this app.
	 */
	private void pickDate() {
		MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
				.setTitleText(R.string.hint_filter_date)
				.build();

		picker.addOnPositiveButtonClickListener(selection -> {
			selectedDate = Instant.ofEpochMilli(selection).atZone(ZoneId.of("UTC"))
					.toLocalDate();
			observeForDate();
		});

		picker.show(getParentFragmentManager(), "pickerDate");
	}

	/**
	 * Each date change creates a fresh LiveData from the DAO, so the previous one is detached
	 * first to stop two observers writing into the same adapter.
	 */
	private void observeForDate() {

		dateLabel.setText(formatDate(selectedDate));

		if (currentSource != null) {
			currentSource.removeObservers(getViewLifecycleOwner());
		}

		// Until the new day's lectures arrive, the last list is another day's. An export asked
		// for in that gap is then refused as having nothing, rather than checked against the
		// wrong day.
		shownLectures = null;

		currentSource = viewModel.getLecturesOn(selectedDate);
		currentSource.observe(getViewLifecycleOwner(), lectures -> {
			shownLectures = lectures;
			adapter.submitList(lectures);
			boolean empty = lectures == null || lectures.isEmpty();
			emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
		});
	}

	/** Today is named rather than dated, since that is the case the screen opens on. */
	@NonNull
	private String formatDate(@NonNull LocalDate date) {
		if (date.equals(LocalDate.now())) {
			return getString(R.string.label_today);
		}
		return date.getDayOfMonth() + " "
				+ date.getMonth().getDisplayName(TextStyle.SHORT, Locale.getDefault())
				+ " " + date.getYear();
	}
}
