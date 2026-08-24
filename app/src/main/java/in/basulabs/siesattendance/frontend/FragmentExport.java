package in.basulabs.siesattendance.frontend;

import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.EntityCourse;
import in.basulabs.siesattendance.backend.util.AttendanceExporter;
import in.basulabs.siesattendance.backend.util.CourseGroup;
import in.basulabs.siesattendance.backend.util.ExportFilter;
import in.basulabs.siesattendance.frontend.adapter.AdapterExportCourses;
import in.basulabs.siesattendance.frontend.adapter.AdapterExportGroups;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * Sets up and runs the data export.
 * <p>
 * This was a chain of four dialogs, which was the wrong shape for it. Choosing courses across
 * departments and streams needs a list you can search and scroll, and the earlier answers need
 * to stay visible while the later ones are made: a group is only meaningful once you can see
 * which courses are in scope, and the defaulter line only once you know whose figures are
 * being counted. A screen shows all of it at once and can be revised in any order.
 *
 * <h3>What each section does</h3>
 * The two kinds of narrowing are deliberately separated, because they are easy to confuse:
 * dates decide which lectures count and so change the numbers, while the course list decides
 * which columns appear. Students are not filtered directly; whoever is on the roll of a
 * selected course appears. Nothing chosen anywhere means the whole session.
 */
public class FragmentExport extends Fragment {

	private AttendanceViewModel viewModel;

	private final ExportFilter filter = new ExportFilter();

	private final List<CourseGroup> groups = new ArrayList<>();

	private final Set<Long> selectedCourseIDs = new LinkedHashSet<>();

	private List<EntityCourse> allCourses = new ArrayList<>();

	private AdapterExportCourses courseAdapter;

	private AdapterExportGroups groupAdapter;

	private TextInputEditText fromField, toField;

	private TextView scopeSummary, noCourses;

	private LinearProgressIndicator progress;

	private MaterialButton runExport;

	/**
	 * The defaulter line, or {@code AttendanceExporter.NO_THRESHOLD} for no shading at all.
	 * Defaults to the 75% most universities use.
	 */
	private int thresholdPercent = 75;

	private static final DateTimeFormatter DATE_LABEL =
			DateTimeFormatter.ofPattern("d MMM yyyy");

	/** The thresholds offered, with the sentinel for no shading last. */
	private static final int[] THRESHOLDS =
			{50, 60, 75, 80, AttendanceExporter.NO_THRESHOLD};

	private final ActivityResultLauncher<String> picker = registerForActivityResult(
			new ActivityResultContracts.CreateDocument(
					"application/vnd.oasis.opendocument.spreadsheet"), uri -> {
				if (uri != null) {
					startExport(uri);
				}
			});

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
	                         @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_export, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity()).get(AttendanceViewModel.class);

		bindDates(view);
		bindCourses(view);
		bindThreshold(view);
		bindGroups(view);

		scopeSummary = view.findViewById(R.id.tvScopeSummary);
		progress = view.findViewById(R.id.progressExport);
		runExport = view.findViewById(R.id.btnRunExport);

		runExport.setOnClickListener(v -> picker.launch(
				AttendanceExporter.suggestedFileName(viewModel.academicYearValue())));

		observeResult();
		refreshSummary();
	}

	// ── Dates ────────────────────────────────────────────────────────────

	/**
	 * The date window, which narrows the lectures counted and so changes the figures in the
	 * cells. Distinct from the course list, which changes only which columns appear.
	 */
	private void bindDates(@NonNull View view) {

		TextInputLayout fromLayout = view.findViewById(R.id.tilFrom);
		TextInputLayout toLayout = view.findViewById(R.id.tilTo);

		fromField = view.findViewById(R.id.etFrom);
		toField = view.findViewById(R.id.etTo);

		fromField.setOnClickListener(v -> pickDate(true));
		toField.setOnClickListener(v -> pickDate(false));

		// An explicit clear, rather than the long-press the dialog version relied on. A bound
		// set by accident should be as easy to undo as it was to set.
		fromLayout.setEndIconOnClickListener(v -> {
			filter.from = null;
			fromField.setText("");
			refreshSummary();
		});
		toLayout.setEndIconOnClickListener(v -> {
			filter.to = null;
			toField.setText("");
			refreshSummary();
		});
	}

	private void pickDate(boolean isFrom) {

		LocalDate current = isFrom ? filter.from : filter.to;

		MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
				.setTitleText(isFrom ? R.string.hint_from_date : R.string.hint_to_date)
				.setSelection(current == null
						? MaterialDatePicker.todayInUtcMilliseconds()
						: current.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
				.build();

		picker.addOnPositiveButtonClickListener(selection -> {

			LocalDate chosen = Instant.ofEpochMilli(selection)
					.atZone(ZoneId.of("UTC")).toLocalDate();

			if (isFrom) {
				filter.from = chosen;
				fromField.setText(chosen.format(DATE_LABEL));
			} else {
				filter.to = chosen;
				toField.setText(chosen.format(DATE_LABEL));
			}
			refreshSummary();
		});

		picker.show(getParentFragmentManager(), isFrom ? "exportFrom" : "exportTo");
	}

	// ── Courses ──────────────────────────────────────────────────────────

	/** The course picker, with its search box and select-all covering whatever the search leaves. */
	private void bindCourses(@NonNull View view) {

		RecyclerView list = view.findViewById(R.id.rvCourses);
		noCourses = view.findViewById(R.id.tvNoCourses);

		courseAdapter = new AdapterExportCourses(selectedCourseIDs, this::onCourseSelectionChanged);
		list.setLayoutManager(new LinearLayoutManager(requireContext()));
		list.setAdapter(courseAdapter);

		TextInputEditText search = view.findViewById(R.id.etCourseSearch);

		search.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int a, int b, int c) {
			}

			@Override
			public void onTextChanged(CharSequence s, int a, int b, int c) {
			}

			@Override
			public void afterTextChanged(Editable s) {
				courseAdapter.filter(s.toString());
				noCourses.setVisibility(courseAdapter.visibleCount() == 0
						? View.VISIBLE : View.GONE);
			}
		});

		// Select all applies to what the search is showing, so "type CS, select all" picks a
		// department's courses without touching anything else.
		view.findViewById(R.id.btnSelectAllCourses)
				.setOnClickListener(v -> courseAdapter.setAllVisible(true));
		view.findViewById(R.id.btnClearCourses)
				.setOnClickListener(v -> courseAdapter.setAllVisible(false));

		viewModel.loadCoursesSync(courses -> {

			if (courses == null || !isAdded()) {
				return;
			}

			requireActivity().runOnUiThread(() -> {
				if (!isAdded()) {
					return;
				}
				allCourses = courses;
				courseAdapter.submit(courses);
				noCourses.setVisibility(courses.isEmpty() ? View.VISIBLE : View.GONE);
				onCourseSelectionChanged();
			});
		});
	}

	/**
	 * Keeps the groups and the summary in step with the selection. Groups are rebuilt from the
	 * courses now in scope, so one can never total a course the export does not cover.
	 */
	private void onCourseSelectionChanged() {

		filter.courseIDs.clear();
		filter.courseIDs.addAll(selectedCourseIDs);

		if (groupAdapter != null) {
			groupAdapter.setInScope(coursesInScope());
		}
		refreshSummary();
	}

	/**
	 * The courses a group can be built from: the ones explicitly ticked above, and nothing
	 * else.
	 * <p>
	 * Note this is not the same as what the export covers. Ticking nothing exports every
	 * course, but it does not fill the group cards with the whole register: a group is a
	 * deliberate subset, and offering fifty chips to pick three from is worse than asking for
	 * the three to be selected first. With nothing ticked the cards say so instead.
	 */
	@NonNull
	private List<EntityCourse> coursesInScope() {

		List<EntityCourse> scope = new ArrayList<>();

		for (EntityCourse course : allCourses) {
			if (selectedCourseIDs.contains(course.courseID)) {
				scope.add(course);
			}
		}
		return scope;
	}

	// ── Threshold ────────────────────────────────────────────────────────

	/** The defaulter threshold control, including the entry that turns shading off entirely. */
	private void bindThreshold(@NonNull View view) {

		ChipGroup group = view.findViewById(R.id.cgThreshold);

		for (int threshold : THRESHOLDS) {

			Chip chip = new Chip(requireContext());
			chip.setText(threshold == AttendanceExporter.NO_THRESHOLD
					? getString(R.string.label_no_highlighting)
					: getString(R.string.label_threshold_option, threshold));
			chip.setCheckable(true);
			chip.setChecked(threshold == thresholdPercent);

			chip.setOnClickListener(v -> {
				thresholdPercent = threshold;
				refreshSummary();
			});

			group.addView(chip);
		}
	}

	// ── Groups ───────────────────────────────────────────────────────────

	/**
	 * The course groups, each of which becomes its own sheet with its own total and defaulter
	 * list. A group's percentage is not derivable from the overall one, which is why they are
	 * computed separately rather than summarised from the main sheet.
	 */
	private void bindGroups(@NonNull View view) {

		RecyclerView list = view.findViewById(R.id.rvGroups);

		groupAdapter = new AdapterExportGroups(groups, coursesInScope(),
				new AdapterExportGroups.Callbacks() {

					@Override
					public void onRemove(int position) {
						groups.remove(position);
						groupAdapter.notifyItemRemoved(position);
						groupAdapter.notifyItemRangeChanged(position, groups.size() - position);
						refreshSummary();
					}

					@Override
					public void onChanged() {
						refreshSummary();
					}
				});

		list.setLayoutManager(new LinearLayoutManager(requireContext()));
		list.setAdapter(groupAdapter);

		view.findViewById(R.id.btnAddGroup).setOnClickListener(v -> {
			groups.add(new CourseGroup(
					getString(R.string.label_default_group_name, groups.size() + 1)));
			groupAdapter.notifyItemInserted(groups.size() - 1);
			refreshSummary();
		});
	}

	// ── Summary and run ──────────────────────────────────────────────────

	/** Restates what the export will actually contain, so the scope is visible before it is run. */
	private void refreshSummary() {

		StringBuilder out = new StringBuilder(filter.describe());

		int usable = 0;
		for (CourseGroup group : groups) {
			if (group.isUsable()) {
				usable++;
			}
		}

		if (usable > 0) {
			out.append("\n").append(getResources().getQuantityString(
					R.plurals.label_group_sheets, usable, usable));
		}

		out.append("\n").append(thresholdPercent == AttendanceExporter.NO_THRESHOLD
				? getString(R.string.label_summary_no_shading)
				: getString(R.string.label_summary_threshold, thresholdPercent));

		scopeSummary.setText(out.toString());
	}

	/** Runs the export off the main thread; the file can take a moment to assemble and write. */
	private void startExport(@NonNull Uri destination) {

		// Empty groups would produce empty sheets. Dropped rather than refused: leaving one
		// blank is a clear enough statement that it was not wanted.
		List<CourseGroup> usable = new ArrayList<>();

		for (CourseGroup group : groups) {
			if (group.isUsable()) {
				usable.add(group);
			}
		}

		progress.setVisibility(View.VISIBLE);
		runExport.setEnabled(false);

		viewModel.exportAttendanceSheet(destination, thresholdPercent, usable, filter);
	}

	/** Reports what was written, or why nothing was. */
	private void observeResult() {

		viewModel.getExportResult().observe(getViewLifecycleOwner(), summary -> {

			if (summary == null) {
				return;
			}
			viewModel.clearExportResult();

			progress.setVisibility(View.GONE);
			runExport.setEnabled(true);

			new MaterialAlertDialogBuilder(requireContext())
					.setTitle(R.string.dialog_data_export_done_title)
					.setMessage(getString(R.string.message_ods_export_done,
							summary.students, summary.courses, summary.defaulters))
					.setPositiveButton(android.R.string.ok, null)
					.show();
		});

		// A failure arrives as a toast from the ViewModel, so the controls have to be put back
		// on any change of the busy flag rather than only on success.
		viewModel.isBusy().observe(getViewLifecycleOwner(), busy -> {
			if (busy == null || !busy) {
				progress.setVisibility(View.GONE);
				runExport.setEnabled(true);
			}
		});
	}
}
