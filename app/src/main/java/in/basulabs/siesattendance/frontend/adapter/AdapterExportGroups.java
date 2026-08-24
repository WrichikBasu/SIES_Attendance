package in.basulabs.siesattendance.frontend.adapter;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;

import java.util.List;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.EntityCourse;
import in.basulabs.siesattendance.backend.util.CourseGroup;

/**
 * The groups section of the export screen.
 * <p>
 * Each group is a card with a name and a row of chips, one per course currently in scope.
 * Drawing the chips from the courses in scope rather than from the whole register means a group
 * can only ever contain courses the export actually covers, so a group cannot quietly total
 * something that is not in the file.
 */
public class AdapterExportGroups extends RecyclerView.Adapter<AdapterExportGroups.GroupHolder> {

	public interface Callbacks {

		/** The group's remove button was pressed. The fragment owns the list, so it does the removal. */
		void onRemove(int position);

		/** A course was ticked or unticked, so any summary of the groups needs refreshing. */
		void onChanged();
	}

	/** The fragment's own list, mutated in place as the user edits names and ticks chips. */
	@NonNull
	private final List<CourseGroup> groups;

	@NonNull
	private final Callbacks callbacks;

	/** Courses currently in scope, which become the chips. */
	@NonNull
	private List<EntityCourse> inScope;

	public AdapterExportGroups(@NonNull List<CourseGroup> groups,
	                           @NonNull List<EntityCourse> inScope,
	                           @NonNull Callbacks callbacks) {
		this.groups = groups;
		this.inScope = inScope;
		this.callbacks = callbacks;
	}

	/**
	 * Called when the course selection changes.
	 * <p>
	 * Codes that have left the scope are dropped from every group, because a group holding a
	 * course the export no longer covers would silently contribute nothing to its own total.
	 */
	public void setInScope(@NonNull List<EntityCourse> courses) {

		inScope = courses;

		for (CourseGroup group : groups) {
			group.courseCodes.removeIf(code -> {
				for (EntityCourse course : courses) {
					if (course.courseCode.equals(code)) {
						return false;
					}
				}
				return true;
			});
		}
		notifyDataSetChanged();
	}

	@NonNull
	@Override
	public GroupHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		return new GroupHolder(LayoutInflater.from(parent.getContext())
				.inflate(R.layout.item_export_group, parent, false));
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

		private final ChipGroup chips;

		private final TextView hint;

		/** Detached while rebinding, or recycling a row writes one group's name onto another. */
		private TextWatcher watcher;

		GroupHolder(@NonNull View itemView) {
			super(itemView);

			name = itemView.findViewById(R.id.etGroupName);
			chips = itemView.findViewById(R.id.cgGroupCourses);
			hint = itemView.findViewById(R.id.tvGroupHint);

			MaterialButton remove = itemView.findViewById(R.id.btnRemoveGroup);

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

			hint.setText(inScope.isEmpty()
					? itemView.getContext().getString(R.string.hint_group_no_courses)
					: itemView.getContext().getString(R.string.hint_group_pick_courses));

			chips.removeAllViews();

			for (EntityCourse course : inScope) {

				Chip chip = new Chip(itemView.getContext());
				chip.setText(course.courseCode);
				chip.setCheckable(true);
				chip.setChecked(group.courseCodes.contains(course.courseCode));

				chip.setOnCheckedChangeListener((view, isChecked) -> {
					int position = getBindingAdapterPosition();

					if (position == RecyclerView.NO_POSITION) {
						return;
					}

					CourseGroup target = groups.get(position);

					if (isChecked) {
						target.courseCodes.add(course.courseCode);
					} else {
						target.courseCodes.remove(course.courseCode);
					}
					callbacks.onChanged();
				});

				chips.addView(chip);
			}
		}
	}
}
