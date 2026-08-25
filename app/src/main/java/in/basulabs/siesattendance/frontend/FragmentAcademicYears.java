package in.basulabs.siesattendance.frontend;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.EntityAcademicYear;
import in.basulabs.siesattendance.backend.pojo.SessionImpact;
import in.basulabs.siesattendance.frontend.adapter.AdapterAcademicYear;
import in.basulabs.siesattendance.frontend.util.Ui;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * Defines the academic sessions.
 * <p>
 * A session's dates are what every lecture is matched against to decide which year it belongs
 * to, so this screen is the single source of truth that used to be a hardcoded "starts in June"
 * in three separate files. Migration seeded June to May for existing data; from here the dates
 * can be set to whatever the college actually uses.
 */
public class FragmentAcademicYears extends Fragment {

	private static final DateTimeFormatter DATE_LABEL =
			DateTimeFormatter.ofPattern("d MMM yyyy");

	private AttendanceViewModel viewModel;

	private AdapterAcademicYear adapter;

	private TextView warning;

	/**
	 * The sessions currently on record, newest first, kept so the add sheet can offer the year
	 * after the latest one. Empty until the first emission, which only affects the default the
	 * editor opens with.
	 */
	@NonNull
	private List<EntityAcademicYear> sessions = new ArrayList<>();

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
	                         @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_academic_years, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity()).get(AttendanceViewModel.class);
		warning = view.findViewById(R.id.tvOrphanWarning);

		RecyclerView recyclerView = view.findViewById(R.id.rvAcademicYears);
		adapter = new AdapterAcademicYear(new AdapterAcademicYear.OnAction() {

			@Override
			public void onEdit(EntityAcademicYear year) {
				showEditor(year);
			}

			@Override
			public void onLongPress(EntityAcademicYear year) {
				confirmDelete(year);
			}
		});

		recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
		recyclerView.setAdapter(adapter);

		FloatingActionButton fab = view.findViewById(R.id.fabAddYear);
		fab.setOnClickListener(v -> showEditor(null));

		viewModel.getAcademicYears().observe(getViewLifecycleOwner(), years -> {
			sessions = years == null ? new ArrayList<>() : years;
			adapter.submitList(years);
			adapter.setCurrent(viewModel.academicYearValue());
		});

		// Lectures outside every session are invisible to statistics, so the count is shown
		// here where it can actually be acted on.
		viewModel.getOrphanLectureCount().observe(getViewLifecycleOwner(), count -> {
			boolean any = count != null && count > 0;
			warning.setVisibility(any ? View.VISIBLE : View.GONE);
			if (any) {
				warning.setText(getResources().getQuantityString(
						R.plurals.warn_orphan_lectures, count, count));
			}
		});
	}

	/**
	 * Shows the add or edit sheet. Passing null creates a session, defaulted to the year after
	 * the latest one already defined.
	 */
	private void showEditor(@Nullable EntityAcademicYear existing) {

		// The year itself is the primary key, so editing one would move the row rather than
		// change it, orphaning everything that refers to it. The field is locked in edit mode.


		View content = LayoutInflater.from(requireContext())
				.inflate(R.layout.dialog_academic_year, null, false);

		EditText etYear = content.findViewById(R.id.etYear);
		MaterialButton btnStart = content.findViewById(R.id.btnStartDate);
		MaterialButton btnEnd = content.findViewById(R.id.btnEndDate);

		final LocalDate[] dates = new LocalDate[2];

		if (existing != null) {
			etYear.setText(String.valueOf(existing.academicYear));
			etYear.setEnabled(false);
			dates[0] = existing.startDate;
			dates[1] = existing.endDate;
		} else {
			// The year after the latest already defined. Offering the current session
			// instead put an existing year in the field, and since the upsert replaces on
			// the key, saving it rewrote that session's dates rather than adding one.
			int suggested = nextSessionYear();
			EntityAcademicYear shape = EntityAcademicYear.defaultFor(suggested);

			etYear.setText(String.valueOf(suggested));
			dates[0] = shape.startDate;
			dates[1] = shape.endDate;
		}

		btnStart.setText(dates[0].format(DATE_LABEL));
		btnEnd.setText(dates[1].format(DATE_LABEL));

		btnStart.setOnClickListener(v -> pickDate(dates[0], picked -> {
			dates[0] = picked;
			btnStart.setText(picked.format(DATE_LABEL));
		}));

		btnEnd.setOnClickListener(v -> pickDate(dates[1], picked -> {
			dates[1] = picked;
			btnEnd.setText(picked.format(DATE_LABEL));
		}));

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(existing == null
						? R.string.title_add_session : R.string.title_edit_session)
				.setView(content)
				.setPositiveButton(R.string.action_save_changes, (d, w) -> {
					int year;
					try {
						year = Integer.parseInt(etYear.getText().toString().trim());
					} catch (NumberFormatException e) {
						return;
					}
					viewModel.saveAcademicYear(year, dates[0], dates[1],
							existing == null, () -> {
							});
				})
				.setNegativeButton(R.string.action_cancel, null)
				.show();
	}

	/**
	 * The year to offer when adding a session: the one after the latest already defined, since
	 * sessions are added going forwards.
	 * <p>
	 * Falls back to the current session only when nothing is defined at all, which happens
	 * before the first seed and nowhere else. The list is not assumed to be sorted, so that
	 * this does not quietly depend on the query's ORDER BY.
	 */
	private int nextSessionYear() {

		int latest = Integer.MIN_VALUE;

		for (EntityAcademicYear session : sessions) {
			latest = Math.max(latest, session.academicYear);
		}

		return latest == Integer.MIN_VALUE ? viewModel.academicYearValue() : latest + 1;
	}

	/** Callback for {@link #pickDate}, since the two date buttons want different destinations. */
	private interface DatePicked {
		void onPicked(LocalDate date);
	}

	/**
	 * Opens the calendar at the date already chosen.
	 * <p>
	 * Both the initial selection and the result are handled at UTC, which is the zone the
	 * Material picker works in. Converting at the device zone shifts the date by a day for
	 * anyone east of Greenwich.
	 */
	private void pickDate(@NonNull LocalDate initial, @NonNull DatePicked callback) {

		MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
				.setSelection(initial.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
				.build();

		picker.addOnPositiveButtonClickListener(selection -> callback.onPicked(
				Instant.ofEpochMilli(selection).atZone(ZoneId.of("UTC")).toLocalDate()));

		picker.show(getParentFragmentManager(), "sessionDate");
	}

	/**
	 * Removing a session does not touch any attendance, but it does hide every lecture inside
	 * it from statistics, and it makes an export for that year come back empty rather than
	 * short. The counts are read first so the dialog can say which of those is at stake here
	 * rather than warning in the abstract.
	 */
	private void confirmDelete(@NonNull EntityAcademicYear year) {

		viewModel.loadSessionDeletionImpact(year, impact -> Ui.onMain(this, () ->
				showDeleteConfirmation(year, impact)));
	}

	private void showDeleteConfirmation(@NonNull EntityAcademicYear year,
	                                    @Nullable SessionImpact impact) {

		String message = getString(R.string.dialog_delete_session_message, year.label())
				+ "\n\n"
				+ (impact == null || impact.isEmpty()
				? getString(R.string.dialog_delete_session_empty)
				: getString(R.string.dialog_delete_session_impact,
						getResources().getQuantityString(R.plurals.count_lectures_plain,
								impact.lectures, impact.lectures),
						getResources().getQuantityString(R.plurals.count_enrolments_plain,
								impact.enrolments, impact.enrolments)));

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_delete_session_title)
				.setMessage(message)
				.setPositiveButton(R.string.action_delete, (d, w) ->
						viewModel.deleteAcademicYear(year, () -> {
						}))
				.setNegativeButton(R.string.action_cancel, null)
				.show();
	}
}
