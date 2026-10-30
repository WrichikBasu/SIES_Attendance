package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import in.basulabs.siesattendance.backend.Designations;

/** The four designations, and what a designation typed before they were fixed reads as. */
public class DesignationsTest {

	@Test
	public void theListIsFourAndInRankOrder() {
		assertEquals(List.of("Assistant Professor", "Associate Professor", "Professor", "HOD"),
				Designations.ALL);
	}

	@Test
	public void aHeadIsAHeadInEitherSpelling() {
		assertTrue(Designations.isHeads("HOD"));
		assertTrue(Designations.isHeads("Head of Department"));
		assertTrue(Designations.isHeads(" head of department "));
		assertFalse(Designations.isHeads("Professor"));
		assertFalse(Designations.isHeads(null));
	}

	@Test
	public void aDesignationOnFileOpensAsOneOfTheFourOrAsNothing() {
		assertEquals("HOD", Designations.normalise("Head of Department"));
		assertEquals("Associate Professor", Designations.normalise("associate professor"));
		assertNull("a title no longer offered is asked for again, not guessed",
				Designations.normalise("Visiting Faculty"));
		assertNull(Designations.normalise(null));
	}
}
