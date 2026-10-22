package in.basulabs.siesattendance.server.importer;

import androidx.annotation.NonNull;

/**
 * How far a long import or export has got, for a page to show while it waits.
 * <p>
 * Written by the one thread doing the work and read by others, so every change replaces a
 * whole {@link State} at once: a reader never sees one stage's count against another's total.
 * Nothing else depends on it, and a run nobody watches simply writes to one nobody reads.
 */
public final class Progress {

	/**
	 * @param stage what is happening, in a sentence a person reads
	 * @param done  how much of it is done
	 * @param total how much there is, or 0 when that is not known
	 */
	public record State(@NonNull String stage, long done, long total) {
	}

	@NonNull
	private volatile State state = new State("Starting", 0, 0);

	@NonNull
	public State state() {
		return state;
	}

	/** A stage whose size is not known. */
	public void stage(@NonNull String stage) {
		state = new State(stage, 0, 0);
	}

	/** A stage of {@code total} steps, none of them done yet. */
	public void stage(@NonNull String stage, long total) {
		state = new State(stage, 0, total);
	}

	/** Renames the stage and keeps its count, as a run moves from one table to the next. */
	public void rename(@NonNull String stage) {
		State now = state;
		state = new State(stage, now.done(), now.total());
	}

	/** One more step of the current stage done. */
	public void advance() {
		advance(1);
	}

	public void advance(long steps) {
		State now = state;
		state = new State(now.stage(), now.done() + steps, now.total());
	}
}
