package in.basulabs.siesattendance.frontend.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.checkbox.MaterialCheckBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.EntityCourse;

/**
 * The course picker on the export screen.
 * <p>
 * Every course in the register is offered, whatever department or stream it belongs to, because
 * a report often crosses both: a year tutor wants one stream's courses regardless of who
 * teaches them, and a head of department wants their own department's across every stream. The
 * row therefore spells out department, stream and year, since a code like CS301 says none of
 * that on its own.
 * <p>
 * Selection is held in the caller's set so the fragment can read it without a transfer step,
 * and search filters what is shown without disturbing what is selected. That distinction
 * matters: narrowing the search must not silently drop courses already ticked.
 */
public class AdapterExportCourses
		extends RecyclerView.Adapter<AdapterExportCourses.CourseHolder> {

	/** Every course offered, regardless of the current search. */
	@NonNull
	private final List<EntityCourse> all = new ArrayList<>();

	/** What the search currently leaves on screen. A subset of {@link #all}, in the same order. */
	@NonNull
	private final List<EntityCourse> visible = new ArrayList<>();

	/**
	 * The caller's set, mutated in place. Deliberately not a copy: the fragment reads it back
	 * directly, and a selection that survives a search has to outlive {@link #visible}.
	 */
	@NonNull
	private final Set<Long> selected;

	/** Fired after any change to {@link #selected}, so the fragment can update its count. */
	@NonNull
	private final Runnable onSelectionChanged;

	public AdapterExportCourses(@NonNull Set<Long> selected,
	                            @NonNull Runnable onSelectionChanged) {
		this.selected = selected;
		this.onSelectionChanged = onSelectionChanged;
	}

	/** Replaces the course list and clears any active search. */
	public void submit(@NonNull List<EntityCourse> courses) {
		all.clear();
		all.addAll(courses);
		visible.clear();
		visible.addAll(courses);
		notifyDataSetChanged();
	}

	/** Matches code, topic, department, stream and year, so any of them can be searched for. */
	public void filter(@NonNull String queryText) {

		String needle = queryText.trim().toLowerCase(Locale.getDefault());

		visible.clear();

		for (EntityCourse course : all) {
			if (needle.isEmpty() || haystack(course).contains(needle)) {
				visible.add(course);
			}
		}
		notifyDataSetChanged();
	}

	/** Everything about a course that search should match, lowercased and run together. */
	@NonNull
	private static String haystack(@NonNull EntityCourse course) {
		return (course.courseCode + " " + course.courseTopic + " "
				+ course.offeringDepartment + " " + course.stream + " "
				+ course.year + " " + course.courseType).toLowerCase(Locale.getDefault());
	}

	/** Ticks or clears everything currently shown, which is what makes search plus select-all useful. */
	public void setAllVisible(boolean chosen) {

		for (EntityCourse course : visible) {
			if (chosen) {
				selected.add(course.courseID);
			} else {
				selected.remove(course.courseID);
			}
		}
		notifyDataSetChanged();
		onSelectionChanged.run();
	}

	/** How many rows the search currently leaves, which is what the select-all label reports. */
	public int visibleCount() {
		return visible.size();
	}

	@NonNull
	@Override
	public CourseHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		return new CourseHolder(LayoutInflater.from(parent.getContext())
				.inflate(R.layout.item_export_course, parent, false));
	}

	@Override
	public void onBindViewHolder(@NonNull CourseHolder holder, int position) {
		holder.bind(visible.get(position));
	}

	@Override
	public int getItemCount() {
		return visible.size();
	}

	class CourseHolder extends RecyclerView.ViewHolder {

		private final MaterialCheckBox checkBox;

		private final TextView title;

		private final TextView meta;

		CourseHolder(@NonNull View itemView) {
			super(itemView);

			checkBox = itemView.findViewById(R.id.cbCourse);
			title = itemView.findViewById(R.id.tvCourseTitle);
			meta = itemView.findViewById(R.id.tvCourseMeta);

			// The whole row toggles, rather than only the box: a checkbox is a small target
			// in a long list.
			itemView.setOnClickListener(v -> {
				int position = getBindingAdapterPosition();

				if (position == RecyclerView.NO_POSITION) {
					return;
				}

				long courseID = visible.get(position).courseID;

				if (!selected.remove(courseID)) {
					selected.add(courseID);
				}
				notifyItemChanged(position);
				onSelectionChanged.run();
			});
		}

		void bind(@NonNull EntityCourse course) {
			title.setText(course.courseCode + " \u2013 " + course.courseTopic);
			meta.setText(course.offeringDepartment + " \u00b7 " + course.stream
					+ " \u00b7 " + course.year + " \u00b7 " + course.courseType);
			checkBox.setChecked(selected.contains(course.courseID));
		}
	}
}
