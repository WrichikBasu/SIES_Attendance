package in.basulabs.siesattendance.frontend.adapter;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.List;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.util.CourseGroup;

/**
 * The editable list of course groups in the export dialog.
 * <p>
 * Backed directly by the caller's list rather than a copy, so the dialog can read the groups
 * back without any transfer step. Groups exist only for the length of the dialog.
 */
public class AdapterCourseGroups extends RecyclerView.Adapter<AdapterCourseGroups.GroupHolder> {

	/** What the holder needs from the dialog. */
	public interface Callbacks {

		void onChooseCourses(int position);

		void onRemove(int position);
	}

	@NonNull
	private final List<CourseGroup> groups;

	@NonNull
	private final Callbacks callbacks;

	public AdapterCourseGroups(@NonNull List<CourseGroup> groups,
	                           @NonNull Callbacks callbacks) {
		this.groups = groups;
		this.callbacks = callbacks;
	}

	@NonNull
	@Override
	public GroupHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		return new GroupHolder(LayoutInflater.from(parent.getContext())
				.inflate(R.layout.item_course_group, parent, false));
	}

	@Override
	public void onBindViewHolder(@NonNull GroupHolder holder, int position) {
		holder.bind(groups.get(position));
	}

	@Override
	public int getItemCount() {
		return groups.size();
	}

	class GroupHolder extends RecyclerView.ViewHolder {

		private final TextInputEditText name;

		private final MaterialButton chooseCourses;

		private final MaterialButton remove;

		/**
		 * Held so it can be detached while the field is rebound. Without that, recycling a row
		 * fires the watcher and writes one group's name onto another.
		 */
		private TextWatcher watcher;

		GroupHolder(@NonNull View itemView) {
			super(itemView);

			name = itemView.findViewById(R.id.etGroupName);
			chooseCourses = itemView.findViewById(R.id.btnChooseCourses);
			remove = itemView.findViewById(R.id.btnRemoveGroup);

			chooseCourses.setOnClickListener(v -> {
				int position = getBindingAdapterPosition();
				if (position != RecyclerView.NO_POSITION) {
					callbacks.onChooseCourses(position);
				}
			});

			remove.setOnClickListener(v -> {
				int position = getBindingAdapterPosition();
				if (position != RecyclerView.NO_POSITION) {
					callbacks.onRemove(position);
				}
			});
		}

		void bind(@NonNull CourseGroup group) {

			if (watcher != null) {
				name.removeTextChangedListener(watcher);
			}

			name.setText(group.name);

			watcher = new TextWatcher() {
				@Override
				public void beforeTextChanged(CharSequence s, int a, int b, int c) {
				}

				@Override
				public void onTextChanged(CharSequence s, int a, int b, int c) {
				}

				@Override
				public void afterTextChanged(Editable s) {
					int position = getBindingAdapterPosition();
					if (position != RecyclerView.NO_POSITION) {
						groups.get(position).name = s.toString();
					}
				}
			};
			name.addTextChangedListener(watcher);

			chooseCourses.setText(group.courseCodes.isEmpty()
					? itemView.getContext().getString(R.string.action_choose_courses)
					: group.describeCourses());
		}
	}
}
