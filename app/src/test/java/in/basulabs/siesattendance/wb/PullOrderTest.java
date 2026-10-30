package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import in.basulabs.siesattendance.backend.sync.DeviceTables;
import in.basulabs.siesattendance.backend.sync.OutboxDrain;
import in.basulabs.siesattendance.backend.sync.OutboxOp;
import in.basulabs.siesattendance.backend.sync.SyncIntent;

/**
 * The order a pulled page is written to the device's own database in.
 * <p>
 * A page arrives in whatever order the server built it: a snapshot is table by table in
 * dependency order, a feed read is in the order things happened, and the second of those is not
 * the first. Room's foreign keys are {@code RESTRICT}, so a lecture written before its course is
 * a failed transaction and a page that is applied again and again without ever succeeding.
 * <p>
 * Nothing else covers this. {@code OutboxDrain.pull} needs a Room database and a server, which
 * is the handset half of Phase 6's gate; the sort is a pure function and can be held to its rule
 * here.
 */
public class PullOrderTest {

	private static SyncIntent upsert(String table, long key) {
		return new SyncIntent(table, List.of(key), OutboxOp.UPSERT, Map.of("x", 1L));
	}

	private static SyncIntent delete(String table, long key) {
		return new SyncIntent(table, List.of(key), OutboxOp.DELETE, null);
	}

	@Test
	public void parentsAreWrittenBeforeTheRowsThatPointAtThem() {

		// Deliberately backwards, which is what a feed read can hand over: a lecture's course
		// renamed after the lecture was created puts the course later in the log.
		List<SyncIntent> page = new ArrayList<>(List.of(
				upsert("attendance", 1), upsert("lectures", 2), upsert("courses", 3),
				upsert("professors", 4), upsert("students", 5)));

		List<SyncIntent> sorted = OutboxDrain.inApplicationOrder(page);
		List<Integer> depths = new ArrayList<>();

		for (SyncIntent change : sorted) {
			depths.add(DeviceTables.depthOf(change.table()));
		}

		for (int at = 1; at < depths.size(); at++) {
			assertTrue("a parent has to be written before the row that points at it: " + depths,
					depths.get(at) >= depths.get(at - 1));
		}

		assertEquals("attendance", sorted.get(sorted.size() - 1).table());
	}

	@Test
	public void deletionsComeLastAndInTheOppositeOrder() {

		List<SyncIntent> page = new ArrayList<>(List.of(
				delete("courses", 1), delete("attendance", 2), delete("lectures", 3),
				upsert("students", 4)));

		List<SyncIntent> sorted = OutboxDrain.inApplicationOrder(page);

		assertEquals("an upsert first: a row being made and unmade in one page ends up gone, "
				+ "and that is the right answer", "students", sorted.get(0).table());

		// A lecture cannot go while its marks are there, and a course cannot go while its
		// lectures are.
		assertEquals("attendance", sorted.get(1).table());
		assertEquals("lectures", sorted.get(2).table());
		assertEquals("courses", sorted.get(3).table());
	}

	@Test
	public void aTableThisBuildDoesNotKnowSortsLast() {

		// An older build meeting a newer server, which is the one case where the device cannot
		// know where a table belongs. Last is where a row nothing can point at belongs, and
		// the drain drops the intent rather than applying it.
		assertEquals(DeviceTables.inDependencyOrder().size(),
				DeviceTables.depthOf("something_this_build_has_never_heard_of"));
	}
}
