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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;

import java.util.List;
import java.util.Locale;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.pojo.StudentAttendanceStat;
import in.basulabs.siesattendance.frontend.adapter.AdapterCourseStat;
import in.basulabs.siesattendance.frontend.adapter.AdapterStudentStat;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * Attendance statistics, split by student and by course. Students are sorted worst-first so the
 * ones at risk of a shortfall appear without scrolling.
 */
public class FragmentStats extends Fragment {

	private AttendanceViewModel viewModel;

	private AdapterStudentStat studentAdapter;

	private AdapterCourseStat courseAdapter;

	private RecyclerView recyclerView;

	private TextView overallValue, shortCount, markedLectures;

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
	                         @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_stats, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity()).get(AttendanceViewModel.class);

		overallValue = view.findViewById(R.id.tvOverallPercentage);
		shortCount = view.findViewById(R.id.tvShortCount);
		markedLectures = view.findViewById(R.id.tvMarkedLectures);

		recyclerView = view.findViewById(R.id.rvStats);
		recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

		studentAdapter = new AdapterStudentStat();
		courseAdapter = new AdapterCourseStat();
		recyclerView.setAdapter(studentAdapter);

		TabLayout tabs = view.findViewById(R.id.tabStats);
		tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
			@Override
			public void onTabSelected(TabLayout.Tab tab) {
				recyclerView.setAdapter(tab.getPosition() == 0
						? studentAdapter : courseAdapter);
			}

			@Override
			public void onTabUnselected(TabLayout.Tab tab) {
			}

			@Override
			public void onTabReselected(TabLayout.Tab tab) {
			}
		});

		observe();
	}

	private void observe() {

		viewModel.getOverallPercentage().observe(getViewLifecycleOwner(), percentage ->
				overallValue.setText(percentage == null
						? "—"
						: String.format(Locale.getDefault(), "%.1f%%", percentage)));

		viewModel.getStudentStats().observe(getViewLifecycleOwner(), stats -> {
			studentAdapter.submitList(stats);
			shortCount.setText(String.valueOf(countShort(stats)));
		});

		viewModel.getCourseStats().observe(getViewLifecycleOwner(), stats -> {
			courseAdapter.submitList(stats);
			int lectures = 0;
			if (stats != null) {
				for (in.basulabs.siesattendance.backend.pojo.CourseAttendanceStat stat : stats) {
					lectures += stat.lectureCount;
				}
			}
			markedLectures.setText(String.valueOf(lectures));
		});
	}

	private int countShort(@Nullable List<StudentAttendanceStat> stats) {
		if (stats == null) {
			return 0;
		}
		int count = 0;
		for (StudentAttendanceStat stat : stats) {
			if (stat.isShort()) {
				count++;
			}
		}
		return count;
	}
}
