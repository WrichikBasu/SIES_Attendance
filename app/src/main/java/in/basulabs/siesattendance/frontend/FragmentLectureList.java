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
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.CourseType;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.EntityAcademicYear;
import in.basulabs.siesattendance.backend.EntityBatchDesc;
import in.basulabs.siesattendance.backend.EntityCourse;
import in.basulabs.siesattendance.backend.EntityProfessor;
import in.basulabs.siesattendance.backend.pojo.LectureDetails;
import in.basulabs.siesattendance.backend.util.LectureFilter;
import in.basulabs.siesattendance.frontend.adapter.AdapterLecture;
import in.basulabs.siesattendance.frontend.util.DropdownBinder;
import in.basulabs.siesattendance.frontend.util.Ui;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * The record of lectures already held.
 * <p>
 * Two kinds of narrowing sit side by side. The period decides when, and keeps its own button
 * because sessions and months are picked from lists built out of the data rather than from a
 * fixed set: the month chooser only ever offers months the college was actually running. The
 * rest — course, professor, department, batch, type — are ordinary choices and live in a panel
 * that collapses, since the screen is mostly used unfiltered and a wall of dropdowns above a
 * list is a wall of dropdowns.
 * <p>
 * All of it resolves to one query. The period is a pair of dates however it was chosen, and
 * every other dimension is a nullable column comparison, so {@code DaoLecture.getFilteredDetails}
 * serves the unfiltered list and every combination alike.
 * <p>
 * The filter lives in the ViewModel rather than here, so it survives the tab being swiped away
 * and back, and the red line under the bar says the list is narrowed even when the panel is
 * closed.
 */
public class FragmentLectureList extends Fragment {

	private static final DateTimeFormatter DATE_LABEL =
			DateTimeFormatter.ofPattern("d MMM yyyy");

	private AttendanceViewModel viewModel;

	private AdapterLecture adapter;

	private TextView windowLabel, emptyMessage, countLabel, filtersActive;

	private View filterPanel;

	private MaterialButton toggleFilters;

	private AutoCompleteTextView actvCourse, actvProfessor, actvDept, actvBatch, actvCourseType;

	/** Shown as the first entry of every filter dropdown, and what selects "no restriction". */
	private String anyLabel;

	/**
	 * Loaded once and kept, because the filter chooser needs them synchronously when the user
	 * opens it. Empty until the load returns, which only suppresses the session and month
	 * options until then.
	 */
	private List<EntityAcademicYear> sessions = new ArrayList<>();

	/*
	 * The dropdown contents, kept for the same reason: the adapters are rebuilt from them, and
	 * the "filters active" line names the course or professor a filter holds the ID of.
	 */

	private List<EntityCourse> courses = new ArrayList<>();

	private List<EntityProfessor> professors = new ArrayList<>();

	private List<EntityBatchDesc> batches = new ArrayList<>();

	/** Suppresses re-queries while the dropdowns are populated in code. */
	private boolean binding;

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
	                         @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_lecture_list, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity()).get(AttendanceViewModel.class);
		anyLabel = getString(R.string.filter_any);

		windowLabel = view.findViewById(R.id.tvWindowLabel);
		emptyMessage = view.findViewById(R.id.tvEmptyLectures);
		countLabel = view.findViewById(R.id.tvLectureCount);
		filtersActive = view.findViewById(R.id.tvFiltersActive);
		filterPanel = view.findViewById(R.id.panelLectureFilters);
		toggleFilters = view.findViewById(R.id.btnToggleFilters);

		actvCourse = view.findViewById(R.id.actvCourse);
		actvProfessor = view.findViewById(R.id.actvProfessor);
		actvDept = view.findViewById(R.id.actvDept);
		actvBatch = view.findViewById(R.id.actvBatch);
		actvCourseType = view.findViewById(R.id.actvCourseType);

		RecyclerView recyclerView = view.findViewById(R.id.rvLectures);

		adapter = new AdapterLecture(new AdapterLecture.OnClick() {

			@Override
			public void onLectureClicked(LectureDetails lecture) {
				Bundle args = new Bundle();
				args.putLong("lectureID", lecture.lectureID);
				Navigation.findNavController(requireParentFragment().requireView())
						.navigate(R.id.action_coursesHub_to_attendance, args);
			}

			@Override
			public void onLectureLongPressed(LectureDetails lecture) {
				confirmDelete(lecture);
			}
		});

		recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
		recyclerView.setAdapter(adapter);

		FloatingActionButton fab = view.findViewById(R.id.fabAddLecture);
		fab.setOnClickListener(v -> Navigation.findNavController(
						requireParentFragment().requireView())
				.navigate(R.id.action_coursesHub_to_takeAttendance));

		view.findViewById(R.id.btnFilterPeriod).setOnClickListener(v -> showPeriodChooser());

		toggleFilters.setOnClickListener(v -> {
			boolean showing = filterPanel.getVisibility() == View.VISIBLE;
			filterPanel.setVisibility(showing ? View.GONE : View.VISIBLE);
			toggleFilters.setText(showing ? R.string.action_show_filters
					: R.string.action_hide_filters);
		});

		// Clears everything, period included: one button, because a half-cleared filter is
		// exactly the state that leaves someone looking at a list they think is complete.
		view.findViewById(R.id.btnClearPeriod).setOnClickListener(v -> {
			viewModel.lectureFilter().clear();
			bindFilterControls();
			viewModel.refreshLectures();
		});

		bindStaticDropdowns();

		viewModel.loadAcademicYearsSync(loaded -> {
			if (!isAdded()) {
				return;
			}
			Ui.onMain(this, () -> sessions = loaded);
		});

		viewModel.loadStatsFilterOptions((loadedCourses, loadedBatches, loadedProfessors) -> {
			if (!isAdded()) {
				return;
			}
			Ui.onMain(this, () -> {
				courses = loadedCourses;
				batches = loadedBatches;
				professors = loadedProfessors;
				bindDataDropdowns();
				updateFilterState();
			});
		});

		observe();
		bindFilterControls();

		// Publishes the filter the ViewModel already holds, which is what runs the query on
		// first open and re-runs it with whatever the filter was left as last time.
		viewModel.refreshLectures();
	}

	/**
	 * The list, its count and its heading all follow the same filter, which lives in the
	 * ViewModel so it survives the tab being swiped away and back.
	 */
	private void observe() {

		viewModel.getFilteredLectures().observe(getViewLifecycleOwner(), lectures -> {
			adapter.submitList(lectures);

			boolean empty = lectures == null || lectures.isEmpty();
			emptyMessage.setVisibility(empty ? View.VISIBLE : View.GONE);
			countLabel.setText(empty
					? ""
					: getResources().getQuantityString(R.plurals.lecture_count,
					lectures.size(), lectures.size()));
		});

		viewModel.getLectureFilterLive().observe(getViewLifecycleOwner(),
				filter -> updateFilterState());
	}

	// ── Filter controls ──────────────────────────────────────────────────

	/** The dropdowns whose contents come from enums and never change. */
	private void bindStaticDropdowns() {

		binding = true;
		LectureFilter filter = viewModel.lectureFilter();

		DropdownBinder.bind(actvDept, Arrays.asList(Department.values()), anyLabel,
				filter.department, value -> {
					viewModel.lectureFilter().department = value;
					rerun();
				});

		DropdownBinder.bind(actvCourseType, Arrays.asList(CourseType.values()), anyLabel,
				filter.courseType, value -> {
					viewModel.lectureFilter().courseType = value;
					rerun();
				});

		binding = false;
	}

	/**
	 * The dropdowns filled from the database. Rebuilt when the lists arrive, which is also when
	 * a filter already holding an ID can first be shown by name.
	 */
	private void bindDataDropdowns() {

		binding = true;
		LectureFilter filter = viewModel.lectureFilter();

		List<String> courseLabels = new ArrayList<>();
		courseLabels.add(anyLabel);
		for (EntityCourse course : courses) {
			courseLabels.add(course.courseCode + " · " + course.courseTopic);
		}
		bindIdDropdown(actvCourse, courseLabels, courseLabelFor(filter.courseID),
				position -> {
					viewModel.lectureFilter().courseID = position == 0
							? null
							: courses.get(position - 1).courseID;
					rerun();
				});

		List<String> professorLabels = new ArrayList<>();
		professorLabels.add(anyLabel);
		for (EntityProfessor professor : professors) {
			professorLabels.add(professor.name);
		}
		bindIdDropdown(actvProfessor, professorLabels, professorLabelFor(filter.professorID),
				position -> {
					viewModel.lectureFilter().professorID = position == 0
							? null
							: professors.get(position - 1).profID;
					rerun();
				});

		// Batch names repeat across sessions, so each carries its session. Two "Batch A" rows
		// are otherwise indistinguishable in the list and the wrong one gets picked.
		List<String> batchLabels = new ArrayList<>();
		batchLabels.add(anyLabel);
		for (EntityBatchDesc batch : batches) {
			batchLabels.add(batch.batchName + " · "
					+ EntityAcademicYear.labelFor(batch.academicYear));
		}
		bindIdDropdown(actvBatch, batchLabels, batchLabelFor(filter.batchID),
				position -> {
					viewModel.lectureFilter().batchID = position == 0
							? null
							: batches.get(position - 1).batchID;
					rerun();
				});

		binding = false;
	}

	/** Receives the index chosen in a dropdown, where zero is always the "any" entry. */
	private interface OnPosition {
		void onChosen(int position);
	}

	/**
	 * Fills a dropdown whose entries stand for database rows rather than enum constants, so the
	 * selection has to be carried as a position rather than a value.
	 */
	private void bindIdDropdown(@NonNull AutoCompleteTextView field,
	                            @NonNull List<String> labels, @Nullable String selected,
	                            @NonNull OnPosition callback) {

		field.setAdapter(new ArrayAdapter<>(requireContext(),
				android.R.layout.simple_list_item_1, labels));
		field.setText(selected == null ? anyLabel : selected, false);
		field.setOnItemClickListener((parent, view, position, id) -> callback.onChosen(position));
	}

	/** Puts the filter's current state onto every control, without firing a re-query. */
	private void bindFilterControls() {

		binding = true;

		LectureFilter filter = viewModel.lectureFilter();

		DropdownBinder.setSelection(actvDept, filter.department, anyLabel);
		DropdownBinder.setSelection(actvCourseType, filter.courseType, anyLabel);

		actvCourse.setText(orAny(courseLabelFor(filter.courseID)), false);
		actvProfessor.setText(orAny(professorLabelFor(filter.professorID)), false);
		actvBatch.setText(orAny(batchLabelFor(filter.batchID)), false);

		binding = false;

		updateFilterState();
	}

	@NonNull
	private String orAny(@Nullable String label) {
		return label == null ? anyLabel : label;
	}

	/** Re-runs the query unless the change came from populating a control in code. */
	private void rerun() {
		if (!binding) {
			updateFilterState();
			viewModel.refreshLectures();
		}
	}

	/**
	 * Keeps the period line and the "filters active" warning in step with the filter.
	 * <p>
	 * The warning is the reason this is called from everywhere the filter can change: the panel
	 * is usually closed, and a narrowed list is otherwise indistinguishable from a short one.
	 */
	private void updateFilterState() {

		LectureFilter filter = viewModel.lectureFilter();

		windowLabel.setText(filter.periodLabel == null
				? getString(R.string.filter_all_lectures)
				: filter.periodLabel);

		filtersActive.setVisibility(filter.hasActiveFilters() ? View.VISIBLE : View.GONE);
	}

	/*
	 * The three lookups below turn an ID the filter holds back into the label its dropdown
	 * shows. They return null when the lists have not loaded yet, which the callers read as
	 * "any": the filter is still applied, it just cannot be named until the rows arrive.
	 */

	@Nullable
	private String courseLabelFor(@Nullable Long courseID) {

		if (courseID == null) {
			return null;
		}

		for (EntityCourse course : courses) {
			if (course.courseID == courseID) {
				return course.courseCode + " · " + course.courseTopic;
			}
		}
		return null;
	}

	@Nullable
	private String professorLabelFor(@Nullable Long profID) {

		if (profID == null) {
			return null;
		}

		for (EntityProfessor professor : professors) {
			if (professor.profID == profID) {
				return professor.name;
			}
		}
		return null;
	}

	@Nullable
	private String batchLabelFor(@Nullable Long batchID) {

		if (batchID == null) {
			return null;
		}

		for (EntityBatchDesc batch : batches) {
			if (batch.batchID == batchID) {
				return batch.batchName + " · "
						+ EntityAcademicYear.labelFor(batch.academicYear);
			}
		}
		return null;
	}

	// ── Period ───────────────────────────────────────────────────────────

	/**
	 * Offers the three ways of narrowing by date. Sessions and months are both just date ranges
	 * underneath, so choosing one only has to produce a pair of dates.
	 */
	private void showPeriodChooser() {
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.title_filter_lectures)
				.setItems(new CharSequence[]{
						getString(R.string.filter_by_session),
						getString(R.string.filter_by_month),
						getString(R.string.filter_custom_range),
						getString(R.string.filter_all_lectures)
				}, (dialog, which) -> {
					switch (which) {
						case 0:
							chooseSession();
							break;
						case 1:
							chooseMonth();
							break;
						case 2:
							chooseCustomRange();
							break;
						default:
							applyPeriod(LectureFilter.Period.ALL, null, null, null);
					}
				})
				.show();
	}

	/** Narrows to one session, using the dates on the session row rather than assuming any. */
	private void chooseSession() {

		if (sessions.isEmpty()) {
			return;
		}

		CharSequence[] labels = new CharSequence[sessions.size()];
		for (int i = 0; i < sessions.size(); i++) {
			labels[i] = sessions.get(i).label();
		}

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.filter_by_session)
				.setItems(labels, (dialog, which) -> {
					EntityAcademicYear session = sessions.get(which);
					applyPeriod(LectureFilter.Period.SESSION, session.startDate,
							session.endDate, session.label());
				})
				.show();
	}

	/**
	 * Lists the months covered by the defined sessions, newest first, so the chooser only ever
	 * offers months the college was actually running.
	 */
	private void chooseMonth() {

		List<YearMonth> months = new ArrayList<>();

		for (EntityAcademicYear session : sessions) {
			YearMonth cursor = YearMonth.from(session.startDate);
			YearMonth last = YearMonth.from(session.endDate);
			while (!cursor.isAfter(last)) {
				if (!months.contains(cursor)) {
					months.add(cursor);
				}
				cursor = cursor.plusMonths(1);
			}
		}

		if (months.isEmpty()) {
			return;
		}

		// Newest first: the month someone wants is almost always a recent one.
		months.sort((a, b) -> b.compareTo(a));

		CharSequence[] labels = new CharSequence[months.size()];
		for (int i = 0; i < months.size(); i++) {
			YearMonth month = months.get(i);
			labels[i] = month.getMonth().getDisplayName(TextStyle.FULL, Locale.getDefault())
					+ " " + month.getYear();
		}

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.filter_by_month)
				.setItems(labels, (dialog, which) -> {
					YearMonth month = months.get(which);
					applyPeriod(LectureFilter.Period.MONTH, month.atDay(1),
							month.atEndOfMonth(), labels[which].toString());
				})
				.show();
	}

	/** An arbitrary pair of dates, for the cases the session and month presets do not cover. */
	private void chooseCustomRange() {

		MaterialDatePicker<androidx.core.util.Pair<Long, Long>> picker =
				MaterialDatePicker.Builder.dateRangePicker()
						.setTitleText(R.string.filter_custom_range)
						.build();

		picker.addOnPositiveButtonClickListener(selection -> {
			if (selection.first == null || selection.second == null) {
				return;
			}

			// The range picker returns UTC midnights, so they are read back in UTC to avoid
			// landing a day early either side.
			LocalDate from = Instant.ofEpochMilli(selection.first)
					.atZone(ZoneId.of("UTC")).toLocalDate();
			LocalDate to = Instant.ofEpochMilli(selection.second)
					.atZone(ZoneId.of("UTC")).toLocalDate();

			applyPeriod(LectureFilter.Period.CUSTOM, from, to,
					from.format(DATE_LABEL) + " – " + to.format(DATE_LABEL));
		});

		picker.show(getParentFragmentManager(), "lectureRange");
	}

	private void applyPeriod(@NonNull LectureFilter.Period period, @Nullable LocalDate from,
	                         @Nullable LocalDate to, @Nullable String label) {
		viewModel.lectureFilter().setPeriod(period, from, to, label);
		updateFilterState();
		viewModel.refreshLectures();
	}

	/**
	 * Deleting a lecture discards its attendance sheet too, so the count is spelled out before
	 * the user commits.
	 */
	private void confirmDelete(@NonNull LectureDetails lecture) {

		String message = lecture.isMarked()
				? getString(R.string.dialog_delete_lecture_marked, lecture.markedCount)
				: getString(R.string.dialog_delete_lecture_message);

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_delete_lecture_title)
				.setMessage(message)
				.setPositiveButton(R.string.action_delete, (d, w) ->
						viewModel.deleteLecture(lecture.lectureID, () -> {
						}))
				.setNegativeButton(R.string.action_cancel, null)
				.show();
	}
}
