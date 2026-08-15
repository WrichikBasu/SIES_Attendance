package in.basulabs.siesattendance.frontend.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Locale;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.pojo.CourseAttendanceStat;

public class AdapterCourseStat
		extends ListAdapter<CourseAttendanceStat, AdapterCourseStat.Holder> {

	private static final DiffUtil.ItemCallback<CourseAttendanceStat> DIFF =
			new DiffUtil.ItemCallback<CourseAttendanceStat>() {
				@Override
				public boolean areItemsTheSame(@NonNull CourseAttendanceStat a,
				                               @NonNull CourseAttendanceStat b) {
					return a.courseID == b.courseID;
				}

				@Override
				public boolean areContentsTheSame(@NonNull CourseAttendanceStat a,
				                                  @NonNull CourseAttendanceStat b) {
					return a.presentRecords == b.presentRecords
							&& a.totalRecords == b.totalRecords
							&& a.lectureCount == b.lectureCount;
				}
			};

	public AdapterCourseStat() {
		super(DIFF);
	}

	@NonNull
	@Override
	public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		return new Holder(LayoutInflater.from(parent.getContext())
				.inflate(R.layout.item_stat_course, parent, false));
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

		void bind(@NonNull CourseAttendanceStat stat) {

			name.setText(stat.courseCode + " · " + stat.courseTopic);
			detail.setText(itemView.getContext().getString(R.string.stat_course_detail,
					stat.lectureCount, stat.presentRecords, stat.totalRecords));

			percentage.setText(stat.totalRecords == 0
					? "—"
					: String.format(Locale.getDefault(), "%.0f%%", stat.percentage()));
			bar.setProgress((int) Math.round(stat.percentage()));
		}
	}
}
