package in.basulabs.siesattendance.frontend.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.EntityBatchDesc;

public class AdapterBatch extends ListAdapter<EntityBatchDesc, AdapterBatch.Holder> {

	public interface OnAction {
		void onLongPress(EntityBatchDesc batch);
	}

	private final OnAction listener;

	private static final DiffUtil.ItemCallback<EntityBatchDesc> DIFF =
			new DiffUtil.ItemCallback<EntityBatchDesc>() {
				@Override
				public boolean areItemsTheSame(@NonNull EntityBatchDesc a,
				                               @NonNull EntityBatchDesc b) {
					return a.batchID == b.batchID;
				}

				@Override
				public boolean areContentsTheSame(@NonNull EntityBatchDesc a,
				                                  @NonNull EntityBatchDesc b) {
					return a.batchName.equals(b.batchName)
							&& a.academicYear == b.academicYear;
				}
			};

	public AdapterBatch(OnAction listener) {
		super(DIFF);
		this.listener = listener;
	}

	@NonNull
	@Override
	public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		return new Holder(LayoutInflater.from(parent.getContext())
				.inflate(R.layout.item_batch, parent, false));
	}

	@Override
	public void onBindViewHolder(@NonNull Holder holder, int position) {
		holder.bind(getItem(position));
	}

	class Holder extends RecyclerView.ViewHolder {

		private final TextView name, details;

		Holder(@NonNull View itemView) {
			super(itemView);
			name = itemView.findViewById(R.id.tvBatchName);
			details = itemView.findViewById(R.id.tvBatchDetails);
		}

		void bind(@NonNull EntityBatchDesc batch) {
			name.setText(batch.batchName);
			String session = batch.academicYear + "-" + ((batch.academicYear + 1) % 100);
			details.setText(batch.batchDesc == null || batch.batchDesc.isEmpty()
					? session
					: session + " · " + batch.batchDesc);

			itemView.setOnLongClickListener(v -> {
				if (listener != null) {
					listener.onLongPress(batch);
					return true;
				}
				return false;
			});
		}
	}
}
