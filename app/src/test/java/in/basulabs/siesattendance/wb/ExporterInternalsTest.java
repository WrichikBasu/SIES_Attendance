package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import in.basulabs.siesattendance.backend.util.AttendanceExporter;
import in.basulabs.siesattendance.backend.util.OdsWriter;

/**
 * The sheet-assembly half of the exporter, reached by reflection because the whole
 * {@code export} entry point needs a Context and a content URI.
 * <p>
 * These are the parts that decide what a reader of the finished spreadsheet sees, and they
 * are worth pinning: the file leaves the app and gets read by people who cannot check it
 * against the database.
 */
public class ExporterInternalsTest {

	/** Builds the exporter's private per-student row holder. */
	private static Object student(String rollNo, String name) throws Exception {
		Class<?> type = Class.forName(
				"in.basulabs.siesattendance.backend.util.AttendanceExporter$Student");
		Constructor<?> ctor = type.getDeclaredConstructor(String.class, String.class);
		ctor.setAccessible(true);
		return ctor.newInstance(rollNo, name);
	}

	@SuppressWarnings("unchecked")
	private static void figures(Object student, long courseID, int held, int attended)
			throws Exception {
		Field field = student.getClass().getDeclaredField("figures");
		field.setAccessible(true);
		((Map<Long, int[]>) field.get(student)).put(courseID, new int[]{held, attended});
	}

	private static boolean writeStudent(OdsWriter ods, Object student,
	                                    Map<Long, String> columns, int threshold)
			throws Exception {
		Method m = AttendanceExporter.class.getDeclaredMethod("writeStudent",
				OdsWriter.class, Class.forName(
						"in.basulabs.siesattendance.backend.util.AttendanceExporter$Student"),
				Map.class, int.class);
		m.setAccessible(true);
		return (Boolean) m.invoke(null, ods, student, columns, threshold);
	}

	private static String contentOf(OdsWriter ods) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ods.writeTo(out);

		try (ZipInputStream zip = new ZipInputStream(
				new ByteArrayInputStream(out.toByteArray()))) {
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

	/** The cell values of the last row written, in order, blanks as nulls. */
	private static List<String> lastRow(String contentXml) {
		Matcher rows = Pattern.compile("<table:table-row>(.*?)</table:table-row>")
				.matcher(contentXml);
		String last = null;
		while (rows.find()) {
			last = rows.group(1);
		}

		List<String> values = new ArrayList<>();
		Matcher cells = Pattern.compile(
						"<table:table-cell[^>]*/>|<table:table-cell[^>]*>.*?</table:table-cell>")
				.matcher(last == null ? "" : last);

		while (cells.find()) {
			String cell = cells.group();
			Matcher text = Pattern.compile("<text:p>(.*?)</text:p>").matcher(cell);
			values.add(text.find() ? text.group(1) : null);
		}
		return values;
	}

	private static Map<Long, String> columns(Object... pairs) {
		Map<Long, String> map = new LinkedHashMap<>();
		for (int i = 0; i + 1 < pairs.length; i += 2) {
			map.put((Long) pairs[i], (String) pairs[i + 1]);
		}
		return map;
	}

	// ── Row shape ────────────────────────────────────────────────────────

	@Test
	public void aStudentRowIsRollNameThenAPairPerCourseThenTheOverallTriple() throws Exception {
		OdsWriter ods = new OdsWriter("S");
		Object aarav = student("2025CS01", "AARAV PATIL");
		figures(aarav, 1L, 10, 8);
		figures(aarav, 2L, 4, 4);

		writeStudent(ods, aarav, columns(1L, "CS201", 2L, "CS202"), 75);

		List<String> row = lastRow(contentOf(ods));

		assertEquals("roll, name, 2 pairs, then held/attended/percent", 9, row.size());
		assertEquals("2025CS01", row.get(0));
		assertEquals("AARAV PATIL", row.get(1));
		assertEquals("10", row.get(2));
		assertEquals("8", row.get(3));
		assertEquals("4", row.get(4));
		assertEquals("4", row.get(5));
		assertEquals("14", row.get(6));
		assertEquals("12", row.get(7));
		assertEquals("85.7", row.get(8));
	}

	@Test
	public void aCourseTheStudentDoesNotTakeIsBlankNotZero() throws Exception {
		// A zero would read as "took it and attended none of it", which is a different and
		// much more serious statement about a student.
		OdsWriter ods = new OdsWriter("S");
		Object rahul = student("2025CS03", "RAHUL DESAI");
		figures(rahul, 1L, 6, 6);

		writeStudent(ods, rahul, columns(1L, "CS201", 2L, "CS202"), 75);

		List<String> row = lastRow(contentOf(ods));

		assertEquals("6", row.get(2));
		assertEquals("6", row.get(3));
		assertEquals("the untaken course must be blank", null, row.get(4));
		assertEquals(null, row.get(5));
		assertEquals("the untaken course must not enter the total", "6", row.get(6));
	}

	@Test
	public void aZeroAttendedIsWrittenAsAZeroNotABlank() throws Exception {
		OdsWriter ods = new OdsWriter("S");
		Object s = student("R1", "N");
		figures(s, 1L, 5, 0);

		writeStudent(ods, s, columns(1L, "CS201"), 75);

		List<String> row = lastRow(contentOf(ods));
		assertEquals("5", row.get(2));
		assertEquals("0", row.get(3));
		assertEquals("0% is printed as a zero, not hidden", "0", row.get(6));
	}

	// ── Totals and the defaulter line ────────────────────────────────────

	@Test
	public void totalsCoverOnlyTheColumnsOnThisSheet() throws Exception {
		// This is what makes a group sheet report the group's percentage rather than the
		// overall one.
		OdsWriter ods = new OdsWriter("S");
		Object s = student("R1", "N");
		figures(s, 1L, 10, 5);
		figures(s, 2L, 10, 10);

		writeStudent(ods, s, columns(1L, "CS201"), AttendanceExporter.NO_THRESHOLD);

		List<String> row = lastRow(contentOf(ods));
		assertEquals("10", row.get(2));
		assertEquals("5", row.get(3));
		assertEquals("the second course is not on this sheet and must not be totalled",
				"10", row.get(4));
		assertEquals("50", row.get(6));
	}

	@Test
	public void theDefaulterLineIsExclusiveAndReportedByTheReturnValue() throws Exception {
		Map<Long, String> columns = columns(1L, "CS201");

		OdsWriter exactly = new OdsWriter("S");
		Object onTheLine = student("R1", "N");
		figures(onTheLine, 1L, 4, 3);
		assertFalse("exactly 75% is not a defaulter",
				writeStudent(exactly, onTheLine, columns, 75));

		OdsWriter below = new OdsWriter("S");
		Object justUnder = student("R2", "N");
		figures(justUnder, 1L, 5, 3);
		assertTrue("60% is a defaulter at a 75% line",
				writeStudent(below, justUnder, columns, 75));
	}

	@Test
	public void noThresholdShadesNobodyEvenAtZeroPerCent() throws Exception {
		OdsWriter ods = new OdsWriter("S");
		Object s = student("R1", "N");
		figures(s, 1L, 10, 0);

		assertFalse(writeStudent(ods, s, columns(1L, "CS201"),
				AttendanceExporter.NO_THRESHOLD));
		assertFalse("nothing should be shaded",
				contentOf(ods).contains("table:style-name=\"ceDefaulter\""));
	}

	@Test
	public void aThresholdOfZeroIsNotTheSameAsNoThreshold() throws Exception {
		// 0% is a value a student can genuinely have, so the sentinel has to be distinct.
		assertEquals(-1, AttendanceExporter.NO_THRESHOLD);

		OdsWriter ods = new OdsWriter("S");
		Object s = student("R1", "N");
		figures(s, 1L, 10, 0);

		assertFalse("nobody is below 0%", writeStudent(ods, s, columns(1L, "CS201"), 0));
	}

	@Test
	public void aStudentWithNoLecturesHeldIsNotShadedAsADefaulter() throws Exception {
		// Treating 0 of 0 as 0% would shade the whole sheet red on the first day of term.
		OdsWriter ods = new OdsWriter("S");
		Object s = student("R1", "N");
		figures(s, 1L, 0, 0);

		assertFalse(writeStudent(ods, s, columns(1L, "CS201"), 75));
	}

	@Test
	public void aStudentWithNoLecturesHeldIsPrintedAsOneHundredPerCent() throws Exception {
		// DEFECT: the 100.0 that keeps such a student out of the defaulter list is also what
		// gets written into the "%" column, so a student for whom no lecture has been held
		// reads on the sheet as a perfect attender. The defaulter test is separately guarded
		// by totalHeld > 0, so the placeholder is not needed for that and only reaches the
		// cell. A blank, or the em dash the app's own screens use, would be honest.
		OdsWriter ods = new OdsWriter("S");
		Object s = student("R1", "N");
		figures(s, 1L, 0, 0);

		writeStudent(ods, s, columns(1L, "CS201"), 75);

		List<String> row = lastRow(contentOf(ods));
		// roll, name, held, attended, totalHeld, totalAttended, percent
		assertEquals("0", row.get(2));
		assertEquals("0", row.get(3));
		assertEquals("0", row.get(4));
		assertEquals("0", row.get(5));
		assertEquals("100", row.get(6));
	}

	@Test
	public void aStudentTakingNoneOfTheSheetsCoursesAlsoReadsAsOneHundredPerCent()
			throws Exception {
		// The same defect from the other direction: on a group sheet, a student with no
		// figures at all for any of the group's courses totals 0 of 0 and prints 100%.
		OdsWriter ods = new OdsWriter("S");
		Object s = student("R1", "N");
		figures(s, 99L, 10, 10);

		writeStudent(ods, s, columns(1L, "CS201"), 75);

		List<String> row = lastRow(contentOf(ods));
		assertEquals(null, row.get(2));
		assertEquals("0", row.get(4));
		assertEquals("100", row.get(6));
	}

	@Test
	public void thePercentageIsRoundedToOneDecimal() throws Exception {
		OdsWriter ods = new OdsWriter("S");
		Object s = student("R1", "N");
		figures(s, 1L, 3, 2);

		writeStudent(ods, s, columns(1L, "CS201"), AttendanceExporter.NO_THRESHOLD);

		assertEquals("66.7", lastRow(contentOf(ods)).get(6));
	}

	@Test
	public void namesAreWrittenExactlyAsTheRosterSuppliedThem() throws Exception {
		// The sheet is checked against the college's own records, so the office's spelling
		// is what belongs in it, not the app's display tidying.
		OdsWriter ods = new OdsWriter("S");
		Object s = student("2025CS01", "AARAV  PATIL");
		figures(s, 1L, 1, 1);

		writeStudent(ods, s, columns(1L, "CS201"), 75);
		assertEquals("AARAV  PATIL", lastRow(contentOf(ods)).get(1));
	}

	// ── Suggested file name ──────────────────────────────────────────────

	@Test
	public void theSuggestedFileNameCarriesTheSession() {
		assertEquals("attendance-2025-26.ods", AttendanceExporter.suggestedFileName(2025));
		assertEquals("attendance-2009-10.ods", AttendanceExporter.suggestedFileName(2009));
	}

	@Test
	public void theSuggestedFileNameDoesNotPadAcrossTheCenturyRollover() {
		// DEFECT: EntityAcademicYear.label() formats the trailing year with %02d and gets
		// "2099-00"; the exporter uses a bare modulo in two places and gets "2099-0", so the
		// file name and the heading inside the file disagree with every other label in the
		// app. Cosmetic, but the two spellings are meant to be the same thing.
		assertEquals("attendance-2099-0.ods", AttendanceExporter.suggestedFileName(2099));
		assertEquals("2099-00",
				in.basulabs.siesattendance.backend.EntityAcademicYear
						.defaultFor(2099).label());
	}
}
