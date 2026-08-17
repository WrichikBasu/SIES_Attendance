package in.basulabs.siesattendance.frontend;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.textfield.TextInputLayout;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.CourseType;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.EntityBatchDesc;
import in.basulabs.siesattendance.backend.EntityCourse;
import in.basulabs.siesattendance.backend.EntityProfessor;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;
import in.basulabs.siesattendance.backend.pojo.StatRow;
import in.basulabs.siesattendance.backend.pojo.StatsSummary;
import in.basulabs.siesattendance.backend.stats.StatsFilter;
import in.basulabs.siesattendance.backend.stats.StatsGroupBy;
import in.basulabs.siesattendance.frontend.adapter.AdapterStatRow;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * The attendance report.
 * <p>
 * Rather than a fixed set of views, this is one query surface: choose what to group by, narrow
 * with any combination of filters, and the same list renders the result. "Attendance by course",
 * "by month", "which Friday slots are being skipped" and "TY BSc Physics in September" are all
 * the same query with different arguments, so they cost one screen rather than four.
 * <p>
 * Grouping by student or by course produces rows that can be tapped to drill into that subject.
 */
public class FragmentStats extends Fragment {

	private static final DateTimeFormatter DATE_LABEL =
			DateTimeFormatter.ofPattern("d MMM yyyy");

	private AttendanceViewModel viewModel;

	private AdapterStatRow adapter;

	private ChipGroup groupChips;

	private View filterPanel;

	private MaterialButton toggleFilters, btnFrom, btnTo;

	private TextView tvPercent, tvLectures, tvRecords, tvBelow, tvEmpty, tvActiveFilters;

	private AutoCompleteTextView actvRange, actvSort, actvCourse, actvBatch, actvProfessor,
			actvCourseType, actvYear, actvStream, actvDept;

	private TextInputLayout tilFrom, tilTo;

	private List<EntityCourse> courses = new ArrayList<>();

	private List<EntityBatchDesc> batches = new ArrayList<>();

	private List<EntityProfessor> professors = new ArrayList<>();

	private String anyLabel;

	/** Suppresses query re-runs while dropdowns are being populated programmatically. */
	private boolean binding;

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
	                         @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_stats, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity()).get(AttendanceViewModel.class);
		anyLabel = getString(R.string.filter_any);

		bindViews(view);
		buildGroupChips();
		buildStaticDropdowns();
		setupDateButtons();
		setupActions(view);

		RecyclerView recyclerView = view.findViewById(R.id.rvStats);
		adapter = new AdapterStatRow(this::onRowClicked);
		recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
		recyclerView.setAdapter(adapter);

		observe();
		loadOptions();

		viewModel.runStatsQuery();
	}

	private void bindViews(@NonNull View view) {
		groupChips = view.findViewById(R.id.chipGroupBy);
		filterPanel = view.findViewById(R.id.layoutFilters);
		toggleFilters = view.findViewById(R.id.btnToggleFilters);

		tvPercent = view.findViewById(R.id.tvOverallPercentage);
		tvLectures = view.findViewById(R.id.tvLectureCount);
		tvRecords = view.findViewById(R.id.tvRecordCount);
		tvBelow = view.findViewById(R.id.tvBelowCount);
		tvEmpty = view.findViewById(R.id.tvEmptyStats);
		tvActiveFilters = view.findViewById(R.id.tvActiveFilters);

		actvRange = view.findViewById(R.id.actvDateRange);
		actvSort = view.findViewById(R.id.actvSort);
		actvCourse = view.findViewById(R.id.actvCourse);
		actvBatch = view.findViewById(R.id.actvBatch);
		actvProfessor = view.findViewById(R.id.actvProfessor);
		actvCourseType = view.findViewById(R.id.actvCourseType);
		actvYear = view.findViewById(R.id.actvYear);
		actvStream = view.findViewById(R.id.actvStream);
		actvDept = view.findViewById(R.id.actvDept);

		btnFrom = view.findViewById(R.id.btnFromDate);
		btnTo = view.findViewById(R.id.btnToDate);
		tilFrom = view.findViewById(R.id.tilFrom);
		tilTo = view.findViewById(R.id.tilTo);
	}

	// ── Grouping ─────────────────────────────────────────────────────────

	private void buildGroupChips() {

		StatsFilter filter = viewModel.statsFilter();

		for (StatsGroupBy option : StatsGroupBy.values()) {
			Chip chip = new Chip(requireContext());
			chip.setText(option.label);
			chip.setCheckable(true);
			chip.setChecked(filter.groupBy == option);

			chip.setOnClickListener(v -> {
				viewModel.statsFilter().groupBy = option;
				viewModel.runStatsQuery();
			});

			groupChips.addView(chip);
		}

		groupChips.setSingleSelection(true);
		groupChips.setSelectionRequired(true);
	}

	// ── Dropdowns ────────────────────────────────────────────────────────

	private void buildStaticDropdowns() {

		binding = true;
		StatsFilter filter = viewModel.statsFilter();

		List<String> ranges = new ArrayList<>();
		for (StatsFilter.DateRange range : StatsFilter.DateRange.values()) {
			ranges.add(range.label);
		}
		actvRange.setAdapter(simple(ranges));
		actvRange.setText(filter.dateRange.label, false);
		actvRange.setOnItemClickListener((p, v, position, id) -> {
			StatsFilter.DateRange range = StatsFilter.DateRange.values()[position];
			viewModel.statsFilter().applyDateRange(range);
			updateDateButtons();
			rerun();
		});

		List<String> sorts = new ArrayList<>();
		for (StatsFilter.SortOrder order : StatsFilter.SortOrder.values()) {
			sorts.add(order.label);
		}
		actvSort.setAdapter(simple(sorts));
		actvSort.setText(filter.sortOrder.label, false);
		actvSort.setOnItemClickListener((p, v, position, id) -> {
			viewModel.statsFilter().sortOrder = StatsFilter.SortOrder.values()[position];
			rerun();
		});

		bindEnumDropdown(actvCourseType, CourseType.values(),
				value -> viewModel.statsFilter().courseType = (CourseType) value);
		bindEnumDropdown(actvYear, Year.values(),
				value -> viewModel.statsFilter().year = (Year) value);
		bindEnumDropdown(actvStream, Stream.values(),
				value -> viewModel.statsFilter().stream = (Stream) value);
		bindEnumDropdown(actvDept, Department.values(),
				value -> viewModel.statsFilter().department = (Department) value);

		binding = false;
	}

	private interface EnumSetter {
		void set(@Nullable Enum<?> value);
	}

	/**
	 * Wires a dropdown whose first entry is "Any" and clears that filter.
	 */
	private <E extends Enum<E>> void bindEnumDropdown(@NonNull AutoCompleteTextView field,
	                                                  @NonNull E[] values,
	                                                  @NonNull EnumSetter setter) {
		List<String> items = new ArrayList<>();
		items.add(anyLabel);
		for (E value : values) {
			items.add(value.name());
		}

		field.setAdapter(simple(items));
		field.setText(anyLabel, false);
		field.setOnItemClickListener((p, v, position, id) -> {
			setter.set(position == 0 ? null : values[position - 1]);
			rerun();
		});
	}

	/**
	 * Course, batch and professor lists come from the database, so they are filled in once the
	 * query returns rather than at construction.
	 */
	private void loadOptions() {
		viewModel.loadStatsFilterOptions((courseList, batchList, professorList) -> {
			if (!isAdded()) {
				return;
			}
			requireActivity().runOnUiThread(() -> {
				if (!isAdded()) {
					return;
				}

				binding = true;
				courses = courseList;
				batches = batchList;
				professors = professorList;

				List<String> courseLabels = new ArrayList<>();
				courseLabels.add(anyLabel);
				for (EntityCourse course : courseList) {
					courseLabels.add(course.courseCode + " – " + course.courseTopic);
				}
				actvCourse.setAdapter(simple(courseLabels));
				actvCourse.setText(anyLabel, false);
				actvCourse.setOnItemClickListener((p, v, position, id) -> {
					viewModel.statsFilter().courseID =
							position == 0 ? null : courses.get(position - 1).courseID;
					rerun();
				});

				List<String> batchLabels = new ArrayList<>();
				batchLabels.add(anyLabel);
				for (EntityBatchDesc batch : batchList) {
					batchLabels.add(batch.batchName);
				}
				actvBatch.setAdapter(simple(batchLabels));
				actvBatch.setText(anyLabel, false);
				actvBatch.setOnItemClickListener((p, v, position, id) -> {
					viewModel.statsFilter().batchID =
							position == 0 ? null : batches.get(position - 1).batchID;
					rerun();
				});

				List<String> profLabels = new ArrayList<>();
				profLabels.add(anyLabel);
				for (EntityProfessor professor : professorList) {
					profLabels.add(professor.name);
				}
				actvProfessor.setAdapter(simple(profLabels));
				actvProfessor.setText(anyLabel, false);
				actvProfessor.setOnItemClickListener((p, v, position, id) -> {
					viewModel.statsFilter().professorID =
							position == 0 ? null : professors.get(position - 1).profID;
					rerun();
				});

				binding = false;
			});
		});
	}

	@NonNull
	private ArrayAdapter<String> simple(@NonNull List<String> items) {
		return new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, items);
	}

	// ── Custom date window ───────────────────────────────────────────────

	private void setupDateButtons() {
		btnFrom.setOnClickListener(v -> pickDate(true));
		btnTo.setOnClickListener(v -> pickDate(false));
		updateDateButtons();
	}

	private void pickDate(boolean isFrom) {

		StatsFilter filter = viewModel.statsFilter();
		LocalDate current = isFrom ? filter.from : filter.to;
		if (current == null) {
			current = LocalDate.now();
		}

		MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
				.setTitleText(isFrom ? R.string.label_from : R.string.label_to)
				.setSelection(current.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
				.build();

		picker.addOnPositiveButtonClickListener(selection -> {
			LocalDate picked = Instant.ofEpochMilli(selection)
					.atZone(ZoneId.of("UTC")).toLocalDate();

			StatsFilter f = viewModel.statsFilter();
			if (isFrom) {
				f.from = picked;
				// Choosing a start after the existing end would select nothing, so the end
				// follows rather than leaving an empty window.
				if (f.to != null && f.to.isBefore(picked)) {
					f.to = picked;
				}
			} else {
				f.to = picked;
				if (f.from != null && f.from.isAfter(picked)) {
					f.from = picked;
				}
			}

			f.dateRange = StatsFilter.DateRange.CUSTOM;
			actvRange.setText(StatsFilter.DateRange.CUSTOM.label, false);
			updateDateButtons();
			rerun();
		});

		picker.show(getParentFragmentManager(), isFrom ? "from" : "to");
	}

	private void updateDateButtons() {

		StatsFilter filter = viewModel.statsFilter();
		boolean custom = filter.dateRange == StatsFilter.DateRange.CUSTOM;

		tilFrom.setVisibility(custom ? View.VISIBLE : View.GONE);
		tilTo.setVisibility(custom ? View.VISIBLE : View.GONE);

		btnFrom.setText(filter.from == null
				? getString(R.string.label_from)
				: filter.from.format(DATE_LABEL));
		btnTo.setText(filter.to == null
				? getString(R.string.label_to)
				: filter.to.format(DATE_LABEL));
	}

	// ── Actions ──────────────────────────────────────────────────────────

	private void setupActions(@NonNull View view) {

		toggleFilters.setOnClickListener(v -> {
			boolean showing = filterPanel.getVisibility() == View.VISIBLE;
			filterPanel.setVisibility(showing ? View.GONE : View.VISIBLE);
			toggleFilters.setText(showing ? R.string.action_show_filters
					: R.string.action_hide_filters);
		});

		view.findViewById(R.id.btnClearFilters).setOnClickListener(v -> {
			viewModel.resetStatsFilter();
			resetFilterControls();
		});
	}

	/**
	 * Puts every filter control back to "Any" after a reset. The filter object is already clear
	 * by this point; this only resynchronises what is on screen.
	 */
	private void resetFilterControls() {
		binding = true;

		actvRange.setText(StatsFilter.DateRange.ALL_TIME.label, false);
		for (AutoCompleteTextView field : new AutoCompleteTextView[]{
				actvCourse, actvBatch, actvProfessor, actvCourseType,
				actvYear, actvStream, actvDept}) {
			field.setText(anyLabel, false);
		}

		updateDateButtons();
		binding = false;
	}

	private void rerun() {
		if (!binding) {
			viewModel.runStatsQuery();
		}
	}

	private void onRowClicked(@NonNull StatRow row) {

		StatsGroupBy groupBy = viewModel.statsFilter().groupBy;

		if (groupBy == StatsGroupBy.STUDENT) {
			Bundle args = new Bundle();
			args.putLong("studentID", row.rowId);
			Navigation.findNavController(requireView())
					.navigate(R.id.action_stats_to_studentDetail, args);
			return;
		}

		if (groupBy == StatsGroupBy.COURSE) {
			// Narrowing to the tapped course and regrouping by student answers the obvious
			// follow-up question: who is short in this course?
			viewModel.statsFilter().courseID = row.rowId;
			viewModel.statsFilter().groupBy = StatsGroupBy.STUDENT;
			syncControlsToFilter();
			viewModel.runStatsQuery();
		}
	}

	/**
	 * Reflects a filter change made in code (rather than by the user) back onto the controls.
	 */
	private void syncControlsToFilter() {

		binding = true;
		StatsFilter filter = viewModel.statsFilter();

		for (int i = 0; i < groupChips.getChildCount(); i++) {
			Chip chip = (Chip) groupChips.getChildAt(i);
			chip.setChecked(StatsGroupBy.values()[i] == filter.groupBy);
		}

		if (filter.courseID != null) {
			for (int i = 0; i < courses.size(); i++) {
				if (courses.get(i).courseID == filter.courseID) {
					EntityCourse course = courses.get(i);
					actvCourse.setText(course.courseCode + " – " + course.courseTopic, false);
					break;
				}
			}
		}

		adapter.setContext(filter.groupBy, filter.threshold);
		binding = false;
	}

	// ── Results ──────────────────────────────────────────────────────────

	private void observe() {

		adapter.setContext(viewModel.statsFilter().groupBy, viewModel.statsFilter().threshold);

		viewModel.getStatRows().observe(getViewLifecycleOwner(), rows -> {
			adapter.setContext(viewModel.statsFilter().groupBy,
					viewModel.statsFilter().threshold);
			adapter.submitList(rows);
			tvEmpty.setVisibility(rows == null || rows.isEmpty() ? View.VISIBLE : View.GONE);
			updateFilterSummary();
		});

		viewModel.getStatsSummary().observe(getViewLifecycleOwner(), this::bindSummary);

		viewModel.getStatsBelowCount().observe(getViewLifecycleOwner(), count ->
				tvBelow.setText(String.valueOf(count == null ? 0 : count)));
	}

	private void bindSummary(@Nullable StatsSummary summary) {

		if (summary == null || summary.isEmpty()) {
			tvPercent.setText("—");
			tvLectures.setText("0");
			tvRecords.setText("0");
			return;
		}

		tvPercent.setText(String.format(Locale.getDefault(), "%.1f%%", summary.percentage()));
		tvLectures.setText(String.valueOf(summary.lectures));
		tvRecords.setText(String.valueOf(summary.total));
	}

	/**
	 * A one-line description of what the numbers above currently cover, so a filtered report is
	 * never mistaken for the whole picture.
	 */
	private void updateFilterSummary() {

		StatsFilter filter = viewModel.statsFilter();
		List<String> parts = new ArrayList<>();

		if (filter.dateRange != StatsFilter.DateRange.ALL_TIME) {
			if (filter.from != null && filter.to != null) {
				parts.add(filter.from.format(DATE_LABEL) + " – " + filter.to.format(DATE_LABEL));
			} else {
				parts.add(filter.dateRange.label);
			}
		}

		if (filter.courseID != null) {
			for (EntityCourse course : courses) {
				if (course.courseID == filter.courseID) {
					parts.add(course.courseCode);
					break;
				}
			}
		}

		if (filter.batchID != null) {
			for (EntityBatchDesc batch : batches) {
				if (batch.batchID == filter.batchID) {
					parts.add(batch.batchName);
					break;
				}
			}
		}

		if (filter.professorID != null) {
			for (EntityProfessor professor : professors) {
				if (professor.profID == filter.professorID) {
					parts.add(professor.name);
					break;
				}
			}
		}

		if (filter.courseType != null) {
			parts.add(filter.courseType.name());
		}
		if (filter.year != null) {
			parts.add(filter.year.name());
		}
		if (filter.stream != null) {
			parts.add(filter.stream.name());
		}
		if (filter.department != null) {
			parts.add(filter.department.name());
		}

		if (parts.isEmpty()) {
			tvActiveFilters.setText(R.string.stats_no_filters);
		} else {
			tvActiveFilters.setText(String.join(" · ", parts));
		}
	}
}
