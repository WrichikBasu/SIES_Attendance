package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import in.basulabs.siesattendance.backend.util.AttendanceCsvParser;
import in.basulabs.siesattendance.backend.util.AttendanceCsvParser.Mark;
import in.basulabs.siesattendance.backend.util.AttendanceCsvParser.Sheet;
import in.basulabs.siesattendance.backend.util.BatchMatcher;

/**
 * The two halves of the attendance register import that can be reasoned about without a
 * database: reading the grid, and deciding which batch sat each session.
 * <p>
 * The batch rule is worth testing hard. It is the one part of the import that invents an answer
 * the file does not contain, and getting it wrong changes the attendance percentage of students
 * who were never in the room.
 */
public class AttendanceImportTest {

	// ── Reading the grid ─────────────────────────────────────────────────

	private static Sheet parse(String... lines) {
		return AttendanceCsvParser.parse(Arrays.asList(lines));
	}

	/** The shape described in the format help, read end to end. */
	@Test
	public void aWellFormedSheetYieldsOneColumnPerClassAndOneRowPerStudent() {

		Sheet sheet = parse(
				"Course code,PHYS101,PHYS102",
				"Date,2025-07-01,2025-07-01",
				"Start time,09:00,11:00",
				"End time,10:00,13:00",
				"2024PH001,P,AB",
				"2024PH002,AB,P");

		assertTrue(sheet.errors.isEmpty());
		assertEquals(2, sheet.columns.size());
		assertEquals(2, sheet.rows.size());

		assertEquals("PHYS101", sheet.columns.get(0).courseCode);
		assertEquals(LocalDate.of(2025, 7, 1), sheet.columns.get(0).date);
		assertEquals(LocalTime.of(9, 0), sheet.columns.get(0).start);
		assertEquals(LocalTime.of(10, 0), sheet.columns.get(0).end);
		assertTrue(sheet.columns.get(0).isUsable());

		assertEquals("2024PH001", sheet.rows.get(0).rollNo);
		assertEquals(5, sheet.rows.get(0).lineNumber);
		assertArrayEquals(new Mark[]{Mark.PRESENT, Mark.ABSENT}, sheet.rows.get(0).marks);
		assertArrayEquals(new Mark[]{Mark.ABSENT, Mark.PRESENT}, sheet.rows.get(1).marks);
	}

	/**
	 * The distinction the whole feature rests on: a blank cell is not an absence, and nothing
	 * anywhere may quietly turn it into one.
	 */
	@Test
	public void anEmptyCellIsNotInClassRatherThanAbsent() {

		Sheet sheet = parse(
				"Course code,PHYS101",
				"Date,2025-07-01",
				"Start time,09:00",
				"End time,10:00",
				"2024PH001,",
				"2024PH002,AB");

		assertEquals(Mark.NOT_IN_CLASS, sheet.rows.get(0).marks[0]);
		assertEquals(Mark.ABSENT, sheet.rows.get(1).marks[0]);
	}

	@Test
	public void marksAreReadCaseInsensitivelyAndInTheirLongerSpellings() {
		assertEquals(Mark.PRESENT, AttendanceCsvParser.parseMark("p"));
		assertEquals(Mark.PRESENT, AttendanceCsvParser.parseMark(" Present "));
		assertEquals(Mark.ABSENT, AttendanceCsvParser.parseMark("ab"));
		assertEquals(Mark.ABSENT, AttendanceCsvParser.parseMark("A"));
		assertEquals(Mark.ABSENT, AttendanceCsvParser.parseMark("Absent"));
		assertEquals(Mark.NOT_IN_CLASS, AttendanceCsvParser.parseMark(""));
		assertEquals(Mark.NOT_IN_CLASS, AttendanceCsvParser.parseMark("-"));
		assertNull(AttendanceCsvParser.parseMark("x"));
	}

	/** A typo is reported rather than read as an empty cell, which would move the batch. */
	@Test
	public void anUnrecognisedMarkStopsItsColumnAndNoOther() {

		Sheet sheet = parse(
				"Course code,PHYS101,PHYS102",
				"Date,2025-07-01,2025-07-01",
				"Start time,09:00,11:00",
				"End time,10:00,13:00",
				"2024PH001,P,P",
				"2024PH002,ZZ,P");

		assertTrue(sheet.errors.isEmpty());
		assertFalse(sheet.columns.get(0).isUsable());
		assertTrue(sheet.columns.get(0).problems.get(0).contains("Line 6"));
		assertTrue(sheet.columns.get(0).problems.get(0).contains("ZZ"));
		assertTrue(sheet.columns.get(1).isUsable());
	}

	@Test
	public void headingLinesMayBeGivenInAnyOrderWhenTheyAreLabelled() {

		Sheet sheet = parse(
				"Date,2025-07-01",
				"End time,10:00",
				"Course code,PHYS101",
				"Start time,09:00",
				"2024PH001,P");

		assertTrue(sheet.columns.get(0).isUsable());
		assertEquals("PHYS101", sheet.columns.get(0).courseCode);
		assertEquals(LocalTime.of(9, 0), sheet.columns.get(0).start);
		assertEquals(LocalTime.of(10, 0), sheet.columns.get(0).end);
	}

	/**
	 * An unlabelled file is read in the documented order. Half-labelled counts as unlabelled,
	 * since a partial set says nothing about where the missing line went.
	 */
	@Test
	public void unlabelledHeadingLinesAreReadPositionally() {

		Sheet sheet = parse(
				",PHYS101",
				",2025-07-01",
				",09:00",
				",10:00",
				"2024PH001,P");

		assertTrue(sheet.columns.get(0).isUsable());
		assertEquals("PHYS101", sheet.columns.get(0).courseCode);
		assertEquals(LocalDate.of(2025, 7, 1), sheet.columns.get(0).date);
	}

	@Test
	public void datesAndTimesAreReadInTheSpellingsPeopleActuallyUse() {

		Sheet sheet = parse(
				"Course code,A,B,C,D",
				"Date,01/07/2025,1-7-2025,1 Jul 2025,2025-07-01",
				"Start time,9:00,09.30,9:00 am,9 AM",
				"End time,10:00,10:30,10:00 AM,10 am",
				"2024PH001,P,P,P,P");

		for (AttendanceCsvParser.Column column : sheet.columns) {
			assertTrue(column.describe() + " " + column.problems, column.isUsable());
			assertEquals(LocalDate.of(2025, 7, 1), column.date);
		}
		assertEquals(LocalTime.of(9, 30), sheet.columns.get(1).start);
		assertEquals(LocalTime.of(9, 0), sheet.columns.get(2).start);
	}

	/**
	 * Day-first, always. Admitting the month-first reading as well would let a file import
	 * silently against the wrong dates instead of being rejected.
	 */
	@Test
	public void slashedDatesAreReadDayFirst() {

		Sheet sheet = parse(
				"Course code,A",
				"Date,03/07/2025",
				"Start time,09:00",
				"End time,10:00",
				"2024PH001,P");

		assertEquals(LocalDate.of(2025, 7, 3), sheet.columns.get(0).date);
	}

	@Test
	public void aBadHeadingCellStopsOnlyItsOwnColumn() {

		Sheet sheet = parse(
				"Course code,PHYS101,PHYS102,PHYS103",
				"Date,2025-07-01,not a date,2025-07-01",
				"Start time,09:00,11:00,11:00",
				"End time,10:00,13:00,10:00",
				"2024PH001,P,P,P");

		assertTrue(sheet.errors.isEmpty());
		assertTrue(sheet.columns.get(0).isUsable());

		assertFalse(sheet.columns.get(1).isUsable());
		assertTrue(sheet.columns.get(1).problems.get(0).contains("not a date"));

		// The third column parses but ends before it starts.
		assertFalse(sheet.columns.get(2).isUsable());
		assertTrue(sheet.columns.get(2).problems.get(0).contains("not after"));
	}

	/**
	 * Two lines for one student make every column ambiguous, so this has to stop the file
	 * rather than a column.
	 */
	@Test
	public void aRepeatedRollNumberStopsTheWholeFile() {

		Sheet sheet = parse(
				"Course code,PHYS101",
				"Date,2025-07-01",
				"Start time,09:00",
				"End time,10:00",
				"2024PH001,P",
				"2024PH002,AB",
				"2024PH001,AB");

		assertEquals(1, sheet.errors.size());
		assertTrue(sheet.errors.get(0).contains("lines 5 and 7"));
		assertFalse(sheet.isUsable());
	}

	@Test
	public void trailingBlankColumnsAndBlankLinesAreIgnored() {

		Sheet sheet = parse(
				"Course code,PHYS101,,",
				"Date,2025-07-01,,",
				"Start time,09:00,,",
				"End time,10:00,,",
				"",
				"2024PH001,P,,",
				"");

		assertTrue(sheet.errors.isEmpty());
		assertEquals(1, sheet.columns.size());
		assertEquals(1, sheet.rows.size());
	}

	@Test
	public void aRowShorterThanTheHeadingIsReadAsNotInClass() {

		Sheet sheet = parse(
				"Course code,PHYS101,PHYS102",
				"Date,2025-07-01,2025-07-01",
				"Start time,09:00,11:00",
				"End time,10:00,13:00",
				"2024PH001,P");

		assertArrayEquals(new Mark[]{Mark.PRESENT, Mark.NOT_IN_CLASS},
				sheet.rows.get(0).marks);
	}

	@Test
	public void aByteOrderMarkOnTheFirstLineDoesNotHideTheHeadingLabel() {

		Sheet sheet = parse(
				"﻿Course code,PHYS101",
				"Date,2025-07-01",
				"Start time,09:00",
				"End time,10:00",
				"2024PH001,P");

		assertTrue(sheet.columns.get(0).isUsable());
		assertEquals("PHYS101", sheet.columns.get(0).courseCode);
	}

	@Test
	public void quotedHeadingCellsSurviveTheirCommas() {

		Sheet sheet = parse(
				"Course code,\"PHYS101, part 2\"",
				"Date,2025-07-01",
				"Start time,09:00",
				"End time,10:00",
				"2024PH001,P");

		assertEquals("PHYS101, part 2", sheet.columns.get(0).courseCode);
	}

	@Test
	public void tooFewHeadingLinesIsAWholeFileFailure() {

		Sheet sheet = parse(
				"Course code,PHYS101",
				"Date,2025-07-01",
				"Start time,09:00");

		assertFalse(sheet.errors.isEmpty());
		assertTrue(sheet.columns.isEmpty());
	}

	@Test
	public void aHeadingWithNoClassColumnsIsAWholeFileFailure() {

		Sheet sheet = parse("Course code", "Date", "Start time", "End time", "2024PH001");

		assertFalse(sheet.errors.isEmpty());
	}

	@Test
	public void aFileWithNoStudentsIsAWholeFileFailure() {

		Sheet sheet = parse(
				"Course code,PHYS101",
				"Date,2025-07-01",
				"Start time,09:00",
				"End time,10:00");

		assertFalse(sheet.errors.isEmpty());
	}

	// ── Choosing the batch ───────────────────────────────────────────────

	private static Set<Long> ids(long... values) {

		Set<Long> set = new HashSet<>();

		for (long value : values) {
			set.add(value);
		}
		return set;
	}

	private static BatchMatcher.Candidate batch(long id, String name, long... members) {
		return new BatchMatcher.Candidate(id, name, ids(members), false);
	}

	/** The case the feature was asked for: half the class blank means the other half's batch. */
	@Test
	public void marksMatchingOneBatchExactlyChooseThatBatch() {

		BatchMatcher.Choice choice = BatchMatcher.choose(ids(1, 2),
				Arrays.asList(batch(10, "Batch A", 1, 2), batch(11, "Batch B", 3, 4)));

		assertNotNull(choice);
		assertEquals(10L, choice.candidate.batchID);
		assertEquals(0, choice.extra);
		assertEquals(0, choice.missing);
	}

	/**
	 * A class taught to everybody is described by the batch that holds everybody, since the
	 * whole class is no longer a candidate.
	 */
	@Test
	public void everybodyMarkedChoosesTheBatchHoldingEverybody() {

		BatchMatcher.Choice choice = BatchMatcher.choose(ids(1, 2, 3, 4),
				Arrays.asList(batch(10, "Practicals A", 1, 2),
						batch(11, "Full class", 1, 2, 3, 4)));

		assertNotNull(choice);
		assertEquals(11L, choice.candidate.batchID);
		assertTrue(choice.covers());
		assertEquals(0, choice.extra);
	}

	/**
	 * The rule that keeps a practical off the wrong denominator: of two batches that both hold
	 * everybody marked, the one carrying fewer unmarked students wins.
	 */
	@Test
	public void theTighterOfTwoCoveringBatchesWins() {

		BatchMatcher.Choice choice = BatchMatcher.choose(ids(1, 2),
				Arrays.asList(batch(11, "Combined", 1, 2, 3, 4, 5, 6),
						batch(10, "Batch A", 1, 2, 3)));

		assertNotNull(choice);
		assertEquals(10L, choice.candidate.batchID);
		assertEquals(1, choice.extra);
	}

	/**
	 * A batch that leaves a marked student out cannot be the group that sat the session,
	 * however tidy it looks otherwise: their mark would have nowhere to go.
	 */
	@Test
	public void coveringEveryMarkedStudentBeatsAnyTighterFit() {

		BatchMatcher.Choice choice = BatchMatcher.choose(ids(1, 2, 3),
				Arrays.asList(batch(10, "Batch A", 1, 2),
						batch(11, "Combined", 1, 2, 3, 4, 5, 6, 7, 8)));

		assertNotNull(choice);
		assertEquals(11L, choice.candidate.batchID);
		assertTrue(choice.covers());
	}

	@Test
	public void whenNothingCoversTheBatchAccountingForMostMarksWins() {

		BatchMatcher.Choice choice = BatchMatcher.choose(ids(1, 2, 3, 99),
				Arrays.asList(batch(10, "Batch A", 1), batch(11, "Batch B", 1, 2, 3)));

		assertNotNull(choice);
		assertEquals(11L, choice.candidate.batchID);
		assertFalse(choice.covers());
		assertEquals(3, choice.matched);
		assertEquals(1, choice.missing);
	}

	@Test
	public void aBatchAlreadyLinkedToTheCourseBreaksAnOtherwiseExactTie() {

		BatchMatcher.Candidate unlinked = new BatchMatcher.Candidate(10L, "Batch A",
				ids(1, 2), false);
		BatchMatcher.Candidate linked = new BatchMatcher.Candidate(11L, "Batch B",
				ids(1, 2), true);

		BatchMatcher.Choice choice =
				BatchMatcher.choose(ids(1, 2), Arrays.asList(unlinked, linked));

		assertNotNull(choice);
		assertEquals(11L, choice.candidate.batchID);
	}

	/** With nothing else to separate them, the answer must not depend on the query's order. */
	@Test
	public void theRemainingTieIsBrokenByNameSoTheAnswerIsStable() {

		List<BatchMatcher.Candidate> forwards =
				Arrays.asList(batch(10, "Alpha", 1, 2), batch(11, "Beta", 1, 2));
		List<BatchMatcher.Candidate> backwards =
				Arrays.asList(batch(11, "Beta", 1, 2), batch(10, "Alpha", 1, 2));

		assertEquals(10L, BatchMatcher.choose(ids(1, 2), forwards).candidate.batchID);
		assertEquals(10L, BatchMatcher.choose(ids(1, 2), backwards).candidate.batchID);
	}

	/**
	 * A session with no batches has nowhere to file a class, since the whole class is not a
	 * fallback. The importer turns this into a problem naming the session.
	 */
	@Test
	public void aSessionWithNoBatchesYieldsNoChoice() {
		assertNull(BatchMatcher.choose(ids(1, 2), Collections.emptyList()));
	}

	/** A batch sharing nobody with the marks is reported, not quietly returned as a match. */
	@Test
	public void aBatchSharingNobodyWithTheMarksScoresZero() {

		BatchMatcher.Choice choice = BatchMatcher.choose(ids(1, 2),
				Collections.singletonList(batch(10, "Batch A", 7, 8)));

		assertNotNull(choice);
		assertEquals(0, choice.matched);
		assertEquals(2, choice.missing);
	}

	private static void assertArrayEquals(Mark[] expected, Mark[] actual) {
		assertEquals(Arrays.toString(expected), Arrays.toString(actual));
	}
}
