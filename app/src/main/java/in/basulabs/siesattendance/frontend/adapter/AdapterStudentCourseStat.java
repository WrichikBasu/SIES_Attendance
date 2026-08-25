package in.basulabs.siesattendance.frontend.adapter;

import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Locale;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.pojo.StudentCourseStat;

/**
 * One student's attendance broken down by course, on the student detail screen. Shares its row
 * layout with {@link AdapterStudentStat}, since both show a label, a fraction and a bar.
 * <p>
 * A row opens the dates behind its fraction, which is the only place in the app that says which
 * lectures a student actually missed.
 */
public class AdapterStudentCourseStat
		extends ListAdapter<StudentCourseStat, AdapterStudentCourseStat.Holder> {

	public interface OnClick {
		void onCourseClicked(@NonNull StudentCourseStat stat);
	}

	@Nullable
	private final OnClick listener;

	private static final DiffUtil.ItemCallback<StudentCourseStat> DIFF =
			new DiffUtil.ItemCallback<StudentCourseStat>() {
				@Override
				public boolean areItemsTheSame(@NonNull StudentCourseStat a,
				                               @NonNull StudentCourseStat b) {
					return a.courseID == b.courseID;
				}

				@Override
				public boolean areContentsTheSame(@NonNull StudentCourseStat a,
				                                  @NonNull StudentCourseStat b) {
					return a.attended == b.attended && a.totalLectures == b.totalLectures;
				}
			};

	public AdapterStudentCourseStat(@Nullable OnClick listener) {
		super(DIFF);
		this.listener = listener;
	}

	@NonNull
	@Override
	public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		return new Holder(LayoutInflater.from(parent.getContext())
				.inflate(R.layout.item_stat_student, parent, false));
	}

	@Override
	public void onBindViewHolder(@NonNull Holder holder, int position) {
		holder.bind(getItem(position));
	}

	class Holder extends RecyclerView.ViewHolder {

		private final TextView name, detail, percentage;

		private final ProgressBar bar;

		Holder(@NonNull View itemView) {
			super(itemView);
			name = itemView.findViewById(R.id.tvStatName);
			detail = itemView.findViewById(R.id.tvStatDetail);
			percentage = itemView.findViewById(R.id.tvStatPercentage);
			bar = itemView.findViewById(R.id.progressStat);

			// The row layout is shared with AdapterStudentStat, whose rows go nowhere, so the
			// ripple is attached here rather than in the XML. A row that lights up under a
			// finger and then does nothing is worse than one that never responds at all.
			if (listener != null) {
				TypedValue background = new TypedValue();
				itemView.getContext().getTheme().resolveAttribute(
						android.R.attr.selectableItemBackground, background, true);
				itemView.setBackgroundResource(background.resourceId);
			}
		}

		void bind(@NonNull StudentCourseStat stat) {

			name.setText(stat.courseCode + " · " + stat.courseTopic);
			detail.setText(itemView.getContext().getString(R.string.label_attended_of,
					stat.attended, stat.totalLectures));

			percentage.setText(String.format(Locale.getDefault(), "%.0f%%", stat.percentage()));
			bar.setProgress((int) Math.round(stat.percentage()));

			percentage.setTextColor(ContextCompat.getColor(itemView.getContext(),
					stat.isShort() ? R.color.attendance_short : R.color.attendance_ok));

			itemView.setOnClickListener(listener == null
					? null
					: v -> listener.onCourseClicked(stat));
			itemView.setClickable(listener != null);
		}
	}
}
