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
import in.basulabs.siesattendance.frontend.util.BatchPicker;
import in.basulabs.siesattendance.frontend.util.Permissions;
import in.basulabs.siesattendance.frontend.util.ReadOnlyDetails;
import in.basulabs.siesattendance.frontend.util.Ui;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * The courses tab of the courses hub. Long-pressing a course offers to discontinue it, which is
 * as close to deletion as a course gets.
 */
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

				// Read rather than refused: a class representative needs to see who teaches
				// the course they mark and which batches sit it.
				if (!viewModel.may(Permissions.Capability.COURSES)) {
					showReadOnly(course);
					return;
				}

				Bundle args = new Bundle();
				args.putLong("courseID", course.courseID);
				Navigation.findNavController(requireParentFragment().requireView())
						.navigate(R.id.action_coursesHub_to_addCourse, args);
			}

			@Override
			public void onLongPress(EntityCourse course) {

				// Discontinuing is an edit, and the gentlest-looking one: it changes what
				// every later register offers without changing anything already recorded.
				if (!viewModel.may(Permissions.Capability.COURSES)) {
					showReadOnly(course);
					return;
				}

				confirmDiscontinue(course);
			}
		});
		recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
		recyclerView.setAdapter(adapter);

		FloatingActionButton fab = view.findViewById(R.id.fabAddCourse);

		if (viewModel.may(Permissions.Capability.COURSES)) {
			// The FAB lives inside a ViewPager2 page, so navigation must go through the parent
			// fragment's NavController rather than this view's.
			fab.setOnClickListener(v -> Navigation.findNavController(
							requireParentFragment().requireView())
					.navigate(R.id.action_coursesHub_to_addCourse));
		} else {
			fab.setVisibility(View.GONE);
		}

		viewModel.getCourses().observe(getViewLifecycleOwner(), adapter::submitList);
	}

	/**
	 * The course, who teaches it and its batches. Nothing to change, except that a professor may
	 * assign batches to it: any professor may, for any course, whether or not they may edit the
	 * course itself.
	 */
	private void showReadOnly(@NonNull EntityCourse course) {

		Runnable assign = viewModel.may(Permissions.Capability.BATCHES)
				? () -> assignBatches(course) : null;

		viewModel.loadCourseDetails(course.courseID, (loaded, instructors, batches) ->
				Ui.onMain(this, () -> ReadOnlyDetails.showCourse(requireContext(), loaded,
						instructors, batches, assign)));
	}

	private void assignBatches(@NonNull EntityCourse course) {
		viewModel.loadCourseBatchOptions(course.courseID, (options, linked) ->
				Ui.onMain(this, () -> BatchPicker.show(requireContext(), options,
						new java.util.LinkedHashSet<>(linked), picked ->
								viewModel.setCourseBatches(course.courseID,
										new java.util.ArrayList<>(picked)))));
	}

	/**
	 * Courses are never deleted, because every lecture and attendance record is filed under
	 * one. A course that is no longer offered is marked discontinued instead, which keeps its
	 * history readable.
	 */
	private void confirmDiscontinue(@NonNull EntityCourse course) {
		if (course.dateDiscontinued != null) {
			return;
		}

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_discontinue_course_title)
				.setMessage(getString(R.string.dialog_discontinue_course_message,
						course.courseCode))
				.setPositiveButton(R.string.action_discontinue, (d, w) ->
						viewModel.discontinueCourse(course.courseID, () -> {
						}))
				.setNegativeButton(R.string.action_cancel, null)
				.show();
	}
}
