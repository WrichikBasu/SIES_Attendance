package in.basulabs.siesattendance.frontend.util;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.SessionYears;
import in.basulabs.siesattendance.backend.EntityBatchDesc;

/**
 * Ticks the batches a course is for.
 * <p>
 * One dialog for both ways a professor reaches it: the course editor, for whoever may edit the
 * course, and the course's read-only view, for every other professor. Both have to offer the same
 * choices and say them the same way, or the same link would read differently depending on which
 * door somebody came in by.
 */
public final class BatchPicker {

	private BatchPicker() {
	}

	/**
	 * @param options    the batches that may be ticked, in the order to list them
	 * @param chosen     the ones ticked to begin with
	 * @param department the course's offering department, or null before one is chosen
	 * @param anyDepartment whether the course takes any department's batches: an OE, AEC, IKS
	 *                   or VEC course (V52)
	 * @param keeps      whether this account keeps that department's batches, and so may link
	 *                   them. Not asked when {@code anyDepartment}
	 * @param session    the session the batches were looked for in
	 * @param onDone     given the new choice when the dialog is confirmed, and never when it
	 *                   is cancelled
	 */
	public static void show(@NonNull Context context, @NonNull List<EntityBatchDesc> options,
	                        @NonNull Set<Long> chosen, @Nullable Department department,
	                        boolean anyDepartment, boolean keeps, int session,
	                        @NonNull Consumer<Set<Long>> onDone) {

		if (options.isEmpty()) {
			new MaterialAlertDialogBuilder(context)
					.setTitle(R.string.dialog_course_batches_title)
					.setMessage(whyNothing(context, department, anyDepartment, keeps, session))
					.setPositiveButton(R.string.action_close, null)
					.show();
			return;
		}

		String[] labels = new String[options.size()];
		boolean[] checked = new boolean[options.size()];

		for (int at = 0; at < options.size(); at++) {
			labels[at] = describe(options.get(at));
			checked[at] = chosen.contains(options.get(at).batchID);
		}

		new MaterialAlertDialogBuilder(context)
				.setTitle(R.string.dialog_course_batches_title)
				.setMultiChoiceItems(labels, checked, (d, at, on) -> checked[at] = on)
				.setPositiveButton(R.string.action_done, (d, w) -> {

					Set<Long> picked = new LinkedHashSet<>();

					for (int at = 0; at < options.size(); at++) {
						if (checked[at]) {
							picked.add(options.get(at).batchID);
						}
					}
					onDone.accept(picked);
				})
				.setNegativeButton(R.string.action_cancel, null)
				.show();
	}

	/**
	 * Why there is nothing to tick, which is almost never that the session has no batches.
	 * <p>
	 * A course is linked only to its own department's batches (V31), so an Environmental
	 * Science open elective in a session full of other departments' batches has nothing to
	 * offer. The dialog used to say "No batches in this session yet" about exactly that, while
	 * the batch list showed eight of them.
	 */
	@NonNull
	static String whyNothing(@NonNull Context context, @Nullable Department department,
	                         boolean anyDepartment, boolean keeps, int session) {

		if (anyDepartment) {
			return context.getString(R.string.batches_none_in_session,
					SessionYears.labelFor(session));
		}

		if (department == null) {
			return context.getString(R.string.batches_none_choose_department);
		}

		if (!keeps) {
			return context.getString(R.string.batches_none_not_yours, department.toString());
		}

		return context.getString(R.string.batches_none_of_department, department.toString(),
				SessionYears.labelFor(session));
	}

	/**
	 * A batch's name, department and session: two sessions can each have a "Practical B1",
	 * and an OE course's batches are of several departments.
	 */
	@NonNull
	public static String describe(@NonNull EntityBatchDesc batch) {
		return batch.batchName
				+ (batch.department == null ? "" : " · " + batch.department)
				+ " · " + batch.academicYear;
	}
}
