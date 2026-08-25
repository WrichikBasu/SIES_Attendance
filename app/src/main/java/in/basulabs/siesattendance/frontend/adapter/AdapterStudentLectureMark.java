package in.basulabs.siesattendance.frontend.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.time.format.DateTimeFormatter;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.pojo.StudentLectureMark;

/**
 * One student's individual lecture marks for one course: the date, when it was held, and whether
 * they were there.
 * <p>
 * Present and absent are said in words and in colour together. Colour alone would leave the
 * distinction unreadable to anyone who cannot separate the two hues, and it is the entire content
 * of the row.
 * <p>
 * The lecture's own note is deliberately left out. Notes are written about the class as a whole,
 * a headcount or a topic, and only some lectures carry one, so putting them here made a handful
 * of rows longer than the rest and said nothing about this student. The lecture list is where a
 * note belongs.
 */
public class AdapterStudentLectureMark
		extends ListAdapter<StudentLectureMark, AdapterStudentLectureMark.Holder> {

	/**
	 * The weekday is worth the space. An absence on a Wednesday when Wednesday is the only
	 * practical slot means something a bare date does not.
	 */
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, d MMM yyyy");

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

	private static final DiffUtil.ItemCallback<StudentLectureMark> DIFF =
			new DiffUtil.ItemCallback<StudentLectureMark>() {
				@Override
				public boolean areItemsTheSame(@NonNull StudentLectureMark a,
				                               @NonNull StudentLectureMark b) {
					return a.lectureID == b.lectureID;
				}

				@Override
				public boolean areContentsTheSame(@NonNull StudentLectureMark a,
				                                  @NonNull StudentLectureMark b) {
					return a.present == b.present
							&& a.startDateTime.equals(b.startDateTime)
							&& a.endDateTime.equals(b.endDateTime);
				}
			};

	public AdapterStudentLectureMark() {
		super(DIFF);
	}

	@NonNull
	@Override
	public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		return new Holder(LayoutInflater.from(parent.getContext())
				.inflate(R.layout.item_lecture_mark, parent, false));
	}

	@Override
	public void onBindViewHolder(@NonNull Holder holder, int position) {
		holder.bind(getItem(position));
	}

	static class Holder extends RecyclerView.ViewHolder {

		private final TextView date, details, status;

		private final View stripe;

		Holder(@NonNull View itemView) {
			super(itemView);
			date = itemView.findViewById(R.id.tvMarkDate);
			details = itemView.findViewById(R.id.tvMarkDetails);
			status = itemView.findViewById(R.id.tvMarkStatus);
			stripe = itemView.findViewById(R.id.viewMarkStripe);
		}

		void bind(@NonNull StudentLectureMark mark) {

			date.setText(mark.startDateTime.format(DAY));

			StringBuilder detail = new StringBuilder()
					.append(mark.startDateTime.format(TIME))
					.append(" – ")
					.append(mark.endDateTime.format(TIME))
					.append(" · ")
					.append(mark.isWholeClass()
							? itemView.getContext().getString(R.string.label_whole_class)
							: mark.batchName);

			details.setText(detail);

			int colour = ContextCompat.getColor(itemView.getContext(),
					mark.present ? R.color.attendance_ok : R.color.attendance_short);

			status.setText(mark.present ? R.string.label_present : R.string.label_absent);
			status.setTextColor(colour);
			stripe.setBackgroundColor(colour);
		}
	}
}
