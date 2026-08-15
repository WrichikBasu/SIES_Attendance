package in.basulabs.siesattendance.frontend;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
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
import in.basulabs.siesattendance.frontend.adapter.AdapterLecture;
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

	private LiveData<List<LectureDetails>> currentSource;

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

		view.findViewById(R.id.fabTakeAttendance).setOnClickListener(v ->
				Navigation.findNavController(v)
						.navigate(R.id.action_attendancePicker_to_takeAttendance));

		MaterialButton prev = view.findViewById(R.id.btnPrevDay);
		MaterialButton next = view.findViewById(R.id.btnNextDay);
		prev.setOnClickListener(v -> shiftDate(-1));
		next.setOnClickListener(v -> shiftDate(1));

		observeForDate();
	}

	private void shiftDate(int days) {
		selectedDate = selectedDate.plusDays(days);
		observeForDate();
	}

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

		currentSource = viewModel.getLecturesOn(selectedDate);
		currentSource.observe(getViewLifecycleOwner(), lectures -> {
			adapter.submitList(lectures);
			boolean empty = lectures == null || lectures.isEmpty();
			emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
		});
	}

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
