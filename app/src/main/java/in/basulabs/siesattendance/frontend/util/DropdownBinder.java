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
 * <p>
 * These are exposed dropdowns, which are {@code AutoCompleteTextView}s rather than spinners, so
 * the displayed text is set with {@code setText(value, false)} throughout. The false suppresses
 * filtering; without it, showing a value would filter the list down to entries matching that
 * value and the dropdown would open with only one option in it.
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
	 * Ties a department field, a stream field and optionally a year field together.
	 * <p>
	 * Narrowing runs one way: department, then stream, then year. Choosing a department
	 * restricts the stream list to what it offers and selects it outright when there is only
	 * one, so Commerce never asks whether the student is doing a BSc; choosing a stream
	 * restricts the year list. A later choice never rewrites an earlier one, so correcting the
	 * year cannot silently change the department.
	 * <p>
	 * The cost of that is that a stale value can survive a change made above it, which is why
	 * every caller runs {@link AcademicRules#validate} before saving.
	 * <p>
	 * A convenience wrapper over {@link #linkAcademicFields} for the screens that have no
	 * semester field.
	 *
	 * @param yearField may be null on screens with no year field
	 * @param anyLabel  when non-null, every field gains a leading entry that selects null, which
	 *                  is what turns these into filters rather than required fields
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

		linkAcademicFields(departmentField, streamField, yearField, null, departments, anyLabel,
				department, stream, year, null,
				(dept, str, yr, semester) -> onChanged.onChanged(dept, str, yr));
	}

	/**
	 * The full linkage, including semester.
	 * <p>
	 * Semester follows year the way stream follows department: first year covers semesters 1
	 * and 2, second 3 and 4, third 5 and 6, with a master's course restarting the count.
	 * Choosing a semester does not alter the year, in keeping with the one-way rule.
	 * <p>
	 * Reconciliation happens on change, not on the way in. The initial values are displayed as
	 * given, even where they contradict each other, so a record saved before a rule existed opens
	 * showing what is actually stored rather than silently losing a field. That does mean a field
	 * can display a value its own list no longer contains, until the user touches something above
	 * it. {@link AcademicRules#validate} is what stops such a combination being saved again.
	 *
	 * @param semesterField may be null on screens with no semester
	 */
	public static void linkAcademicFields(@NonNull AutoCompleteTextView departmentField,
	                                      @NonNull AutoCompleteTextView streamField,
	                                      @Nullable AutoCompleteTextView yearField,
	                                      @Nullable AutoCompleteTextView semesterField,
	                                      @NonNull List<Department> departments,
	                                      @Nullable String anyLabel,
	                                      @Nullable Department department,
	                                      @Nullable Stream stream,
	                                      @Nullable Year year,
	                                      @Nullable Integer semester,
	                                      @NonNull LinkedFull onChanged) {

		final Holder state = new Holder(department, stream, year, semester);

		// Every field rebuilds all three lists, because a change to any one of them can alter
		// what the other two may contain.
		Runnable rebuild = () -> rebuildAll(departmentField, streamField, yearField,
				semesterField, departments, anyLabel, state);

		departmentField.setOnItemClickListener((parent, view, position, id) -> {

			// The department list is never narrowed, so it is always the full supplied list.
			int offset = anyLabel == null ? 0 : 1;
			state.department = position < offset ? null : departments.get(position - offset);

			// The stream already chosen may not exist for this department, so it is kept,
			// replaced by the sole option, or reduced to the equivalent level.
			state.stream = AcademicRules.reconcile(state.department, state.stream);
			state.year = reconcileYear(state);
			state.semester = AcademicRules.reconcileSemester(state.year, state.stream,
					state.semester);

			rebuild.run();
			notifyChanged(state, onChanged);
		});

		streamField.setOnItemClickListener((parent, view, position, id) -> {

			List<Stream> allowed = AcademicRules.streamsFor(state.department);
			int offset = anyLabel == null ? 0 : 1;
			state.stream = position < offset ? null : allowed.get(position - offset);

			// The mirror of the above: a department that does not offer this stream is
			// cleared, or replaced when the stream leaves only one possibility.
			// Deliberately leaves the department alone: narrowing is one-way.
			state.year = reconcileYear(state);
			state.semester = AcademicRules.reconcileSemester(state.year, state.stream,
					state.semester);

			rebuild.run();
			notifyChanged(state, onChanged);
		});

		if (yearField != null) {
			yearField.setOnItemClickListener((parent, view, position, id) -> {

				List<Year> allowed = AcademicRules.yearsFor(state.department, state.stream);
				int offset = anyLabel == null ? 0 : 1;
				state.year = position < offset ? null : allowed.get(position - offset);

				// A semester left over from another year is dropped rather than kept as an
				// impossible pairing such as FY with semester 5.
				state.semester = AcademicRules.reconcileSemester(state.year, state.stream,
						state.semester);

				rebuild.run();
				notifyChanged(state, onChanged);
			});
		}

		if (semesterField != null) {
			semesterField.setOnItemClickListener((parent, view, position, id) -> {

				List<Integer> allowed =
						AcademicRules.semestersFor(state.year, state.stream);
				int offset = anyLabel == null ? 0 : 1;
				state.semester = position < offset ? null : allowed.get(position - offset);

				rebuild.run();
				notifyChanged(state, onChanged);
			});
		}

		rebuild.run();
	}

	/** Hands the whole reconciled state back, since a change to one field can alter three. */
	private static void notifyChanged(@NonNull Holder state, @NonNull LinkedFull onChanged) {
		onChanged.onChanged(state.department, state.stream, state.year, state.semester);
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
	 * Rebuilds all three adapters for the current state and reapplies the selections, without
	 * firing any callback.
	 */
	private static void rebuildAll(@NonNull AutoCompleteTextView departmentField,
	                               @NonNull AutoCompleteTextView streamField,
	                               @Nullable AutoCompleteTextView yearField,
	                               @Nullable AutoCompleteTextView semesterField,
	                               @NonNull List<Department> departments,
	                               @Nullable String anyLabel,
	                               @NonNull Holder state) {

		Context context = streamField.getContext();

		setItems(departmentField, departments, anyLabel, state.department, context);

		List<Stream> streams = AcademicRules.streamsFor(state.department);
		setItems(streamField, streams, anyLabel, state.stream, context);

		// A single-stream department leaves nothing to choose, so the field is shown filled in
		// but not editable rather than inviting a selection that cannot vary.
		streamField.setEnabled(!(state.department != null && streams.size() == 1));

		if (yearField != null) {
			setItems(yearField, AcademicRules.yearsFor(state.department, state.stream),
					anyLabel, state.year, context);
		}

		if (semesterField != null) {

			List<Integer> semesters =
					AcademicRules.semestersFor(state.year, state.stream);

			List<String> labels = new ArrayList<>();
			if (anyLabel != null) {
				labels.add(anyLabel);
			}
			for (Integer value : semesters) {
				labels.add(String.valueOf(value));
			}

			semesterField.setAdapter(new ArrayAdapter<>(context,
					android.R.layout.simple_list_item_1, labels));

			semesterField.setText(state.semester == null
					? (anyLabel == null ? "" : anyLabel)
					: String.valueOf(state.semester), false);
		}
	}

	/**
	 * Replaces a field's adapter and reapplies its selection. Unlike {@link #bind}, this attaches
	 * no click listener: the linked fields install their own, and rebuilding must not disturb
	 * them.
	 */
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

	/** Result of {@link #linkDepartmentAndStream}. Any value may be null. */
	public interface Linked {
		void onChanged(@Nullable Department department, @Nullable Stream stream,
		               @Nullable Year year);
	}

	/** Result of {@link #linkAcademicFields}, including the semester. Any value may be null. */
	public interface LinkedFull {
		void onChanged(@Nullable Department department, @Nullable Stream stream,
		               @Nullable Year year, @Nullable Integer semester);
	}

	/**
	 * Mutable selection state shared by the linked fields.
	 * <p>
	 * A small mutable object rather than four captured locals, because the listeners have to see
	 * each other's changes and a lambda cannot close over anything it reassigns.
	 */
	private static class Holder {

		@Nullable
		Department department;

		@Nullable
		Stream stream;

		@Nullable
		Year year;

		@Nullable
		Integer semester;

		Holder(@Nullable Department department, @Nullable Stream stream, @Nullable Year year,
		       @Nullable Integer semester) {
			this.department = department;
			this.stream = stream;
			this.year = year;
			this.semester = semester;
		}
	}
}
