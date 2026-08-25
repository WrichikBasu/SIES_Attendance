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

import java.util.List;
import java.util.Locale;

import in.basulabs.siesattendance.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import in.basulabs.siesattendance.backend.pojo.DeletionImpact;
import in.basulabs.siesattendance.backend.pojo.StudentCourseStat;
import in.basulabs.siesattendance.backend.pojo.StudentWithYear;
import in.basulabs.siesattendance.frontend.adapter.AdapterStudentCourseStat;
import in.basulabs.siesattendance.frontend.util.Ui;
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

	/**
	 * The session this screen is showing the student in.
	 * <p>
	 * Supplied by whoever opened it, because the two routes here mean different years: the
	 * students list has a session of its own, and a drill-down from the report has another.
	 * Everything on the screen — the header, the deletion counts, the withdrawal and the edit
	 * form — uses this one value, so they cannot disagree about which year is on show.
	 */
	private int academicYear;

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

		// Absent when the students list opened this, which is already showing the session it
		// wants; the report passes its own when it has one selected.
		int requested = getArguments() == null ? 0 : getArguments().getInt("academicYear", 0);
		academicYear = requested > 0 ? requested : viewModel.studentFilter().academicYear;

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

	/**
	 * Adds the edit and delete items to the toolbar.
	 * <p>
	 * Registered against the view lifecycle and only while RESUMED, so the items disappear with
	 * the screen. Registering against the activity without that bound leaves the menu behind
	 * after navigating away, and the actions would then run against a student no longer shown.
	 */
	private void setupMenu() {
		requireActivity().addMenuProvider(new MenuProvider() {

			@Override
			public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
				inflater.inflate(R.menu.menu_edit, menu);
			}

			@Override
			public boolean onMenuItemSelected(@NonNull MenuItem item) {
				if (item.getItemId() == R.id.action_edit) {
					Bundle args = new Bundle();
					args.putLong("studentID", studentID);
					args.putInt("academicYear", academicYear);
					Navigation.findNavController(requireView())
							.navigate(R.id.action_studentDetail_to_editStudent, args);
					return true;
				}
				if (item.getItemId() == R.id.action_delete) {
					askHowToRemove();
					return true;
				}
				return false;
			}
		}, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
	}

	/**
	 * Works out what removing this student would cost, then offers only the choices that make
	 * sense for them.
	 * <p>
	 * A student in several sessions can be taken off this one and left intact elsewhere, which
	 * is what withdrawal usually means in practice. A student in a single session has no such
	 * middle ground: removing that enrolment would leave a record nothing points at, so the
	 * only honest option is to delete them.
	 */
	private void askHowToRemove() {

		viewModel.loadDeletionImpact(studentID, academicYear, impact -> {

			if (!isAdded() || impact == null) {
				return;
			}

			Ui.onMain(this, () -> {
				if (impact.spansMultipleSessions()) {
					offerWithdrawOrDelete(impact);
				} else {
					confirmDelete(impact);
				}
			});
		});
	}

	/**
	 * The choice offered for a student enrolled in more than one session: take them off this one,
	 * or remove them entirely.
	 */
	private void offerWithdrawOrDelete(@NonNull DeletionImpact impact) {

		String[] options = {
				getString(R.string.option_withdraw_from_session),
				getString(R.string.option_delete_student_everywhere)
		};

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_remove_student_title)
				.setItems(options, (d, which) -> {
					if (which == 0) {
						confirmWithdraw(impact);
					} else {
						confirmDelete(impact);
					}
				})
				.setNegativeButton(R.string.action_cancel, null)
				.show();
	}

	/**
	 * Withdrawal keeps the student and their attendance, and removes only this session's
	 * enrolment and batch memberships. The count quoted is what stays, not what goes.
	 */
	private void confirmWithdraw(@NonNull DeletionImpact impact) {

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_withdraw_title)
				.setMessage(getString(R.string.dialog_withdraw_message,
						impact.attendanceThisSession))
				.setNegativeButton(R.string.action_cancel, null)
				.setPositiveButton(R.string.action_withdraw, (d, w) ->
						viewModel.withdrawStudentFromSession(studentID, academicYear,
							this::leaveScreen))
				.show();
	}

	/**
	 * The last stop before the data goes. The message quotes the counts rather than warning in
	 * the abstract, because "this deletes 214 attendance records" is a different decision from
	 * "this deletes a student with no history yet".
	 */
	private void confirmDelete(@NonNull DeletionImpact impact) {

		String message = impact.hasHistory()
				? getString(R.string.dialog_delete_student_message_with_history,
				impact.attendanceRecords, impact.enrolments)
				: getString(R.string.dialog_delete_student_message_clean);

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_delete_student_title)
				.setMessage(message)
				.setNegativeButton(R.string.action_cancel, null)
				.setPositiveButton(R.string.action_delete, (d, w) -> {
					// A student with no attendance is a correction and goes in one step. One
					// with history gets asked twice, because there is no undo and no backup
					// unless the user happened to make one.
					if (impact.hasHistory()) {
						confirmDeleteAgain(impact);
					} else {
						viewModel.deleteStudent(studentID, this::leaveScreen);
					}
				})
				.show();
	}

	/** The second confirmation, shown only when there is history to lose. */
	private void confirmDeleteAgain(@NonNull DeletionImpact impact) {

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_delete_student_confirm_title)
				.setMessage(getString(R.string.dialog_delete_student_confirm_message,
						impact.attendanceRecords))
				.setNegativeButton(R.string.action_cancel, null)
				.setPositiveButton(R.string.action_delete_permanently, (d, w) ->
						viewModel.deleteStudent(studentID, this::leaveScreen))
				.show();
	}

	/** The student this screen is showing is gone, so there is nothing left to display. */
	private void leaveScreen() {
		Ui.onMain(this, () -> {
			Navigation.findNavController(requireView()).navigateUp();
		});
	}

	/**
	 * The identity and the breakdown come from separate queries, so the header fills in as soon
	 * as the student is read rather than waiting for the attendance to be totalled.
	 */
	private void observe() {

		viewModel.getStudent(studentID, academicYear)
				.observe(getViewLifecycleOwner(), this::bindStudent);

		viewModel.getStudentCourseBreakdown(studentID)
				.observe(getViewLifecycleOwner(), this::bindBreakdown);
	}

	/**
	 * Fills in the header. A null student means the record has gone, which happens briefly after
	 * a delete while the screen is still being dismissed, so the previous values are left in
	 * place rather than blanked.
	 */
	private void bindStudent(@Nullable StudentWithYear student) {
		if (student == null) {
			return;
		}

		initials.setText(student.initials());
		name.setText(student.displayName());
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
