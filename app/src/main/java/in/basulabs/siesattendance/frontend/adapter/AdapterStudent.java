package in.basulabs.siesattendance.frontend.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.pojo.StudentWithYear;

/**
 * The student list.
 * <p>
 * Item identity is the student and the session together, not the student alone. The same person
 * appears once per session they are enrolled in, and treating those as one row would make the
 * list flicker between two sets of details whenever both were on screen.
 */
public class AdapterStudent
		extends ListAdapter<StudentWithYear, AdapterStudent.Holder> {

	public interface OnClick {
		void onStudentClicked(StudentWithYear student);

		/** Optional: screens that only navigate, such as the dashboard, leave this empty. */
		default void onStudentLongPressed(StudentWithYear student) {
		}
	}

	private final OnClick listener;

	private static final DiffUtil.ItemCallback<StudentWithYear> DIFF =
			new DiffUtil.ItemCallback<>() {
				@Override
				public boolean areItemsTheSame(@NonNull StudentWithYear a,
				                               @NonNull StudentWithYear b) {
					return a.studentID == b.studentID && a.academicYear == b.academicYear;
				}

				@Override
				public boolean areContentsTheSame(@NonNull StudentWithYear a,
				                                  @NonNull StudentWithYear b) {
					return a.name.equals(b.name) && a.rollNo.equals(b.rollNo)
							&& a.majorDept == b.majorDept && a.year == b.year
							&& a.stream == b.stream;
				}
			};

	public AdapterStudent(OnClick listener) {
		super(DIFF);
		this.listener = listener;
	}

	@NonNull
	@Override
	public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		View view = LayoutInflater.from(parent.getContext())
				.inflate(R.layout.item_student, parent, false);
		return new Holder(view);
	}

	@Override
	public void onBindViewHolder(@NonNull Holder holder, int position) {
		holder.bind(getItem(position));
	}

	class Holder extends RecyclerView.ViewHolder {

		private final TextView initials, rollNo, name, info;

		Holder(@NonNull View itemView) {
			super(itemView);
			initials = itemView.findViewById(R.id.tvInitials);
			rollNo = itemView.findViewById(R.id.tvRollNo);
			name = itemView.findViewById(R.id.tvStudentName);
			info = itemView.findViewById(R.id.tvStudentInfo);
		}

		void bind(@NonNull StudentWithYear student) {
			initials.setText(student.initials());
			rollNo.setText(student.rollNo);
			name.setText(student.displayName());
			info.setText(student.classLabel());

			itemView.setOnClickListener(v -> {
				if (listener != null) {
					listener.onStudentClicked(student);
				}
			});

			itemView.setOnLongClickListener(v -> {
				if (listener != null) {
					listener.onStudentLongPressed(student);
					return true;
				}
				return false;
			});
		}
	}
}
