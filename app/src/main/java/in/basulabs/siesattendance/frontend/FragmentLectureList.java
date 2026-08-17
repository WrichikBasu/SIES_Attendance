package in.basulabs.siesattendance.frontend;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import java.util.List;
import java.util.Locale;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.EntityAcademicYear;
import in.basulabs.siesattendance.backend.pojo.LectureDetails;
import in.basulabs.siesattendance.frontend.adapter.AdapterLecture;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel.LectureWindow;

/**
 * The record of lectures already held, filterable by session, month or a custom date range.
 * <p>
 * The filter produces nothing more than a pair of dates, so all three modes run through one
 * query. Sessions supply their own bounds from the {@code academic_years} table rather than
 * assuming when a year starts.
 */
public class FragmentLectureList extends Fragment {

	private static final DateTimeFormatter DATE_LABEL =
			DateTimeFormatter.ofPattern("d MMM yyyy");

	private AttendanceViewModel viewModel;

	private AdapterLecture adapter;

	private TextView windowLabel, emptyMessage, countLabel;

	private List<EntityAcademicYear> sessions = new ArrayList<>();

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

		windowLabel = view.findViewById(R.id.tvWindowLabel);
		emptyMessage = view.findViewById(R.id.tvEmptyLectures);
		countLabel = view.findViewById(R.id.tvLectureCount);

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

		MaterialButton btnFilter = view.findViewById(R.id.btnFilterPeriod);
		btnFilter.setOnClickListener(v -> showFilterChooser());

		view.findViewById(R.id.btnClearPeriod).setOnClickListener(v ->
				viewModel.setLectureWindow(
						new LectureWindow(null, null, LectureWindow.Kind.ALL, null)));

		viewModel.loadAcademicYearsSync(loaded -> {
			if (!isAdded()) {
				return;
			}
			requireActivity().runOnUiThread(() -> sessions = loaded);
		});

		observe();
	}

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

		viewModel.getLectureWindow().observe(getViewLifecycleOwner(), window ->
				windowLabel.setText(window == null || window.label == null
						? getString(R.string.filter_all_lectures)
						: window.label));
	}

	// ── Filtering ────────────────────────────────────────────────────────

	/**
	 * Offers the three ways of narrowing the list. Sessions and months are both just date
	 * ranges underneath, so choosing one only has to produce a pair of dates.
	 */
	private void showFilterChooser() {
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
							viewModel.setLectureWindow(new LectureWindow(
									null, null, LectureWindow.Kind.ALL, null));
					}
				})
				.show();
	}

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
					viewModel.setLectureWindow(new LectureWindow(
							session.startDate, session.endDate,
							LectureWindow.Kind.SESSION, session.label()));
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
					viewModel.setLectureWindow(new LectureWindow(
							month.atDay(1), month.atEndOfMonth(),
							LectureWindow.Kind.MONTH, labels[which].toString()));
				})
				.show();
	}

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

			viewModel.setLectureWindow(new LectureWindow(from, to, LectureWindow.Kind.CUSTOM,
					from.format(DATE_LABEL) + " – " + to.format(DATE_LABEL)));
		});

		picker.show(getParentFragmentManager(), "lectureRange");
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
