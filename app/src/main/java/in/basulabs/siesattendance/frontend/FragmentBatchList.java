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
import in.basulabs.siesattendance.backend.EntityBatchDesc;
import in.basulabs.siesattendance.frontend.adapter.AdapterBatch;
import in.basulabs.siesattendance.frontend.viewmodel.AttendanceViewModel;

/**
 * The batches tab of the courses hub. Lists the batches in the current session, and is the way
 * into the batch editor for both creating and editing.
 */
public class FragmentBatchList extends Fragment {

	private AttendanceViewModel viewModel;

	private AdapterBatch adapter;

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
	                         @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_batch_list, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity()).get(AttendanceViewModel.class);

		RecyclerView recyclerView = view.findViewById(R.id.rvBatches);
		adapter = new AdapterBatch(new AdapterBatch.OnAction() {

			@Override
			public void onEdit(EntityBatchDesc batch) {
				Bundle args = new Bundle();
				args.putLong("batchID", batch.batchID);
				Navigation.findNavController(requireParentFragment().requireView())
						.navigate(R.id.action_coursesHub_to_editBatch, args);
			}

			@Override
			public void onLongPress(EntityBatchDesc batch) {
				confirmDelete(batch);
			}
		});
		recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
		recyclerView.setAdapter(adapter);

		FloatingActionButton fab = view.findViewById(R.id.fabAddBatch);
		fab.setOnClickListener(v -> {
			// -1 puts the editor in create mode with nothing preselected.
			Bundle args = new Bundle();
			args.putLong("batchID", -1L);
			viewModel.setBatchSeed(null);
			Navigation.findNavController(requireParentFragment().requireView())
					.navigate(R.id.action_coursesHub_to_editBatch, args);
		});

		viewModel.getBatches().observe(getViewLifecycleOwner(), adapter::submitList);
	}

	/**
	 * Deleting a batch also drops its membership and course links, but is refused while any
	 * lecture still points at it.
	 */
	private void confirmDelete(@NonNull EntityBatchDesc batch) {
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.dialog_delete_batch_title)
				.setMessage(getString(R.string.dialog_delete_batch_message, batch.batchName))
				.setPositiveButton(R.string.action_delete, (d, w) ->
						viewModel.deleteBatch(batch.batchID, () -> {
						}))
				.setNegativeButton(R.string.action_cancel, null)
				.show();
	}
}
