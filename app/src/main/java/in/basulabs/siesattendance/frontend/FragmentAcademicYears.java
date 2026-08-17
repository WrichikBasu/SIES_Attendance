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

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.EntityAcademicYear;
import in.basulabs.siesattendance.frontend.adapter.AdapterAcademicYear;
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
			int suggested = viewModel.academicYearValue();
			etYear.setText(String.valueOf(suggested));
			dates[0] = LocalDate.of(suggested, 6, 1);
			dates[1] = LocalDate.of(suggested + 1, 5, 31);
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
					viewModel.saveAcademicYear(year, dates[0], dates[1], () -> {
					});
				})
				.setNegativeButton(R.string.action_cancel, null)
				.show();
	}

	private interface DatePicked {
		void onPicked(LocalDate date);
	}

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
	 * it from statistics until another session covers those dates.
	 */
	private void confirmDelete(@NonNull EntityAcademicYear year) {
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_delete_session_title)
				.setMessage(getString(R.string.dialog_delete_session_message, year.label()))
				.setPositiveButton(R.string.action_delete, (d, w) ->
						viewModel.deleteAcademicYear(year, () -> {
						}))
				.setNegativeButton(R.string.action_cancel, null)
				.show();
	}
}
