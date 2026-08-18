package in.basulabs.siesattendance.frontend.util;

import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import in.basulabs.siesattendance.backend.AcademicRules;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;

/**
 * Fills exposed dropdowns from enums and keeps the department, stream and year fields
 * consistent with each other.
 * <p>
 * Every dropdown in the app goes through here so the rules in {@link AcademicRules} cannot be
 * enforced on one screen and forgotten on another, and so that every label comes from
 * {@code toString()} rather than the stored constant name.
 */
public final class DropdownBinder {

	/**
	 * Called when a selection changes. The value is null when the optional "any" entry is
	 * chosen.
	 */
	public interface OnSelected<T> {
		void onSelected(@Nullable T value);
	}

	private DropdownBinder() {
	}

	/**
	 * Populates a dropdown with enum constants, labelled by {@code toString()}.
	 *
	 * @param anyLabel when non-null, prepended as an entry that selects null
	 */
	public static <E extends Enum<E>> void bind(@NonNull AutoCompleteTextView field,
	                                            @NonNull List<E> values,
	                                            @Nullable String anyLabel,
	                                            @Nullable E selected,
	                                            @NonNull OnSelected<E> callback) {

		Context context = field.getContext();
		List<String> labels = new ArrayList<>();

		if (anyLabel != null) {
			labels.add(anyLabel);
		}
		for (E value : values) {
			labels.add(value.toString());
		}

		field.setAdapter(new ArrayAdapter<>(context,
				android.R.layout.simple_list_item_1, labels));

		setSelection(field, selected, anyLabel);

		final int offset = anyLabel == null ? 0 : 1;

		field.setOnItemClickListener((parent, view, position, id) ->
				callback.onSelected(position < offset ? null : values.get(position - offset)));
	}

	/**
	 * Shows a value without firing the selection callback. Used when one field changes another.
	 */
	public static <E extends Enum<E>> void setSelection(@NonNull AutoCompleteTextView field,
	                                                    @Nullable E value,
	                                                    @Nullable String anyLabel) {
		if (value == null) {
			field.setText(anyLabel == null ? "" : anyLabel, false);
		} else {
			field.setText(value.toString(), false);
		}
	}

	/**
	 * Ties a department field, a stream field and optionally a year field together, in both
	 * directions.
	 * <p>
	 * Choosing a department narrows the stream list to what that department offers and selects
	 * it outright when there is only one, so Commerce never asks whether the student is doing a
	 * BSc. Choosing a stream narrows the department list the same way, so the fields can be
	 * filled in whichever order suits the user. The year list follows both: a department with no
	 * postgraduate course drops the master's years, and a postgraduate stream drops the
	 * undergraduate ones.
	 *
	 * @param yearField may be null on screens with no year field
	 * @param onChanged receives the reconciled department, stream and year after any change
	 */
	public static void linkDepartmentAndStream(@NonNull AutoCompleteTextView departmentField,
	                                           @NonNull AutoCompleteTextView streamField,
	                                           @Nullable AutoCompleteTextView yearField,
	                                           @NonNull List<Department> departments,
	                                           @Nullable String anyLabel,
	                                           @Nullable Department department,
	                                           @Nullable Stream stream,
	                                           @Nullable Year year,
	                                           @NonNull Linked onChanged) {

		final Holder state = new Holder(department, stream, year);

		// Every field rebuilds all three lists, because a change to any one of them can alter
		// what the other two may contain.
		Runnable rebuild = () -> rebuildAll(departmentField, streamField, yearField,
				departments, anyLabel, state);

		departmentField.setOnItemClickListener((parent, view, position, id) -> {

			List<Department> shown = visibleDepartments(departments, state.stream);
			int offset = anyLabel == null ? 0 : 1;
			state.department = position < offset ? null : shown.get(position - offset);

			// The stream already chosen may not exist for this department, so it is kept,
			// replaced by the sole option, or reduced to the equivalent level.
			state.stream = AcademicRules.reconcile(state.department, state.stream);
			state.year = reconcileYear(state);

			rebuild.run();
			onChanged.onChanged(state.department, state.stream, state.year);
		});

		streamField.setOnItemClickListener((parent, view, position, id) -> {

			List<Stream> allowed = AcademicRules.streamsFor(state.department);
			int offset = anyLabel == null ? 0 : 1;
			state.stream = position < offset ? null : allowed.get(position - offset);

			// The mirror of the above: a department that does not offer this stream is
			// cleared, or replaced when the stream leaves only one possibility.
			state.department =
					AcademicRules.reconcileDepartment(state.stream, state.department);
			state.year = reconcileYear(state);

			rebuild.run();
			onChanged.onChanged(state.department, state.stream, state.year);
		});

		if (yearField != null) {
			yearField.setOnItemClickListener((parent, view, position, id) -> {
				List<Year> allowed =
						AcademicRules.yearsFor(state.department, state.stream);
				int offset = anyLabel == null ? 0 : 1;
				state.year = position < offset ? null : allowed.get(position - offset);
				onChanged.onChanged(state.department, state.stream, state.year);
			});
		}

		rebuild.run();
	}

	/**
	 * Drops a year that the current department and stream no longer permit, so switching from
	 * an MSc to a BCom does not leave "Masters Part 1" showing.
	 */
	@Nullable
	private static Year reconcileYear(@NonNull Holder state) {
		if (state.year == null) {
			return null;
		}
		return AcademicRules.yearsFor(state.department, state.stream).contains(state.year)
				? state.year
				: null;
	}

	/**
	 * The departments on offer, narrowed by the chosen stream and intersected with whatever the
	 * caller supplied.
	 */
	@NonNull
	private static List<Department> visibleDepartments(@NonNull List<Department> supplied,
	                                                   @Nullable Stream stream) {
		if (stream == null) {
			return supplied;
		}

		List<Department> allowed = AcademicRules.departmentsFor(stream);
		List<Department> out = new ArrayList<>();
		for (Department department : supplied) {
			if (allowed.contains(department)) {
				out.add(department);
			}
		}
		return out;
	}

	/**
	 * Rebuilds all three adapters for the current state and reapplies the selections, without
	 * firing any callback.
	 */
	private static void rebuildAll(@NonNull AutoCompleteTextView departmentField,
	                               @NonNull AutoCompleteTextView streamField,
	                               @Nullable AutoCompleteTextView yearField,
	                               @NonNull List<Department> departments,
	                               @Nullable String anyLabel,
	                               @NonNull Holder state) {

		Context context = streamField.getContext();

		setItems(departmentField, visibleDepartments(departments, state.stream), anyLabel,
				state.department, context);

		List<Stream> streams = AcademicRules.streamsFor(state.department);
		setItems(streamField, streams, anyLabel, state.stream, context);

		// A single-stream department leaves nothing to choose, so the field is shown filled in
		// but not editable rather than inviting a selection that cannot vary.
		streamField.setEnabled(!(state.department != null && streams.size() == 1));

		if (yearField != null) {
			setItems(yearField, AcademicRules.yearsFor(state.department, state.stream),
					anyLabel, state.year, context);
		}
	}

	private static <E extends Enum<E>> void setItems(@NonNull AutoCompleteTextView field,
	                                                 @NonNull List<E> values,
	                                                 @Nullable String anyLabel,
	                                                 @Nullable E selected,
	                                                 @NonNull Context context) {
		List<String> labels = new ArrayList<>();
		if (anyLabel != null) {
			labels.add(anyLabel);
		}
		for (E value : values) {
			labels.add(value.toString());
		}

		field.setAdapter(new ArrayAdapter<>(context,
				android.R.layout.simple_list_item_1, labels));
		setSelection(field, selected, anyLabel);
	}

	public interface Linked {
		void onChanged(@Nullable Department department, @Nullable Stream stream,
		               @Nullable Year year);
	}

	/** Mutable selection state shared by the linked fields. */
	private static class Holder {

		@Nullable
		Department department;

		@Nullable
		Stream stream;

		@Nullable
		Year year;

		Holder(@Nullable Department department, @Nullable Stream stream, @Nullable Year year) {
			this.department = department;
			this.stream = stream;
			this.year = year;
		}
	}
}
