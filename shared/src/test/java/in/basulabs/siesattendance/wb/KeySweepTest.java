package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import in.basulabs.siesattendance.backend.sync.KeySweep;
import in.basulabs.siesattendance.backend.sync.SyncJson;

/**
 * Which of a phone's rows a sweep removes: the ones the server's list of keys leaves out.
 * <p>
 * The server's list arrives a page at a time, so the cases that matter are the ones at the
 * edges of a page and past the end of the list. A key wrongly called absent is a row deleted
 * from somebody's phone.
 */
public class KeySweepTest {

	/** The server, holding these keys in order and answering in pages of this size. */
	private static KeySweep.ServerKeys server(int pageSize, List<List<Long>> keys) {

		return after -> {

			List<List<Long>> page = new ArrayList<>();
			boolean more = false;

			for (List<Long> key : keys) {

				if (!after.isEmpty() && KeySweep.KEY_ORDER.compare(key, after) <= 0) {
					continue;
				}
				if (page.size() == pageSize) {
					more = true;
					break;
				}
				page.add(key);
			}
			return new SyncJson.KeyPage(page, more);
		};
	}

	private static List<Long> k(long... parts) {

		List<Long> key = new ArrayList<>();
		for (long part : parts) {
			key.add(part);
		}
		return key;
	}

	@Test
	public void whatTheServerListsStaysAndWhatItDoesNotGoes() throws IOException {

		List<List<Long>> absent = KeySweep.absentFromServer(
				List.of(k(1, 1), k(1, 2), k(2, 1), k(3, 7)),
				server(500, List.of(k(1, 1), k(2, 1))));

		assertEquals(List.of(k(1, 2), k(3, 7)), absent);
	}

	@Test
	public void pageBoundariesDoNotMakeAKeyLookAbsent() throws IOException {

		List<List<Long>> serverKeys = new ArrayList<>();
		List<List<Long>> local = new ArrayList<>();

		for (long id = 1; id <= 100; id++) {
			serverKeys.add(k(id));

			if (id % 3 != 0) {
				local.add(k(id));
			}
		}

		// And three the server does not have: before, among and after its keys.
		local.add(k(0));
		local.add(k(250));
		local.add(k(101));

		for (int pageSize : new int[]{1, 2, 7, 33, 100, 1000}) {
			assertEquals("pages of " + pageSize, List.of(k(0), k(101), k(250)),
					KeySweep.absentFromServer(local, server(pageSize, serverKeys)));
		}
	}

	@Test
	public void theDevicesOrderDoesNotMatter() throws IOException {

		assertEquals(List.of(k(2, 9)), KeySweep.absentFromServer(
				List.of(k(5, 1), k(2, 9), k(1, 3)),
				server(1, List.of(k(1, 3), k(5, 1)))));
	}

	@Test
	public void anEmptyServerListMeansEverythingGoes() throws IOException {
		assertEquals(List.of(k(1), k(2)), KeySweep.absentFromServer(
				List.of(k(2), k(1)), server(10, List.of())));
	}

	@Test
	public void anEmptyTableAsksNothing() throws IOException {
		assertEquals(List.of(), KeySweep.absentFromServer(List.of(), after -> {
			throw new AssertionError("asked the server about an empty table");
		}));
	}

	@Test
	public void aPageThatDoesNotArriveDecidesNothing() {

		KeySweep.ServerKeys failing = after -> {
			if (!after.isEmpty()) {
				throw new IOException("no signal");
			}
			return new SyncJson.KeyPage(List.of(k(1)), true);
		};

		assertThrows(IOException.class,
				() -> KeySweep.absentFromServer(List.of(k(1), k(2)), failing));
	}

	@Test
	public void keysCompareColumnByColumnNumerically() {

		assertEquals(-1, Integer.signum(KeySweep.KEY_ORDER.compare(k(1, 99), k(2, 0))));
		assertEquals(-1, Integer.signum(KeySweep.KEY_ORDER.compare(k(9), k(10))));
		assertEquals(0, KeySweep.KEY_ORDER.compare(k(3, 4), k(3, 4)));
	}
}
