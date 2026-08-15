package in.basulabs.siesattendance.frontend;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.pojo.LectureDetails;
import in.basulabs.siesattendance.frontend.adapter.AdapterLecture;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

public class FragmentLectureList extends Fragment {

	private AttendanceViewModel viewModel;

	private AdapterLecture adapter;

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
	                         @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_lecture_list, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity()).get(AttendanceViewModel.class);

		RecyclerView recyclerView = view.findViewById(R.id.rvLectures);

		adapter = new AdapterLecture(new AdapterLecture.OnClick() {

			@Override
			public void onLectureClicked(LectureDetails lecture) {
				Bundle args = new Bundle();
				args.putLong("lectureID", lecture.lectureID);
				Navigation.findNavController(requireParentFragment().requireView())
						.navigate(R.id.action_coursesHub_to_attendance, args);
			}

			@Override
			public void onLectureLongPressed(LectureDetails lecture) {
				confirmDelete(lecture);
			}
		});

		recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
		recyclerView.setAdapter(adapter);

		FloatingActionButton fab = view.findViewById(R.id.fabAddLecture);
		fab.setOnClickListener(v -> Navigation.findNavController(
						requireParentFragment().requireView())
				.navigate(R.id.action_coursesHub_to_takeAttendance));

		viewModel.getAllLectures().observe(getViewLifecycleOwner(), adapter::submitList);
	}

	/**
	 * Deleting a lecture discards its attendance sheet too, so the count is spelled out before
	 * the user commits.
	 */
	private void confirmDelete(@NonNull LectureDetails lecture) {
		String message = lecture.isMarked()
				? getString(R.string.dialog_delete_lecture_marked, lecture.markedCount)
				: getString(R.string.dialog_delete_lecture_message);

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_delete_lecture_title)
				.setMessage(message)
				.setPositiveButton(R.string.action_delete, (d, w) ->
						viewModel.deleteLecture(lecture.lectureID, () -> {
						}))
				.setNegativeButton(R.string.action_cancel, null)
				.show();
	}
}
