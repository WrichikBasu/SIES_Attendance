package in.basulabs.siesattendance.frontend.util;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.EntityBatchDesc;
import in.basulabs.siesattendance.backend.EntityCourse;
import in.basulabs.siesattendance.backend.EntityProfessor;
import in.basulabs.siesattendance.backend.pojo.StudentWithYear;

/**
 * A batch or a course, shown to somebody who may read it and not change it.
 * <p>
 * A class representative marks registers for a course and a batch, and has to be able to see what
 * those are: who is in the batch, which courses it is for, who teaches the course. The editors
 * are not the way to show that. They leave rather than show a form whose save would be refused,
 * and an editor with its fields switched off is still an editor, one forgotten switch away from
 * saving. So this is a dialog with nothing to press but Close.
 */
public final class ReadOnlyDetails {

	private ReadOnlyDetails() {
	}

	public static void showBatch(@NonNull Context context, @NonNull EntityBatchDesc batch,
	                             @NonNull List<StudentWithYear> members,
	                             @NonNull List<EntityCourse> courses) {

		StringBuilder text = new StringBuilder();

		if (batch.batchDesc != null && !batch.batchDesc.isBlank()) {
			text.append(batch.batchDesc).append("\n\n");
		}

		List<String> codes = new ArrayList<>();
		for (EntityCourse course : courses) {
			codes.add(course.courseCode);
		}

		text.append(context.getString(R.string.section_batch_courses)).append('\n')
				.append(codes.isEmpty()
						? context.getString(R.string.label_no_linked_courses)
						: String.join(", ", codes))
				.append("\n\n")
				.append(context.getResources().getQuantityString(R.plurals.student_count,
						members.size(), members.size()));

		for (StudentWithYear student : members) {
			text.append('\n').append(student.rollNo).append(" · ").append(student.name);
		}

		show(context, batch.batchName, text);
	}

	/**
	 * @param onAssignBatches what the Assign batches button does, or null for no button: a
	 *                        professor may link batches to a course they cannot edit, and
	 *                        nobody else may
	 */
	public static void showCourse(@NonNull Context context, @NonNull EntityCourse course,
	                              @NonNull List<EntityProfessor> instructors,
	                              @NonNull List<EntityBatchDesc> batches,
	                              @Nullable Runnable onAssignBatches) {

		StringBuilder text = new StringBuilder(course.courseTopic).append("\n\n")
				.append(course.courseType).append(" · ").append(course.offeringDepartment)
				.append('\n')
				.append(course.year).append(' ').append(course.stream)
				.append(" · ").append(context.getString(R.string.label_semester_number,
						course.semester));

		if (course.dateDiscontinued != null) {
			text.append('\n').append(context.getString(R.string.label_discontinued));
		}

		List<String> names = new ArrayList<>();
		for (EntityProfessor professor : instructors) {
			names.add(professor.name);
		}

		text.append("\n\n").append(context.getString(R.string.section_course_instructors))
				.append('\n').append(names.isEmpty() ? "—" : String.join(", ", names));

		List<String> batchNames = new ArrayList<>();
		for (EntityBatchDesc batch : batches) {
			batchNames.add(BatchPicker.describe(batch));
		}

		text.append("\n\n").append(context.getString(R.string.tab_batches)).append('\n')
				.append(batchNames.isEmpty()
						? context.getString(R.string.label_no_linked_batches)
						: String.join(", ", batchNames));

		MaterialAlertDialogBuilder dialog = builder(context, course.courseCode, text);

		if (onAssignBatches != null) {
			dialog.setNeutralButton(R.string.action_assign_batches,
					(d, w) -> onAssignBatches.run());
		}
		dialog.show();
	}

	private static void show(@NonNull Context context, @NonNull String title,
	                         @NonNull CharSequence text) {
		builder(context, title, text).show();
	}

	@NonNull
	private static MaterialAlertDialogBuilder builder(@NonNull Context context,
	                                                  @NonNull String title,
	                                                  @NonNull CharSequence text) {
		return new MaterialAlertDialogBuilder(context)
				.setTitle(title)
				.setMessage(text)
				.setPositiveButton(R.string.action_close, null);
	}
}
