package in.basulabs.siesattendance.frontend.util;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.EntityAcademicYear;
import in.basulabs.siesattendance.backend.EntityCourse;
import in.basulabs.siesattendance.backend.sync.SyncJson;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * Ticks a course's class representatives on its roll.
 * <p>
 * The phone's side of the console's Representatives tab, and from both ways into a course, as
 * {@link BatchPicker} is: the editor, for whoever may edit the course, and the read-only view
 * for its instructor. The roll is the server's, asked for when the dialog opens, because that is
 * the roll an appointment is checked against.
 */
public final class RepresentativePicker {

	private RepresentativePicker() {
	}

	/**
	 * Asks the server for the course's roll in the current session, shows it, and saves what
	 * was changed. Every refusal on the way is the server's own sentence, in a toast.
	 */
	public static void open(@NonNull Fragment fragment, @NonNull AttendanceViewModel viewModel,
	                        @NonNull EntityCourse course) {

		int session = viewModel.academicYearValue();

		viewModel.loadRepresentativeRoll(course.courseID, roll -> Ui.onMain(fragment, () ->
				show(fragment.requireContext(), course.courseCode, session, roll,
						(appoint, end) -> viewModel.setRepresentatives(course.courseID,
								appoint, end))));
	}

	/**
	 * @param roll   the course's roll for the session, as the server sent it
	 * @param onDone given who to appoint and whose appointment to end when the dialog is
	 *               confirmed, and never when it is cancelled. Either may be empty
	 */
	public static void show(@NonNull Context context, @NonNull String courseCode,
	                        int academicYear, @NonNull List<SyncJson.OnTheRoll> roll,
	                        @NonNull BiConsumer<Set<Long>, Set<Long>> onDone) {

		String title = context.getString(R.string.dialog_representatives_title, courseCode,
				EntityAcademicYear.labelFor(academicYear));

		if (roll.isEmpty()) {
			new MaterialAlertDialogBuilder(context)
					.setTitle(title)
					.setMessage(R.string.empty_roll_for_course)
					.setPositiveButton(R.string.action_close, null)
					.show();
			return;
		}

		String[] labels = new String[roll.size()];
		boolean[] checked = new boolean[roll.size()];

		for (int at = 0; at < roll.size(); at++) {
			SyncJson.OnTheRoll student = roll.get(at);

			String label = student.rollNo() == null || student.rollNo().isBlank()
					? student.name() : student.rollNo() + " · " + student.name();

			// A grant without an account is legal and useless until somebody makes the
			// login, so it is said here rather than discovered later.
			labels[at] = student.hasAccount() ? label
					: context.getString(R.string.label_representative_no_account, label);
			checked[at] = student.represents();
		}

		new MaterialAlertDialogBuilder(context)
				.setTitle(title)
				.setMultiChoiceItems(labels, checked, (d, at, on) -> checked[at] = on)
				.setPositiveButton(R.string.action_done, (d, w) -> {

					Set<Long> appoint = new LinkedHashSet<>();
					Set<Long> end = new LinkedHashSet<>();

					for (int at = 0; at < roll.size(); at++) {
						SyncJson.OnTheRoll student = roll.get(at);

						if (checked[at] && !student.represents()) {
							appoint.add(student.studentId());
						} else if (!checked[at] && student.represents()) {
							end.add(student.studentId());
						}
					}
					onDone.accept(appoint, end);
				})
				.setNegativeButton(R.string.action_cancel, null)
				.show();
	}
}
