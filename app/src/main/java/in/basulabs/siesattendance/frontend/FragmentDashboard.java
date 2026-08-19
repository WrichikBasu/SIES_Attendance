package in.basulabs.siesattendance.frontend;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.time.LocalDate;
import java.util.Locale;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.frontend.adapter.AdapterLecture;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

public class FragmentDashboard extends Fragment {

	private AttendanceViewModel viewModel;

	private TextView statStudents, statCourses, statLectures, statAttendance;

	private AdapterLecture adapter;

	private View emptyToday;

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
	                         @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_dashboard, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity()).get(AttendanceViewModel.class);

		statStudents = view.findViewById(R.id.statStudents);
		statCourses = view.findViewById(R.id.statCourses);
		statLectures = view.findViewById(R.id.statLecturesToday);
		statAttendance = view.findViewById(R.id.statAvgAttendance);
		emptyToday = view.findViewById(R.id.tvNoLecturesToday);

		RecyclerView recyclerView = view.findViewById(R.id.rvUpcomingLectures);

		adapter = new AdapterLecture(lecture -> {
			Bundle args = new Bundle();
			args.putLong("lectureID", lecture.lectureID);
			Navigation.findNavController(view)
					.navigate(R.id.action_dashboard_to_attendance, args);
		});

		recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
		recyclerView.setNestedScrollingEnabled(false);
		recyclerView.setAdapter(adapter);

		view.findViewById(R.id.btnViewStats).setOnClickListener(v ->
				Navigation.findNavController(v).navigate(R.id.action_dashboard_to_stats));

		view.findViewById(R.id.btnSessions).setOnClickListener(v ->
				Navigation.findNavController(v).navigate(R.id.action_dashboard_to_sessions));

		view.findViewById(R.id.btnTakeAttendance).setOnClickListener(v ->
				Navigation.findNavController(v)
						.navigate(R.id.action_dashboard_to_takeAttendance));

		view.findViewById(R.id.btnBackup).setOnClickListener(v ->
				Navigation.findNavController(v).navigate(R.id.action_dashboard_to_backup));

		observe();
	}

	private void observe() {

		viewModel.countStudents().observe(getViewLifecycleOwner(), count ->
				statStudents.setText(String.valueOf(count == null ? 0 : count)));

		viewModel.countCourses().observe(getViewLifecycleOwner(), count ->
				statCourses.setText(String.valueOf(count == null ? 0 : count)));

		viewModel.countLecturesToday().observe(getViewLifecycleOwner(), count ->
				statLectures.setText(String.valueOf(count == null ? 0 : count)));

		viewModel.getOverallPercentage().observe(getViewLifecycleOwner(), percentage ->
				statAttendance.setText(percentage == null
						? "—"
						: String.format(Locale.getDefault(), "%.0f%%", percentage)));

		viewModel.getLecturesOn(LocalDate.now())
				.observe(getViewLifecycleOwner(), lectures -> {
					adapter.submitList(lectures);
					emptyToday.setVisibility(
							lectures == null || lectures.isEmpty() ? View.VISIBLE : View.GONE);
				});
	}
}
