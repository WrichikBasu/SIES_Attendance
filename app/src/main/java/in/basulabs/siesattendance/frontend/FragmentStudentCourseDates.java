package in.basulabs.siesattendance.frontend;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.Locale;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.pojo.StudentLectureMark;
import in.basulabs.siesattendance.backend.pojo.StudentWithYear;
import in.basulabs.siesattendance.frontend.adapter.AdapterStudentLectureMark;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * One student, one course, lecture by lecture: the dates they were present and the dates they
 * were absent.
 * <p>
 * The course breakdown on the student detail screen stops at a fraction, which answers whether a
 * student is short but not what to do about it. Six absences clustered in the fortnight before an
 * exam and six spread evenly over a term are the same number and different problems, and the
 * only way to tell them apart is to see the dates.
 * <p>
 * The header restates the total the previous screen showed rather than recomputing anything
 * separately: both are derived from the same rows, so the summary here cannot drift from the
 * list beneath it.
 */
public class FragmentStudentCourseDates extends Fragment {

	private AttendanceViewModel viewModel;

	private AdapterStudentLectureMark adapter;

	private TextView courseName, studentLine, summary, percentage, empty;

	private ProgressBar bar;

	private long studentID, courseID;

	/**
	 * The session the student is being shown in, carried down from the detail screen so the roll
	 * number in the header is the one that screen displayed.
	 * <p>
	 * The marks are not filtered by it. A session is not what bounds this list; the window below
	 * is, and it expresses a session as the dates it spans when that is what was asked for.
	 */
	private int academicYear;

	/**
	 * The window the breakdown was filtered by, inherited so this list adds up to the fraction
	 * the previous screen showed against this course. {@link FragmentStudentDetail#NO_BOUND}
	 * for an open end, since a navigation argument cannot hold a nullable long.
	 */
	@Nullable
	private Long fromEpoch, toEpoch;

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
	                         @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_student_course_dates, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity()).get(AttendanceViewModel.class);

		Bundle args = getArguments();
		studentID = args == null ? -1L : args.getLong("studentID", -1L);
		courseID = args == null ? -1L : args.getLong("courseID", -1L);
		academicYear = args == null ? 0 : args.getInt("academicYear", 0);
		fromEpoch = bound(args, "fromEpoch");
		toEpoch = bound(args, "toEpoch");

		courseName = view.findViewById(R.id.tvCourseName);
		studentLine = view.findViewById(R.id.tvStudentLine);
		summary = view.findViewById(R.id.tvMarkSummary);
		percentage = view.findViewById(R.id.tvCoursePercentage);
		empty = view.findViewById(R.id.tvEmptyMarks);
		bar = view.findViewById(R.id.progressCourse);

		String code = args == null ? null : args.getString("courseCode");
		String topic = args == null ? null : args.getString("courseTopic");
		courseName.setText(topic == null || topic.isEmpty()
				? (code == null ? "" : code)
				: code + " · " + topic);

		// A filtered list has to say so on its face. Without it the figures here look like the
		// student's whole record for this course, and a run of absences outside the window
		// would simply appear not to exist.
		TextView window = view.findViewById(R.id.tvMarkWindow);
		String label = args == null ? null : args.getString("windowLabel");
		window.setText(label);
		window.setVisibility(label == null || label.isEmpty() ? View.GONE : View.VISIBLE);

		RecyclerView recyclerView = view.findViewById(R.id.rvLectureMarks);
		adapter = new AdapterStudentLectureMark();
		recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
		recyclerView.setAdapter(adapter);

		observe();
	}

	/** Reads one end of the window, turning the sentinel back into an absent bound. */
	@Nullable
	private static Long bound(@Nullable Bundle args, @NonNull String key) {

		if (args == null) {
			return null;
		}

		long value = args.getLong(key, FragmentStudentDetail.NO_BOUND);
		return value == FragmentStudentDetail.NO_BOUND ? null : value;
	}

	private void observe() {

		viewModel.getStudent(studentID, academicYear)
				.observe(getViewLifecycleOwner(), this::bindStudent);

		viewModel.getStudentLectureMarks(studentID, courseID, fromEpoch, toEpoch)
				.observe(getViewLifecycleOwner(), this::bindMarks);
	}

	/** The name and roll number, so the list is anchored to a person rather than an ID. */
	private void bindStudent(@Nullable StudentWithYear student) {

		if (student == null) {
			return;
		}
		studentLine.setText(student.displayName() + " · " + student.rollNo);
	}

	/**
	 * Fills the list and the totals above it. Both come from the same rows, which is what keeps
	 * the header honest: the count of absences is the number of red rows and nothing else.
	 */
	private void bindMarks(@Nullable List<StudentLectureMark> marks) {

		adapter.submitList(marks);

		boolean none = marks == null || marks.isEmpty();
		empty.setVisibility(none ? View.VISIBLE : View.GONE);

		int total = marks == null ? 0 : marks.size();
		int present = 0;

		if (marks != null) {
			for (StudentLectureMark mark : marks) {
				if (mark.present) {
					present++;
				}
			}
		}

		if (total == 0) {
			percentage.setText("—");
			summary.setText(R.string.label_no_attendance_yet);
			bar.setProgress(0);
			return;
		}

		double percent = (present * 100d) / total;

		percentage.setText(String.format(Locale.getDefault(), "%.0f%%", percent));
		percentage.setTextColor(ContextCompat.getColor(requireContext(),
				percent < 75d ? R.color.attendance_short : R.color.attendance_ok));
		summary.setText(getString(R.string.label_present_absent_summary,
				present, total, total - present));
		bar.setProgress((int) Math.round(percent));
	}
}
