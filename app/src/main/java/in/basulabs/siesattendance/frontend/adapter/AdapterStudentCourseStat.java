package in.basulabs.siesattendance.frontend.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Locale;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.pojo.StudentCourseStat;

public class AdapterStudentCourseStat
		extends ListAdapter<StudentCourseStat, AdapterStudentCourseStat.Holder> {

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

	public AdapterStudentCourseStat() {
		super(DIFF);
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

	static class Holder extends RecyclerView.ViewHolder {

		private final TextView name, detail, percentage;

		private final ProgressBar bar;

		Holder(@NonNull View itemView) {
			super(itemView);
			name = itemView.findViewById(R.id.tvStatName);
			detail = itemView.findViewById(R.id.tvStatDetail);
			percentage = itemView.findViewById(R.id.tvStatPercentage);
			bar = itemView.findViewById(R.id.progressStat);
		}

		void bind(@NonNull StudentCourseStat stat) {

			name.setText(stat.courseCode + " · " + stat.courseTopic);
			detail.setText(itemView.getContext().getString(R.string.label_attended_of,
					stat.attended, stat.totalLectures));

			percentage.setText(String.format(Locale.getDefault(), "%.0f%%", stat.percentage()));
			bar.setProgress((int) Math.round(stat.percentage()));

			percentage.setTextColor(ContextCompat.getColor(itemView.getContext(),
					stat.isShort() ? R.color.stat_short : R.color.stat_ok));
		}
	}
}
