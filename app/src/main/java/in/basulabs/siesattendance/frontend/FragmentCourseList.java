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
import in.basulabs.siesattendance.backend.EntityCourse;
import in.basulabs.siesattendance.frontend.adapter.AdapterCourse;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

public class FragmentCourseList extends Fragment {

	private AttendanceViewModel viewModel;

	private AdapterCourse adapter;

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
	                         @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_course_list, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity()).get(AttendanceViewModel.class);

		RecyclerView recyclerView = view.findViewById(R.id.rvCourses);
		adapter = new AdapterCourse(new AdapterCourse.OnAction() {

			@Override
			public void onEdit(EntityCourse course) {
				Bundle args = new Bundle();
				args.putLong("courseID", course.courseID);
				Navigation.findNavController(requireParentFragment().requireView())
						.navigate(R.id.action_coursesHub_to_addCourse, args);
			}

			@Override
			public void onLongPress(EntityCourse course) {
				confirmDelete(course);
			}
		});
		recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
		recyclerView.setAdapter(adapter);

		FloatingActionButton fab = view.findViewById(R.id.fabAddCourse);
		// The FAB lives inside a ViewPager2 page, so navigation must go through the parent
		// fragment's NavController rather than this view's.
		fab.setOnClickListener(v -> Navigation.findNavController(
						requireParentFragment().requireView())
				.navigate(R.id.action_coursesHub_to_addCourse));

		viewModel.getCourses().observe(getViewLifecycleOwner(), adapter::submitList);
	}

	/**
	 * Refused if the course still has lectures; the DAO returns the count so the user knows
	 * what to clear first.
	 */
	private void confirmDelete(@NonNull EntityCourse course) {
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_delete_course_title)
				.setMessage(getString(R.string.dialog_delete_generic_message,
						course.courseCode))
				.setPositiveButton(R.string.action_delete, (d, w) ->
						viewModel.deleteCourse(course, () -> {
						}))
				.setNegativeButton(R.string.action_cancel, null)
				.show();
	}
}
