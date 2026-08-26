package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Assert;
import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import in.basulabs.siesattendance.backend.CourseType;
import in.basulabs.siesattendance.backend.EntityAcademicYear;
import in.basulabs.siesattendance.backend.util.BreakdownFilter;

/**
 * The window behind a student's per-course breakdown: what each preset resolves to, how the
 * dates become the epoch bounds the query compares against, and what the screen says it is
 * covering.
 */
public class BreakdownFilterTest {

	private static final EntityAcademicYear SESSION_2025 = EntityAcademicYear.of(2025,
			LocalDate.of(2025, 6, 1), LocalDate.of(2026, 5, 31));

	private static final ZoneId ZONE = ZoneId.systemDefault();

	private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("d MMM yyyy");

	// ── Presets ──────────────────────────────────────────────────────────

	@Test
	public void aFreshFilterCoversTheWholeRecord() {

		BreakdownFilter filter = new BreakdownFilter();

		assertTrue(filter.isUnrestricted());
		assertFalse(filter.hasDateWindow());
		assertNull(filter.fromEpoch());
		assertNull(filter.toEpoch());
		assertEquals(BreakdownFilter.Window.ALL_TIME, filter.window);
	}

	@Test
	public void theSessionPresetTakesTheSessionsOwnDates() {

		BreakdownFilter filter = new BreakdownFilter();
		filter.apply(BreakdownFilter.Window.SESSION, SESSION_2025);

		assertEquals(LocalDate.of(2025, 6, 1), filter.from);
		assertEquals(LocalDate.of(2026, 5, 31), filter.to);
	}

	/**
	 * Failing open rather than guessing. A breakdown covering too much is visibly wrong; one
	 * silently bounded to an invented year is not.
	 */
	@Test
	public void theSessionPresetLeavesTheWindowOpenWhenTheSessionIsUnknown() {

		BreakdownFilter filter = new BreakdownFilter();
		filter.apply(BreakdownFilter.Window.SESSION, null);

		assertNull(filter.from);
		assertNull(filter.to);
		assertFalse(filter.hasDateWindow());
	}

	@Test
	public void thisMonthCoversTheCalendarMonth() {

		BreakdownFilter filter = new BreakdownFilter();
		filter.apply(BreakdownFilter.Window.THIS_MONTH, SESSION_2025);

		LocalDate today = LocalDate.now();
		assertEquals(today.withDayOfMonth(1), filter.from);
		assertEquals(today.withDayOfMonth(today.lengthOfMonth()), filter.to);
	}

	/**
	 * Thirty days including today, not thirty days before it.
	 */
	@Test
	public void lastThirtyDaysIncludesToday() {

		BreakdownFilter filter = new BreakdownFilter();
		filter.apply(BreakdownFilter.Window.LAST_30_DAYS, SESSION_2025);

		assertEquals(LocalDate.now(), filter.to);
		assertEquals(LocalDate.now().minusDays(29), filter.from);
	}

	@Test
	public void customLeavesWhateverTheDateFieldsSet() {

		BreakdownFilter filter = new BreakdownFilter();
		filter.from = LocalDate.of(2025, 9, 1);
		filter.to = LocalDate.of(2025, 9, 30);

		filter.apply(BreakdownFilter.Window.CUSTOM, SESSION_2025);

		assertEquals(LocalDate.of(2025, 9, 1), filter.from);
		assertEquals(LocalDate.of(2025, 9, 30), filter.to);
		assertEquals(BreakdownFilter.Window.CUSTOM, filter.window);
	}

	@Test
	public void switchingBackToAllTimeDropsTheBounds() {

		BreakdownFilter filter = new BreakdownFilter();
		filter.apply(BreakdownFilter.Window.SESSION, SESSION_2025);
		filter.apply(BreakdownFilter.Window.ALL_TIME, SESSION_2025);

		assertNull(filter.from);
		assertNull(filter.to);
	}

	// ── Epoch bounds ─────────────────────────────────────────────────────

	@Test
	public void theLowerBoundIsTheStartOfItsDay() {

		BreakdownFilter filter = new BreakdownFilter();
		filter.from = LocalDate.of(2025, 9, 1);

		assertEquals(Long.valueOf(filter.from.atStartOfDay(ZONE).toEpochSecond()),
				filter.fromEpoch());
	}

	/**
	 * The upper bound has to take in the whole of the final day, or a lecture at five in the
	 * afternoon on the last day of the window falls outside it.
	 */
	@Test
	public void theUpperBoundTakesInTheWholeOfItsDay() {

		BreakdownFilter filter = new BreakdownFilter();
		filter.to = LocalDate.of(2025, 9, 30);

		long lastMoment = filter.to.atTime(23, 59, 59).atZone(ZONE).toEpochSecond();
		long nextMidnight = filter.to.plusDays(1).atStartOfDay(ZONE).toEpochSecond();

		assertTrue(filter.toEpoch() >= lastMoment);
		assertTrue(filter.toEpoch() < nextMidnight);
	}

	@Test
	public void anOpenEndHasNoBound() {

		BreakdownFilter filter = new BreakdownFilter();
		filter.to = LocalDate.of(2025, 9, 30);

		assertNull(filter.fromEpoch());
		assertTrue(filter.hasDateWindow());
	}

	// ── Course type ──────────────────────────────────────────────────────

	/**
	 * A type on its own narrows the breakdown, so it counts as a restriction.
	 */
	@Test
	public void aCourseTypeAloneIsStillARestriction() {

		BreakdownFilter filter = new BreakdownFilter();
		filter.courseType = CourseType.MAJOR_PRACTICALS;

		assertFalse(filter.isUnrestricted());
		assertFalse(filter.hasDateWindow());
	}

	// ── What the screen says ─────────────────────────────────────────────

	@Test
	public void anUnrestrictedFilterSaysSoRatherThanSayingNothing() {
		assertEquals("Every course, all time", new BreakdownFilter().describe());
	}

	/**
	 * The preset's own label, since that is the words the user chose.
	 */
	@Test
	public void aPresetIsDescribedByItsLabel() {

		BreakdownFilter filter = new BreakdownFilter();
		filter.apply(BreakdownFilter.Window.SESSION, SESSION_2025);

		assertEquals("This session", filter.describe());
	}

	@Test
	public void aCustomRangeIsDescribedByItsDates() {

		BreakdownFilter filter = new BreakdownFilter();
		filter.from = LocalDate.of(2025, 9, 1);
		filter.to = LocalDate.of(2025, 9, 30);
		filter.window = BreakdownFilter.Window.CUSTOM;

		assertNotNull(filter.from);
		assertNotNull(filter.to);
		assertEquals(filter.from.format(LABEL) + " – " + filter.to.format(LABEL),
				filter.describe());
	}

	@Test
	public void aHalfOpenRangeSaysWhichEndIsSet() {

		BreakdownFilter from = new BreakdownFilter();
		from.from = LocalDate.of(2025, 9, 1);
		from.window = BreakdownFilter.Window.CUSTOM;
		assertEquals("From " + from.from.format(LABEL), from.describe());

		BreakdownFilter to = new BreakdownFilter();
		to.to = LocalDate.of(2025, 9, 30);
		to.window = BreakdownFilter.Window.CUSTOM;
		assertEquals("Up to " + to.to.format(LABEL), to.describe());
	}

	@Test
	public void theCourseTypeIsAppendedToWhateverTheWindowSays() {

		BreakdownFilter filter = new BreakdownFilter();
		filter.apply(BreakdownFilter.Window.THIS_MONTH, SESSION_2025);
		filter.courseType = CourseType.MAJOR;

		assertEquals("This month · Major", filter.describe());
	}

	@Test
	public void aCourseTypeWithNoWindowStillNamesTheWindow() {

		BreakdownFilter filter = new BreakdownFilter();
		filter.courseType = CourseType.OE;

		assertEquals("All time · OE", filter.describe());
	}

	// ── Clearing ─────────────────────────────────────────────────────────

	@Test
	public void clearingPutsEverythingBackToTheWholeRecord() {

		BreakdownFilter filter = new BreakdownFilter();
		filter.apply(BreakdownFilter.Window.THIS_MONTH, SESSION_2025);
		filter.courseType = CourseType.MINOR;

		filter.clear();

		assertTrue(filter.isUnrestricted());
		assertNull(filter.from);
		assertNull(filter.to);
		assertNull(filter.courseType);
		assertEquals(BreakdownFilter.Window.ALL_TIME, filter.window);
	}
}
