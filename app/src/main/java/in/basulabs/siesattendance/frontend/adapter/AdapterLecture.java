package in.basulabs.siesattendance.frontend.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;

import java.time.format.DateTimeFormatter;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.pojo.LectureDetails;

/**
 * The lecture list, used by the dashboard, the courses hub and the attendance picker.
 * <p>
 * The status chip is the point of the row: it says whether attendance has been taken and, if so,
 * how many were present. A lecture with no sheet reads as pending rather than as nobody
 * attending.
 */
public class AdapterLecture extends ListAdapter<LectureDetails, AdapterLecture.Holder> {

	public interface OnClick {
		void onLectureClicked(LectureDetails lecture);

		/**
		 * Long-press is optional; screens that only navigate (the dashboard) leave it empty.
		 */
		default void onLectureLongPressed(LectureDetails lecture) {
		}
	}

	private final OnClick listener;

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM");

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

	private static final DiffUtil.ItemCallback<LectureDetails> DIFF =
			new DiffUtil.ItemCallback<LectureDetails>() {
				@Override
				public boolean areItemsTheSame(@NonNull LectureDetails a,
				                               @NonNull LectureDetails b) {
					return a.lectureID == b.lectureID;
				}

				@Override
				public boolean areContentsTheSame(@NonNull LectureDetails a,
				                                  @NonNull LectureDetails b) {
					return a.markedCount == b.markedCount
							&& a.presentCount == b.presentCount
							&& a.startDateTime.equals(b.startDateTime);
				}
			};

	public AdapterLecture(OnClick listener) {
		super(DIFF);
		this.listener = listener;
	}

	@NonNull
	@Override
	public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		return new Holder(LayoutInflater.from(parent.getContext())
				.inflate(R.layout.item_lecture, parent, false));
	}

	@Override
	public void onBindViewHolder(@NonNull Holder holder, int position) {
		holder.bind(getItem(position));
	}

	class Holder extends RecyclerView.ViewHolder {

		private final TextView name, details;

		private final Chip status;

		Holder(@NonNull View itemView) {
			super(itemView);
			name = itemView.findViewById(R.id.tvLectureCourseName);
			details = itemView.findViewById(R.id.tvLectureDetails);
			status = itemView.findViewById(R.id.chipAttendanceStatus);
		}

		void bind(@NonNull LectureDetails lecture) {

			name.setText(lecture.courseCode + " · " + lecture.courseTopic);
			String batchLabel = lecture.isWholeClass()
					? itemView.getContext().getString(R.string.label_whole_class)
					: lecture.batchName;

			details.setText(batchLabel + " · "
					+ lecture.startDateTime.format(DAY) + " · "
					+ lecture.startDateTime.format(TIME) + " – "
					+ lecture.endDateTime.format(TIME));

			if (lecture.isMarked()) {
				status.setText(itemView.getContext().getString(
						R.string.chip_marked_summary,
						lecture.presentCount, lecture.markedCount));
			} else {
				status.setText(R.string.chip_pending);
			}

			itemView.setOnClickListener(v -> {
				if (listener != null) {
					listener.onLectureClicked(lecture);
				}
			});

			itemView.setOnLongClickListener(v -> {
				if (listener != null) {
					listener.onLectureLongPressed(lecture);
					return true;
				}
				return false;
			});
		}
	}
}
