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
import in.basulabs.siesattendance.backend.pojo.StudentAttendanceStat;

public class AdapterStudentStat
		extends ListAdapter<StudentAttendanceStat, AdapterStudentStat.Holder> {

	private static final DiffUtil.ItemCallback<StudentAttendanceStat> DIFF =
			new DiffUtil.ItemCallback<StudentAttendanceStat>() {
				@Override
				public boolean areItemsTheSame(@NonNull StudentAttendanceStat a,
				                               @NonNull StudentAttendanceStat b) {
					return a.studentID == b.studentID;
				}

				@Override
				public boolean areContentsTheSame(@NonNull StudentAttendanceStat a,
				                                  @NonNull StudentAttendanceStat b) {
					return a.attended == b.attended && a.totalLectures == b.totalLectures;
				}
			};

	public AdapterStudentStat() {
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

		void bind(@NonNull StudentAttendanceStat stat) {

			name.setText(stat.name);
			detail.setText(itemView.getContext().getString(R.string.stat_attended,
					stat.rollNo, stat.attended, stat.totalLectures));

			percentage.setText(String.format(Locale.getDefault(), "%.0f%%",
					stat.percentage()));
			bar.setProgress((int) Math.round(stat.percentage()));

			// Anyone below the 75% threshold is tinted so a shortfall is visible at a glance.
			int colour = ContextCompat.getColor(itemView.getContext(),
					stat.isShort() ? R.color.stat_short : R.color.stat_ok);
			percentage.setTextColor(colour);
		}
	}
}
