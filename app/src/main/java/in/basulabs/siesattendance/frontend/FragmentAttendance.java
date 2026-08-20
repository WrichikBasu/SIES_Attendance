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
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

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
 * saved at any point — an untouched sheet means a full house.
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

	private void setupMenu() {
		requireActivity().addMenuProvider(new MenuProvider() {

			@Override
			public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
				inflater.inflate(R.menu.menu_attendance_record, menu);
			}

			@Override
			public boolean onMenuItemSelected(@NonNull MenuItem item) {
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
	 * happened. Statistics treat them differently — a cleared lecture still counts as a
	 * session that was scheduled — so the choice is put to the user rather than assumed.
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

	private void confirmClearMarks() {

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_clear_marks_title)
				.setMessage(R.string.dialog_clear_marks_message)
				.setNegativeButton(R.string.action_cancel, null)
				.setPositiveButton(R.string.action_delete, (d, w) ->
						viewModel.clearAttendanceForLecture(lectureID, this::leaveScreen))
				.show();
	}

	private void confirmDeleteLecture() {

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_delete_lecture_title)
				.setMessage(R.string.dialog_delete_lecture_and_marks)
				.setNegativeButton(R.string.action_cancel, null)
				.setPositiveButton(R.string.action_delete, (d, w) ->
						viewModel.deleteLecture(lectureID, this::leaveScreen))
				.show();
	}

	private void leaveScreen() {
		requireActivity().runOnUiThread(() -> {
			if (isAdded()) {
				Navigation.findNavController(requireView()).navigateUp();
			}
		});
	}

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
