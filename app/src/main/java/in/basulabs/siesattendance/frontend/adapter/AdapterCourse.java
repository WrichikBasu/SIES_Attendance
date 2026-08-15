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
import in.basulabs.siesattendance.backend.EntityCourse;

public class AdapterCourse extends ListAdapter<EntityCourse, AdapterCourse.Holder> {

	public interface OnAction {
		void onEdit(EntityCourse course);

		void onLongPress(EntityCourse course);
	}

	private final OnAction listener;

	private static final DiffUtil.ItemCallback<EntityCourse> DIFF =
			new DiffUtil.ItemCallback<EntityCourse>() {
				@Override
				public boolean areItemsTheSame(@NonNull EntityCourse a, @NonNull EntityCourse b) {
					return a.courseID == b.courseID;
				}

				@Override
				public boolean areContentsTheSame(@NonNull EntityCourse a,
				                                  @NonNull EntityCourse b) {
					return a.courseCode.equals(b.courseCode)
							&& a.courseTopic.equals(b.courseTopic)
							&& a.semester == b.semester;
				}
			};

	public AdapterCourse(OnAction listener) {
		super(DIFF);
		this.listener = listener;
	}

	@NonNull
	@Override
	public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		return new Holder(LayoutInflater.from(parent.getContext())
				.inflate(R.layout.item_course, parent, false));
	}

	@Override
	public void onBindViewHolder(@NonNull Holder holder, int position) {
		holder.bind(getItem(position));
	}

	class Holder extends RecyclerView.ViewHolder {

		private final TextView title, details;

		Holder(@NonNull View itemView) {
			super(itemView);
			title = itemView.findViewById(R.id.tvCourseTitle);
			details = itemView.findViewById(R.id.tvCourseDetails);
		}

		void bind(@NonNull EntityCourse course) {
			title.setText(course.courseCode + " · " + course.courseTopic);
			details.setText(course.offeringDepartment.name() + " · " + course.stream.name()
					+ " · " + course.year.name() + " · Sem " + course.semester
					+ " · " + course.courseType.name());

			itemView.setOnClickListener(v -> {
				if (listener != null) {
					listener.onEdit(course);
				}
			});

			itemView.setOnLongClickListener(v -> {
				if (listener != null) {
					listener.onLongPress(course);
					return true;
				}
				return false;
			});
		}
	}
}
