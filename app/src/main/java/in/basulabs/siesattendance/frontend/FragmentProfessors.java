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

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.EntityProfessor;
import in.basulabs.siesattendance.frontend.adapter.AdapterProfessor;
import in.basulabs.siesattendance.frontend.util.Permissions;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * The professor list, with a search-free layout since the number of staff stays small enough to
 * scroll. Adding and editing both route to {@link FragmentAddProfessor}.
 */
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

				if (!viewModel.may(Permissions.Capability.PROFESSORS)) {
					viewModel.sayNotPermitted();
					return;
				}

				Bundle args = new Bundle();
				args.putLong("profID", professor.profID);
				Navigation.findNavController(view)
						.navigate(R.id.action_professors_to_addProfessor, args);
			}

			@Override
			public void onLongPress(EntityProfessor professor) {
				// Same as tapping: professors cannot be deleted, only corrected.
				onEdit(professor);
			}
		});
		recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
		recyclerView.setAdapter(adapter);

		FloatingActionButton fab = view.findViewById(R.id.fabAddProfessor);

		if (viewModel.may(Permissions.Capability.PROFESSORS)) {
			fab.setOnClickListener(v -> Navigation.findNavController(v)
					.navigate(R.id.action_professors_to_addProfessor));
		} else {
			fab.setVisibility(View.GONE);
		}

		observeList();
	}

	/** Swaps in the empty state when there are no professors, rather than showing a blank list. */
	private void observeList() {
		viewModel.getProfessors().observe(getViewLifecycleOwner(), professors -> {
			adapter.submitList(professors);
			boolean empty = professors == null || professors.isEmpty();
			emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
			recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
		});
	}
}
