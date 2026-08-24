package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import in.basulabs.siesattendance.backend.util.OdsWriter;

/**
 * The hand-rolled OpenDocument writer. Every assertion here is about a rule a spreadsheet
 * reader enforces: get one wrong and the file simply will not open.
 */
public class OdsWriterTest {

	/** Unzips a written workbook into part name to bytes. */
	private static Map<String, byte[]> unzip(byte[] ods) throws Exception {
		Map<String, byte[]> parts = new HashMap<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(ods))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				byte[] buf = new byte[4096];
				int n;
				while ((n = zip.read(buf)) != -1) {
					out.write(buf, 0, n);
				}
				parts.put(entry.getName(), out.toByteArray());
			}
		}
		return parts;
	}

	private static byte[] write(OdsWriter ods) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ods.writeTo(out);
		return out.toByteArray();
	}

	private static String content(byte[] ods) throws Exception {
		return new String(unzip(ods).get("content.xml"), StandardCharsets.UTF_8);
	}

	private static List<String> sheetNames(String contentXml) {
		List<String> names = new ArrayList<>();
		Matcher m = Pattern.compile("<table:table table:name=\"([^\"]*)\"").matcher(contentXml);
		while (m.find()) {
			names.add(m.group(1));
		}
		return names;
	}

	// ── Package structure ────────────────────────────────────────────────

	@Test
	public void theMimetypePartComesFirstAndIsStoredUncompressed() throws Exception {
		OdsWriter ods = new OdsWriter("Attendance");
		ods.addRow(List.of(OdsWriter.Cell.text("hello", null)));

		byte[] bytes = write(ods);

		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
			ZipEntry first = zip.getNextEntry();
			assertNotNull(first);
			assertEquals("mimetype must be the first entry in an ODF package",
					"mimetype", first.getName());
			assertEquals("mimetype must be STORED, not deflated",
					ZipEntry.STORED, first.getMethod());
		}

		// Stored uncompressed means the media type is readable as literal bytes near the
		// start of the file, which is how a reader identifies an ODF package without
		// inflating anything.
		String head = new String(bytes, 0, 128, StandardCharsets.ISO_8859_1);
		assertTrue("mimetype not found in the first 128 bytes",
				head.indexOf("mimetype") >= 0);
		assertTrue("the media type is not stored literally",
				head.indexOf("application/vnd.oasis.opendocument.spreadsheet")
						> head.indexOf("mimetype"));
	}

	@Test
	public void everyPartTheManifestPromisesIsActuallyPresent() throws Exception {
		OdsWriter ods = new OdsWriter("Sheet");
		ods.addRow(List.of(OdsWriter.Cell.number(1, null)));

		Map<String, byte[]> parts = unzip(write(ods));
		String manifest = new String(parts.get("META-INF/manifest.xml"),
				StandardCharsets.UTF_8);

		Matcher m = Pattern.compile("manifest:full-path=\"([^\"]+)\"").matcher(manifest);
		while (m.find()) {
			String path = m.group(1);
			if (!"/".equals(path)) {
				assertTrue("manifest lists " + path + " but the package does not contain it",
						parts.containsKey(path));
			}
		}
		assertTrue(parts.containsKey("content.xml"));
		assertTrue(parts.containsKey("styles.xml"));
	}

	@Test
	public void allThreeXmlPartsAreWellFormed() throws Exception {
		OdsWriter ods = new OdsWriter("Attendance 2025");
		ods.defineStyle("ceDefaulter", "#FFC7CE", "#9C0006", false);
		ods.addRow(List.of(OdsWriter.Cell.text("Roll no.", "ceDefaulter"),
				OdsWriter.Cell.number(93.5, null), OdsWriter.Cell.empty()));

		Map<String, byte[]> parts = unzip(write(ods));

		for (String name : List.of("content.xml", "styles.xml", "META-INF/manifest.xml")) {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			assertNotNull(name + " is not well-formed XML",
					factory.newDocumentBuilder()
							.parse(new ByteArrayInputStream(parts.get(name))));
		}
	}

	// ── Cells ────────────────────────────────────────────────────────────

	@Test
	public void numberCellsCarryARealNumericValueSoTheSheetCanBeSummed() throws Exception {
		OdsWriter ods = new OdsWriter("S");
		ods.addRow(List.of(OdsWriter.Cell.number(12, null), OdsWriter.Cell.number(66.7, null)));

		String xml = content(write(ods));

		assertTrue(xml.contains("office:value-type=\"float\" office:value=\"12.0\""));
		assertTrue(xml.contains("office:value-type=\"float\" office:value=\"66.7\""));
		// Whole numbers display without a trailing .0 even though the stored value is a float.
		assertTrue(xml.contains("<text:p>12</text:p>"));
		assertTrue(xml.contains("<text:p>66.7</text:p>"));
	}

	@Test
	public void blankCellsAreSelfClosingAndCarryNoValue() throws Exception {
		OdsWriter ods = new OdsWriter("S");
		ods.addRow(List.of(OdsWriter.Cell.empty(), OdsWriter.Cell.text(null, null)));

		String xml = content(write(ods));
		Matcher m = Pattern.compile("<table:table-cell/>").matcher(xml);

		int blanks = 0;
		while (m.find()) {
			blanks++;
		}
		assertEquals("a null text cell must be blank, not the word \"null\"", 2, blanks);
		assertFalse(xml.contains("null</text:p>"));
	}

	@Test
	public void aZeroIsNotWrittenAsABlank() throws Exception {
		// The sheet leans on this distinction: blank means "does not take the course",
		// zero means "took it and attended none of it".
		OdsWriter ods = new OdsWriter("S");
		ods.addRow(List.of(OdsWriter.Cell.number(0, null)));

		String xml = content(write(ods));
		assertTrue(xml.contains("office:value=\"0.0\""));
		assertTrue(xml.contains("<text:p>0</text:p>"));
	}

	@Test
	public void textIsXmlEscapedSoAStudentNameCannotBreakTheFile() throws Exception {
		OdsWriter ods = new OdsWriter("S");
		ods.addRow(List.of(OdsWriter.Cell.text("A & B <c> \"d\" 'e'", null)));

		String xml = content(write(ods));

		assertTrue(xml.contains("A &amp; B &lt;c&gt; &quot;d&quot; &apos;e&apos;"));
		DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
		f.setNamespaceAware(true);
		assertNotNull(f.newDocumentBuilder().parse(
				new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
	}

	@Test
	public void nonAsciiTextSurvivesAsUtf8() throws Exception {
		OdsWriter ods = new OdsWriter("S");
		ods.addRow(List.of(OdsWriter.Cell.text("श्री कुलकर्णी – naïve", null)));

		assertTrue(content(write(ods)).contains("श्री कुलकर्णी – naïve"));
	}

	// ── Sheets ───────────────────────────────────────────────────────────

	@Test
	public void sheetNamesAreStrippedOfCharactersOdfForbids() throws Exception {
		OdsWriter ods = new OdsWriter("Sem 1 / 2 [core]: *?'");
		ods.addRow(List.of(OdsWriter.Cell.text("x", null)));

		String name = sheetNames(content(write(ods))).get(0);

		for (char c : new char[]{'\\', '/', ':', '*', '?', '[', ']', '\''}) {
			assertFalse("sheet name still contains " + c, name.indexOf(c) >= 0);
		}
	}

	@Test
	public void sheetNamesAreTrimmedToTheThirtyOneCharacterLimit() throws Exception {
		OdsWriter ods = new OdsWriter("A".repeat(60));
		ods.addRow(List.of(OdsWriter.Cell.text("x", null)));

		assertEquals(31, sheetNames(content(write(ods))).get(0).length());
	}

	@Test
	public void duplicateSheetNamesAreNumberedRatherThanLeftToCollide() throws Exception {
		// Two tables of the same name produce a file no reader will open, and group names
		// come from the user, who has every right to call two of them "Theory".
		OdsWriter ods = new OdsWriter("Theory");
		ods.addRow(List.of(OdsWriter.Cell.text("x", null)));
		ods.startSheet("Theory");
		ods.addRow(List.of(OdsWriter.Cell.text("y", null)));
		ods.startSheet("Theory");
		ods.addRow(List.of(OdsWriter.Cell.text("z", null)));

		List<String> names = sheetNames(content(write(ods)));

		assertEquals(3, names.size());
		assertEquals(3, new java.util.HashSet<>(names).size());
		assertEquals(List.of("Theory", "Theory (2)", "Theory (3)"), names);
	}

	@Test
	public void deduplicationStillRespectsTheLengthLimit() throws Exception {
		String longName = "B".repeat(31);

		OdsWriter ods = new OdsWriter(longName);
		ods.addRow(List.of(OdsWriter.Cell.text("x", null)));
		ods.startSheet(longName);
		ods.addRow(List.of(OdsWriter.Cell.text("y", null)));

		List<String> names = sheetNames(content(write(ods)));

		assertEquals(2, new java.util.HashSet<>(names).size());
		for (String n : names) {
			assertTrue(n + " exceeds 31 characters", n.length() <= 31);
		}
	}

	@Test
	public void anEmptySheetNameGetsAGeneratedOne() throws Exception {
		OdsWriter ods = new OdsWriter("   ");
		ods.addRow(List.of(OdsWriter.Cell.text("x", null)));

		String name = sheetNames(content(write(ods))).get(0);
		assertFalse(name.trim().isEmpty());
	}

	@Test
	public void theColumnCountCoversTheWidestRow() throws Exception {
		OdsWriter ods = new OdsWriter("S");
		ods.addRow(List.of(OdsWriter.Cell.text("title", null)));
		ods.addBlankRow();
		ods.addRow(List.of(OdsWriter.Cell.text("a", null), OdsWriter.Cell.text("b", null),
				OdsWriter.Cell.text("c", null), OdsWriter.Cell.text("d", null),
				OdsWriter.Cell.text("e", null)));

		String xml = content(write(ods));
		assertTrue("the declared column count must cover the widest row",
				xml.contains("table:number-columns-repeated=\"5\""));
	}

	@Test
	public void rowsGoToWhicheverSheetWasStartedLast() throws Exception {
		OdsWriter ods = new OdsWriter("First");
		ods.addRow(List.of(OdsWriter.Cell.text("on first", null)));
		ods.startSheet("Second");
		ods.addRow(List.of(OdsWriter.Cell.text("on second", null)));

		String xml = content(write(ods));
		int firstTable = xml.indexOf("table:name=\"First\"");
		int secondTable = xml.indexOf("table:name=\"Second\"");

		assertTrue(xml.indexOf("on first") > firstTable);
		assertTrue(xml.indexOf("on first") < secondTable);
		assertTrue(xml.indexOf("on second") > secondTable);
	}

	// ── Styles ───────────────────────────────────────────────────────────

	@Test
	public void definedStylesAreDeclaredAndReferencedByName() throws Exception {
		OdsWriter ods = new OdsWriter("S");
		ods.defineStyle("ceDefaulter", "#FFC7CE", "#9C0006", false);
		ods.defineStyle("ceHeader", "#D9E1F2", null, true);
		ods.addRow(List.of(OdsWriter.Cell.text("short", "ceDefaulter"),
				OdsWriter.Cell.text("head", "ceHeader")));

		String xml = content(write(ods));

		assertTrue(xml.contains("style:name=\"ceDefaulter\""));
		assertTrue(xml.contains("fo:background-color=\"#FFC7CE\""));
		assertTrue(xml.contains("fo:color=\"#9C0006\""));
		assertTrue(xml.contains("fo:font-weight=\"bold\""));
		assertTrue(xml.contains("table:style-name=\"ceDefaulter\""));
		assertTrue(xml.contains("table:style-name=\"ceHeader\""));
	}

	@Test
	public void redefiningAStyleReplacesItRatherThanDeclaringItTwice() throws Exception {
		OdsWriter ods = new OdsWriter("S");
		ods.defineStyle("ce1", "#000000", null, false);
		ods.defineStyle("ce1", "#FFFFFF", null, false);
		ods.addRow(List.of(OdsWriter.Cell.text("x", "ce1")));

		String xml = content(write(ods));
		int first = xml.indexOf("style:name=\"ce1\"");

		assertTrue(first >= 0);
		assertEquals("a duplicate style declaration makes the file invalid",
				-1, xml.indexOf("style:name=\"ce1\"", first + 1));
		assertTrue(xml.contains("#FFFFFF"));
		assertFalse(xml.contains("#000000"));
	}
}
