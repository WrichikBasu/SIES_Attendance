package in.basulabs.siesattendance.frontend.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.pojo.AttendanceRow;

/**
 * Backs the mark-attendance list.
 * <p>
 * Rows are mutated in place as the user taps, so this keeps its own copy of the list rather
 * than sharing the ViewModel's instances — otherwise DiffUtil would compare an object against
 * itself and conclude nothing had changed.
 */
public class AdapterAttendance extends RecyclerView.Adapter<AdapterAttendance.Holder> {

	public interface OnToggle {
		void onToggled(long studentID, boolean present);
	}

	/** Marks a rebind that only needs to refresh the two toggle buttons. */
	private static final Object PAYLOAD_PRESENCE = new Object();

	private final OnToggle listener;

	private final List<AttendanceRow> rows = new ArrayList<>();

	public AdapterAttendance(OnToggle listener) {
		this.listener = listener;
	}

	/**
	 * Diffs against the current contents and dispatches only the rows that actually changed.
	 * A bulk "all present" therefore updates the visible rows rather than rebuilding the list.
	 */
	public void submitRows(@Nullable List<AttendanceRow> newRows) {

		final List<AttendanceRow> incoming = new ArrayList<>();
		if (newRows != null) {
			for (AttendanceRow row : newRows) {
				incoming.add(new AttendanceRow(row.studentID, row.name, row.rollNo, row.present));
			}
		}

		DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {

			@Override
			public int getOldListSize() {
				return rows.size();
			}

			@Override
			public int getNewListSize() {
				return incoming.size();
			}

			@Override
			public boolean areItemsTheSame(int oldPos, int newPos) {
				return rows.get(oldPos).studentID == incoming.get(newPos).studentID;
			}

			@Override
			public boolean areContentsTheSame(int oldPos, int newPos) {
				AttendanceRow a = rows.get(oldPos);
				AttendanceRow b = incoming.get(newPos);
				return Objects.equals(a.present, b.present)
						&& a.name.equals(b.name)
						&& a.rollNo.equals(b.rollNo);
			}

			@Nullable
			@Override
			public Object getChangePayload(int oldPos, int newPos) {
				AttendanceRow a = rows.get(oldPos);
				AttendanceRow b = incoming.get(newPos);
				// Name and roll number never change mid-sheet, so a difference here is always
				// the presence toggle and only the buttons need rebinding.
				return a.name.equals(b.name) && a.rollNo.equals(b.rollNo)
						? PAYLOAD_PRESENCE
						: null;
			}
		});

		rows.clear();
		rows.addAll(incoming);
		result.dispatchUpdatesTo(this);
	}

	@NonNull
	@Override
	public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		return new Holder(LayoutInflater.from(parent.getContext())
				.inflate(R.layout.item_attendance, parent, false));
	}

	@Override
	public void onBindViewHolder(@NonNull Holder holder, int position) {
		holder.bind(rows.get(position));
	}

	@Override
	public void onBindViewHolder(@NonNull Holder holder, int position,
	                             @NonNull List<Object> payloads) {
		if (payloads.contains(PAYLOAD_PRESENCE)) {
			holder.bindPresence(rows.get(position));
		} else {
			super.onBindViewHolder(holder, position, payloads);
		}
	}

	@Override
	public int getItemCount() {
		return rows.size();
	}

	class Holder extends RecyclerView.ViewHolder {

		private final TextView initials, name, rollNo;

		private final MaterialButton present, absent;

		Holder(@NonNull View itemView) {
			super(itemView);
			initials = itemView.findViewById(R.id.tvInitials);
			name = itemView.findViewById(R.id.tvStudentName);
			rollNo = itemView.findViewById(R.id.tvRollNo);
			present = itemView.findViewById(R.id.btnPresent);
			absent = itemView.findViewById(R.id.btnAbsent);

			present.setOnClickListener(v -> toggle(true));
			absent.setOnClickListener(v -> toggle(false));
		}

		/**
		 * Listeners are attached once in the constructor and read the position at click time,
		 * so a recycled holder never fires against whichever student used to occupy it.
		 */
		private void toggle(boolean isPresent) {
			int position = getBindingAdapterPosition();
			if (position == RecyclerView.NO_POSITION) {
				return;
			}

			AttendanceRow row = rows.get(position);
			if (Boolean.valueOf(isPresent).equals(row.present)) {
				return;
			}

			row.present = isPresent;
			bindPresence(row);

			if (listener != null) {
				listener.onToggled(row.studentID, isPresent);
			}
		}

		void bind(@NonNull AttendanceRow row) {
			initials.setText(row.initials());
			rollNo.setText(row.rollNo);
			name.setText(row.displayName());
			bindPresence(row);
		}

		void bindPresence(@NonNull AttendanceRow row) {
			present.setChecked(Boolean.TRUE.equals(row.present));
			absent.setChecked(Boolean.FALSE.equals(row.present));
		}
	}
}
