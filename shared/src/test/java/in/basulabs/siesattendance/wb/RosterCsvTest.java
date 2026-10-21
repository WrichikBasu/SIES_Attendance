package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;

import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Division;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;
import in.basulabs.siesattendance.backend.util.RosterCsv;

/**
 * The roster file, as both sides now read it.
 * <p>
 * This parser was the app's until Phase 5.5, when the server came to need the same answers:
 * which cells are optional, which spellings of a department resolve, which combinations are
 * refused. The cases below are the ones where "the app accepts it and the server does not" would
 * be a support call rather than an error message.
 *
 * @see RosterCsv
 */
public class RosterCsvTest {

	private static final String HEADER =
			"rollNo,name,pronouns,email,year,stream,majorDept,minorDept,oeDept,semester,division";

	// ── A whole file ─────────────────────────────────────────────────────

	@Test
	public void everyRowThatParsesComesBackWithItsLineNumber() throws IOException {

		RosterCsv.Reading reading = read(HEADER + "\n"
				+ "2024CS032,Aarav Patil,he/him,aarav@sies.edu.in,SY,BSc,COMPUTER_SCIENCE,"
				+ "MATHEMATICS,ENGLISH,3,\n"
				+ "2024PH017,Ishaan Rao,he/him,ishaan@sies.edu.in,SY,BSc,PHYSICS,,,3,B\n");

		assertFalse(reading.rejected());
		assertFalse(reading.hasErrors());
		assertEquals(2, reading.rows().size());

		RosterCsv.Row first = reading.rows().get(0);

		assertEquals(2, first.line());
		assertEquals("2024CS032", first.rollNo());
		assertEquals("Aarav Patil", first.name());
		assertEquals("he/him", first.pronouns());
		assertEquals("aarav@sies.edu.in", first.email());
		assertEquals(Year.SY, first.year());
		assertEquals(Department.COMPUTER_SCIENCE, first.majorDept());
		assertEquals(Department.MATHEMATICS, first.minorDept());
		assertEquals(Department.ENGLISH, first.oeDept());
		assertEquals(Integer.valueOf(3), first.semester());
		assertNull(first.division());

		// The line number is the file's, so it still points at the right row after a blank
		// line or a bad one.
		assertEquals(3, reading.rows().get(1).line());
		assertEquals(Division.B, reading.rows().get(1).division());
	}

	@Test
	public void aBadRowCostsOnlyThatRow() throws IOException {

		RosterCsv.Reading reading = read(HEADER + "\n"
				+ "2024CS032,Aarav Patil,he/him,aarav@sies.edu.in,SY,BSc,COMPUTER_SCIENCE,,,3,\n"
				+ "2024CS033,Bad Department,,,SY,BSc,NOT_A_DEPARTMENT,,,3,\n"
				+ "2024CS034,Sneha Mehta,,,SY,BSc,COMPUTER_SCIENCE,,,3,\n");

		assertEquals(2, reading.rows().size());
		assertEquals(1, reading.errors().size());
		assertTrue(reading.errors().get(0), reading.errors().get(0).startsWith("Line 3:"));
		assertFalse("one bad row is not a bad file", reading.rejected());
	}

	@Test
	public void blankLinesAreSkippedRatherThanCounted() throws IOException {

		RosterCsv.Reading reading = read(HEADER + "\n\n"
				+ "2024CS032,Aarav Patil,,,SY,BSc,COMPUTER_SCIENCE,,,3,\n"
				+ "   \n");

		assertEquals(1, reading.rows().size());
		assertFalse(reading.hasErrors());
		assertEquals("the blank second line is skipped, not renumbered away",
				3, reading.rows().get(0).line());
	}

	@Test
	public void aFileWithoutARequiredColumnIsRejectedWhole() throws IOException {

		RosterCsv.Reading reading = read("name,email\nSomebody,somebody@sies.edu.in\n");

		assertTrue(reading.rejected());
		assertTrue(reading.rows().isEmpty());
		assertEquals(1, reading.errors().size());
		assertTrue(reading.errors().get(0),
				reading.errors().get(0).contains("Missing required column"));
	}

	@Test
	public void anEmptyFileIsRejectedAndSaysSo() throws IOException {

		RosterCsv.Reading reading = read("");

		assertTrue(reading.rejected());
		assertEquals(1, reading.errors().size());
	}

	@Test
	public void aByteOrderMarkDoesNotCostTheFirstColumn() throws IOException {

		// Excel and Google Sheets both write one, and it is invisible: without stripping it
		// the file is rejected for a missing rollNo whose header plainly says rollNo.
		RosterCsv.Reading reading = read("﻿" + HEADER + "\n"
				+ "2024CS032,Aarav Patil,,,SY,BSc,COMPUTER_SCIENCE,,,3,\n");

		assertFalse(reading.errors().toString(), reading.rejected());
		assertEquals(1, reading.rows().size());
	}

	// ── Cells ────────────────────────────────────────────────────────────

	@Test
	public void aBlankPronounCellBecomesTheyThem() throws IOException {

		RosterCsv.Row row = read(HEADER + "\n"
				+ "2024CS032,Aarav Patil,,,SY,BSc,COMPUTER_SCIENCE,,,3,\n").rows().get(0);

		assertEquals("they/them", row.pronouns());
		assertNull("a blank address is absence, not an empty address", row.email());
	}

	@Test
	public void theStreamIsResolvedAgainstTheDepartment() throws IOException {

		// "BSc" with an unaided department means that department's only BSc. The alternative
		// -- filing it as the aided one -- would put the student on another department's roll.
		RosterCsv.Row row = read("rollNo,name,year,stream,majorDept\n"
				+ "2024CS032,Aarav Patil,SY,BSc,COMPUTER_SCIENCE\n").rows().get(0);

		assertEquals(Stream.BSc_unaided, row.stream());
	}

	@Test
	public void aStreamTheDepartmentDoesNotOfferIsReportedRatherThanCorrected() throws IOException {

		RosterCsv.Reading reading = read("rollNo,name,year,stream,majorDept\n"
				+ "2024CM001,Somebody,SY,BSc,COMMERCE\n");

		assertTrue(reading.rows().isEmpty());
		assertTrue(reading.errors().get(0),
				reading.errors().get(0).contains("is not offered as"));
	}

	@Test
	public void enumCellsTakeTheDisplayLabelAsWellAsTheConstant() throws IOException {

		RosterCsv.Row row = read("rollNo,name,year,stream,majorDept,oeDept\n"
				+ "2024CS032,Aarav Patil,SY,BSc (unaided),Computer Science,English\n")
				.rows().get(0);

		assertEquals(Stream.BSc_unaided, row.stream());
		assertEquals(Department.COMPUTER_SCIENCE, row.majorDept());
		assertEquals(Department.ENGLISH, row.oeDept());
	}

	@Test
	public void aDivisionMayBeWrittenOut() throws IOException {

		RosterCsv.Row row = read("rollNo,name,year,stream,majorDept,division\n"
				+ "2024PH017,Ishaan Rao,SY,BSc,PHYSICS,Div B\n").rows().get(0);

		assertEquals(Division.B, row.division());
	}

	@Test
	public void columnNamesAreMatchedLooselyAndInAnyOrder() throws IOException {

		RosterCsv.Row row = read("Major Dept,name,Roll-No,STREAM,year\n"
				+ "COMPUTER_SCIENCE,Aarav Patil,2024CS032,BSc,SY\n").rows().get(0);

		assertEquals("2024CS032", row.rollNo());
		assertEquals(Department.COMPUTER_SCIENCE, row.majorDept());
	}

	// ── The three departments have to be three ───────────────────────────

	@Test
	public void aMinorEqualToTheMajorIsRefused() throws IOException {

		// student_year_minor_not_major, from V2. A CSV is the only way to reach it -- the forms
		// cannot offer the same department twice -- and it is checked here so the app refuses
		// the row for the same reason the server would, with the line number instead of a
		// constraint name.
		RosterCsv.Reading reading = read("rollNo,name,year,stream,majorDept,minorDept\n"
				+ "2024CS032,Aarav Patil,SY,BSc,COMPUTER_SCIENCE,COMPUTER_SCIENCE\n");

		assertTrue(reading.rows().isEmpty());
		assertTrue(reading.errors().get(0),
				reading.errors().get(0).contains("minor department"));
	}

	@Test
	public void anOpenElectiveEqualToTheMajorOrTheMinorIsRefused() throws IOException {

		RosterCsv.Reading major = read("rollNo,name,year,stream,majorDept,oeDept\n"
				+ "2024CS032,Aarav Patil,SY,BSc,COMPUTER_SCIENCE,COMPUTER_SCIENCE\n");

		assertTrue(major.rows().isEmpty());
		assertTrue(major.errors().get(0), major.errors().get(0).contains("major"));

		RosterCsv.Reading minor = read("rollNo,name,year,stream,majorDept,minorDept,oeDept\n"
				+ "2024CS032,Aarav Patil,SY,BSc,COMPUTER_SCIENCE,MATHEMATICS,MATHEMATICS\n");

		assertTrue(minor.rows().isEmpty());
		assertTrue(minor.errors().get(0), minor.errors().get(0).contains("minor"));
	}

	@Test
	public void aCombinationTheFormsWouldRefuseIsRefusedHereToo() throws IOException {

		// AcademicRules is the backstop, and it is the same one the forms use: a first-year in
		// semester five is not a thing a file gets to introduce.
		RosterCsv.Reading reading = read("rollNo,name,year,stream,majorDept,semester\n"
				+ "2024CS032,Aarav Patil,FY,BSc,COMPUTER_SCIENCE,5\n");

		assertTrue(reading.rows().isEmpty());
		assertEquals(1, reading.errors().size());
	}

	// ── The template ─────────────────────────────────────────────────────

	@Test
	public void theShippedTemplateParsesEveryRowOfItself() throws IOException {

		RosterCsv.Reading reading = read(RosterCsv.templateCsv());

		assertFalse(reading.errors().toString(), reading.hasErrors());
		assertEquals(3, reading.rows().size());
	}

	// ── APAAR ID and the enrolment dates ─────────────────────────────────

	@Test
	public void theApaarIdAndTheDatesAreOptionalColumns() throws IOException {

		RosterCsv.Reading reading = read(HEADER + ",apaarId,joinDate,leaveDate\n"
				+ "2024CS032,Aarav Patil,he/him,,SY,BSc,COMPUTER_SCIENCE,,,3,,"
				+ "123456789012,2024-07-15,15/03/2025\n"
				+ "2024CS033,Sneha Mehta,she/her,,SY,BSc,COMPUTER_SCIENCE,,,3,,,,\n");

		assertFalse(reading.toString(), reading.hasErrors());

		RosterCsv.Row late = reading.rows().get(0);
		assertEquals(Long.valueOf(123456789012L), late.apaarId());
		assertEquals(java.time.LocalDate.of(2024, 7, 15), late.joinDate());
		assertEquals("a day-first date, the way a spreadsheet here writes it",
				java.time.LocalDate.of(2025, 3, 15), late.leaveDate());

		RosterCsv.Row plain = reading.rows().get(1);
		assertNull(plain.apaarId());
		assertNull("blank is the start of the session", plain.joinDate());
		assertNull("and still enrolled", plain.leaveDate());

		// A file with none of the three columns at all reads exactly as before.
		assertNull(read(HEADER + "\n2024CS034,Rahul,he/him,,SY,BSc,COMPUTER_SCIENCE,,,3,\n")
				.rows().get(0).apaarId());
	}

	@Test
	public void aBadApaarIdOrBackwardsDatesCostOnlyThatRow() throws IOException {

		RosterCsv.Reading reading = read(HEADER + ",apaarId,joinDate,leaveDate\n"
				+ "2024CS032,Aarav,he/him,,SY,BSc,COMPUTER_SCIENCE,,,3,,12AB,,\n"
				+ "2024CS033,Sneha,she/her,,SY,BSc,COMPUTER_SCIENCE,,,3,,,2025-03-01,2025-01-01\n"
				+ "2024CS034,Rahul,he/him,,SY,BSc,COMPUTER_SCIENCE,,,3,,987654321,,\n");

		assertEquals(1, reading.rows().size());
		assertEquals(2, reading.errors().size());
		assertTrue(reading.errors().get(0), reading.errors().get(0).contains("APAAR"));
		assertTrue(reading.errors().get(1), reading.errors().get(1).contains("before the join"));
	}

	@Test
	public void theFormatTheScreensShowIsTheFormatThatIsRead() throws IOException {

		String help = RosterCsv.formatHelp();

		for (Year year : Year.values()) {
			assertTrue("the help never names " + year, help.contains(year.toString()));
		}
		for (Stream stream : Stream.values()) {
			assertTrue("the help never names " + stream.name(), help.contains(stream.name()));
		}

		RosterCsv.Reading example = read(RosterCsv.templateCsv());
		assertFalse("the example the help shows has to import: " + example.errors(),
				example.hasErrors());
		assertEquals(3, example.rows().size());

		in.basulabs.siesattendance.backend.util.AttendanceCsvParser.Sheet sheet =
				in.basulabs.siesattendance.backend.util.AttendanceCsvParser.parse(
						java.util.Arrays.asList(in.basulabs.siesattendance.backend.util
								.AttendanceCsvParser.templateCsv().split("\n")));
		assertTrue("and so does the attendance example: " + sheet.errors,
				sheet.errors.isEmpty());
	}

	private static RosterCsv.Reading read(String csv) throws IOException {
		return RosterCsv.read(new StringReader(csv));
	}
}
