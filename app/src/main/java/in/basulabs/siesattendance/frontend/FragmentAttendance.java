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

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;

import java.time.format.DateTimeFormatter;
import java.util.List;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.pojo.AttendanceRow;
import in.basulabs.siesattendance.backend.pojo.LectureDetails;
import in.basulabs.siesattendance.frontend.adapter.AdapterAttendance;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * Step two: mark each student present or absent. The sheet refuses to save while anyone is
 * still unmarked, so a partially-filled roll can never masquerade as a complete record.
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
	}

	private void onRowsChanged(@Nullable List<AttendanceRow> rows) {

		adapter.submitRows(rows);
		updateHeader();

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

		chipPresent.setText(getString(R.string.chip_present_count, present));
		chipAbsent.setText(getString(R.string.chip_absent_count, absent));

		View root = getView();
		if (root != null) {
			TextView unmarkedLabel = root.findViewById(R.id.tvUnmarkedCount);
			unmarkedLabel.setVisibility(unmarked > 0 ? View.VISIBLE : View.GONE);
			unmarkedLabel.setText(getString(R.string.label_unmarked_count, unmarked));
		}
	}

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
}
