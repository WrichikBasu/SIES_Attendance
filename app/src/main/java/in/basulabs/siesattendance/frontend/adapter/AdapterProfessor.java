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
import in.basulabs.siesattendance.backend.EntityProfessor;

public class AdapterProfessor
		extends ListAdapter<EntityProfessor, AdapterProfessor.Holder> {

	public interface OnAction {
		void onEdit(EntityProfessor professor);

		void onLongPress(EntityProfessor professor);
	}

	private final OnAction listener;

	private static final DiffUtil.ItemCallback<EntityProfessor> DIFF =
			new DiffUtil.ItemCallback<EntityProfessor>() {
				@Override
				public boolean areItemsTheSame(@NonNull EntityProfessor a,
				                               @NonNull EntityProfessor b) {
					return a.profID == b.profID;
				}

				@Override
				public boolean areContentsTheSame(@NonNull EntityProfessor a,
				                                  @NonNull EntityProfessor b) {
					return a.name.equals(b.name) && a.email.equals(b.email)
							&& a.department == b.department
							&& a.designation.equals(b.designation);
				}
			};

	public AdapterProfessor(OnAction listener) {
		super(DIFF);
		this.listener = listener;
	}

	@NonNull
	@Override
	public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		return new Holder(LayoutInflater.from(parent.getContext())
				.inflate(R.layout.item_professor, parent, false));
	}

	@Override
	public void onBindViewHolder(@NonNull Holder holder, int position) {
		holder.bind(getItem(position));
	}

	class Holder extends RecyclerView.ViewHolder {

		private final TextView initials, name, info;

		Holder(@NonNull View itemView) {
			super(itemView);
			initials = itemView.findViewById(R.id.tvInitials);
			name = itemView.findViewById(R.id.tvProfName);
			info = itemView.findViewById(R.id.tvProfInfo);
		}

		void bind(@NonNull EntityProfessor professor) {
			String[] parts = professor.name.trim().split("\\s+");
			String label = parts.length == 1
					? parts[0].substring(0, Math.min(2, parts[0].length()))
					: "" + parts[0].charAt(0) + parts[parts.length - 1].charAt(0);

			initials.setText(label.toUpperCase());
			name.setText(professor.name);
			info.setText(professor.department.name() + " · " + professor.designation);

			itemView.setOnClickListener(v -> {
				if (listener != null) {
					listener.onEdit(professor);
				}
			});

			itemView.setOnLongClickListener(v -> {
				if (listener != null) {
					listener.onLongPress(professor);
					return true;
				}
				return false;
			});
		}
	}
}
