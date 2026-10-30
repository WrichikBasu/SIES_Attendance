package in.basulabs.siesattendance.frontend.util;

import android.content.Context;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import in.basulabs.siesattendance.R;
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
	 * @param options the batches that may be ticked, in the order to list them
	 * @param chosen  the ones ticked to begin with
	 * @param onDone  given the new choice when the dialog is confirmed, and never when it is
	 *                cancelled
	 */
	public static void show(@NonNull Context context, @NonNull List<EntityBatchDesc> options,
	                        @NonNull Set<Long> chosen, @NonNull Consumer<Set<Long>> onDone) {

		if (options.isEmpty()) {
			new MaterialAlertDialogBuilder(context)
					.setTitle(R.string.dialog_course_batches_title)
					.setMessage(R.string.helper_no_batches_to_link)
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

	/** A batch's name and session: two sessions can each have a "Practical B1". */
	@NonNull
	public static String describe(@NonNull EntityBatchDesc batch) {
		return batch.batchName + " · " + batch.academicYear;
	}
}
