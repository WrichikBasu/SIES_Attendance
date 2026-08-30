package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import in.basulabs.siesattendance.backend.util.CsvImporter;

/**
 * The CSV reader, exercised through its public splitter and, via reflection, through the
 * private header parser that decides whether a file is importable at all.
 */
public class CsvParsingTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Integer> parseHeader(String headerLine) throws Exception {
		Method m = CsvImporter.class.getDeclaredMethod("parseHeader", String.class);
		m.setAccessible(true);
		return (Map<String, Integer>) m.invoke(null, headerLine);
	}

	// ── splitCsvLine ─────────────────────────────────────────────────────

	@Test
	public void plainFieldsSplitOnCommas() {
		assertEquals(Arrays.asList("2024CS032", "Aarav Patil", "he/him"),
				CsvImporter.splitCsvLine("2024CS032,Aarav Patil,he/him"));
	}

	@Test
	public void quotedFieldsKeepTheirCommas() {
		assertEquals(Arrays.asList("2024CS032", "Patil, Aarav", "he/him"),
				CsvImporter.splitCsvLine("2024CS032,\"Patil, Aarav\",he/him"));
	}

	@Test
	public void doubledQuotesInsideAQuotedFieldBecomeOneLiteralQuote() {
		assertEquals(Arrays.asList("a", "say \"hi\"", "b"),
				CsvImporter.splitCsvLine("a,\"say \"\"hi\"\"\",b"));
	}

	@Test
	public void trailingAndLeadingEmptyFieldsSurvive() {
		// The optional columns are usually the trailing ones, so a row ending in ",," has to
		// come back as three cells, not one.
		assertEquals(Arrays.asList("2024CS033", "Sneha", ""),
				CsvImporter.splitCsvLine("2024CS033,Sneha,"));
		assertEquals(Arrays.asList("", "a", ""), CsvImporter.splitCsvLine(",a,"));
		assertEquals(Arrays.asList("", ""), CsvImporter.splitCsvLine(","));
	}

	@Test
	public void surroundingSpaceIsTrimmedFromEveryCell() {
		assertEquals(Arrays.asList("a", "b", "c"),
				CsvImporter.splitCsvLine(" a , b ,  c  "));
	}

	@Test
	public void aSingleCellLineIsOneField() {
		assertEquals(Arrays.asList("only"), CsvImporter.splitCsvLine("only"));
		assertEquals(Arrays.asList(""), CsvImporter.splitCsvLine(""));
	}

	@Test
	public void anUnterminatedQuoteConsumesTheRestOfTheLineWithoutThrowing() {
		// Malformed input must not take the whole import down; it costs this row only.
		assertEquals(Arrays.asList("a", "b,c"), CsvImporter.splitCsvLine("a,\"b,c"));
	}

	// ── parseHeader ──────────────────────────────────────────────────────

	@Test
	public void headerNamesAreNormalisedSoSpellingVariantsAllMatch() throws Exception {
		Map<String, Integer> columns = parseHeader(
				"Roll No,Name,major_dept,minor-dept,OE Dept,Semester");

		assertEquals(Integer.valueOf(0), columns.get("rollno"));
		assertEquals(Integer.valueOf(1), columns.get("name"));
		assertEquals(Integer.valueOf(2), columns.get("majordept"));
		assertEquals(Integer.valueOf(3), columns.get("minordept"));
		assertEquals(Integer.valueOf(4), columns.get("oedept"));
		assertEquals(Integer.valueOf(5), columns.get("semester"));
	}

	@Test
	public void theShippedTemplateSatisfiesItsOwnRequiredColumns() throws Exception {
		String header = CsvImporter.templateCsv().split("\n")[0];
		Map<String, Integer> columns = parseHeader(header);

		for (String required : CsvImporter.REQUIRED_COLUMNS) {
			assertTrue("the template is missing the required column " + required,
					columns.containsKey(required));
		}
	}

	@Test
	public void theShippedTemplateRowsHaveOneCellPerHeaderColumn() {
		String[] lines = CsvImporter.templateCsv().split("\n");
		int width = CsvImporter.splitCsvLine(lines[0]).size();

		for (int i = 1; i < lines.length; i++) {
			assertEquals("template row " + i + " has the wrong number of cells",
					width, CsvImporter.splitCsvLine(lines[i]).size());
		}
	}

	@Test
	public void theShippedTemplateValuesAllResolve() {
		// A template that does not import is worse than no template.
		String[] lines = CsvImporter.templateCsv().split("\n");
		List<String> header = CsvImporter.splitCsvLine(lines[0]);

		int yearCol = header.indexOf("year");
		int deptCol = header.indexOf("majorDept");
		int streamCol = header.indexOf("stream");
		int semCol = header.indexOf("semester");
		int divCol = header.indexOf("division");

		for (int i = 1; i < lines.length; i++) {
			List<String> cells = CsvImporter.splitCsvLine(lines[i]);

			var year = in.basulabs.siesattendance.backend.Year.fromAny(cells.get(yearCol));
			var dept = in.basulabs.siesattendance.backend.Department
					.fromAny(cells.get(deptCol));
			var requested = in.basulabs.siesattendance.backend.Stream
					.fromAny(cells.get(streamCol));

			assertTrue("row " + i + " year did not resolve", year != null);
			assertTrue("row " + i + " department did not resolve", dept != null);
			assertTrue("row " + i + " stream did not resolve", requested != null);

			var stream = in.basulabs.siesattendance.backend.AcademicRules
					.resolveRequested(dept, requested);
			assertTrue("row " + i + ": " + dept + " does not offer " + requested,
					stream != null);

			Integer semester = Integer.valueOf(cells.get(semCol));

			String rawDivision = cells.get(divCol);
			var division = in.basulabs.siesattendance.backend.Division.fromAny(rawDivision);
			assertTrue("row " + i + " division did not resolve: " + rawDivision,
					rawDivision.isEmpty() || division != null);

			assertEquals("row " + i + " fails the same validation the import runs", null,
					in.basulabs.siesattendance.backend.AcademicRules
							.validate(dept, stream, year, semester, division));
		}
	}

	@Test
	public void aByteOrderMarkIsStrippedFromTheFirstHeaderCell() throws Exception {
		// A UTF-8 CSV saved by Excel or Google Sheets begins with U+FEFF. It is invisible, and
		// it attaches to the first column name only, so leaving it in place rejected the file
		// for a missing rollNo whose header plainly said rollNo while every other column
		// resolved.
		Map<String, Integer> columns = parseHeader(
				"\uFEFF" + "rollNo,name,year,stream,majorDept");

		assertEquals(Integer.valueOf(0), columns.get("rollno"));
		assertFalse(columns.containsKey("\uFEFF" + "rollno"));

		for (String required : CsvImporter.REQUIRED_COLUMNS) {
			assertTrue("a roster saved by a spreadsheet must still import: " + required,
					columns.containsKey(required));
		}
	}

	@Test
	public void aHeaderWithoutAByteOrderMarkIsUntouched() {
		// The strip must not eat a real first character.
		assertEquals(Arrays.asList("rollNo", "name"),
				CsvImporter.splitCsvLine("rollNo,name"));
	}

	@Test
	public void headerNamesLowercaseIndependentlyOfTheDeviceLocale() throws Exception {
		// Turkish maps I to a dotless i, which would lose minorDept on a Turkish phone if the
		// default locale were used here. These are internal keys compared against ASCII.
		Locale original = Locale.getDefault();
		try {
			Locale.setDefault(new Locale("tr", "TR"));
			Map<String, Integer> columns = parseHeader("ROLLNO,NAME,MINORDEPT,OEDEPT");

			assertTrue(columns.containsKey("rollno"));
			assertTrue(columns.containsKey("minordept"));
			assertTrue(columns.containsKey("oedept"));
		} finally {
			Locale.setDefault(original);
		}
	}

	@Test
	public void aQuotedHeaderCellStillNormalisesCorrectly() throws Exception {
		Map<String, Integer> columns = parseHeader("\"Roll No\",\"Name\"");
		assertTrue(columns.containsKey("rollno"));
		assertTrue(columns.containsKey("name"));
	}

	@Test
	public void duplicateHeaderNamesKeepTheLastColumn() throws Exception {
		// Not obviously right or wrong, but worth pinning: a HashMap put overwrites.
		Map<String, Integer> columns = parseHeader("name,rollNo,name");
		assertEquals(Integer.valueOf(2), columns.get("name"));
	}
}
