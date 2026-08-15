package in.basulabs.siesattendance.frontend;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.EntityProfessor;
import in.basulabs.siesattendance.frontend.adapter.AdapterProfessor;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

public class FragmentProfessors extends Fragment {

	private AttendanceViewModel viewModel;

	private AdapterProfessor adapter;

	private RecyclerView recyclerView;

	private View emptyState;

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
	                         @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_professors, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity()).get(AttendanceViewModel.class);

		recyclerView = view.findViewById(R.id.rvProfessors);
		emptyState = view.findViewById(R.id.layoutEmptyState);

		adapter = new AdapterProfessor(new AdapterProfessor.OnAction() {

			@Override
			public void onEdit(EntityProfessor professor) {
				Bundle args = new Bundle();
				args.putLong("profID", professor.profID);
				Navigation.findNavController(view)
						.navigate(R.id.action_professors_to_addProfessor, args);
			}

			@Override
			public void onLongPress(EntityProfessor professor) {
				confirmDelete(professor);
			}
		});
		recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
		recyclerView.setAdapter(adapter);

		FloatingActionButton fab = view.findViewById(R.id.fabAddProfessor);
		fab.setOnClickListener(v -> Navigation.findNavController(v)
				.navigate(R.id.action_professors_to_addProfessor));

		observeList();
	}

	/**
	 * Courses hold a RESTRICT key onto professors, so a delete may legitimately fail. The DAO
	 * reports how many courses are blocking it and that message surfaces as a snackbar.
	 */
	private void confirmDelete(@NonNull EntityProfessor professor) {
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_delete_professor_title)
				.setMessage(getString(R.string.dialog_delete_generic_message, professor.name))
				.setPositiveButton(R.string.action_delete, (d, w) ->
						viewModel.deleteProfessor(professor, () -> {
						}))
				.setNegativeButton(R.string.action_cancel, null)
				.show();
	}

	private void observeList() {
		viewModel.getProfessors().observe(getViewLifecycleOwner(), professors -> {
			adapter.submitList(professors);
			boolean empty = professors == null || professors.isEmpty();
			emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
			recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
		});
	}
}
