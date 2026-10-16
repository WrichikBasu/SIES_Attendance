package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import in.basulabs.siesattendance.backend.pojo.LectureDetails;
import in.basulabs.siesattendance.backend.pojo.LectureMark;
import in.basulabs.siesattendance.backend.util.MarkSheetExporter;
import in.basulabs.siesattendance.backend.util.MarkSheetExporter.Format;

/**
 * The per-lecture and per-day export, from the marks to the bytes of each format.
 * <p>
 * The sheet-building and writing halves are reached by reflection, as in
 * {@code ExporterInternalsTest}, because the public entry point needs a Context, a content URI
 * and a database. These are the parts that decide what someone opening the file sees, and they
 * cannot check it against the app.
 */
public class MarkSheetExportTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	// ── Fixtures ─────────────────────────────────────────────────────────

	private static LectureDetails lecture(long id, String code, int hour,
	                                      String batch, int marked) {
		ZonedDateTime start = ZonedDateTime.of(2026, 9, 19, hour, 0, 0, 0, IST);
		return new LectureDetails(id, id, null, code, code + " topic", batch, start,
				start.plusHours(1), null, marked, 0);
	}

	private static LectureMark mark(long lectureID, long studentID, String roll, String name,
	                                boolean present) {
		LectureMark mark = new LectureMark();
		mark.lectureID = lectureID;
		mark.studentID = studentID;
		mark.rollNo = roll;
		mark.name = name;
		mark.present = present;
		return mark;
	}

	/**
	 * A morning with a theory lecture for the whole class, a practical for one batch of it, and
	 * an afternoon lecture nobody has marked yet.
	 */
	private static List<LectureDetails> day() {
		return Arrays.asList(
				lecture(1, "CS201", 10, null, 3),
				lecture(2, "CS202", 11, "Batch A", 2),
				lecture(3, "CS203", 14, null, 0));
	}

	/** Marks arrive in roll order, as the query returns them. */
	private static List<LectureMark> dayMarks() {
		return Arrays.asList(
				mark(1, 1, "2025CS01", "AARAV PATIL", true),
				mark(2, 1, "2025CS01", "AARAV PATIL", false),
				mark(1, 2, "2025CS02", "SNEHA MEHTA", false),
				mark(1, 3, "2025CS03", "RAHUL IYER", true),
				mark(2, 3, "2025CS03", "RAHUL IYER", true));
	}

	// ── Reflection into the exporter ─────────────────────────────────────

	private static Object build(List<LectureDetails> lectures, List<LectureMark> marks)
			throws Exception {
		Method m = MarkSheetExporter.class.getDeclaredMethod("build", List.class, List.class);
		m.setAccessible(true);
		return m.invoke(null, lectures, marks);
	}

	private static Class<?> sheetType() throws Exception {
		return Class.forName("in.basulabs.siesattendance.backend.util.MarkSheetExporter$Sheet");
	}

	private static String csv(Object sheet) throws Exception {
		Method m = MarkSheetExporter.class.getDeclaredMethod("writeCsv", sheetType(),
				OutputStream.class);
		m.setAccessible(true);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		m.invoke(null, sheet, out);
		return new String(out.toByteArray(), StandardCharsets.UTF_8);
	}

	private static String ods(Object sheet, boolean highlight) throws Exception {
		Method m = MarkSheetExporter.class.getDeclaredMethod("writeOds", sheetType(),
				boolean.class, OutputStream.class);
		m.setAccessible(true);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		m.invoke(null, sheet, highlight, out);
		return contentOf(out.toByteArray());
	}

	private static String csvText(String raw) throws Exception {
		Method m = MarkSheetExporter.class.getDeclaredMethod("csvText", String.class);
		m.setAccessible(true);
		return (String) m.invoke(null, raw);
	}

	private static String contentOf(byte[] file) throws Exception {
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(file))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (!"content.xml".equals(entry.getName())) {
					continue;
				}
				ByteArrayOutputStream body = new ByteArrayOutputStream();
				byte[] buf = new byte[4096];
				int n;
				while ((n = zip.read(buf)) != -1) {
					body.write(buf, 0, n);
				}
				return new String(body.toByteArray(), StandardCharsets.UTF_8);
			}
		}
		throw new IllegalStateException("no content.xml");
	}

	/** The CSV's lines, with the byte-order mark taken off the first. */
	private static List<String> lines(String csv) {
		String body = csv.startsWith("﻿") ? csv.substring(1) : csv;
		List<String> out = new ArrayList<>(Arrays.asList(body.split("\r\n", -1)));
		// The file ends with a line break, which leaves one empty entry after it.
		assertEquals("", out.remove(out.size() - 1));
		return out;
	}

	/** Every cell in the ODS whose text is exactly this, with its style name or "" for none. */
	private static List<String> stylesOfCellsReading(String contentXml, String text) {
		Matcher cells = Pattern.compile(
				"<table:table-cell(?: table:style-name=\"([^\"]*)\")?[^>]*><text:p>"
						+ Pattern.quote(text) + "</text:p>").matcher(contentXml);
		List<String> styles = new ArrayList<>();
		while (cells.find()) {
			styles.add(cells.group(1) == null ? "" : cells.group(1));
		}
		return styles;
	}

	// ── The table ────────────────────────────────────────────────────────

	@Test
	public void aDayIsOneRowPerStudentAndOneColumnPerMarkedLecture() throws Exception {

		List<String> rows = lines(csv(build(day(), dayMarks())));

		assertEquals(Arrays.asList(
				"Roll no.,Name,"
						+ "CS201 · 19 Sep 2026 · 10:00–11:00,"
						+ "CS202 · 19 Sep 2026 · 11:00–12:00 · Batch A,"
						+ "Present,Absent",
				"2025CS01,Aarav Patil,P,AB,1,1",
				// Sneha was not on the batch A practical's sheet: blank, not absent.
				"2025CS02,Sneha Mehta,AB,,0,1",
				"2025CS03,Rahul Iyer,P,P,2,0"), rows);
	}

	@Test
	public void aLectureNobodyHasMarkedIsNotAColumnOfBlanks() throws Exception {
		// A column of blanks would read as a lecture every student skipped.
		String header = lines(csv(build(day(), dayMarks()))).get(0);

		assertFalse(header, header.contains("CS203"));
	}

	@Test
	public void oneLectureGetsNoPerStudentTotalsToRepeatItsOneMark() throws Exception {

		List<LectureDetails> one = Collections.singletonList(lecture(1, "CS201", 10, null, 3));
		List<String> rows = lines(csv(build(one, dayMarks())));

		assertEquals("Roll no.,Name,CS201 · 19 Sep 2026 · 10:00–11:00", rows.get(0));
		assertEquals("2025CS01,Aarav Patil,P", rows.get(1));
		assertEquals("2025CS02,Sneha Mehta,AB", rows.get(2));
	}

	@Test
	public void aStudentWithNoRollNumberForTheSessionComesLastNotFirst() throws Exception {

		List<LectureDetails> one = Collections.singletonList(lecture(1, "CS201", 10, null, 2));
		List<LectureMark> marks = Arrays.asList(
				mark(1, 9, "", "ZARA KHAN", true),
				mark(1, 1, "2025CS01", "AARAV PATIL", false));

		List<String> rows = lines(csv(build(one, marks)));

		assertEquals("2025CS01,Aarav Patil,AB", rows.get(1));
		assertEquals(",Zara Khan,P", rows.get(2));
	}

	// ── CSV specifics ────────────────────────────────────────────────────

	@Test
	public void theCsvIsUtf8WithAByteOrderMarkAndCrlfLines() throws Exception {
		// Without the mark, Excel reads UTF-8 as the local code page and mangles any name
		// outside ASCII.
		String csv = csv(build(day(), dayMarks()));

		assertTrue(csv.startsWith("﻿Roll no.,"));
		assertTrue(csv.contains("\r\n2025CS01,"));
		assertFalse(csv.replace("\r\n", "").contains("\n"));
	}

	@Test
	public void aFieldWithACommaOrAQuoteIsQuoted() throws Exception {
		assertEquals("\"Patil, Aarav\"", csvText("Patil, Aarav"));
		assertEquals("\"The \"\"Prof\"\"\"", csvText("The \"Prof\""));
		assertEquals("Aarav Patil", csvText("Aarav Patil"));
	}

	@Test
	public void aFieldASpreadsheetWouldRunAsAFormulaIsDefused() throws Exception {
		// Rosters are typed by other people, and a CSV opened with a double-click is where a
		// leading "=" would execute rather than show.
		assertEquals("'=1+1", csvText("=1+1"));
		// Defused first, then quoted for the quotes it contains.
		assertEquals("\"'=HYPERLINK(\"\"x\"\")\"", csvText("=HYPERLINK(\"x\")"));
		assertEquals("'+1", csvText("+1"));
		assertEquals("'-1", csvText("-1"));
		assertEquals("'@SUM(A1)", csvText("@SUM(A1)"));
	}

	@Test
	public void theCsvHasNoTotalsRowsThatWouldReadAsStudents() throws Exception {

		List<String> rows = lines(csv(build(day(), dayMarks())));

		assertEquals("a header and three students", 4, rows.size());
	}

	// ── Spreadsheet specifics ────────────────────────────────────────────

	@Test
	public void highlightingColoursEveryPresentGreenAndEveryAbsentRed() throws Exception {

		String xml = ods(build(day(), dayMarks()), true);

		assertEquals(Arrays.asList("cePresent", "cePresent", "cePresent"),
				stylesOfCellsReading(xml, "P"));
		assertEquals(Arrays.asList("ceAbsent", "ceAbsent"),
				stylesOfCellsReading(xml, "AB"));

		// Red and green as asked, on the fills the session export already uses for shading.
		assertTrue(xml.contains("style:name=\"cePresent\""));
		assertTrue(xml.matches("(?s).*style:name=\"cePresent\".*?fo:color=\"#006100\".*"));
		assertTrue(xml.matches("(?s).*style:name=\"ceAbsent\".*?fo:color=\"#9C0006\".*"));
	}

	@Test
	public void withoutHighlightingTheMarksAreTheSameButPlain() throws Exception {

		String xml = ods(build(day(), dayMarks()), false);

		assertEquals(Arrays.asList("", "", ""), stylesOfCellsReading(xml, "P"));
		assertEquals(Arrays.asList("", ""), stylesOfCellsReading(xml, "AB"));
		assertFalse(xml.contains("cePresent"));
		assertFalse(xml.contains("ceAbsent"));
	}

	@Test
	public void theSpreadsheetTotalsEachLecturesPresentAndAbsent() throws Exception {

		String xml = ods(build(day(), dayMarks()), false);

		// CS201: Aarav and Rahul present, Sneha absent. CS202: Rahul present, Aarav absent.
		assertTrue(xml, xml.matches("(?s).*<text:p>Present</text:p></table:table-cell>"
				+ "<table:table-cell office:value-type=\"float\" office:value=\"2\\.0\">"
				+ "<text:p>2</text:p></table:table-cell>"
				+ "<table:table-cell office:value-type=\"float\" office:value=\"1\\.0\">.*"));
		assertTrue(xml, xml.matches("(?s).*<text:p>Absent</text:p></table:table-cell>"
				+ "<table:table-cell office:value-type=\"float\" office:value=\"1\\.0\">"
				+ "<text:p>1</text:p></table:table-cell>"
				+ "<table:table-cell office:value-type=\"float\" office:value=\"1\\.0\">.*"));
	}

	@Test
	public void aStudentNotOnALecturesSheetIsABlankCellNotAnAbsence() throws Exception {
		// Sneha's CS202 cell: neither P nor AB.
		String xml = ods(build(day(), dayMarks()), true);

		assertTrue(xml.contains("<text:p>Sneha Mehta</text:p></table:table-cell>"
				+ "<table:table-cell table:style-name=\"ceAbsent\" office:value-type=\"string\">"
				+ "<text:p>AB</text:p></table:table-cell><table:table-cell/>"));
	}

	// ── File names ───────────────────────────────────────────────────────

	@Test
	public void fileNamesCarryTheDateAndForALectureTheTime() {

		assertEquals("attendance-2026-09-19.ods",
				MarkSheetExporter.dayFileName(LocalDate.of(2026, 9, 19), Format.ODS));

		// The time, because one course is often taught twice a day, once per batch.
		assertEquals("attendance-CS201-2026-09-19-1000.csv",
				MarkSheetExporter.lectureFileName(lecture(1, "CS201", 10, null, 1), Format.CSV));
	}

	@Test
	public void aCourseCodeIsMadeSafeForAFileName() {
		assertEquals("attendance-CS-201-2026-09-19-1000.ods",
				MarkSheetExporter.lectureFileName(lecture(1, "CS/201", 10, null, 1), Format.ODS));
	}

	@Test
	public void eachFormatTellsTheSaveDialogWhatItIs() {
		assertEquals("text/csv", Format.CSV.mimeType);
		assertEquals("application/vnd.oasis.opendocument.spreadsheet", Format.ODS.mimeType);
	}
}
