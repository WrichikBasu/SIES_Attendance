package in.basulabs.siesattendance.frontend;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import androidx.lifecycle.LiveData;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import in.basulabs.siesattendance.R;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import in.basulabs.siesattendance.backend.CourseType;
import in.basulabs.siesattendance.backend.EntityAcademicYear;
import in.basulabs.siesattendance.backend.EntityCourse;
import in.basulabs.siesattendance.backend.pojo.DeletionImpact;
import in.basulabs.siesattendance.backend.pojo.StudentCourseStat;
import in.basulabs.siesattendance.backend.pojo.StudentWithYear;
import in.basulabs.siesattendance.backend.util.BreakdownFilter;
import in.basulabs.siesattendance.frontend.adapter.AdapterStudentCourseStat;
import in.basulabs.siesattendance.frontend.util.DropdownBinder;
import in.basulabs.siesattendance.frontend.util.Ui;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * One student's record: identity, class, and a per-course attendance breakdown. Edit and delete
 * live in the toolbar overflow.
 * <p>
 * Each course in the breakdown opens onto the dates behind its fraction; see
 * {@link FragmentStudentCourseDates}.
 * <p>
 * The breakdown covers the student's whole record by default, which is the honest default for a
 * record of a person rather than of a year, and the wrong answer to most of what gets asked of
 * it: whether attendance fell off after the mid-terms, what last session looked like, whether
 * the shortfall is in the practicals. The filter above the list narrows it, and the overall
 * figure in the card above is recomputed from the same rows, so the headline always describes
 * whatever is on screen. The window travels down into the per-course dates as well —
 * see {@link #openCourseDates} for why it has to.
 * <p>
 * The chips in that filter also pick which courses the breakdown covers, several at a time. With
 * two or three ticked the card above stops being the student's overall figure and becomes their
 * combined figure across those courses — which is not the average of the rows, since the courses
 * have different numbers of lectures behind them, and is the number a student short of the
 * threshold in one paper actually needs.
 */
public class FragmentStudentDetail extends Fragment {

	private AttendanceViewModel viewModel;

	private AdapterStudentCourseStat adapter;

	private TextView initials, name, rollNo, classLabel, pronouns, email,
			overallPercentage, overallDetail, emptyBreakdown;

	private ProgressBar overallBar;

	private TextView breakdownScope;

	/** The red line saying the breakdown below is not the student's whole record. */
	private TextView breakdownFiltersActive;

	/**
	 * The same warning against the headline figure itself. The card and the filter controls sit
	 * far enough apart that the percentage can be read without the line beside them ever coming
	 * into view, and a narrowed percentage read as a whole one is the mistake this screen makes
	 * easiest.
	 */
	private TextView overallFiltered;

	private TextInputEditText fromField, toField;

	private ChipGroup windowChips;

	/**
	 * One checkable chip per course this student has been marked in. Ticking several shows
	 * their attendance as one figure in the card above.
	 */
	private ChipGroup courseChips;

	/** The heading that folds the chips away, and what it says when they are folded. */
	private View courseChipsRow;

	private TextView courseChipsCount;

	private ImageView courseChipsChevron;

	/**
	 * Whether the chips are unrolled. Folded to begin with: a student can carry a dozen courses
	 * across their years, and a dozen chips is most of a panel to scroll past before reaching
	 * the figures they filter. A selection already in force unrolls them, since a control the
	 * user has set should be visible where they set it.
	 */
	private boolean courseChipsExpanded;

	/** The courses behind those chips, read once when the screen opens. */
	private final List<EntityCourse> studentCourses = new ArrayList<>();

	private View filterPanel;

	private MaterialButton clearFilter;

	private long studentID;

	/**
	 * The session's own row, needed by the "This session" preset to resolve to real dates.
	 * Null until it has been read, and the preset leaves the window open rather than guessing
	 * if it is asked for before then.
	 */
	@Nullable
	private EntityAcademicYear session;

	/**
	 * The LiveData currently feeding the breakdown, detached when the window changes.
	 */
	@Nullable
	private LiveData<List<StudentCourseStat>> breakdownSource;

	/**
	 * Suppresses re-queries while the controls are filled in from the filter.
	 */
	private boolean binding;

	private static final DateTimeFormatter DATE_LABEL =
			DateTimeFormatter.ofPattern("d MMM yyyy");

	/**
	 * Stands for an open end of the window in a navigation argument, which cannot hold a
	 * nullable long. No real lecture predates 1970, so a negative epoch is unambiguous.
	 */
	public static final long NO_BOUND = Long.MIN_VALUE;

	/**
	 * The session this screen is showing the student in.
	 * <p>
	 * Supplied by whoever opened it, because the two routes here mean different years: the
	 * students list has a session of its own, and a drill-down from the report has another.
	 * Everything on the screen — the header, the deletion counts, the withdrawal and the edit
	 * form — uses this one value, so they cannot disagree about which year is on show.
	 */
	private int academicYear;

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
	                         @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_student_detail, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity()).get(AttendanceViewModel.class);
		studentID = getArguments() == null ? -1L : getArguments().getLong("studentID", -1L);

		// Absent when the students list opened this, which is already showing the session it
		// wants; the report passes its own when it has one selected.
		int requested = getArguments() == null ? 0 : getArguments().getInt("academicYear", 0);
		academicYear = requested > 0 ? requested : viewModel.studentFilter().academicYear;

		initials = view.findViewById(R.id.tvInitials);
		name = view.findViewById(R.id.tvStudentName);
		rollNo = view.findViewById(R.id.tvRollNo);
		classLabel = view.findViewById(R.id.tvClassLabel);
		pronouns = view.findViewById(R.id.tvPronouns);
		email = view.findViewById(R.id.tvEmail);
		overallPercentage = view.findViewById(R.id.tvOverallPercentage);
		overallDetail = view.findViewById(R.id.tvOverallDetail);
		overallBar = view.findViewById(R.id.progressOverall);
		overallFiltered = view.findViewById(R.id.tvOverallFiltered);
		emptyBreakdown = view.findViewById(R.id.tvEmptyBreakdown);

		RecyclerView recyclerView = view.findViewById(R.id.rvCourseBreakdown);
		adapter = new AdapterStudentCourseStat(this::openCourseDates);
		recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
		recyclerView.setNestedScrollingEnabled(false);
		recyclerView.setAdapter(adapter);

		setupFilter(view);
		setupMenu();
		observe();
		loadSession();
	}

	// ── The breakdown's filter ───────────────────────────────────────────

	@NonNull
	private BreakdownFilter filter() {
		return viewModel.breakdownFilter(studentID);
	}

	/**
	 * Wires the window presets, the two date fields and the course type.
	 * <p>
	 * The chips and the date fields are two views of one window rather than two filters: a chip
	 * fills the dates in so the range it stands for is visible and can then be adjusted, and
	 * setting a date by hand moves the selection to Custom, because the dates no longer match
	 * what the chip claims.
	 */
	private void setupFilter(@NonNull View view) {

		breakdownScope = view.findViewById(R.id.tvBreakdownScope);
		filterPanel = view.findViewById(R.id.panelBreakdownFilter);
		windowChips = view.findViewById(R.id.cgBreakdownWindow);
		breakdownFiltersActive = view.findViewById(R.id.tvBreakdownFiltersActive);
		fromField = view.findViewById(R.id.etBreakdownFrom);
		toField = view.findViewById(R.id.etBreakdownTo);
		clearFilter = view.findViewById(R.id.btnBreakdownClear);
		courseChips = view.findViewById(R.id.cgBreakdownCourses);
		courseChipsRow = view.findViewById(R.id.rowBreakdownCourses);
		courseChipsCount = view.findViewById(R.id.tvBreakdownCoursesCount);
		courseChipsChevron = view.findViewById(R.id.ivBreakdownCoursesChevron);

		courseChipsRow.setOnClickListener(v -> {
			courseChipsExpanded = !courseChipsExpanded;
			bindCourseChipsSection();
		});

		view.findViewById(R.id.btnBreakdownFilter).setOnClickListener(v -> {
			boolean showing = filterPanel.getVisibility() == View.VISIBLE;
			filterPanel.setVisibility(showing ? View.GONE : View.VISIBLE);
		});

		clearFilter.setOnClickListener(v -> {
			filter().clear();
			// Clearing puts the course type back to every type, so the chips it had hidden
			// come back with it.
			buildCourseChips();
			bindFilterControls();
			refreshBreakdown();
		});

		for (BreakdownFilter.Window preset : BreakdownFilter.Window.values()) {

			// Custom is not offered as a chip. It is not something to choose, it is what the
			// selection becomes once a date is set by hand, and a chip for it would be one the
			// user could tap with nothing happening.
			if (preset == BreakdownFilter.Window.CUSTOM) {
				continue;
			}

			Chip chip = new Chip(requireContext());
			chip.setText(preset.label);
			chip.setCheckable(true);
			chip.setId(View.generateViewId());
			chip.setTag(preset);
			windowChips.addView(chip);
		}

		windowChips.setOnCheckedStateChangeListener((group, checkedIds) -> {

			if (binding || checkedIds.isEmpty()) {
				return;
			}

			Chip chip = group.findViewById(checkedIds.get(0));
			filter().apply((BreakdownFilter.Window) chip.getTag(), session);
			bindFilterControls();
			refreshBreakdown();
		});

		fromField.setOnClickListener(v -> pickDate(true));
		toField.setOnClickListener(v -> pickDate(false));

		bindClearIcon(view.findViewById(R.id.tilBreakdownFrom), true);
		bindClearIcon(view.findViewById(R.id.tilBreakdownTo), false);

		DropdownBinder.bind(view.findViewById(R.id.actvBreakdownCourseType),
				Arrays.asList(CourseType.values()), getString(R.string.filter_any),
				filter().courseType, value -> {
					filter().courseType = value;

					// The chips are the courses of the chosen type, so choosing one rebuilds
					// them and drops whatever no longer belongs; see reconcileCourseSelection.
					reconcileCourseSelection();
					buildCourseChips();

					bindFilterControls();
					refreshBreakdown();
				});

		loadCourseChips();
		bindFilterControls();
	}

	/**
	 * Reads the student's courses, which are what the chips are built from.
	 * <p>
	 * Read once from the whole record rather than derived from the breakdown currently on
	 * screen, which is filtered by these very chips: taking them from it would make a course
	 * vanish the moment the window it falls outside was chosen, and with it the only way to
	 * deselect it. The course type is the one filter the chips do follow, and it is applied
	 * when they are built rather than when they are read; see {@link #buildCourseChips()}.
	 */
	private void loadCourseChips() {

		viewModel.loadStudentCourses(studentID, loaded -> {

			if (loaded == null || !isAdded()) {
				return;
			}

			Ui.onMain(this, () -> {
				studentCourses.clear();
				studentCourses.addAll(loaded);
				buildCourseChips();
				bindFilterControls();
			});
		});
	}

	/**
	 * One chip per course of the chosen type, checkable and multi-select: the point is to see
	 * two or three of them together, which is a question no single-select control can ask.
	 * <p>
	 * Nothing ticked means every course, so the chips start clear rather than all lit. A
	 * student with no attendance at all — or none of the chosen type — gets no chips and no
	 * heading, since an empty row of controls is worse than none.
	 */
	private void buildCourseChips() {

		courseChips.removeAllViews();

		// A selection made before this screen was opened — a report drilled into, or the filter
		// this student was last looked at under — is a restriction in force, so the chips
		// holding it start unrolled rather than folded away behind a heading.
		courseChipsExpanded = courseChipsExpanded || !filter().courseIDs.isEmpty();

		for (EntityCourse course : studentCourses) {

			// A type narrows what the chips offer as well as what the breakdown counts. Two
			// controls that disagree — a practicals-only breakdown beside a chip for a theory
			// paper — would leave a chip that empties the list when tapped.
			if (!matchesChosenType(course)) {
				continue;
			}

			Chip chip = new Chip(requireContext());
			chip.setText(course.courseCode);
			chip.setCheckable(true);
			chip.setId(View.generateViewId());
			chip.setTag(course.courseID);
			chip.setChecked(filter().courseIDs.contains(course.courseID));

			chip.setOnCheckedChangeListener((button, isChecked) -> {

				// The same listener fires when the controls are filled in from the filter,
				// which would re-query the breakdown that is being displayed.
				if (binding) {
					return;
				}

				if (isChecked) {
					filter().courseIDs.add(course.courseID);
				} else {
					filter().courseIDs.remove(course.courseID);
				}

				bindFilterControls();
				refreshBreakdown();
			});

			courseChips.addView(chip);
		}

		bindCourseChipsSection();
	}

	/**
	 * Folds the chips in or out, and says on the heading what is behind the fold.
	 * <p>
	 * The count matters more than the chevron: a filter that is hiding is worse than one taking
	 * up room, so a folded selection is named on the line that folded it. Nothing selected says
	 * so too, since "every course" is the thing the figures below are over.
	 * <p>
	 * A student with no attendance at all gets no heading and no chips, an empty row of
	 * controls being worse than none.
	 */
	private void bindCourseChipsSection() {

		// Counted from the chips on screen rather than from the student's courses, because a
		// course type may have left none of them to show.
		boolean any = courseChips.getChildCount() > 0;
		courseChipsRow.setVisibility(any ? View.VISIBLE : View.GONE);
		courseChips.setVisibility(any && courseChipsExpanded ? View.VISIBLE : View.GONE);

		int selected = filter().courseIDs.size();
		courseChipsCount.setText(selected == 0
				? getString(R.string.label_every_course)
				: getResources().getQuantityString(R.plurals.count_courses_selected,
				selected, selected));

		// Right for folded, down for unrolled: one drawable, turned, rather than two that
		// could drift apart.
		courseChipsChevron.setRotation(courseChipsExpanded ? 90f : 0f);
		courseChipsChevron.setContentDescription(getString(courseChipsExpanded
				? R.string.action_hide_courses : R.string.action_show_courses));
	}

	/** Whether a course survives the chosen course type. Every course does when none is set. */
	private boolean matchesChosenType(@NonNull EntityCourse course) {
		CourseType chosen = filter().courseType;
		return chosen == null || course.courseType == chosen;
	}

	/**
	 * Drops any chosen course that the course type has just excluded.
	 * <p>
	 * The alternative is a restriction in force with no control showing it: the breakdown would
	 * be narrowed to a course whose chip is no longer on the screen, and nothing on the screen
	 * would say which. Dropping it is the same rule the report applies to a division left over
	 * from another stream — see {@code AcademicRules.reconcileDivision}.
	 * <p>
	 * Skipped while the courses are still being read, since nothing can be said about which
	 * type a course is before its row has arrived.
	 */
	private void reconcileCourseSelection() {

		if (studentCourses.isEmpty()) {
			return;
		}

		Set<Long> kept = new LinkedHashSet<>();

		for (EntityCourse course : studentCourses) {
			if (matchesChosenType(course) && filter().courseIDs.contains(course.courseID)) {
				kept.add(course.courseID);
			}
		}

		filter().courseIDs.clear();
		filter().courseIDs.addAll(kept);
	}

	/**
	 * The cross on a date field clears that bound. An explicit clear, because a bound set by
	 * accident should be as easy to undo as it was to set.
	 */
	private void bindClearIcon(@NonNull TextInputLayout layout, boolean isFrom) {

		layout.setEndIconOnClickListener(v -> {
			BreakdownFilter filter = filter();

			if (isFrom) {
				filter.from = null;
			} else {
				filter.to = null;
			}

			// Clearing one end still leaves a window if the other is set, and either way the
			// range is no longer the one any preset stands for.
			filter.window = filter.hasDateWindow()
					? BreakdownFilter.Window.CUSTOM
					: BreakdownFilter.Window.ALL_TIME;

			bindFilterControls();
			refreshBreakdown();
		});
	}

	/**
	 * Opens the calendar. The selection comes back as UTC midnight, which is what the Material
	 * picker documents, so it is converted at UTC rather than at the device zone: the local zone
	 * shifts the date back a day for anyone east of Greenwich, which is everyone using this.
	 */
	private void pickDate(boolean isFrom) {

		LocalDate current = isFrom ? filter().from : filter().to;

		MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
				.setTitleText(isFrom ? R.string.hint_from_date : R.string.hint_to_date)
				.setSelection(current == null
						? MaterialDatePicker.todayInUtcMilliseconds()
						: current.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
				.build();

		picker.addOnPositiveButtonClickListener(selection -> {

			LocalDate chosen = Instant.ofEpochMilli(selection)
					.atZone(ZoneId.of("UTC")).toLocalDate();

			BreakdownFilter filter = filter();

			if (isFrom) {
				filter.from = chosen;
			} else {
				filter.to = chosen;
			}
			filter.window = BreakdownFilter.Window.CUSTOM;

			bindFilterControls();
			refreshBreakdown();
		});

		picker.show(getParentFragmentManager(), isFrom ? "breakdownFrom" : "breakdownTo");
	}

	/**
	 * Puts the filter's current state onto the controls and the scope line.
	 */
	private void bindFilterControls() {

		BreakdownFilter filter = filter();

		binding = true;

		fromField.setText(filter.from == null ? "" : filter.from.format(DATE_LABEL));
		toField.setText(filter.to == null ? "" : filter.to.format(DATE_LABEL));

		windowChips.clearCheck();

		for (int i = 0; i < windowChips.getChildCount(); i++) {
			Chip chip = (Chip) windowChips.getChildAt(i);
			chip.setChecked(chip.getTag() == filter.window);
		}

		for (int i = 0; i < courseChips.getChildCount(); i++) {
			Chip chip = (Chip) courseChips.getChildAt(i);
			chip.setChecked(filter.courseIDs.contains((Long) chip.getTag()));
		}

		binding = false;

		bindCourseChipsSection();

		breakdownScope.setText(filter.describe());
		clearFilter.setVisibility(filter.isUnrestricted() ? View.GONE : View.VISIBLE);

		// The percentages under this line are computed over the filtered rows, so a narrowed
		// breakdown has to announce itself: 40% of one month reads exactly like 40% of a career.
		if (breakdownFiltersActive != null) {
			breakdownFiltersActive.setVisibility(
					filter.isUnrestricted() ? View.GONE : View.VISIBLE);
		}

		// And again beside the figure itself, which is the thing that gets quoted.
		if (overallFiltered != null) {
			overallFiltered.setVisibility(
					filter.isUnrestricted() ? View.GONE : View.VISIBLE);
		}
	}

	/**
	 * Reads the session row behind the "This session" preset.
	 * <p>
	 * Loaded once and kept, rather than being read when the preset is tapped, so tapping it does
	 * not have to wait on a round trip before the list changes.
	 */
	private void loadSession() {

		viewModel.loadAcademicYearsSync(sessions -> {

			if (sessions == null || !isAdded()) {
				return;
			}

			Ui.onMain(this, () -> {
				for (EntityAcademicYear year : sessions) {
					if (year.academicYear == academicYear) {
						session = year;
						break;
					}
				}

				// A window already set to this session was resolved before the row arrived, so
				// it is open rather than bounded. Resolve it now the dates are known.
				if (session != null && filter().window == BreakdownFilter.Window.SESSION
						&& !filter().hasDateWindow()) {
					filter().apply(BreakdownFilter.Window.SESSION, session);
					bindFilterControls();
					refreshBreakdown();
				}
			});
		});
	}

	/**
	 * Re-runs the breakdown against the current window.
	 * <p>
	 * Each window makes a fresh LiveData from the DAO, so the previous one is detached first;
	 * two observers writing into the same adapter would otherwise fight over it.
	 */
	private void refreshBreakdown() {

		if (breakdownSource != null) {
			breakdownSource.removeObservers(getViewLifecycleOwner());
		}

		breakdownSource = viewModel.getStudentCourseBreakdown(studentID, filter());
		breakdownSource.observe(getViewLifecycleOwner(), this::bindBreakdown);
	}

	/**
	 * Opens the dates behind one course's fraction.
	 * <p>
	 * The course code and topic travel as arguments rather than being looked up again on the
	 * next screen: they are already on this row, and re-querying them would leave the heading
	 * blank for as long as the round trip took.
	 * <p>
	 * The window travels with them, and has to: the row says 8 of 11, and a list that ignored
	 * the window would show every lecture of the course under a fraction that counted only some
	 * of them. The bounds go as epoch seconds because navigation arguments have no nullable
	 * long, with {@link #NO_BOUND} standing in for an open end.
	 */
	private void openCourseDates(@NonNull StudentCourseStat stat) {

		BreakdownFilter filter = filter();

		Bundle args = new Bundle();
		args.putLong("studentID", studentID);
		args.putLong("courseID", stat.courseID);
		args.putInt("academicYear", academicYear);
		args.putString("courseCode", stat.courseCode);
		args.putString("courseTopic", stat.courseTopic);
		args.putLong("fromEpoch", filter.fromEpoch() == null
				? NO_BOUND : filter.fromEpoch());
		args.putLong("toEpoch", filter.toEpoch() == null
				? NO_BOUND : filter.toEpoch());
		args.putString("windowLabel",
				filter.hasDateWindow() ? filter.describe() : null);

		Navigation.findNavController(requireView())
				.navigate(R.id.action_studentDetail_to_studentCourseDates, args);
	}

	/**
	 * Adds the edit and delete items to the toolbar.
	 * <p>
	 * Registered against the view lifecycle and only while RESUMED, so the items disappear with
	 * the screen. Registering against the activity without that bound leaves the menu behind
	 * after navigating away, and the actions would then run against a student no longer shown.
	 */
	private void setupMenu() {
		requireActivity().addMenuProvider(new MenuProvider() {

			@Override
			public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
				inflater.inflate(R.menu.menu_edit, menu);
			}

			@Override
			public boolean onMenuItemSelected(@NonNull MenuItem item) {
				if (item.getItemId() == R.id.action_edit) {
					Bundle args = new Bundle();
					args.putLong("studentID", studentID);
					args.putInt("academicYear", academicYear);
					Navigation.findNavController(requireView())
							.navigate(R.id.action_studentDetail_to_editStudent, args);
					return true;
				}
				if (item.getItemId() == R.id.action_delete) {
					askHowToRemove();
					return true;
				}
				return false;
			}
		}, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
	}

	/**
	 * Works out what removing this student would cost, then offers only the choices that make
	 * sense for them.
	 * <p>
	 * A student in several sessions can be taken off this one and left intact elsewhere, which
	 * is what withdrawal usually means in practice. A student in a single session has no such
	 * middle ground: removing that enrolment would leave a record nothing points at, so the
	 * only honest option is to delete them.
	 */
	private void askHowToRemove() {

		viewModel.loadDeletionImpact(studentID, academicYear, impact -> {

			if (!isAdded() || impact == null) {
				return;
			}

			Ui.onMain(this, () -> {
				if (impact.spansMultipleSessions()) {
					offerWithdrawOrDelete(impact);
				} else {
					confirmDelete(impact);
				}
			});
		});
	}

	/**
	 * The choice offered for a student enrolled in more than one session: take them off this one,
	 * or remove them entirely.
	 */
	private void offerWithdrawOrDelete(@NonNull DeletionImpact impact) {

		String[] options = {
				getString(R.string.option_withdraw_from_session),
				getString(R.string.option_delete_student_everywhere)
		};

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_remove_student_title)
				.setItems(options, (d, which) -> {
					if (which == 0) {
						confirmWithdraw(impact);
					} else {
						confirmDelete(impact);
					}
				})
				.setNegativeButton(R.string.action_cancel, null)
				.show();
	}

	/**
	 * Withdrawal keeps the student and their attendance, and removes only this session's
	 * enrolment and batch memberships. The count quoted is what stays, not what goes.
	 */
	private void confirmWithdraw(@NonNull DeletionImpact impact) {

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_withdraw_title)
				.setMessage(getString(R.string.dialog_withdraw_message,
						impact.attendanceThisSession))
				.setNegativeButton(R.string.action_cancel, null)
				.setPositiveButton(R.string.action_withdraw, (d, w) ->
						viewModel.withdrawStudentFromSession(studentID, academicYear,
								this::leaveScreen))
				.show();
	}

	/**
	 * The last stop before the data goes. The message quotes the counts rather than warning in
	 * the abstract, because "this deletes 214 attendance records" is a different decision from
	 * "this deletes a student with no history yet".
	 */
	private void confirmDelete(@NonNull DeletionImpact impact) {

		String message = impact.hasHistory()
				? getString(R.string.dialog_delete_student_message_with_history,
				impact.attendanceRecords, impact.enrolments)
				: getString(R.string.dialog_delete_student_message_clean);

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_delete_student_title)
				.setMessage(message)
				.setNegativeButton(R.string.action_cancel, null)
				.setPositiveButton(R.string.action_delete, (d, w) -> {
					// A student with no attendance is a correction and goes in one step. One
					// with history gets asked twice, because there is no undo and no backup
					// unless the user happened to make one.
					if (impact.hasHistory()) {
						confirmDeleteAgain(impact);
					} else {
						viewModel.deleteStudent(studentID, this::leaveScreen);
					}
				})
				.show();
	}

	/**
	 * The second confirmation, shown only when there is history to lose.
	 */
	private void confirmDeleteAgain(@NonNull DeletionImpact impact) {

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_delete_student_confirm_title)
				.setMessage(getString(R.string.dialog_delete_student_confirm_message,
						impact.attendanceRecords))
				.setNegativeButton(R.string.action_cancel, null)
				.setPositiveButton(R.string.action_delete_permanently, (d, w) ->
						viewModel.deleteStudent(studentID, this::leaveScreen))
				.show();
	}

	/**
	 * The student this screen is showing is gone, so there is nothing left to display.
	 */
	private void leaveScreen() {
		Ui.onMain(this, () -> {
			Navigation.findNavController(requireView()).navigateUp();
		});
	}

	/**
	 * The identity and the breakdown come from separate queries, so the header fills in as soon
	 * as the student is read rather than waiting for the attendance to be totalled.
	 */
	private void observe() {

		viewModel.getStudent(studentID, academicYear)
				.observe(getViewLifecycleOwner(), this::bindStudent);

		refreshBreakdown();
	}

	/**
	 * Fills in the header. A null student means the record has gone, which happens briefly after
	 * a delete while the screen is still being dismissed, so the previous values are left in
	 * place rather than blanked.
	 */
	private void bindStudent(@Nullable StudentWithYear student) {
		if (student == null) {
			return;
		}

		initials.setText(student.initials());
		name.setText(student.displayName());
		rollNo.setText(student.rollNo);
		classLabel.setText(student.classLabel());
		pronouns.setText(student.pronouns);
		email.setText(student.email == null || student.email.isEmpty()
				? getString(R.string.label_no_email)
				: student.email);
	}

	/**
	 * The overall figure is derived from the per-course rows rather than queried separately, so
	 * the headline can never disagree with the list underneath it.
	 */
	private void bindBreakdown(@Nullable List<StudentCourseStat> stats) {

		adapter.submitList(stats);

		boolean empty = stats == null || stats.isEmpty();
		emptyBreakdown.setVisibility(empty ? View.VISIBLE : View.GONE);

		int total = 0, attended = 0;
		if (stats != null) {
			for (StudentCourseStat stat : stats) {
				total += stat.totalLectures;
				attended += stat.attended;
			}
		}

		if (total == 0) {
			overallPercentage.setText("—");
			overallDetail.setText(R.string.label_no_attendance_yet);
			overallBar.setProgress(0);
			return;
		}

		double percentage = (attended * 100d) / total;
		overallPercentage.setText(String.format(Locale.getDefault(), "%.0f%%", percentage));
		overallDetail.setText(getString(R.string.label_attended_of, attended, total));
		overallBar.setProgress((int) Math.round(percentage));
	}
}
