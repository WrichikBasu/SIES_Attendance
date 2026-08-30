package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Division;
import in.basulabs.siesattendance.backend.EntityAcademicYear;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.TypeConvertors;
import in.basulabs.siesattendance.backend.Year;
import in.basulabs.siesattendance.backend.pojo.ClassKey;
import in.basulabs.siesattendance.backend.pojo.StatRow;
import in.basulabs.siesattendance.backend.pojo.StatsSummary;
import in.basulabs.siesattendance.backend.pojo.StudentAttendanceStat;
import in.basulabs.siesattendance.backend.pojo.StudentCourseStat;
import in.basulabs.siesattendance.backend.pojo.StudentWithYear;
import in.basulabs.siesattendance.backend.util.NameFormat;

/**
 * Display formatting, the persisted-value converters, and the percentage arithmetic every
 * screen and report depends on.
 */
public class FormattingAndConversionTest {

	// ── NameFormat ───────────────────────────────────────────────────────

	@Test
	public void properCaseCapitalisesEveryPartOfAName() {
		assertEquals("Aarav Patil", NameFormat.properCase("AARAV PATIL"));
		assertEquals("Aarav Patil", NameFormat.properCase("aarav patil"));
		assertEquals("Aarav Patil", NameFormat.properCase("aArAv pAtIl"));
	}

	@Test
	public void properCaseSplitsOnHyphensApostrophesAndDots() {
		assertEquals("Mary-Jane D'Souza", NameFormat.properCase("MARY-JANE D'SOUZA"));
		assertEquals("A.K. Sharma", NameFormat.properCase("a.k. sharma"));
		assertEquals("O'Brien", NameFormat.properCase("o'brien"));
	}

	@Test
	public void properCaseCollapsesStrayWhitespace() {
		assertEquals("Aarav Patil", NameFormat.properCase("  AARAV   PATIL  "));
		assertEquals("Aarav Patil", NameFormat.properCase("AARAV\tPATIL"));
	}

	@Test
	public void properCaseHandlesNullAndBlank() {
		assertEquals("", NameFormat.properCase(null));
		assertEquals("", NameFormat.properCase(""));
		assertEquals("", NameFormat.properCase("   "));
	}

	@Test
	public void properCaseKnownLimitationIsStillDeterministic() {
		// Documented in the class: no dictionary, so these come out "wrong but predictably".
		assertEquals("Mcdonald", NameFormat.properCase("MCDONALD"));
		assertEquals("Van Der Berg", NameFormat.properCase("van der berg"));
	}

	@Test
	public void initialsTakeFirstAndLastWord() {
		assertEquals("AP", NameFormat.initials("AARAV PATIL"));
		assertEquals("AS", NameFormat.initials("Aarav Kumar Sharma"));
	}

	@Test
	public void initialsSplitOnWhitespaceOnlySoSurnamesStayWhole() {
		// "Mary-Jane D'Souza" must give MD, not MS: the hyphen is not a word boundary here
		// even though properCase treats it as one.
		assertEquals("MD", NameFormat.initials("MARY-JANE D'SOUZA"));
	}

	@Test
	public void initialsOfASingleWordAreItsFirstTwoLetters() {
		assertEquals("AA", NameFormat.initials("Aarav"));
		assertEquals("OB", NameFormat.initials("O'Brien"));
		assertEquals("A", NameFormat.initials("A"));
	}

	@Test
	public void initialsFallBackToAQuestionMark() {
		assertEquals("?", NameFormat.initials(null));
		assertEquals("?", NameFormat.initials(""));
		assertEquals("?", NameFormat.initials("   "));
		assertEquals("?", NameFormat.initials("123"));
		assertEquals("?", NameFormat.initials("!!!"));
	}

	@Test
	public void initialsSkipLeadingPunctuation() {
		assertEquals("AP", NameFormat.initials("'aarav 'patil"));
	}

	// ── TypeConvertors ───────────────────────────────────────────────────

	@Test
	public void zonedDateTimeRoundTripsIncludingZoneAndNanos() {
		ZonedDateTime original = ZonedDateTime.of(2025, 6, 22, 10, 30, 0, 123_456_789,
				ZoneId.of("Asia/Kolkata"));

		String stored = TypeConvertors.fromZonedDateTime(original);
		ZonedDateTime back = TypeConvertors.toZonedDateTime(stored);

		assertEquals(original.toInstant(), back.toInstant());
		assertEquals(original.getZone(), back.getZone());
		assertEquals(original.getNano(), back.getNano());
	}

	@Test
	public void storedTimestampLeadsWithThePlainEpochSecond() {
		// Every date-filtering query slices this prefix out with SUBSTR/INSTR and casts it,
		// so the format is load-bearing, not an implementation detail.
		ZonedDateTime t = ZonedDateTime.of(2025, 6, 22, 10, 30, 0, 0,
				ZoneId.of("Asia/Kolkata"));

		String stored = TypeConvertors.fromZonedDateTime(t);
		int bar = stored.indexOf('|');

		assertTrue("no separator in " + stored, bar > 0);
		assertEquals(t.toEpochSecond(), Long.parseLong(stored.substring(0, bar)));
		assertEquals(t.toEpochSecond() + "|0|Asia/Kolkata", stored);
	}

	@Test
	public void epochPrefixIsFixedWidthForEveryDateTheAppWillSee() {
		// getAllDetails() orders on the raw stored string. That only sorts correctly while
		// the epoch prefix has a constant digit count.
		int width = -1;
		for (int year = 2001; year <= 2286; year++) {
			ZonedDateTime t = ZonedDateTime.of(year, 9, 10, 12, 0, 0, 0, ZoneId.of("UTC"));
			String stored = TypeConvertors.fromZonedDateTime(t);
			int digits = stored.indexOf('|');
			if (width == -1) {
				width = digits;
			}
			assertEquals("epoch width changes at " + year, width, digits);
		}
		assertEquals(10, width);
	}

	@Test
	public void localDateIsStoredAsAnEpochDayAndSurvivesAZoneChange() {
		LocalDate date = LocalDate.of(2025, 6, 1);
		assertEquals(date, TypeConvertors.toLocalDate(TypeConvertors.fromLocalDate(date)));
		assertEquals(Long.valueOf(date.toEpochDay()), TypeConvertors.fromLocalDate(date));
		assertNull(TypeConvertors.fromLocalDate(null));
		assertNull(TypeConvertors.toLocalDate(null));
	}

	@Test
	public void enumsAreStoredByNameNotLabel() {
		// Two Stream constants share the base label "BSc"; storing labels would have made
		// them indistinguishable from the day they were added.
		assertEquals("BSc_unaided", TypeConvertors.fromStream(Stream.BSc_unaided));
		assertEquals("COMPUTER_SCIENCE",
				TypeConvertors.fromDepartment(Department.COMPUTER_SCIENCE));
		assertEquals("Masters_P1", TypeConvertors.fromYear(Year.Masters_P1));

		for (Stream s : Stream.values()) {
			assertEquals(s, TypeConvertors.toStream(TypeConvertors.fromStream(s)));
		}
		for (Department d : Department.values()) {
			assertEquals(d, TypeConvertors.toDepartment(TypeConvertors.fromDepartment(d)));
		}
		for (Year y : Year.values()) {
			assertEquals(y, TypeConvertors.toYear(TypeConvertors.fromYear(y)));
		}
		for (Division d : Division.values()) {
			assertEquals(d, TypeConvertors.toDivision(TypeConvertors.fromDivision(d)));
		}

		// A division is the one nullable enum column on a student's enrolment, so both
		// directions have to carry the absence rather than inventing a value for it.
		assertNull(TypeConvertors.fromDivision(null));
		assertNull(TypeConvertors.toDivision(null));
	}

	// ── EntityAcademicYear ───────────────────────────────────────────────

	@Test
	public void sessionBoundsCoverTheWholeOfTheFinalDay() {
		EntityAcademicYear session = EntityAcademicYear.of(2025,
				LocalDate.of(2025, 6, 1), LocalDate.of(2026, 5, 31));

		ZoneId zone = ZoneId.systemDefault();
		long fivePmOnTheLastDay = LocalDate.of(2026, 5, 31)
				.atTime(17, 0).atZone(zone).toEpochSecond();
		long midnightAfter = LocalDate.of(2026, 6, 1).atStartOfDay(zone).toEpochSecond();

		assertTrue("5 p.m. on the last day must still be inside the session",
				fivePmOnTheLastDay <= session.endEpoch);
		assertTrue("the session must end before the next day begins",
				session.endEpoch < midnightAfter);
		assertEquals(LocalDate.of(2025, 6, 1).atStartOfDay(zone).toEpochSecond(),
				session.startEpoch);
	}

	@Test
	public void sessionContainsIsInclusiveAtBothEnds() {
		EntityAcademicYear s = EntityAcademicYear.defaultFor(2025);
		assertTrue(s.contains(LocalDate.of(2025, 6, 1)));
		assertTrue(s.contains(LocalDate.of(2026, 5, 31)));
		assertFalse(s.contains(LocalDate.of(2025, 5, 31)));
		assertFalse(s.contains(LocalDate.of(2026, 6, 1)));
	}

	@Test
	public void defaultYearForFollowsTheJuneBoundary() {
		assertEquals(2025, EntityAcademicYear.defaultYearFor(LocalDate.of(2025, 6, 1)));
		assertEquals(2025, EntityAcademicYear.defaultYearFor(LocalDate.of(2025, 12, 31)));
		assertEquals(2025, EntityAcademicYear.defaultYearFor(LocalDate.of(2026, 5, 31)));
		assertEquals(2024, EntityAcademicYear.defaultYearFor(LocalDate.of(2025, 5, 31)));
	}

	@Test
	public void sessionLabelPadsTheSecondYearToTwoDigits() {
		assertEquals("2025-26", EntityAcademicYear.defaultFor(2025).label());
		assertEquals("2009-10", EntityAcademicYear.defaultFor(2009).label());
		// The rollover case: 2099-2100 must read "2099-00", not "2099-0".
		assertEquals("2099-00", EntityAcademicYear.defaultFor(2099).label());
	}

	@Test
	public void consecutiveDefaultSessionsAbutWithoutOverlappingOrLeavingAGap() {
		EntityAcademicYear a = EntityAcademicYear.defaultFor(2024);
		EntityAcademicYear b = EntityAcademicYear.defaultFor(2025);

		assertTrue("sessions overlap: a lecture would join two sessions and double-count",
				a.endEpoch < b.startEpoch);
		assertEquals("gap between sessions: lectures in it become invisible to every report",
				a.endEpoch + 1, b.startEpoch);
	}

	// ── Percentage arithmetic ────────────────────────────────────────────

	@Test
	public void percentagesAreZeroRatherThanNaNWhenNothingWasRecorded() {
		assertEquals(0d, new StudentAttendanceStat(1, "A", "R1", 0, 0).percentage(), 0d);
		assertEquals(0d, new StudentCourseStat(1, "C", "T", 0, 0).percentage(), 0d);
		assertEquals(0d, new StatRow().percentage(), 0d);
		assertEquals(0d, new StatsSummary().percentage(), 0d);
	}

	@Test
	public void nothingRecordedIsNeverCountedAsAShortfall() {
		// 0% of nothing is not a defaulter; every "is short" check has to agree on that.
		assertFalse(new StudentAttendanceStat(1, "A", "R1", 0, 0).isShort());
		assertFalse(new StudentCourseStat(1, "C", "T", 0, 0).isShort());
		assertFalse(new StatRow().isBelow(75d));

		StatRow row = new StatRow();
		row.total = 4;
		row.attended = 0;
		assertTrue("0 out of 4 IS a shortfall", row.isBelow(75d));
	}

	@Test
	public void theSeventyFivePercentLineIsExclusive() {
		// Exactly 75% is not short. 74.9% is.
		assertFalse(new StudentAttendanceStat(1, "A", "R1", 4, 3).isShort());
		assertTrue(new StudentAttendanceStat(1, "A", "R1", 5, 3).isShort());
		assertEquals(75d, new StudentAttendanceStat(1, "A", "R1", 4, 3).percentage(), 1e-9);
	}

	@Test
	public void summaryDistinguishesAnEmptyFilterFromZeroPerCent() {
		StatsSummary empty = new StatsSummary();
		assertTrue(empty.isEmpty());

		StatsSummary allAbsent = new StatsSummary();
		allAbsent.total = 10;
		allAbsent.attended = 0;
		assertFalse(allAbsent.isEmpty());
		assertEquals(0d, allAbsent.percentage(), 0d);
		assertEquals(10, allAbsent.absent());
	}

	// ── Class labels ─────────────────────────────────────────────────────

	@Test
	public void classLabelsDropTheUnaidedQualifierAndAgreeWithEachOther() {
		ClassKey key = new ClassKey(Year.SY, Stream.BSc_unaided,
				Department.COMPUTER_SCIENCE, null, 42);

		StudentWithYear student = new StudentWithYear(1L, "AARAV PATIL", "he/him",
				"a@sies.edu.in", "2024CS032", 2025, Year.SY, Stream.BSc_unaided,
				Department.COMPUTER_SCIENCE, null, null, 3, null);

		assertEquals("SY BSc CS", key.label());
		assertEquals("the two labels must agree wherever both appear",
				key.label(), student.classLabel());
		assertFalse(key.label().contains("unaided"));
	}

	@Test
	public void aidedClassLabelsAreLeftAlone() {
		assertEquals("SY BSc Physics",
				new ClassKey(Year.SY, Stream.BSc, Department.PHYSICS, null, 1).label());
		assertEquals("Masters Part 1 MSc Physics",
				new ClassKey(Year.Masters_P1, Stream.MSc, Department.PHYSICS, null, 1).label());
	}

	@Test
	public void aDivisionIsAppendedToTheClassLabelAndOnlyWhenThereIsOne() {
		assertEquals("SY BSc Physics Div B",
				new ClassKey(Year.SY, Stream.BSc, Department.PHYSICS, Division.B, 1).label());
		assertEquals("FY BA English Div A",
				new ClassKey(Year.FY, Stream.BA, Department.ENGLISH, Division.A, 1).label());

		assertEquals("a class with no division reads exactly as it did before",
				"SY BSc Physics",
				new ClassKey(Year.SY, Stream.BSc, Department.PHYSICS, null, 1).label());
	}

	@Test
	public void aStudentAndTheirClassChipAgreeOnTheDivision() {
		ClassKey key = new ClassKey(Year.FY, Stream.BA, Department.ENGLISH, Division.C, 30);

		StudentWithYear student = new StudentWithYear(2L, "MEERA IYER", "she/her", null,
				"2025EN014", 2025, Year.FY, Stream.BA, Department.ENGLISH, null, null, 1,
				Division.C);

		assertEquals("the two labels must agree wherever both appear",
				key.label(), student.classLabel());
	}

	@Test
	public void studentDisplayHelpersTidyTheRawRosterName() {
		StudentWithYear student = new StudentWithYear(1L, "AARAV  PATIL", "he/him", null,
				"2024CS032", 2025, Year.SY, Stream.BSc_unaided,
				Department.COMPUTER_SCIENCE, null, null, null, null);

		assertEquals("Aarav Patil", student.displayName());
		assertEquals("AP", student.initials());
		assertEquals("the stored value is left exactly as imported",
				"AARAV  PATIL", student.name);
	}

	@Test
	public void statRowCorrectsOnlyTheLabelThatHoldsAName() {
		StatRow row = new StatRow();
		row.primaryLabel = "2024CS032";
		row.secondaryLabel = "AARAV PATIL";

		// Grouped by student: the name is the secondary label.
		assertEquals("2024CS032", row.displayPrimary(
				in.basulabs.siesattendance.backend.stats.StatsGroupBy.NameField.SECONDARY));
		assertEquals("Aarav Patil", row.displaySecondary(
				in.basulabs.siesattendance.backend.stats.StatsGroupBy.NameField.SECONDARY));

		// Grouped by something with no names: both left untouched.
		assertEquals("2024CS032", row.displayPrimary(
				in.basulabs.siesattendance.backend.stats.StatsGroupBy.NameField.NONE));
		assertEquals("AARAV PATIL", row.displaySecondary(
				in.basulabs.siesattendance.backend.stats.StatsGroupBy.NameField.NONE));
	}

	@Test
	public void anEmptySecondaryLabelBecomesNullSoTheViewCanBeHidden() {
		StatRow row = new StatRow();
		row.primaryLabel = "Monday";
		row.secondaryLabel = "";
		assertNull(row.displaySecondary(
				in.basulabs.siesattendance.backend.stats.StatsGroupBy.NameField.NONE));
	}
}
