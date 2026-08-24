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
import in.basulabs.siesattendance.backend.EntityAcademicYear;

/**
 * The academic sessions list.
 * <p>
 * Content equality ignores the epoch bounds, since those are derived from the dates and cannot
 * differ without them differing too. Comparing them as well would be harmless but misleading
 * about where the truth lies.
 */
public class AdapterAcademicYear
		extends ListAdapter<EntityAcademicYear, AdapterAcademicYear.Holder> {

	/**
	 * Tapping a row opens it for editing; holding it offers to delete. The same split is used by
	 * the other list adapters in this package.
	 */
	public interface OnAction {
		void onEdit(EntityAcademicYear year);

		void onLongPress(EntityAcademicYear year);
	}

	private static final DateTimeFormatter DATE_LABEL =
			DateTimeFormatter.ofPattern("d MMM yyyy");

	private final OnAction listener;

	/**
	 * The session the app is currently working in, which gets a chip. Sentinel rather than a
	 * boxed Integer, because no real session will ever carry this value.
	 */
	private int currentYear = Integer.MIN_VALUE;

	private static final DiffUtil.ItemCallback<EntityAcademicYear> DIFF =
			new DiffUtil.ItemCallback<EntityAcademicYear>() {
				@Override
				public boolean areItemsTheSame(@NonNull EntityAcademicYear a,
				                               @NonNull EntityAcademicYear b) {
					return a.academicYear == b.academicYear;
				}

				@Override
				public boolean areContentsTheSame(@NonNull EntityAcademicYear a,
				                                  @NonNull EntityAcademicYear b) {
					return a.startDate.equals(b.startDate) && a.endDate.equals(b.endDate);
				}
			};

	public AdapterAcademicYear(OnAction listener) {
		super(DIFF);
		this.listener = listener;
	}

	/** Marks which session is the live one, so the list says so rather than leaving it implied. */
	public void setCurrent(int academicYear) {
		if (currentYear != academicYear) {
			currentYear = academicYear;
			notifyDataSetChanged();
		}
	}

	@NonNull
	@Override
	public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		return new Holder(LayoutInflater.from(parent.getContext())
				.inflate(R.layout.item_academic_year, parent, false));
	}

	@Override
	public void onBindViewHolder(@NonNull Holder holder, int position) {
		holder.bind(getItem(position));
	}

	class Holder extends RecyclerView.ViewHolder {

		private final TextView label, dates;

		private final Chip chipCurrent;

		Holder(@NonNull View itemView) {
			super(itemView);
			label = itemView.findViewById(R.id.tvYearLabel);
			dates = itemView.findViewById(R.id.tvYearDates);
			chipCurrent = itemView.findViewById(R.id.chipCurrent);
		}

		void bind(@NonNull EntityAcademicYear year) {

			label.setText(year.label());
			dates.setText(year.startDate.format(DATE_LABEL) + " – "
					+ year.endDate.format(DATE_LABEL));

			chipCurrent.setVisibility(year.academicYear == currentYear
					? View.VISIBLE : View.GONE);

			itemView.setOnClickListener(v -> {
				if (listener != null) {
					listener.onEdit(year);
				}
			});

			itemView.setOnLongClickListener(v -> {
				if (listener != null) {
					listener.onLongPress(year);
					return true;
				}
				return false;
			});
		}
	}
}
