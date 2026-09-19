package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collections;

import in.basulabs.siesattendance.backend.CourseType;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Division;
import in.basulabs.siesattendance.backend.EntityAcademicYear;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;
import in.basulabs.siesattendance.backend.stats.StatsFilter;
import in.basulabs.siesattendance.backend.stats.StatsGroupBy;
import in.basulabs.siesattendance.backend.util.CourseGroup;
import in.basulabs.siesattendance.backend.util.ExportFilter;

/**
 * The two filter objects the report and the export screens edit in place.
 */
public class StatsAndExportFilterTest {

	private static final EntityAcademicYear SESSION_2025 = EntityAcademicYear.of(2025,
			LocalDate.of(2025, 6, 1), LocalDate.of(2026, 5, 31));

	// ── Date presets ─────────────────────────────────────────────────────

	@Test
	public void wholeSessionLeavesTheDateWindowUnsetOnPurpose() {
		// The academic-year filter already bounds the query through the session join; adding
		// redundant date conditions would only be another thing that could disagree with it.
		StatsFilter filter = new StatsFilter(2025);
		filter.from = LocalDate.of(2020, 1, 1);
		filter.to = LocalDate.of(2020, 2, 1);

		filter.applyDateRange(StatsFilter.DateRange.ALL_TIME, SESSION_2025);

		assertNull(filter.from);
		assertNull(filter.to);
		assertFalse(filter.hasDateWindow());
	}

	@Test
	public void theTwoSessionHalvesPartitionTheSessionExactly() {
		StatsFilter first = new StatsFilter(2025);
		first.applyDateRange(StatsFilter.DateRange.SESSION_FIRST_HALF, SESSION_2025);

		StatsFilter second = new StatsFilter(2025);
		second.applyDateRange(StatsFilter.DateRange.SESSION_SECOND_HALF, SESSION_2025);

		assertEquals(SESSION_2025.startDate, first.from);
		assertEquals(SESSION_2025.endDate, second.to);

		assertEquals("the halves must meet without a gap or an overlap",
				first.to.plusDays(1), second.from);

		long total = ChronoUnit.DAYS.between(SESSION_2025.startDate, SESSION_2025.endDate) + 1;
		long covered = ChronoUnit.DAYS.between(first.from, first.to) + 1
				+ ChronoUnit.DAYS.between(second.from, second.to) + 1;
		assertEquals("the two halves must cover the whole session", total, covered);
	}

	@Test
	public void theHalvesAreDerivedFromTheSessionRatherThanAHardcodedMonth() {
		// A college running August to May must get a sensible split with no code change.
		EntityAcademicYear august = EntityAcademicYear.of(2025,
				LocalDate.of(2025, 8, 1), LocalDate.of(2026, 5, 31));

		StatsFilter filter = new StatsFilter(2025);
		filter.applyDateRange(StatsFilter.DateRange.SESSION_FIRST_HALF, august);

		assertEquals(LocalDate.of(2025, 8, 1), filter.from);
		assertTrue(filter.to.isAfter(LocalDate.of(2025, 11, 1)));
		assertTrue(filter.to.isBefore(LocalDate.of(2026, 2, 1)));
	}

	@Test
	public void aHalfSessionWithNoSessionSelectedLeavesTheWindowOpen() {
		StatsFilter filter = new StatsFilter(null);
		filter.from = LocalDate.of(2020, 1, 1);
		filter.to = LocalDate.of(2020, 2, 1);

		filter.applyDateRange(StatsFilter.DateRange.SESSION_FIRST_HALF, null);

		assertNull(filter.from);
		assertNull(filter.to);
	}

	@Test
	public void thisMonthSpansTheWholeCalendarMonth() {
		StatsFilter filter = new StatsFilter(2025);
		filter.applyDateRange(StatsFilter.DateRange.THIS_MONTH, SESSION_2025);

		LocalDate today = LocalDate.now();
		assertEquals(today.withDayOfMonth(1), filter.from);
		assertEquals(today.withDayOfMonth(today.lengthOfMonth()), filter.to);
		assertEquals(today.getMonth(), filter.from.getMonth());
		assertEquals(today.getMonth(), filter.to.getMonth());
	}

	@Test
	public void lastMonthIsTheWholePreviousCalendarMonth() {
		StatsFilter filter = new StatsFilter(2025);
		filter.applyDateRange(StatsFilter.DateRange.LAST_MONTH, SESSION_2025);

		LocalDate lastMonth = LocalDate.now().minusMonths(1);

		assertEquals(lastMonth.withDayOfMonth(1), filter.from);
		assertEquals(lastMonth.withDayOfMonth(lastMonth.lengthOfMonth()), filter.to);
		assertEquals("both ends must fall in the same month", filter.from.getMonth(),
				filter.to.getMonth());
	}

	@Test
	public void lastMonthAndLastThirtyDaysAreNotTheSameWindow() {
		// They are offered side by side and mean different things: one is a calendar month,
		// the other a rolling window that usually straddles two.
		StatsFilter calendar = new StatsFilter(2025);
		calendar.applyDateRange(StatsFilter.DateRange.LAST_MONTH, SESSION_2025);

		StatsFilter rolling = new StatsFilter(2025);
		rolling.applyDateRange(StatsFilter.DateRange.LAST_30_DAYS, SESSION_2025);

		assertNotEquals(calendar.from, rolling.from);
		assertNotEquals(calendar.to, rolling.to);
	}

	@Test
	public void lastMonthCrossesTheYearBoundaryWithoutSpecialCasing() {
		// January's "last month" is December of the year before, which minusMonths handles and
		// a withMonth(month - 1) would not.
		LocalDate january = LocalDate.of(2026, 1, 15);
		LocalDate lastMonth = january.minusMonths(1);

		assertEquals(2025, lastMonth.getYear());
		assertEquals(12, lastMonth.getMonthValue());
		assertEquals(31, lastMonth.withDayOfMonth(lastMonth.lengthOfMonth()).getDayOfMonth());
	}

	@Test
	public void lastThirtyDaysIsThirtyDaysInclusiveOfToday() {
		StatsFilter filter = new StatsFilter(2025);
		filter.applyDateRange(StatsFilter.DateRange.LAST_30_DAYS, SESSION_2025);

		assertEquals(LocalDate.now(), filter.to);
		assertEquals(30, ChronoUnit.DAYS.between(filter.from, filter.to) + 1);
	}

	@Test
	public void customLeavesWhateverTheCallerAlreadySet() {
		StatsFilter filter = new StatsFilter(2025);
		filter.from = LocalDate.of(2025, 9, 3);
		filter.to = LocalDate.of(2025, 9, 17);

		filter.applyDateRange(StatsFilter.DateRange.CUSTOM, SESSION_2025);

		assertEquals(LocalDate.of(2025, 9, 3), filter.from);
		assertEquals(LocalDate.of(2025, 9, 17), filter.to);
		assertEquals(StatsFilter.DateRange.CUSTOM, filter.dateRange);
	}

	@Test
	public void epochBoundsAreInclusiveOfTheWholeFinalDay() {
		StatsFilter filter = new StatsFilter(2025);
		filter.from = LocalDate.of(2025, 9, 3);
		filter.to = LocalDate.of(2025, 9, 17);

		ZoneId zone = ZoneId.systemDefault();
		long fivePmOnTheLastDay = LocalDate.of(2025, 9, 17).atTime(17, 0)
				.atZone(zone).toEpochSecond();

		assertEquals(Long.valueOf(LocalDate.of(2025, 9, 3)
				.atStartOfDay(zone).toEpochSecond()), filter.fromEpoch());
		assertTrue("a 5 p.m. lecture on the final day must be inside the window",
				fivePmOnTheLastDay <= filter.toEpoch());
		assertTrue(filter.toEpoch()
				< LocalDate.of(2025, 9, 18).atStartOfDay(zone).toEpochSecond());
	}

	@Test
	public void unsetBoundsYieldNullEpochsRatherThanSentinels() {
		StatsFilter filter = new StatsFilter(2025);
		assertNull(filter.fromEpoch());
		assertNull(filter.toEpoch());
	}

	// ── Filter bookkeeping ───────────────────────────────────────────────

	@Test
	public void theSessionAndGroupingDoNotCountAsActiveFilters() {
		// Otherwise the "filters active" indicator would be permanently lit.
		StatsFilter filter = new StatsFilter(2025);
		filter.groupBy = StatsGroupBy.COURSE;
		filter.sortOrder = StatsFilter.SortOrder.PERCENT_DESC;
		filter.threshold = 60d;

		assertFalse(filter.hasActiveFilters());

		filter.courseIDs.add(3L);
		assertTrue(filter.hasActiveFilters());
	}

	@Test
	public void everyNarrowingFieldRegistersAsAnActiveFilter() {
		StatsFilter base = new StatsFilter(2025);
		assertFalse(base.hasActiveFilters());

		StatsFilter[] each = new StatsFilter[11];
		for (int i = 0; i < each.length; i++) {
			each[i] = new StatsFilter(2025);
		}

		each[0].from = LocalDate.now();
		each[1].to = LocalDate.now();
		each[2].courseIDs.add(1L);
		each[3].batchID = 1L;
		each[4].professorID = 1L;
		each[5].studentID = 1L;
		each[6].courseTypes.add(CourseType.MAJOR);
		each[7].year = Year.SY;
		each[8].stream = Stream.BSc;
		each[9].department = Department.PHYSICS;
		each[10].division = Division.B;

		for (int i = 0; i < each.length; i++) {
			assertTrue("field " + i + " does not register as an active filter",
					each[i].hasActiveFilters());
		}
	}

	@Test
	public void clearFiltersKeepsHowTheReportIsBeingReadAndDropsWhatItIsNarrowedTo() {
		StatsFilter filter = new StatsFilter(2025);
		filter.groupBy = StatsGroupBy.MONTH;
		filter.sortOrder = StatsFilter.SortOrder.TOTAL_DESC;
		filter.threshold = 60d;
		filter.courseIDs.add(1L);
		filter.batchID = 2L;
		filter.professorID = 3L;
		filter.studentID = 4L;
		filter.courseTypes.add(CourseType.OE);
		filter.year = Year.TY;
		filter.stream = Stream.BSc;
		filter.department = Department.PHYSICS;
		filter.division = Division.C;
		filter.applyDateRange(StatsFilter.DateRange.THIS_MONTH, SESSION_2025);

		filter.clearFilters();

		assertFalse(filter.hasActiveFilters());
		assertEquals(StatsFilter.DateRange.ALL_TIME, filter.dateRange);
		assertEquals(Integer.valueOf(2025), filter.academicYear);
		assertEquals(StatsGroupBy.MONTH, filter.groupBy);
		assertEquals(StatsFilter.SortOrder.TOTAL_DESC, filter.sortOrder);
		assertEquals(60d, filter.threshold, 0d);
	}

	@Test
	public void copyIsADetachedSnapshot() {
		// A query in flight must not see an edit made while it runs.
		StatsFilter filter = new StatsFilter(2025);
		filter.courseIDs.add(1L);
		filter.year = Year.SY;
		filter.threshold = 60d;
		filter.from = LocalDate.of(2025, 9, 1);

		StatsFilter snapshot = filter.copy();

		filter.courseIDs.add(99L);
		filter.year = Year.TY;
		filter.threshold = 40d;
		filter.from = LocalDate.of(2024, 1, 1);
		filter.groupBy = StatsGroupBy.WEEKDAY;

		assertEquals(Collections.singleton(1L), snapshot.courseIDs);
		assertEquals(Year.SY, snapshot.year);
		assertEquals(60d, snapshot.threshold, 0d);
		assertEquals(LocalDate.of(2025, 9, 1), snapshot.from);
		assertEquals(StatsGroupBy.STUDENT, snapshot.groupBy);
	}

	@Test
	public void theCourseSetIsCopiedRatherThanShared() {
		// A shallow copy would leave a query in flight reading the set the screen is still
		// editing, which is the one case copy() exists to prevent.
		StatsFilter filter = new StatsFilter(2025);
		filter.courseIDs.add(1L);

		StatsFilter snapshot = filter.copy();
		filter.courseIDs.add(2L);
		filter.courseIDs.remove(1L);

		assertEquals(Collections.singleton(1L), snapshot.courseIDs);
	}

	@Test
	public void clearingTheFiltersDropsEveryChosenCourse() {
		StatsFilter filter = new StatsFilter(2025);
		filter.courseIDs.add(1L);
		filter.courseIDs.add(2L);

		filter.clearFilters();

		assertTrue(filter.courseIDs.isEmpty());
		assertFalse(filter.hasActiveFilters());
	}

	@Test
	public void theCourseTypeSetIsCopiedRatherThanShared() {
		StatsFilter filter = new StatsFilter(2025);
		filter.courseTypes.add(CourseType.MAJOR);

		StatsFilter snapshot = filter.copy();
		filter.courseTypes.add(CourseType.MINOR);
		filter.courseTypes.remove(CourseType.MAJOR);

		assertEquals(Collections.singleton(CourseType.MAJOR), snapshot.courseTypes);
	}

	@Test
	public void clearingTheFiltersDropsEveryChosenCourseType() {
		StatsFilter filter = new StatsFilter(2025);
		filter.courseTypes.addAll(CourseType.practicals());

		filter.clearFilters();

		assertTrue(filter.courseTypes.isEmpty());
		assertFalse(filter.hasActiveFilters());
	}

	@Test
	public void copyCarriesEveryFieldAcross() {
		StatsFilter filter = new StatsFilter(2025);
		filter.groupBy = StatsGroupBy.PROFESSOR;
		filter.sortOrder = StatsFilter.SortOrder.PERCENT_DESC;
		filter.from = LocalDate.of(2025, 9, 1);
		filter.to = LocalDate.of(2025, 9, 30);
		filter.dateRange = StatsFilter.DateRange.CUSTOM;
		filter.courseIDs.add(1L);
		filter.batchID = 2L;
		filter.professorID = 3L;
		filter.studentID = 4L;
		filter.courseTypes.add(CourseType.IKS);
		// A consistent combination, since a division only exists on an undergraduate BSc.
		filter.year = Year.TY;
		filter.stream = Stream.BSc;
		filter.department = Department.CHEMISTRY;
		filter.division = Division.D;
		filter.threshold = 80d;

		StatsFilter copy = filter.copy();

		assertEquals(filter.academicYear, copy.academicYear);
		assertEquals(filter.groupBy, copy.groupBy);
		assertEquals(filter.sortOrder, copy.sortOrder);
		assertEquals(filter.from, copy.from);
		assertEquals(filter.to, copy.to);
		assertEquals(filter.dateRange, copy.dateRange);
		assertEquals(filter.courseIDs, copy.courseIDs);
		assertEquals(filter.batchID, copy.batchID);
		assertEquals(filter.professorID, copy.professorID);
		assertEquals(filter.studentID, copy.studentID);
		assertEquals(filter.courseTypes, copy.courseTypes);
		assertEquals(filter.year, copy.year);
		assertEquals(filter.stream, copy.stream);
		assertEquals(filter.department, copy.department);
		assertEquals(filter.division, copy.division);
		assertEquals(filter.threshold, copy.threshold, 0d);
	}

	// ── ExportFilter ─────────────────────────────────────────────────────

	@Test
	public void anUntouchedExportFilterMeansTheWholeSession() {
		ExportFilter filter = new ExportFilter();

		assertTrue(filter.isUnrestricted());
		assertEquals("All courses and students for the session", filter.describe());
	}

	@Test
	public void describeNamesEveryRestrictionInUse() {
		ExportFilter filter = new ExportFilter();
		filter.from = LocalDate.of(2025, 7, 1);
		filter.to = LocalDate.of(2025, 7, 31);
		filter.courseIDs.add(1L);
		filter.courseIDs.add(2L);
		filter.departments.add(Department.PHYSICS);
		filter.years.add(Year.SY);
		filter.streams.add(Stream.BSc_unaided);
		filter.divisions.add(Division.A);
		filter.divisions.add(Division.C);

		String description = filter.describe();

		assertFalse(filter.isUnrestricted());
		assertTrue(description.contains("2025-07-01"));
		assertTrue(description.contains("2025-07-31"));
		assertTrue(description.contains("2 selected"));
		assertTrue(description.contains("Physics"));
		assertTrue(description.contains("SY"));
		assertTrue("the description is read by a person, so it uses labels",
				description.contains("BSc (unaided)"));
		assertTrue("a sheet narrowed to a division has to say so on its face",
				description.contains("Divisions: A, C"));
	}

	@Test
	public void anOpenEndedDateRangeSaysSoRatherThanShowingABlank() {
		ExportFilter from = new ExportFilter();
		from.from = LocalDate.of(2025, 7, 1);
		assertTrue(from.describe().contains("end of session"));

		ExportFilter to = new ExportFilter();
		to.to = LocalDate.of(2025, 7, 31);
		assertTrue(to.describe().contains("start of session"));
	}

	@Test
	public void everyRestrictionKindOnItsOwnMakesTheFilterRestricted() {
		ExportFilter dates = new ExportFilter();
		dates.from = LocalDate.now();
		assertFalse(dates.isUnrestricted());

		ExportFilter courses = new ExportFilter();
		courses.courseIDs.add(1L);
		assertFalse(courses.isUnrestricted());

		ExportFilter departments = new ExportFilter();
		departments.departments.add(Department.PHYSICS);
		assertFalse(departments.isUnrestricted());

		ExportFilter years = new ExportFilter();
		years.years.add(Year.SY);
		assertFalse(years.isUnrestricted());

		ExportFilter streams = new ExportFilter();
		streams.streams.add(Stream.BSc);
		assertFalse(streams.isUnrestricted());

		ExportFilter divisions = new ExportFilter();
		divisions.divisions.add(Division.B);
		assertFalse(divisions.isUnrestricted());
	}

	// ── CourseGroup ──────────────────────────────────────────────────────

	@Test
	public void anEmptyGroupIsSkippedRatherThanTreatedAsAnError() {
		// One is created the moment the user adds a row and before they pick any courses.
		CourseGroup group = new CourseGroup("Semester 3");
		assertFalse(group.isUsable());

		group.courseCodes.add("PHY301");
		assertTrue(group.isUsable());
	}

	@Test
	public void aGroupHoldsEachCourseOnceAndKeepsSelectionOrder() {
		CourseGroup group = new CourseGroup("Theory");
		group.courseCodes.add("PHY301");
		group.courseCodes.add("CHE301");
		group.courseCodes.add("PHY301");

		assertEquals(2, group.courseCodes.size());
		assertEquals("PHY301, CHE301", group.describeCourses());
	}

	@Test
	public void noneReturnsAFreshMutableListEachTime() {
		// Callers add to what they are given, so a shared constant would leak between exports.
		assertTrue(CourseGroup.none().isEmpty());
		CourseGroup.none().add(new CourseGroup("x"));
		assertTrue(CourseGroup.none().isEmpty());
		assertNotNull(CourseGroup.none());
	}
}
