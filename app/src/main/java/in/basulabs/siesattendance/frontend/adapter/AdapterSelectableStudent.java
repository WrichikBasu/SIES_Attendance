package in.basulabs.siesattendance.frontend.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.checkbox.MaterialCheckBox;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.pojo.StudentWithYear;

/**
 * A checkable student list, used for editing batch membership.
 * <p>
 * The selection lives with the caller rather than in the adapter, because the visible list is
 * filtered by a search box while the selection has to span everyone.
 */
public class AdapterSelectableStudent
		extends RecyclerView.Adapter<AdapterSelectableStudent.Holder> {

	public interface OnToggle {
		void onToggled(long studentID, boolean selected);
	}

	private final OnToggle listener;

	private final List<StudentWithYear> visible = new ArrayList<>();

	private final Set<Long> selected = new HashSet<>();

	public AdapterSelectableStudent(OnToggle listener) {
		this.listener = listener;
	}

	public void submit(@NonNull List<StudentWithYear> students, @NonNull Set<Long> selectedIDs) {
		visible.clear();
		visible.addAll(students);
		selected.clear();
		selected.addAll(selectedIDs);
		notifyDataSetChanged();
	}

	@NonNull
	@Override
	public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		return new Holder(LayoutInflater.from(parent.getContext())
				.inflate(R.layout.item_student_selectable, parent, false));
	}

	@Override
	public void onBindViewHolder(@NonNull Holder holder, int position) {
		holder.bind(visible.get(position));
	}

	@Override
	public int getItemCount() {
		return visible.size();
	}

	class Holder extends RecyclerView.ViewHolder {

		private final MaterialCheckBox checkBox;

		private final TextView rollNo, name, classLabel;

		Holder(@NonNull View itemView) {
			super(itemView);
			checkBox = itemView.findViewById(R.id.cbSelected);
			rollNo = itemView.findViewById(R.id.tvRollNo);
			name = itemView.findViewById(R.id.tvStudentName);
			classLabel = itemView.findViewById(R.id.tvClassLabel);

			// The whole row toggles, which is a much larger target than the checkbox itself.
			itemView.setOnClickListener(v -> toggle());
			checkBox.setOnClickListener(v -> toggle());
		}

		private void toggle() {
			int position = getBindingAdapterPosition();
			if (position == RecyclerView.NO_POSITION) {
				return;
			}

			long studentID = visible.get(position).studentID;
			boolean nowSelected = !selected.contains(studentID);

			if (nowSelected) {
				selected.add(studentID);
			} else {
				selected.remove(studentID);
			}

			checkBox.setChecked(nowSelected);

			if (listener != null) {
				listener.onToggled(studentID, nowSelected);
			}
		}

		void bind(@NonNull StudentWithYear student) {
			rollNo.setText(student.rollNo);
			name.setText(student.displayName());
			classLabel.setText(student.classLabel());
			checkBox.setChecked(selected.contains(student.studentID));
		}
	}
}
