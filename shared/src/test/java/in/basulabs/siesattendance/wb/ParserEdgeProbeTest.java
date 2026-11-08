package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import in.basulabs.siesattendance.backend.util.AttendanceCsvParser;
import in.basulabs.siesattendance.backend.util.AttendanceCsvParser.Sheet;
import in.basulabs.siesattendance.backend.util.RosterCsv;

/**
 * White-box probes of the two shared CSV parsers, aimed at the branches the existing suites
 * leave alone: impossible calendar dates, malformed quoting, header collisions and numeric
 * bounds.
 * <p>
 * Both parsers try a list of {@code DateTimeFormatter}s in turn. Every pattern-built formatter
 * there uses the default {@code ResolverStyle.SMART}, which <em>clamps</em> a day that is too
 * large for its month to the month's last day instead of refusing it. Only
 * {@code ISO_LOCAL_DATE} is STRICT. The tests marked "date clamping" pin that down.
 */
public class ParserEdgeProbeTest {

	// ── Roster dates (date clamping) ─────────────────────────────────────

	/** ISO is STRICT, so the same impossible day is refused there. The control case. */
	@Test
	public void rosterRefusesAnImpossibleIsoDate() {
		assertThrows(IllegalArgumentException.class,
				() -> RosterCsv.parseDate("2026-02-31", "join date"));
	}

	/**
	 * BUG: {@code 31/02/2026} is read as 28 Feb 2026. A typo in a join or leave date then moves
	 * a student's attendance window without a word, where the ISO spelling of the same typo
	 * is refused.
	 */
	@Test
	public void rosterRefusesAnImpossibleSlashDate() {
		assertThrows("31/02/2026 must be refused, not clamped to 28 Feb",
				IllegalArgumentException.class,
				() -> RosterCsv.parseDate("31/02/2026", "join date"));
	}

	/** BUG: the same, for 31 April in the dash spelling, which is clamped to 30 April. */
	@Test
	public void rosterRefusesAnImpossibleDashDate() {
		assertThrows("31-04-2026 must be refused, not clamped to 30 Apr",
				IllegalArgumentException.class,
				() -> RosterCsv.parseDate("31-04-2026", "leave date"));
	}

	/** A day that exists only in a leap year: 29 Feb 2028 is fine, 29 Feb 2027 is not. */
	@Test
	public void rosterKnowsLeapYears() {
		assertEquals(LocalDate.of(2028, 2, 29), RosterCsv.parseDate("29/02/2028", "d"));
		assertThrows("29/02/2027 must be refused, not clamped to 28 Feb",
				IllegalArgumentException.class,
				() -> RosterCsv.parseDate("29/02/2027", "d"));
	}

	@Test
	public void rosterRefusesTwoDigitYearsAndDayZero() {
		assertThrows(IllegalArgumentException.class, () -> RosterCsv.parseDate("15/06/26", "d"));
		assertThrows(IllegalArgumentException.class,
				() -> RosterCsv.parseDate("00/06/2026", "d"));
		assertThrows(IllegalArgumentException.class,
				() -> RosterCsv.parseDate("32/01/2026", "d"));
		assertThrows(IllegalArgumentException.class,
				() -> RosterCsv.parseDate("15/13/2026", "d"));
	}

	// ── Attendance-sheet dates (date clamping) ───────────────────────────

	private static Sheet sheetDated(String date) {
		return AttendanceCsvParser.parse(Arrays.asList(
				"Course code,PHYS101",
				"Date," + date,
				"Start time,09:00",
				"End time,10:00",
				"2024PH001,P"));
	}

	/**
	 * BUG: 31 June does not exist. The sheet imports marks against 30 June instead, a real
	 * lecture date that may already hold a different class.
	 */
	@Test
	public void anAttendanceSheetRefusesThirtyFirstOfJune() {
		Sheet sheet = sheetDated("31/06/2025");

		boolean refused = !sheet.errors.isEmpty() || !sheet.columns.get(0).isUsable();
		assertTrue("31/06/2025 was accepted as " + sheet.columns.get(0).date
				+ " instead of being refused", refused);
	}

	@Test
	public void anAttendanceSheetRefusesThirtyFirstOfJuneSpelledOut() {
		Sheet sheet = sheetDated("31 Jun 2025");

		boolean refused = !sheet.errors.isEmpty() || !sheet.columns.get(0).isUsable();
		assertTrue("31 Jun 2025 was accepted as " + sheet.columns.get(0).date, refused);
	}

	/** The ISO control case: STRICT, so refused. */
	@Test
	public void anAttendanceSheetRefusesAnImpossibleIsoDate() {
		Sheet sheet = sheetDated("2025-06-31");
		assertTrue(!sheet.errors.isEmpty() || !sheet.columns.get(0).isUsable());
	}

	/** Every date spelling the format help offers still reads, now under STRICT. */
	@Test
	public void everyAcceptedDateSpellingStillReads() {
		for (String spelling : List.of("2025-07-01", "1/7/2025", "01/07/2025", "1-7-2025",
				"1.7.2025", "1 Jul 2025", "1 july 2025", "01-JUL-2025")) {
			Sheet sheet = sheetDated(spelling);
			assertTrue(spelling + ": " + sheet.errors + " " + sheet.columns.get(0).problems,
					sheet.errors.isEmpty() && sheet.columns.get(0).isUsable());
			assertEquals(spelling, LocalDate.of(2025, 7, 1), sheet.columns.get(0).date);
		}
	}

	private static Sheet sheetTimed(String start, String end) {
		return AttendanceCsvParser.parse(Arrays.asList(
				"Course code,PHYS101",
				"Date,2025-07-01",
				"Start time," + start,
				"End time," + end,
				"2024PH001,P"));
	}

	/** And every time spelling, in both halves of the day. */
	@Test
	public void everyAcceptedTimeSpellingStillReads() {
		String[][] pairs = {
				{"9:00", "10:00"}, {"09:00:00", "10:30:00"}, {"9:00 am", "10:00 AM"},
				{"9:00am", "10:00PM"}, {"9 am", "10 am"}, {"12 pm", "1 pm"},
				{"11:00 pm", "11:59 pm"}};

		for (String[] pair : pairs) {
			Sheet sheet = sheetTimed(pair[0], pair[1]);
			assertTrue(pair[0] + "–" + pair[1] + ": " + sheet.errors + " "
							+ sheet.columns.get(0).problems,
					sheet.errors.isEmpty() && sheet.columns.get(0).isUsable());
		}

		assertEquals(java.time.LocalTime.of(12, 0),
				sheetTimed("12 pm", "1 pm").columns.get(0).start);
		assertEquals(java.time.LocalTime.of(13, 0),
				sheetTimed("12 pm", "1 pm").columns.get(0).end);
	}

	/** 24:00 is not a time of day. SMART used to read it as midnight. */
	@Test
	public void twentyFourHundredIsRefused() {
		Sheet sheet = sheetTimed("23:00", "24:00");
		assertTrue(!sheet.errors.isEmpty() || !sheet.columns.get(0).isUsable());
	}

	// ── Attendance-sheet structure ───────────────────────────────────────

	/** A class that ends before it starts is not a class. */
	@Test
	public void aClassThatEndsBeforeItStartsIsRefused() {
		Sheet sheet = AttendanceCsvParser.parse(Arrays.asList(
				"Course code,PHYS101",
				"Date,2025-07-01",
				"Start time,11:00",
				"End time,10:00",
				"2024PH001,P"));

		assertTrue(!sheet.errors.isEmpty() || !sheet.columns.get(0).isUsable());
	}

	/**
	 * One student listed twice with contradicting marks: the sheet cannot say which is true.
	 * Characterises what the parser does today; see the report.
	 */
	@Test
	public void aStudentListedTwiceWithContradictingMarksIsFlagged() {
		Sheet sheet = AttendanceCsvParser.parse(Arrays.asList(
				"Course code,PHYS101",
				"Date,2025-07-01",
				"Start time,09:00",
				"End time,10:00",
				"2024PH001,P",
				"2024PH001,AB"));

		boolean flagged = !sheet.errors.isEmpty() || !sheet.columns.get(0).isUsable();
		assertTrue("a roll number present and absent in the same class went unremarked",
				flagged);
	}

	@Test
	public void anEmptyFileIsAnErrorNotAnException() {
		Sheet sheet = AttendanceCsvParser.parse(List.of());
		assertFalse(sheet.isUsable());
	}

	@Test
	public void headingLinesAloneAreNotAnImport() {
		Sheet sheet = AttendanceCsvParser.parse(Arrays.asList(
				"Course code,PHYS101",
				"Date,2025-07-01",
				"Start time,09:00",
				"End time,10:00"));

		assertTrue(sheet.rows.isEmpty());
	}

	// ── Roster CSV splitting ─────────────────────────────────────────────

	@Test
	public void quotedCommasAndDoubledQuotesSurvive() {
		assertEquals(List.of("a", "b, c", "say \"hi\"", ""),
				RosterCsv.splitCsvLine("a,\"b, c\",\"say \"\"hi\"\"\","));
	}

	/**
	 * A row with an unbalanced quote swallows every comma after it. The row then fails on a
	 * missing column and is reported rather than silently misread. This pins that the failure
	 * is loud.
	 */
	@Test
	public void anUnbalancedQuoteCostsItsRowLoudly() throws IOException {
		RosterCsv.Reading reading = RosterCsv.read(new StringReader(
				"rollNo,name,year,stream,majorDept\n"
						+ "X1,\"Rao,FY,BSc,PHYSICS\n"
						+ "X2,Shah,FY,BSc,PHYSICS\n"));

		assertEquals(1, reading.rows().size());
		assertEquals("X2", reading.rows().get(0).rollNo());
		assertEquals(1, reading.errors().size());
	}

	/**
	 * A multi-line quoted cell, which Excel writes for a cell holding a line break, is split
	 * across two lines by the line-at-a-time reader. Both halves fail loudly; nothing is
	 * imported under a half-name.
	 */
	@Test
	public void aQuotedLineBreakDoesNotImportHalfAName() throws IOException {
		RosterCsv.Reading reading = RosterCsv.read(new StringReader(
				"rollNo,name,year,stream,majorDept\n"
						+ "X1,\"Priya\nRao\",FY,BSc,PHYSICS\n"));

		for (RosterCsv.Row row : reading.rows()) {
			assertFalse("a half-name was imported: " + row.name(),
					row.name().equals("Priya") || row.name().startsWith("Rao"));
		}
	}

	/**
	 * Two header cells that normalise to the same key: the later one silently wins.
	 * Characterisation; the report suggests refusing the header instead.
	 */
	@Test
	public void aDuplicatedHeaderIsResolvedToTheLastColumn() {
		assertEquals(Integer.valueOf(2),
				RosterCsv.parseHeader("roll_no,name,Roll No").get("rollno"));
	}

	@Test
	public void aByteOrderMarkIsStrippedFromTheHeader() {
		assertTrue(RosterCsv.parseHeader("﻿rollNo,name").containsKey("rollno"));
	}

	// ── APAAR IDs ────────────────────────────────────────────────────────

	@Test
	public void apaarIdBounds() {
		assertNull(RosterCsv.parseApaarId("   "));
		assertEquals(Long.valueOf(123456789012L), RosterCsv.parseApaarId(" 123456789012 "));
		assertEquals(Long.valueOf(999_999_999_999_999_999L),
				RosterCsv.parseApaarId("999999999999999999"));

		assertThrows(IllegalArgumentException.class, () -> RosterCsv.parseApaarId("0"));
		assertThrows(IllegalArgumentException.class, () -> RosterCsv.parseApaarId("000"));
		assertThrows("nineteen digits overflows the 18-digit guard",
				IllegalArgumentException.class,
				() -> RosterCsv.parseApaarId("9999999999999999999"));
		assertThrows(IllegalArgumentException.class, () -> RosterCsv.parseApaarId("-12"));
		assertThrows(IllegalArgumentException.class, () -> RosterCsv.parseApaarId("1e5"));
		assertThrows("fullwidth digits are not ASCII digits",
				IllegalArgumentException.class, () -> RosterCsv.parseApaarId("１２３"));
	}
}
