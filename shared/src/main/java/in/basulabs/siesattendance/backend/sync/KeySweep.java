package in.basulabs.siesattendance.backend.sync;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Which of a device's rows the server no longer lists, for a sweep.
 * <p>
 * A pull brings what changed. When the account's access changes and the rows do not — a
 * professor moved to another department keeps that department's batches on the phone, because
 * nothing about the batches was written — the feed has nothing to say about them, and they
 * would stay for ever. So after a snapshot the device asks for the keys of every row it may read
 * ({@code GET /sync/keys}) and removes the ones it holds that are not among them.
 * <p>
 * The server sends keys in key order a page at a time. The device's keys are sorted here the
 * same way and the two lists are walked together, so a roster of keys is never held twice and
 * the server's never at all.
 */
public final class KeySweep {

	/** One page of the server's keys, after the given key, or from the start when it is empty. */
	@FunctionalInterface
	public interface ServerKeys {
		@NonNull
		SyncJson.KeyPage after(@NonNull List<Long> key) throws IOException;
	}

	/**
	 * Key order: column by column, numerically. The order PostgreSQL's row comparison gives a
	 * key of bigints, which is the order the server's pages come in.
	 */
	public static final Comparator<List<Long>> KEY_ORDER = (a, b) -> {

		for (int at = 0; at < Math.min(a.size(), b.size()); at++) {

			int compared = Long.compare(a.get(at), b.get(at));

			if (compared != 0) {
				return compared;
			}
		}
		return Integer.compare(a.size(), b.size());
	};

	private KeySweep() {
	}

	/**
	 * The device's keys the server did not list.
	 *
	 * @param local  the device's keys for one table, in any order; sorted here
	 * @param server the server's keys for the same table
	 * @throws IOException when a page could not be had. Nothing is decided then: a key missing
	 *                     because a page did not arrive is not a key the server left out
	 */
	@NonNull
	public static List<List<Long>> absentFromServer(@NonNull List<List<Long>> local,
	                                                @NonNull ServerKeys server)
			throws IOException {

		List<List<Long>> mine = new ArrayList<>(local);
		mine.sort(KEY_ORDER);

		List<List<Long>> absent = new ArrayList<>();

		if (mine.isEmpty()) {
			// Nothing to keep or lose, so nothing to ask.
			return absent;
		}

		SyncJson.KeyPage page = server.after(List.of());
		int inPage = 0;

		for (List<Long> key : mine) {

			while (true) {

				if (inPage == page.keys().size()) {

					if (!page.more() || page.keys().isEmpty()) {
						// The server has listed everything, and this key and every one after
						// it are past the end of the list.
						absent.add(key);
						break;
					}

					page = server.after(page.keys().get(page.keys().size() - 1));
					inPage = 0;
					continue;
				}

				int compared = KEY_ORDER.compare(page.keys().get(inPage), key);

				if (compared < 0) {
					// A row the server lists and this device does not have. Not this walk's
					// business: the snapshot before it brings those.
					inPage++;

				} else {
					if (compared > 0) {
						absent.add(key);
					}
					break;
				}
			}
		}
		return absent;
	}
}
