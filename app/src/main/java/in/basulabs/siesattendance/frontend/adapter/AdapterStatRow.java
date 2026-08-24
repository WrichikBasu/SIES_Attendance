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
import in.basulabs.siesattendance.backend.pojo.StatRow;
import in.basulabs.siesattendance.backend.stats.StatsGroupBy;

/**
 * Renders one row of a grouped report. The same layout serves every grouping, which is what
 * lets the report screen swap dimensions without swapping adapters.
 */
public class AdapterStatRow extends ListAdapter<StatRow, AdapterStatRow.Holder> {

	public interface OnRowClick {
		void onRowClicked(StatRow row);
	}

	private final OnRowClick listener;

	/**
	 * The grouping the current rows came from. Needed at bind time to decide which label holds a
	 * name, which counts are worth showing, and whether a row can be tapped.
	 */
	private StatsGroupBy groupBy = StatsGroupBy.STUDENT;

	/** The line below which a percentage is tinted as short. Set from the filter. */
	private double threshold = 75d;

	private static final DiffUtil.ItemCallback<StatRow> DIFF =
			new DiffUtil.ItemCallback<StatRow>() {
				@Override
				public boolean areItemsTheSame(@NonNull StatRow a, @NonNull StatRow b) {
					// Groupings without a real key report id 0, so the label identifies the row.
					return a.rowId == b.rowId && a.primaryLabel.equals(b.primaryLabel);
				}

				@Override
				public boolean areContentsTheSame(@NonNull StatRow a, @NonNull StatRow b) {
					return a.attended == b.attended && a.total == b.total
							&& a.lectures == b.lectures && a.students == b.students
							&& a.secondaryLabel.equals(b.secondaryLabel);
				}
			};

	public AdapterStatRow(OnRowClick listener) {
		super(DIFF);
		this.listener = listener;
	}

	/**
	 * The grouping decides whether the primary label is a person's name, which is the only
	 * label that needs case correction.
	 */
	public void setContext(@NonNull StatsGroupBy groupBy, double threshold) {
		this.groupBy = groupBy;
		this.threshold = threshold;
		notifyDataSetChanged();
	}

	@NonNull
	@Override
	public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		return new Holder(LayoutInflater.from(parent.getContext())
				.inflate(R.layout.item_stat_row, parent, false));
	}

	@Override
	public void onBindViewHolder(@NonNull Holder holder, int position) {
		holder.bind(getItem(position));
	}

	class Holder extends RecyclerView.ViewHolder {

		private final TextView primary, secondary, percent, detail;

		private final ProgressBar bar;

		Holder(@NonNull View itemView) {
			super(itemView);
			primary = itemView.findViewById(R.id.tvPrimary);
			secondary = itemView.findViewById(R.id.tvSecondary);
			percent = itemView.findViewById(R.id.tvPercent);
			detail = itemView.findViewById(R.id.tvDetail);
			bar = itemView.findViewById(R.id.progressRow);
		}

		void bind(@NonNull StatRow row) {

			primary.setText(row.displayPrimary(groupBy.nameField));

			String sub = row.displaySecondary(groupBy.nameField);
			secondary.setText(sub);
			secondary.setVisibility(sub == null ? View.GONE : View.VISIBLE);

			percent.setText(row.total == 0
					? "—"
					: String.format(Locale.getDefault(), "%.0f%%", row.percentage()));

			// Which counts are worth showing depends on the grouping: a per-student row already
			// has one student in it, and a per-lecture row already has one lecture.
			StringBuilder details = new StringBuilder();
			details.append(itemView.getContext().getString(
					R.string.stat_present_of, row.attended, row.total));

			if (groupBy != StatsGroupBy.LECTURE) {
				details.append(" · ").append(itemView.getContext().getResources()
						.getQuantityString(R.plurals.lecture_count, row.lectures, row.lectures));
			}

			if (groupBy != StatsGroupBy.STUDENT && row.students > 1) {
				details.append(" · ").append(itemView.getContext().getResources()
						.getQuantityString(R.plurals.student_count, row.students, row.students));
			}

			detail.setText(details.toString());

			bar.setProgress((int) Math.round(row.percentage()));

			int colour = ContextCompat.getColor(itemView.getContext(),
					row.isBelow(threshold) ? R.color.attendance_short : R.color.attendance_ok);
			percent.setTextColor(colour);

			itemView.setClickable(groupBy.isDrillable());
			itemView.setOnClickListener(groupBy.isDrillable()
					? v -> {
				if (listener != null) {
					listener.onRowClicked(row);
				}
			}
					: null);
		}
	}
}
