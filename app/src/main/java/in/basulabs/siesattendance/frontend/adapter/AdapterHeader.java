package in.basulabs.siesattendance.frontend.adapter;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * A one-item adapter presenting an already-built view, meant to be concatenated in front of a
 * list adapter.
 * <p>
 * This is what lets a screen scroll as one piece while its list still recycles. Putting the
 * controls in a scroll view above a {@code wrap_content} RecyclerView achieves the same look but
 * forces every row to be inflated at once; as a header inside the RecyclerView the controls
 * scroll away with the content and the rows below are recycled normally.
 * <p>
 * The view is inflated and wired by the caller <em>before</em> the adapter is attached, rather
 * than being created here on demand. An earlier version inflated it in
 * {@code onCreateViewHolder} and invoked a callback to wire it, which ran in the middle of a
 * layout pass: any {@code notifyDataSetChanged} triggered by that wiring threw
 * "Cannot call this method while RecyclerView is computing a layout or scrolling". Handing in a
 * finished view removes the window in which that can happen.
 */
public class AdapterHeader extends RecyclerView.Adapter<AdapterHeader.Holder> {

	@NonNull
	private final View header;

	/**
	 * @param header a view inflated with {@code attachToRoot = false} against the RecyclerView,
	 *               so it carries the right layout parameters and has no parent
	 */
	public AdapterHeader(@NonNull View header) {
		this.header = header;
	}

	/** The header view, so the fragment can find its children and keep them up to date. */
	@NonNull
	public View header() {
		return header;
	}

	@NonNull
	@Override
	public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {

		// The same view instance is reused for the life of the screen, so if a previous holder
		// is still attached it has to be detached before this one can take it.
		ViewGroup previous = (ViewGroup) header.getParent();
		if (previous != null) {
			previous.removeView(header);
		}

		return new Holder(header);
	}

	@Override
	public void onBindViewHolder(@NonNull Holder holder, int position) {
		// Nothing to bind: the fragment owns the header's contents and updates them directly.
	}

	@Override
	public int getItemCount() {
		return 1;
	}

	static class Holder extends RecyclerView.ViewHolder {
		Holder(@NonNull View itemView) {
			super(itemView);
		}
	}
}
