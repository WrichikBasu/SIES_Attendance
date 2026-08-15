package in.basulabs.siesattendance.frontend;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
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

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;
import java.util.Locale;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.pojo.StudentCourseStat;
import in.basulabs.siesattendance.backend.pojo.StudentWithYear;
import in.basulabs.siesattendance.frontend.adapter.AdapterStudentCourseStat;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * One student's record: identity, class, and a per-course attendance breakdown. Edit and delete
 * live in the toolbar overflow.
 */
public class FragmentStudentDetail extends Fragment {

	private AttendanceViewModel viewModel;

	private AdapterStudentCourseStat adapter;

	private TextView initials, name, rollNo, classLabel, pronouns, email,
			overallPercentage, overallDetail, emptyBreakdown;

	private ProgressBar overallBar;

	private long studentID;

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
	                         @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_student_detail, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity()).get(AttendanceViewModel.class);
		studentID = getArguments() == null ? -1L : getArguments().getLong("studentID", -1L);

		initials = view.findViewById(R.id.tvInitials);
		name = view.findViewById(R.id.tvStudentName);
		rollNo = view.findViewById(R.id.tvRollNo);
		classLabel = view.findViewById(R.id.tvClassLabel);
		pronouns = view.findViewById(R.id.tvPronouns);
		email = view.findViewById(R.id.tvEmail);
		overallPercentage = view.findViewById(R.id.tvOverallPercentage);
		overallDetail = view.findViewById(R.id.tvOverallDetail);
		overallBar = view.findViewById(R.id.progressOverall);
		emptyBreakdown = view.findViewById(R.id.tvEmptyBreakdown);

		RecyclerView recyclerView = view.findViewById(R.id.rvCourseBreakdown);
		adapter = new AdapterStudentCourseStat();
		recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
		recyclerView.setNestedScrollingEnabled(false);
		recyclerView.setAdapter(adapter);

		setupMenu();
		observe();
	}

	private void setupMenu() {
		requireActivity().addMenuProvider(new MenuProvider() {

			@Override
			public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
				inflater.inflate(R.menu.menu_edit_delete, menu);
			}

			@Override
			public boolean onMenuItemSelected(@NonNull MenuItem item) {
				if (item.getItemId() == R.id.action_edit) {
					Bundle args = new Bundle();
					args.putLong("studentID", studentID);
					Navigation.findNavController(requireView())
							.navigate(R.id.action_studentDetail_to_editStudent, args);
					return true;
				}
				if (item.getItemId() == R.id.action_delete) {
					confirmDelete();
					return true;
				}
				return false;
			}
		}, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
	}

	private void confirmDelete() {
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_delete_student_title)
				.setMessage(R.string.dialog_delete_student_message)
				.setPositiveButton(R.string.action_delete, (d, w) ->
						viewModel.deleteStudent(studentID,
								() -> requireActivity().runOnUiThread(() ->
										Navigation.findNavController(requireView())
												.navigateUp())))
				.setNegativeButton(R.string.action_cancel, null)
				.show();
	}

	private void observe() {

		viewModel.getStudent(studentID).observe(getViewLifecycleOwner(), this::bindStudent);

		viewModel.getStudentCourseBreakdown(studentID)
				.observe(getViewLifecycleOwner(), this::bindBreakdown);
	}

	private void bindStudent(@Nullable StudentWithYear student) {
		if (student == null) {
			return;
		}

		initials.setText(student.initials());
		name.setText(student.name);
		rollNo.setText(student.rollNo);
		classLabel.setText(student.classLabel());
		pronouns.setText(student.pronouns);
		email.setText(student.email == null || student.email.isEmpty()
				? getString(R.string.label_no_email)
				: student.email);
	}

	/**
	 * The overall figure is derived from the per-course rows rather than queried separately, so
	 * the headline can never disagree with the list underneath it.
	 */
	private void bindBreakdown(@Nullable List<StudentCourseStat> stats) {

		adapter.submitList(stats);

		boolean empty = stats == null || stats.isEmpty();
		emptyBreakdown.setVisibility(empty ? View.VISIBLE : View.GONE);

		int total = 0, attended = 0;
		if (stats != null) {
			for (StudentCourseStat stat : stats) {
				total += stat.totalLectures;
				attended += stat.attended;
			}
		}

		if (total == 0) {
			overallPercentage.setText("—");
			overallDetail.setText(R.string.label_no_attendance_yet);
			overallBar.setProgress(0);
			return;
		}

		double percentage = (attended * 100d) / total;
		overallPercentage.setText(String.format(Locale.getDefault(), "%.0f%%", percentage));
		overallDetail.setText(getString(R.string.label_attended_of, attended, total));
		overallBar.setProgress((int) Math.round(percentage));
	}
}
