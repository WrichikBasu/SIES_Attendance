package in.basulabs.siesattendance.frontend;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.AcademicRules;
import in.basulabs.siesattendance.backend.CourseType;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.EntityAcademicYear;
import in.basulabs.siesattendance.backend.EntityBatchDesc;
import in.basulabs.siesattendance.backend.EntityCourse;
import in.basulabs.siesattendance.backend.EntityProfessor;
import in.basulabs.siesattendance.backend.pojo.StatRow;
import in.basulabs.siesattendance.backend.pojo.StatsSummary;
import in.basulabs.siesattendance.backend.stats.StatsFilter;
import in.basulabs.siesattendance.backend.stats.StatsGroupBy;
import in.basulabs.siesattendance.frontend.adapter.AdapterHeader;
import in.basulabs.siesattendance.frontend.adapter.AdapterStatRow;
import in.basulabs.siesattendance.frontend.util.DropdownBinder;
import in.basulabs.siesattendance.frontend.util.Ui;
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
 * <p>
 * The course filter takes several courses at once, chosen in a dialog and shown as chips. The
 * headline figures are then the combined attendance across exactly those courses, computed from
 * the same rows the report lists, which is the question "how are my two papers doing together"
 * that one course at a time could not answer.
 * <p>
 * The filter controls and the report are one scrolling surface, achieved by putting the controls
 * in an {@link AdapterHeader} in front of the list rather than in a scroll view above it. The
 * header is built asynchronously, so anything the screen has loaded before it exists is held in
 * the {@code last} fields and applied once it arrives.
 * <p>
 * {@link #binding} guards against a feedback loop. Populating a dropdown in code fires the same
 * listener a user tap does, and without the guard every rebind would re-run the query, which in
 * turn rebinds.
 */
public class FragmentStats extends Fragment {

	private static final DateTimeFormatter DATE_LABEL =
			DateTimeFormatter.ofPattern("d MMM yyyy");

	private AttendanceViewModel viewModel;

	private AdapterStatRow adapter;

	private ChipGroup groupChips;

	private View filterPanel;

	private MaterialButton toggleFilters, btnFrom, btnTo, btnSelectCourses;

	/** The chips standing for the courses the report is restricted to. */
	private ChipGroup courseChips;

	private TextView tvNoCourses;

	private TextView tvPercent, tvLectures, tvRecords, tvBelow, tvEmpty, tvActiveFilters,
			tvFiltersActive;

	private AutoCompleteTextView actvSession, actvRange, actvSort, actvBatch,
			actvProfessor, actvCourseType, actvYear, actvStream, actvDept, actvDivision;

	private TextView tvOrphanWarning;

	private List<EntityAcademicYear> sessions = new ArrayList<>();

	/** Label for the "every session" entry of the session dropdown. */
	private String allSessionsLabel;

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
		allSessionsLabel = getString(R.string.filter_all_sessions);

		setupExportMenu();

		RecyclerView recyclerView = (RecyclerView) view;
		adapter = new AdapterStatRow(this::onRowClicked);
		recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

		// Controls as the list's first item: the report scrolls as one piece and the rows below
		// still recycle, which matters when grouping by Lecture returns thousands of them. The
		// header is wired before the adapter is attached, so nothing it does lands inside a
		// layout pass.
		View header = getLayoutInflater()
				.inflate(R.layout.view_stats_header, recyclerView, false);
		onHeaderReady(header);

		headerAdapter = new AdapterHeader(header);
		recyclerView.setAdapter(new ConcatAdapter(headerAdapter, adapter));

		observe();

		viewModel.getAcademicYears().observe(getViewLifecycleOwner(), years -> {
			lastSessions = years;
			if (actvSession != null && years != null) {
				bindSessions(years);
			}
		});

		// Lectures outside every session are excluded by the session join, so the shortfall is
		// stated rather than left to look like missing attendance.
		viewModel.getOrphanLectureCount().observe(getViewLifecycleOwner(), count -> {
			lastOrphanCount = count == null ? 0 : count;
			bindOrphanWarning();
		});

		viewModel.scheduleStatsQuery();
	}

	private AdapterHeader headerAdapter;

	@Nullable
	private List<EntityAcademicYear> lastSessions;

	private int lastOrphanCount;

	/**
	 * Wires the header once the RecyclerView has created it. Data may already have loaded by
	 * then, so whatever arrived first is applied here.
	 */
	private void onHeaderReady(@NonNull View header) {

		bindViews(header);
		buildGroupChips();
		buildStaticDropdowns();
		setupDateButtons();
		setupActions(header);
		loadOptions();

		if (lastSessions != null) {
			bindSessions(lastSessions);
		}

		bindOrphanWarning();
		bindSummary(viewModel.getStatsSummary().getValue());
		updateFilterSummary();

		List<StatRow> rows = viewModel.getStatRows().getValue();
		tvEmpty.setVisibility(rows == null || rows.isEmpty() ? View.VISIBLE : View.GONE);
	}

	/**
	 * Warns when lectures fall outside every defined session. Those join to no session and are
	 * therefore missing from this report entirely, which is worth saying on the screen whose
	 * numbers are wrong because of it.
	 */
	private void bindOrphanWarning() {
		if (tvOrphanWarning == null) {
			return;
		}
		boolean any = lastOrphanCount > 0;
		tvOrphanWarning.setVisibility(any ? View.VISIBLE : View.GONE);
		if (any) {
			tvOrphanWarning.setText(getResources().getQuantityString(
					R.plurals.warn_orphan_lectures, lastOrphanCount, lastOrphanCount));
		}
	}

	private void bindViews(@NonNull View view) {
		groupChips = view.findViewById(R.id.chipGroupBy);
		filterPanel = view.findViewById(R.id.scrollFilters);
		toggleFilters = view.findViewById(R.id.btnToggleFilters);

		tvPercent = view.findViewById(R.id.tvOverallPercentage);
		tvLectures = view.findViewById(R.id.tvLectureCount);
		tvRecords = view.findViewById(R.id.tvRecordCount);
		tvBelow = view.findViewById(R.id.tvBelowCount);
		tvEmpty = view.findViewById(R.id.tvEmptyStats);
		tvActiveFilters = view.findViewById(R.id.tvActiveFilters);
		tvFiltersActive = view.findViewById(R.id.tvFiltersActive);

		actvSession = view.findViewById(R.id.actvSession);
		tvOrphanWarning = view.findViewById(R.id.tvOrphanWarning);
		actvRange = view.findViewById(R.id.actvDateRange);
		actvSort = view.findViewById(R.id.actvSort);
		courseChips = view.findViewById(R.id.cgCourses);
		tvNoCourses = view.findViewById(R.id.tvNoCourses);
		btnSelectCourses = view.findViewById(R.id.btnSelectCourses);
		actvBatch = view.findViewById(R.id.actvBatch);
		actvProfessor = view.findViewById(R.id.actvProfessor);
		actvCourseType = view.findViewById(R.id.actvCourseType);
		actvYear = view.findViewById(R.id.actvYear);
		actvStream = view.findViewById(R.id.actvStream);
		actvDept = view.findViewById(R.id.actvDept);
		actvDivision = view.findViewById(R.id.actvDivision);

		btnFrom = view.findViewById(R.id.btnFromDate);
		btnTo = view.findViewById(R.id.btnToDate);
		tilFrom = view.findViewById(R.id.tilFrom);
		tilTo = view.findViewById(R.id.tilTo);
	}

	// ── Grouping ─────────────────────────────────────────────────────────

	/** One chip per {@link StatsGroupBy}, which is what makes adding a breakdown a one-line change. */
	private void buildGroupChips() {

		StatsFilter filter = viewModel.statsFilter();

		for (StatsGroupBy option : StatsGroupBy.values()) {
			Chip chip = new Chip(requireContext());
			chip.setText(option.label);
			chip.setCheckable(true);
			chip.setChecked(filter.groupBy == option);

			chip.setOnClickListener(v -> {
				viewModel.statsFilter().groupBy = option;
				viewModel.scheduleStatsQuery();
			});

			groupChips.addView(chip);
		}

		groupChips.setSingleSelection(true);
		groupChips.setSelectionRequired(true);
	}

	// ── Dropdowns ────────────────────────────────────────────────────────

	/** The dropdowns whose contents come from enums and never change: range, sort, class filters. */
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
			// The half-session presets are worked out from the selected session's real dates,
			// so the session has to be handed in rather than assumed.
			viewModel.statsFilter().applyDateRange(range, selectedSession());
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

		DropdownBinder.bind(actvCourseType, Arrays.asList(CourseType.values()), anyLabel,
				null, value -> {
					viewModel.statsFilter().courseType = value;
					rerun();
				});

		// Department, stream and year are linked here too, so a report cannot be asked for
		// something like Commerce students on a BSc stream.
		DropdownBinder.linkDepartmentAndStream(actvDept, actvStream, actvYear,
				Arrays.asList(Department.values()), anyLabel, null, null, null,
				(department, stream, year) -> {
					// Named distinctly from the enclosing method's local: a lambda body shares
					// the enclosing scope, so reusing "filter" here would be shadowing, which
					// Java rejects.
					StatsFilter current = viewModel.statsFilter();
					current.department = department;
					current.stream = stream;
					current.year = year;

					// A stream with no divisions cannot be narrowed to one, so a division
					// left over from the previous stream is dropped rather than left to
					// return an empty report.
					current.division =
							AcademicRules.reconcileDivision(stream, current.division);
					bindDivisionFilter();

					rerun();
				});

		bindDivisionFilter();

		binding = false;
	}

	/**
	 * Fills the division filter for the stream currently being reported on. Divisions belong to
	 * the stream, so this is rebound whenever it changes; a stream with no divisions leaves the
	 * field empty and disabled.
	 */
	private void bindDivisionFilter() {
		DropdownBinder.bindDivisions(actvDivision, viewModel.statsFilter().stream, anyLabel,
				viewModel.statsFilter().division, value -> {
					viewModel.statsFilter().division = value;
					rerun();
				});
	}

	/**
	 * The session object matching the filter's academic year, or null when every session is
	 * selected.
	 */
	@Nullable
	private EntityAcademicYear selectedSession() {
		Integer year = viewModel.statsFilter().academicYear;
		if (year == null) {
			return null;
		}
		for (EntityAcademicYear session : sessions) {
			if (session.academicYear == year) {
				return session;
			}
		}
		return null;
	}

	/**
	 * Fills the session dropdown. The first entry selects every session at once, which is what
	 * makes a year-on-year comparison possible when combined with the Academic year grouping.
	 */
	private void bindSessions(@NonNull List<EntityAcademicYear> loaded) {

		binding = true;
		sessions = loaded;

		List<String> labels = new ArrayList<>();
		labels.add(allSessionsLabel);
		for (EntityAcademicYear session : loaded) {
			labels.add(session.label());
		}

		actvSession.setAdapter(simple(labels));

		Integer selected = viewModel.statsFilter().academicYear;
		String current = allSessionsLabel;
		if (selected != null) {
			for (EntityAcademicYear session : loaded) {
				if (session.academicYear == selected) {
					current = session.label();
					break;
				}
			}
		}
		actvSession.setText(current, false);

		actvSession.setOnItemClickListener((p, v, position, id) -> {
			viewModel.setStatsAcademicYear(position == 0
					? null : sessions.get(position - 1).academicYear);

			StatsFilter filter = viewModel.statsFilter();

			// A date window set for one session is meaningless in another, so switching
			// sessions clears it rather than silently returning nothing.
			if (filter.dateRange != StatsFilter.DateRange.ALL_TIME) {
				filter.applyDateRange(StatsFilter.DateRange.ALL_TIME, selectedSession());
				actvRange.setText(StatsFilter.DateRange.ALL_TIME.label, false);
				updateDateButtons();
			}

			rerun();
		});

		binding = false;
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
			Ui.onMain(this, () -> {
				binding = true;
				courses = courseList;
				batches = batchList;
				professors = professorList;

				bindCourseChips();

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

	// ── Course selection ─────────────────────────────────────────────────

	/**
	 * Offers every course at once, ticked or not, and applies whatever comes back.
	 * <p>
	 * A dialog rather than a row of chips to choose from: the college's whole course list is
	 * far too long to sit inside the filter panel, whereas a selection of half a dozen is not,
	 * which is why the chips show what was chosen rather than what could be.
	 * <p>
	 * The dialog works on its own copy of the ticks and only writes them back on Done, so
	 * changing one's mind halfway through costs nothing and, more importantly, does not fire a
	 * report per tick.
	 */
	private void showCourseDialog() {

		if (courses.isEmpty()) {
			return;
		}

		String[] labels = new String[courses.size()];
		boolean[] checked = new boolean[courses.size()];
		Set<Long> selected = viewModel.statsFilter().courseIDs;

		for (int i = 0; i < courses.size(); i++) {
			EntityCourse course = courses.get(i);
			labels[i] = course.courseCode + " – " + course.courseTopic;
			checked[i] = selected.contains(course.courseID);
		}

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_choose_courses_title)
				.setMultiChoiceItems(labels, checked,
						(dialog, which, isChecked) -> checked[which] = isChecked)
				.setNegativeButton(R.string.action_cancel, null)
				.setNeutralButton(R.string.action_clear_selection, (dialog, which) ->
						applyCourseSelection(new boolean[courses.size()]))
				.setPositiveButton(R.string.action_done, (dialog, which) ->
						applyCourseSelection(checked))
				.show();
	}

	/** Writes a set of ticks onto the filter and re-runs the report once. */
	private void applyCourseSelection(@NonNull boolean[] checked) {

		Set<Long> selected = viewModel.statsFilter().courseIDs;
		selected.clear();

		for (int i = 0; i < courses.size(); i++) {
			if (checked[i]) {
				selected.add(courses.get(i).courseID);
			}
		}

		bindCourseChips();
		updateFilterSummary();
		rerun();
	}

	/**
	 * Draws one closable chip per selected course, or the "every course" line when none are.
	 * <p>
	 * Rebuilt outright rather than diffed: the selection is a handful of chips, and rebuilding
	 * is what keeps this correct after a drill-down, a reset and the dialog alike.
	 */
	private void bindCourseChips() {

		if (courseChips == null) {
			return;
		}

		Set<Long> selected = viewModel.statsFilter().courseIDs;
		courseChips.removeAllViews();

		for (Long courseID : selected) {
			Chip chip = new Chip(requireContext());
			chip.setText(courseLabelFor(courseID));
			chip.setCloseIconVisible(true);
			chip.setOnCloseIconClickListener(v -> {
				viewModel.statsFilter().courseIDs.remove(courseID);
				bindCourseChips();
				updateFilterSummary();
				rerun();
			});
			courseChips.addView(chip);
		}

		boolean any = !selected.isEmpty();
		courseChips.setVisibility(any ? View.VISIBLE : View.GONE);
		tvNoCourses.setVisibility(any ? View.GONE : View.VISIBLE);
	}

	/**
	 * The code to put on a chip. Falls back to the ID's own label while the course list is
	 * still loading, or if a selected course has since gone, so a chip is never blank and the
	 * filter it stands for is never silently invisible.
	 */
	@NonNull
	private String courseLabelFor(long courseID) {

		for (EntityCourse course : courses) {
			if (course.courseID == courseID) {
				return course.courseCode;
			}
		}
		return getString(R.string.hint_course);
	}

	@NonNull
	/** A plain single-line adapter, since every dropdown on this screen uses the same one. */
	private ArrayAdapter<String> simple(@NonNull List<String> items) {
		return new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, items);
	}

	// ── Custom date window ───────────────────────────────────────────────

	/** Wires the custom range buttons. Picking a date switches the range preset to custom. */
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

	/** Labels the two date buttons, or shows a placeholder where a bound is left open. */
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

	/** The filter panel toggle, the clear button, and the threshold control. */
	private void setupActions(@NonNull View view) {

		// Hiding the panel hands its layout weight back to the results list, so the list grows
		// instead of the filters leaving a gap.
		toggleFilters.setOnClickListener(v -> {
			boolean showing = filterPanel.getVisibility() == View.VISIBLE;
			filterPanel.setVisibility(showing ? View.GONE : View.VISIBLE);
			toggleFilters.setText(showing ? R.string.action_show_filters
					: R.string.action_hide_filters);
		});

		btnSelectCourses.setOnClickListener(v -> showCourseDialog());

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
				actvBatch, actvProfessor, actvCourseType,
				actvYear, actvStream, actvDept, actvDivision}) {
			field.setText(anyLabel, false);
		}

		bindCourseChips();

		// The division list depends on the stream, which has just been cleared, so the field
		// goes back to offering every division rather than the last stream's.
		bindDivisionFilter();

		updateDateButtons();
		binding = false;
	}

	/**
	 * All filter edits go through the debounce rather than querying immediately, so scrolling
	 * a dropdown does not fire a report per item.
	 */
	private void rerun() {
		if (!binding) {
			viewModel.scheduleStatsQuery();
		}
	}

	/**
	 * Drills into a row by adding the corresponding filter and regrouping, rather than navigating
	 * away. Only the groupings with a real primary key reach here; see
	 * {@code StatsGroupBy.isDrillable}.
	 */
	private void onRowClicked(@NonNull StatRow row) {

		StatsGroupBy groupBy = viewModel.statsFilter().groupBy;

		if (groupBy == StatsGroupBy.STUDENT) {
			Bundle args = new Bundle();
			args.putLong("studentID", row.rowId);

			// The detail screen otherwise falls back to whichever session the students list
			// is on, which is not the one this row was computed for.
			Integer session = viewModel.statsFilter().academicYear;
			if (session != null) {
				args.putInt("academicYear", session);
			}
			Navigation.findNavController(requireView())
					.navigate(R.id.action_stats_to_studentDetail, args);
			return;
		}

		if (groupBy == StatsGroupBy.COURSE) {
			// Narrowing to the tapped course and regrouping by student answers the obvious
			// follow-up question: who is short in this course? It replaces the selection
			// rather than adding to it: the row that was tapped is the one being asked
			// about, and adding it to a set of others would answer a question nobody asked.
			viewModel.statsFilter().courseIDs.clear();
			viewModel.statsFilter().courseIDs.add(row.rowId);
			viewModel.statsFilter().groupBy = StatsGroupBy.STUDENT;
			syncControlsToFilter();
			viewModel.runStatsQuery();
		}
	}

	/**
	 * Reflects a filter change made in code (rather than by the user) back onto the controls.
	 */
	private void syncControlsToFilter() {

		if (groupChips == null) {
			return;
		}

		binding = true;
		StatsFilter filter = viewModel.statsFilter();

		for (int i = 0; i < groupChips.getChildCount(); i++) {
			Chip chip = (Chip) groupChips.getChildAt(i);
			chip.setChecked(StatsGroupBy.values()[i] == filter.groupBy);
		}

		bindCourseChips();

		adapter.setContext(filter.groupBy, filter.threshold);
		binding = false;
	}

	// ── Results ──────────────────────────────────────────────────────────

	/**
	 * The rows and the summary come from two queries over the same filter, so the totals above
	 * the report always agree with the report itself.
	 */
	private void observe() {

		adapter.setContext(viewModel.statsFilter().groupBy, viewModel.statsFilter().threshold);

		viewModel.getStatRows().observe(getViewLifecycleOwner(), rows -> {
			adapter.setContext(viewModel.statsFilter().groupBy,
					viewModel.statsFilter().threshold);
			adapter.submitList(rows);

			// The header is created by the RecyclerView, so it may not exist on the first
			// emission; onHeaderReady applies the current state once it does.
			if (tvEmpty != null) {
				tvEmpty.setVisibility(rows == null || rows.isEmpty()
						? View.VISIBLE : View.GONE);
				updateFilterSummary();
			}
		});

		viewModel.getStatsSummary().observe(getViewLifecycleOwner(), this::bindSummary);

		viewModel.getStatsBelowCount().observe(getViewLifecycleOwner(), count -> {
			if (tvBelow != null) {
				tvBelow.setText(String.valueOf(count == null ? 0 : count));
			}
		});
	}

	/**
	 * Fills the headline figures. An empty result is shown as such rather than as a row of
	 * zeroes, since a filter that matched nothing is a different thing from nought per cent.
	 */
	private void bindSummary(@Nullable StatsSummary summary) {

		if (tvPercent == null) {
			return;
		}

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

		if (tvActiveFilters == null) {
			return;
		}

		StatsFilter filter = viewModel.statsFilter();
		List<String> parts = new ArrayList<>();

		EntityAcademicYear session = selectedSession();
		parts.add(session == null ? allSessionsLabel : session.label());

		if (filter.dateRange != StatsFilter.DateRange.ALL_TIME) {
			if (filter.from != null && filter.to != null) {
				parts.add(filter.from.format(DATE_LABEL) + " – " + filter.to.format(DATE_LABEL));
			} else {
				parts.add(filter.dateRange.label);
			}
		}

		// One or two courses are named; beyond that the codes would crowd out everything else
		// on the line, and the chips below say which they are anyway.
		if (!filter.courseIDs.isEmpty()) {
			if (filter.courseIDs.size() <= 2) {
				for (EntityCourse course : courses) {
					if (filter.courseIDs.contains(course.courseID)) {
						parts.add(course.courseCode);
					}
				}
			} else {
				parts.add(getResources().getQuantityString(R.plurals.count_courses_selected,
						filter.courseIDs.size(), filter.courseIDs.size()));
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
			parts.add(filter.courseType.toString());
		}
		if (filter.year != null) {
			parts.add(filter.year.toString());
		}
		if (filter.stream != null) {
			parts.add(filter.stream.toString());
		}
		if (filter.department != null) {
			parts.add(filter.department.toString());
		}

		tvActiveFilters.setText(String.join(" · ", parts));

		// The scope line above lists what the report covers whether or not anything was chosen;
		// this only appears when something was, which is what makes it a warning rather than
		// another line of text to read past. The session and the grouping are excluded on
		// purpose, by hasActiveFilters: those are always set and would leave it permanently lit.
		if (tvFiltersActive != null) {
			tvFiltersActive.setVisibility(
					filter.hasActiveFilters() ? View.VISIBLE : View.GONE);
		}
	}

	// ── Export ───────────────────────────────────────────────────────────

	/**
	 * The export lives on its own screen. Setting one up means choosing courses across
	 * departments and streams, and building groups from them, which needs a list that can be
	 * searched and scrolled rather than a chain of dialogs.
	 */
	private void setupExportMenu() {
		requireActivity().addMenuProvider(new MenuProvider() {

			@Override
			public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
				inflater.inflate(R.menu.menu_stats, menu);
			}

			@Override
			public boolean onMenuItemSelected(@NonNull MenuItem item) {
				if (item.getItemId() == R.id.action_export_ods) {
					Navigation.findNavController(requireView())
							.navigate(R.id.action_stats_to_export);
					return true;
				}
				return false;
			}
		}, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
	}
}
