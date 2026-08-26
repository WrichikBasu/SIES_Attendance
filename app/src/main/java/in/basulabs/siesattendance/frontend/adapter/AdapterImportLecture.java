package in.basulabs.siesattendance.frontend.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.util.AttendanceRecordImporter.LecturePlan;

/**
 * The review list shown before an imported attendance sheet is written.
 * <p>
 * One card per column of the file. The batch line is the reason the screen exists: the file does
 * not say which group sat each session, so the app works it out from who was marked, and that
 * guess has to be visible before it is committed. Everything else on the card is there to let
 * that guess be judged — how many students it covers, how many it leaves out, and whether it
 * would overwrite a sheet already on record.
 * <p>
 * A column that cannot be imported stays in the list rather than being filtered out, greyed and
 * carrying its reason, since a session silently missing from the review is exactly what nobody
 * would notice.
 * <p>
 * A plain adapter rather than a {@code ListAdapter}: the list is replaced wholesale when a file
 * is read and never edited in place, so there is nothing for diffing to do.
 */
public class AdapterImportLecture extends RecyclerView.Adapter<AdapterImportLecture.Holder> {

	private final List<LecturePlan> lectures = new ArrayList<>();

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy");

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

	public void submit(@NonNull List<LecturePlan> plans) {
		lectures.clear();
		lectures.addAll(plans);
		notifyDataSetChanged();
	}

	@NonNull
	@Override
	public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		return new Holder(LayoutInflater.from(parent.getContext())
				.inflate(R.layout.item_import_lecture, parent, false));
	}

	@Override
	public void onBindViewHolder(@NonNull Holder holder, int position) {
		holder.bind(lectures.get(position));
	}

	@Override
	public int getItemCount() {
		return lectures.size();
	}

	static class Holder extends RecyclerView.ViewHolder {

		private final TextView course, when, batch, counts, notes, problem;

		Holder(@NonNull View itemView) {
			super(itemView);
			course = itemView.findViewById(R.id.tvImportCourse);
			when = itemView.findViewById(R.id.tvImportWhen);
			batch = itemView.findViewById(R.id.tvImportBatch);
			counts = itemView.findViewById(R.id.tvImportCounts);
			notes = itemView.findViewById(R.id.tvImportNotes);
			problem = itemView.findViewById(R.id.tvImportProblem);
		}

		void bind(@NonNull LecturePlan lecture) {

			Context context = itemView.getContext();

			course.setText(lecture.courseTopic == null
					? (lecture.courseCode == null ? lecture.label : lecture.courseCode)
					: lecture.courseCode + " · " + lecture.courseTopic);

			if (lecture.start == null || lecture.end == null) {
				when.setText(lecture.label);
			} else {
				when.setText(lecture.start.format(DAY) + " · "
						+ lecture.start.format(TIME) + " – "
						+ lecture.end.format(TIME));
			}

			boolean usable = lecture.isUsable();

			batch.setVisibility(usable ? View.VISIBLE : View.GONE);
			counts.setVisibility(usable ? View.VISIBLE : View.GONE);

			if (usable) {

				// Never null on a usable column: an import always names a batch.
				String group = String.valueOf(lecture.batchName);

				// The "not in this class" figure is what justifies the batch, so it is
				// spelt out whenever there is one and left off entirely when the batch and
				// the marks agree exactly.
				batch.setText(lecture.notInClass == 0
						? context.getString(R.string.label_import_batch_all, group,
						lecture.markCount())
						: context.getString(R.string.label_import_batch, group,
						lecture.markCount(), lecture.notInClass));

				counts.setText(context.getString(R.string.label_import_counts,
						lecture.present, lecture.absent));
			}

			// The overwrite warning is generated here rather than in the plan, because it is
			// about what the user is looking at rather than about the file.
			List<String> lines = new ArrayList<>(lecture.notes);

			if (usable && lecture.replacesExisting) {
				lines.add(context.getString(R.string.note_import_replaces_sheet));
			}

			notes.setText(String.join("\n", lines));
			notes.setVisibility(lines.isEmpty() ? View.GONE : View.VISIBLE);

			problem.setText(String.join("\n", lecture.problems));
			problem.setVisibility(lecture.problems.isEmpty() ? View.GONE : View.VISIBLE);

			// A column that will be skipped is dimmed rather than removed, so the count of
			// sessions on screen still matches the count of columns in the file.
			itemView.setAlpha(usable ? 1f : 0.6f);
		}
	}
}
