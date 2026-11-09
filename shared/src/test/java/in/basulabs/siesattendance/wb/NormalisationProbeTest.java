package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import in.basulabs.siesattendance.backend.CourseType;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Division;
import in.basulabs.siesattendance.backend.EnumLookup;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.StructuredId;
import in.basulabs.siesattendance.backend.Year;
import in.basulabs.siesattendance.backend.util.AttendanceCsvParser;
import in.basulabs.siesattendance.backend.util.AttendanceCsvParser.Sheet;
import in.basulabs.siesattendance.backend.util.RosterCsv;

/**
 * White-box probes, second round: how the shared code decides that two spellings are the same
 * thing.
 * <p>
 * Three places compare roll numbers loosely, and they do not agree:
 * <ul>
 *     <li>{@code AttendanceCsvParser.normalise}, for "this student is listed twice", strips
 *     spaces, hyphens, underscores and full stops;</li>
 *     <li>the app's {@code AttendanceRecordImporter} and the server's {@code AttendanceImporter},
 *     which find the student, strip spaces, hyphens and slashes.</li>
 * </ul>
 * So a sheet listing {@code CS/FY/001} and {@code CSFY001} passes the duplicate check and then
 * both lines land on one student, the later one silently winning. The parser's own help text
 * promises slashes do not matter.
 * <p>
 * The tests marked BUG fail as of this commit.
 */
public class NormalisationProbeTest {

	private static List<String> sheet(String... lines) {
		return List.of(lines);
	}

	// ── Roll numbers listed twice ────────────────────────────────────────

	/** The control: hyphens are ignored by both the parser and the importers. */
	@Test
	public void aRollNumberWithAndWithoutHyphensIsListedTwice() {
		Sheet parsed = AttendanceCsvParser.parse(sheet(
				"Course code,CS-101",
				"Date,21/07/2025",
				"Start time,09:00",
				"End time,10:00",
				"CS-FY-001,P",
				"CSFY001,AB"));

		assertFalse("the duplicate must stop the file", parsed.errors.isEmpty());
	}

	/**
	 * BUG: the help text says slashes do not matter, and both importers strip them when they
	 * look the student up, but the parser's duplicate check keeps them. The two lines reach the
	 * importer as two students, resolve to one, and the second line's mark overwrites the first
	 * while the preview counts both.
	 */
	@Test
	public void aRollNumberWithAndWithoutSlashesIsListedTwice() {
		Sheet parsed = AttendanceCsvParser.parse(sheet(
				"Course code,CS-101",
				"Date,21/07/2025",
				"Start time,09:00",
				"End time,10:00",
				"CS/FY/001,P",
				"CSFY001,AB"));

		assertFalse("CS/FY/001 and CSFY001 are one student to the importers, so listing both "
				+ "with contradicting marks has to be refused here", parsed.errors.isEmpty());
	}

	/** And the help text is the promise being held to. */
	@Test
	public void theHelpTextPromisesSlashesDoNotMatter() {
		assertTrue(AttendanceCsvParser.formatHelp().contains("slashes don't matter"));
	}

	// ── A spreadsheet's empty rows ───────────────────────────────────────

	/**
	 * BUG: a spreadsheet saves an empty row as a run of commas. That is not blank to
	 * {@code line.trim().isEmpty()}, so a sheet with an empty row above its heading takes the
	 * empty row as the course-code line, the real End time line becomes the first "student",
	 * and every column is refused for an unrecognised mark "10:00".
	 */
	@Test
	public void anEmptySpreadsheetRowAboveTheHeadingIsSkipped() {
		Sheet parsed = AttendanceCsvParser.parse(sheet(
				",,",
				"Course code,CS-101,CS-101",
				"Date,21/07/2025,22/07/2025",
				"Start time,09:00,09:00",
				"End time,10:00,10:00",
				"CS-FY-001,P,AB"));

		assertTrue("errors: " + parsed.errors, parsed.errors.isEmpty());
		assertEquals(2, parsed.columns.size());
		assertTrue("problems: " + parsed.columns.get(0).problems,
				parsed.columns.get(0).isUsable());
		assertEquals(1, parsed.rows.size());
		assertEquals("CS-FY-001", parsed.rows.get(0).rollNo);
	}

	/** BUG: the same, for an empty row between two heading lines. */
	@Test
	public void anEmptySpreadsheetRowInsideTheHeadingIsSkipped() {
		Sheet parsed = AttendanceCsvParser.parse(sheet(
				"Course code,CS-101",
				"Date,21/07/2025",
				",",
				"Start time,09:00",
				"End time,10:00",
				"CS-FY-001,P"));

		assertTrue("problems: " + parsed.columns.get(0).problems,
				parsed.columns.get(0).isUsable());
		assertEquals(1, parsed.rows.size());
	}

	/** The control: below the heading, a row of commas has no roll number and is passed over. */
	@Test
	public void anEmptySpreadsheetRowAmongTheStudentsIsSkipped() {
		Sheet parsed = AttendanceCsvParser.parse(sheet(
				"Course code,CS-101",
				"Date,21/07/2025",
				"Start time,09:00",
				"End time,10:00",
				"CS-FY-001,P",
				",",
				"CS-FY-002,AB"));

		assertTrue(parsed.isUsable());
		assertEquals(2, parsed.rows.size());
	}

	/**
	 * BUG: a roster saved from a spreadsheet with an empty row in it reports that row as
	 * "Line 3: roll number is blank". Nothing is lost, but the office is sent looking for a
	 * mistake that is not in their data.
	 */
	@Test
	public void anEmptySpreadsheetRowInARosterIsNotAnError() throws IOException {
		RosterCsv.Reading reading = RosterCsv.read(new StringReader(
				"rollNo,name,year,stream,majorDept\n"
						+ "CS-FY-001,R Rao,FY,BSc_unaided,COMPUTER_SCIENCE\n"
						+ ",,,,\n"
						+ "CS-FY-002,S Shah,FY,BSc_unaided,COMPUTER_SCIENCE\n"));

		assertEquals(2, reading.rows().size());
		assertTrue("errors: " + reading.errors(), reading.errors().isEmpty());
	}

	// ── Enum lookup ──────────────────────────────────────────────────────

	/**
	 * Every constant of every enum a file can name is found by its name and by its label, and
	 * no two constants of one enum collapse to the same loose spelling. A collision would file
	 * a student under whichever constant came first, with no error.
	 */
	@Test
	public void everyFileSpellingFindsExactlyOneConstant() {
		assertLookupIsUnambiguous(Department.class);
		assertLookupIsUnambiguous(Stream.class);
		assertLookupIsUnambiguous(Year.class);
		assertLookupIsUnambiguous(Division.class);
		assertLookupIsUnambiguous(CourseType.class);
	}

	private static <E extends Enum<E>> void assertLookupIsUnambiguous(Class<E> type) {
		Map<String, E> seen = new HashMap<>();

		for (E constant : type.getEnumConstants()) {
			assertSame(constant, EnumLookup.find(type, constant.name()));
			assertSame(constant, EnumLookup.find(type, constant.toString()));
			assertSame(constant, EnumLookup.find(type,
					" " + constant.name().toLowerCase(Locale.ROOT) + " "));

			for (String spelling : List.of(loose(constant.name()), loose(constant.toString()))) {
				E earlier = seen.put(spelling, constant);
				assertTrue(type.getSimpleName() + ": '" + spelling + "' names both " + earlier
						+ " and " + constant, earlier == null || earlier == constant);
			}
		}
	}

	private static String loose(String value) {
		return value.toLowerCase(Locale.ROOT).replaceAll("[_\\-,() ]", "");
	}

	/**
	 * The README's own example: "Computer Science" works although the label is "CS", because
	 * the constant name loosens to the same letters.
	 */
	@Test
	public void theReadmesDepartmentSpellingsResolve() {
		assertSame(Department.COMPUTER_SCIENCE, EnumLookup.find(Department.class,
				"Computer Science"));
		assertSame(Department.COMPUTER_SCIENCE, EnumLookup.find(Department.class,
				"COMPUTER_SCIENCE"));
		assertSame(Department.POLITICAL_SCIENCE, EnumLookup.find(Department.class,
				"Political Science"));
		assertSame(Stream.BSc_unaided, EnumLookup.find(Stream.class, "BSc unaided"));
	}

	@Test
	public void divisionSpellings() {
		assertSame(Division.B, Division.fromAny("Div. B"));
		assertSame(Division.B, Division.fromAny("division b"));
		assertSame(Division.C, Division.fromAny("DIV C"));
		assertSame(Division.D, Division.fromAny(" d "));
		assertNull("the word alone names no division", Division.fromAny("Division"));
		assertNull(Division.fromAny("E"));
	}

	// ── Structured keys ──────────────────────────────────────────────────

	/** The documented layout, read back field by field. */
	@Test
	public void aComposedKeyReadsBackItsFields() {
		long counter = StructuredId.MIN_COUNTER + 12_345;
		long id = StructuredId.compose(counter, 4242);

		assertTrue(StructuredId.isStructured(id));
		assertEquals(4242, StructuredId.nodeOf(id));
		assertEquals(counter, StructuredId.counterOf(id));
		assertTrue("positive", id > 0);
		assertEquals(StructuredId.NO_NODE, StructuredId.nodeOf(StructuredId.FIRST_STRUCTURED_ID
				- 1));
		assertEquals(-1L, StructuredId.createdAtMillis(42));
	}

	/** The largest counter still leaves a positive key. */
	@Test
	public void theLastCounterIsStillPositive() {
		assertTrue(StructuredId.compose(StructuredId.MAX_COUNTER, StructuredId.MAX_NODE_ID) > 0);
		assertThrows(IllegalStateException.class,
				() -> StructuredId.compose(StructuredId.MAX_COUNTER + 1, 0));
	}

	/**
	 * BUG (minor): {@code compose} checks the counter's top but not its bottom. A negative
	 * counter composes into a negative key, which {@code isStructured} then calls legacy and
	 * {@code nodeOf} disowns, where an out-of-range node is refused outright. {@code IdGenerator}
	 * clamps before calling, so this is about the method's own contract.
	 */
	@Test
	public void aCounterBelowTheFloorIsRefused() {
		assertThrows(IllegalArgumentException.class, () -> StructuredId.compose(-1, 7));
	}
}
