package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import in.basulabs.siesattendance.backend.IdGenerator;

/**
 * The parts of the ID layout that decide whether two devices can ever hand out the same primary
 * key, and whether the merge can tell a key worth carrying across from one that means nothing
 * outside the file it came from.
 * <p>
 * {@code newID} itself is barely exercised here: it needs a node number, and a node number needs
 * a {@code Context}, which this source set does not have. What is pinned instead is the shape of
 * the value it produces, since every guarantee rests on that shape rather than on the issuing.
 */
public class IdGeneratorTest {

	private static final int NODE_BITS = 16;

	private static final int SEQUENCE_BITS = 6;

	/** 2024-01-01T00:00:00Z, the epoch the time field counts from. */
	private static final long EPOCH_MILLIS = 1_704_067_200_000L;

	/** An ID as {@code newID} would build one, without needing a node number to be assigned. */
	private static long compose(long millis, int sequence, int node) {
		return (((((millis - EPOCH_MILLIS) << SEQUENCE_BITS) | sequence) << NODE_BITS) | node);
	}

	// ── The legacy boundary ──────────────────────────────────────────────

	/**
	 * Keys SQLite counted out, before version 7, must never look structured: the merge would
	 * carry them across as though they identified the same row on two devices, and student 14
	 * in a backup is not student 14 here.
	 */
	@Test
	public void autoincrementKeysAreNotStructured() {

		for (long id : new long[]{1L, 2L, 14L, 1_000L, 250_000L}) {
			assertFalse("autoincrement key " + id, IdGenerator.isStructured(id));
			assertEquals(-1, IdGenerator.nodeOf(id));
			assertEquals(-1L, IdGenerator.createdAtMillis(id));
		}
	}

	/**
	 * Nor may the raw-clock keys issued in between, which is the interesting case: they are
	 * large numbers, and it would be easy to build a boundary that let them through.
	 */
	@Test
	public void rawMillisecondKeysAreNotStructured() {

		// From 2024 through to a date no phone will be running this build.
		for (long millis : new long[]{1_704_067_200_000L, 1_756_000_000_000L,
				2_190_000_000_000L, 10_000_000_000_000L}) {

			assertFalse("raw clock key " + millis, IdGenerator.isStructured(millis));
		}
	}

	/** And the first structured value is on the structured side of its own boundary. */
	@Test
	public void theBoundaryItselfIsStructured() {
		assertTrue(IdGenerator.isStructured(IdGenerator.FIRST_STRUCTURED_ID));
		assertFalse(IdGenerator.isStructured(IdGenerator.FIRST_STRUCTURED_ID - 1));
	}

	// ── The layout ───────────────────────────────────────────────────────

	/**
	 * Node and creation time come back out of a key unchanged, across the whole node range and
	 * the whole sequence range. This is the layout the class documents; if it ever changes,
	 * every key already in the field is misread, so it is pinned rather than described.
	 */
	@Test
	public void nodeAndTimeSurviveTheRoundTrip() {

		long millis = 1_756_000_000_000L;

		for (int node : new int[]{0, 1, IdGenerator.MAX_ASSIGNED_NODE_ID,
				IdGenerator.MAX_ASSIGNED_NODE_ID + 1, IdGenerator.MAX_NODE_ID}) {

			for (int sequence : new int[]{0, 1, 63}) {

				long id = compose(millis, sequence, node);

				assertTrue("node " + node + " seq " + sequence, IdGenerator.isStructured(id));
				assertEquals(node, IdGenerator.nodeOf(id));
				assertEquals(millis, IdGenerator.createdAtMillis(id));
			}
		}
	}

	/** Every key the scheme can produce stays a positive long, which the primary key must be. */
	@Test
	public void theLastKeyOfTheEraIsStillPositive() {

		long lastMillis = EPOCH_MILLIS + (1L << 41) - 1;
		long id = compose(lastMillis, 63, IdGenerator.MAX_NODE_ID);

		assertEquals(Long.MAX_VALUE, id);
		assertTrue(IdGenerator.isStructured(id));
	}

	/**
	 * Two devices creating a row in the same millisecond, with the same sequence value, still
	 * get different keys. This is the whole reason the node field exists.
	 */
	@Test
	public void thesameInstantOnTwoDevicesGivesTwoKeys() {

		long millis = 1_756_000_000_000L;

		assertEquals(1, compose(millis, 0, 7) - compose(millis, 0, 6));
	}

	// ── The node ranges ──────────────────────────────────────────────────

	/**
	 * A number the server assigns can never equal one an install drew for itself, so an install
	 * that has registered is safe from every install that has not.
	 */
	@Test
	public void theAssignedAndSelfAssignedRangesDoNotOverlap() {

		assertTrue(IdGenerator.MAX_ASSIGNED_NODE_ID < IdGenerator.MAX_NODE_ID);
		assertEquals(IdGenerator.MAX_NODE_ID / 2, IdGenerator.MAX_ASSIGNED_NODE_ID);
	}

	/**
	 * A key is refused rather than issued from a node number that does not exist yet. Issuing
	 * one anyway would produce a key that cannot say which device made it, which is the failure
	 * this class exists to prevent.
	 */
	@Test
	public void noKeyIsIssuedBeforeTheNodeIsKnown() {
		assertThrows(IllegalStateException.class, IdGenerator::newID);
	}

	// ── idFor ───────────────────────────────────────────────────────────

	/** A row that already carries a key keeps it, whatever kind of key it is. */
	@Test
	public void anExistingKeyIsLeftAlone() {
		assertEquals(14L, IdGenerator.idFor(14L));
		assertEquals(IdGenerator.FIRST_STRUCTURED_ID,
				IdGenerator.idFor(IdGenerator.FIRST_STRUCTURED_ID));
	}
}
