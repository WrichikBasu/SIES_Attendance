package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;

import in.basulabs.siesattendance.backend.CourseType;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.util.LectureFilter;

/**
 * What the lectures list is narrowed by: which combinations count as active, what the two clear
 * actions leave behind, and how the period becomes the epoch bounds the query compares against.
 */
public class LectureFilterTest {

	private static final ZoneId ZONE = ZoneId.systemDefault();

	@Test
	public void aFreshFilterIsTheWholeRecord() {

		LectureFilter filter = new LectureFilter();

		assertFalse(filter.hasActiveFilters());
		assertFalse(filter.hasPeriod());
		assertFalse(filter.hasSubjectFilters());

		assertNull(filter.fromEpoch());
		assertNull(filter.toEpoch());
		assertNull(filter.periodLabel);
		assertEquals(LectureFilter.Period.ALL, filter.period);
	}

	@Test
	public void everyDimensionOnItsOwnCountsAsActive() {
		// The red "filters active" line follows hasActiveFilters, so a dimension missing from
		// it is a list that has been narrowed without saying so.

		LectureFilter period = new LectureFilter();
		period.setPeriod(LectureFilter.Period.MONTH, LocalDate.of(2025, 7, 1),
				LocalDate.of(2025, 7, 31), "July 2025");
		assertTrue(period.hasActiveFilters());

		LectureFilter course = new LectureFilter();
		course.courseID = 1L;
		assertTrue(course.hasActiveFilters());

		LectureFilter professor = new LectureFilter();
		professor.professorID = 1L;
		assertTrue(professor.hasActiveFilters());

		LectureFilter department = new LectureFilter();
		department.department = Department.PHYSICS;
		assertTrue(department.hasActiveFilters());

		LectureFilter batch = new LectureFilter();
		batch.batchID = 1L;
		assertTrue(batch.hasActiveFilters());

		LectureFilter type = new LectureFilter();
		type.courseType = CourseType.MAJOR;
		assertTrue(type.hasActiveFilters());
	}

	@Test
	public void thePeriodAndTheSubjectFiltersAreCountedApart() {
		// They are cleared separately and labelled separately, so the two questions have to
		// stay answerable on their own.
		LectureFilter filter = new LectureFilter();
		filter.setPeriod(LectureFilter.Period.SESSION, LocalDate.of(2025, 6, 1),
				LocalDate.of(2026, 5, 31), "2025-26");

		assertTrue(filter.hasPeriod());
		assertFalse(filter.hasSubjectFilters());

		filter.courseType = CourseType.MAJOR_PRACTICALS;
		assertTrue(filter.hasSubjectFilters());
	}

	@Test
	public void clearingThePeriodLeavesTheOtherFiltersAlone() {

		LectureFilter filter = new LectureFilter();
		filter.setPeriod(LectureFilter.Period.MONTH, LocalDate.of(2025, 7, 1),
				LocalDate.of(2025, 7, 31), "July 2025");
		filter.courseID = 4L;
		filter.department = Department.CHEMISTRY;

		filter.clearPeriod();

		assertFalse(filter.hasPeriod());
		assertNull(filter.periodLabel);
		assertEquals(LectureFilter.Period.ALL, filter.period);

		assertEquals(Long.valueOf(4L), filter.courseID);
		assertEquals(Department.CHEMISTRY, filter.department);
		assertTrue(filter.hasActiveFilters());
	}

	@Test
	public void clearingEverythingLeavesNothingBehind() {

		LectureFilter filter = new LectureFilter();
		filter.setPeriod(LectureFilter.Period.CUSTOM, LocalDate.of(2025, 7, 1),
				LocalDate.of(2025, 7, 31), "1 Jul 2025 – 31 Jul 2025");
		filter.courseID = 1L;
		filter.professorID = 2L;
		filter.department = Department.PHYSICS;
		filter.batchID = 3L;
		filter.courseType = CourseType.OE;

		filter.clear();

		assertFalse(filter.hasActiveFilters());
		assertNull(filter.courseID);
		assertNull(filter.professorID);
		assertNull(filter.department);
		assertNull(filter.batchID);
		assertNull(filter.courseType);
		assertNull(filter.periodLabel);
	}

	// ── Epoch bounds ─────────────────────────────────────────────────────

	@Test
	public void theLowerBoundIsTheStartOfItsDay() {

		LectureFilter filter = new LectureFilter();
		filter.from = LocalDate.of(2025, 7, 10);

		assertEquals(Long.valueOf(LocalDate.of(2025, 7, 10)
						.atStartOfDay(ZONE).toEpochSecond()),
				filter.fromEpoch());
	}

	@Test
	public void theUpperBoundTakesInTheWholeOfItsDay() {
		// A lecture at five in the afternoon on the last day of the window is inside it.
		LectureFilter filter = new LectureFilter();
		filter.to = LocalDate.of(2025, 7, 31);

		long fivePm = LocalDate.of(2025, 7, 31).atTime(17, 0).atZone(ZONE).toEpochSecond();
		long midnightAfter = LocalDate.of(2025, 8, 1).atStartOfDay(ZONE).toEpochSecond();

		assertTrue(filter.toEpoch() >= fivePm);
		assertEquals(Long.valueOf(midnightAfter - 1), filter.toEpoch());
	}

	@Test
	public void anOpenEndHasNoBoundRatherThanASentinel() {
		// The query reads a null as "no condition", so an unbounded end must stay null: a
		// sentinel epoch would silently become a real comparison.
		LectureFilter filter = new LectureFilter();
		filter.from = LocalDate.of(2025, 7, 1);

		assertNull(filter.toEpoch());
	}

	@Test
	public void copyIsADetachedSnapshot() {
		// The screen edits the live filter while a query built from an earlier one is running.
		LectureFilter filter = new LectureFilter();
		filter.setPeriod(LectureFilter.Period.MONTH, LocalDate.of(2025, 7, 1),
				LocalDate.of(2025, 7, 31), "July 2025");
		filter.courseID = 1L;
		filter.department = Department.PHYSICS;

		LectureFilter snapshot = filter.copy();

		filter.courseID = 99L;
		filter.department = Department.CHEMISTRY;
		filter.clearPeriod();

		assertEquals(Long.valueOf(1L), snapshot.courseID);
		assertEquals(Department.PHYSICS, snapshot.department);
		assertEquals("July 2025", snapshot.periodLabel);
		assertEquals(LocalDate.of(2025, 7, 1), snapshot.from);
		assertTrue(snapshot.hasPeriod());
	}

	@Test
	public void copyCarriesEveryFieldAcross() {

		LectureFilter filter = new LectureFilter();
		filter.setPeriod(LectureFilter.Period.CUSTOM, LocalDate.of(2025, 7, 1),
				LocalDate.of(2025, 7, 31), "1 Jul 2025 – 31 Jul 2025");
		filter.courseID = 1L;
		filter.professorID = 2L;
		filter.department = Department.PHYSICS;
		filter.batchID = 3L;
		filter.courseType = CourseType.IKS;

		LectureFilter copy = filter.copy();

		assertEquals(filter.from, copy.from);
		assertEquals(filter.to, copy.to);
		assertEquals(filter.period, copy.period);
		assertEquals(filter.periodLabel, copy.periodLabel);
		assertEquals(filter.courseID, copy.courseID);
		assertEquals(filter.professorID, copy.professorID);
		assertEquals(filter.department, copy.department);
		assertEquals(filter.batchID, copy.batchID);
		assertEquals(filter.courseType, copy.courseType);
	}
}
