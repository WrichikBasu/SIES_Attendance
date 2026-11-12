package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import in.basulabs.siesattendance.backend.util.MarkSheetExporter;
import in.basulabs.siesattendance.backend.util.OdsWriter;

/**
 * White-box probes of the export writers with hostile cell text: characters that are legal in
 * a Java string, and so in a student's name, but not in an XML document; and spreadsheet
 * formula triggers in the positions the CSV defusing does not look at.
 */
public class ExportEdgeProbeTest {

	private static byte[] contentXml(OdsWriter ods) throws Exception {

		ByteArrayOutputStream file = new ByteArrayOutputStream();
		ods.writeTo(file);

		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(file.toByteArray()))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (entry.getName().equals("content.xml")) {
					return zip.readAllBytes();
				}
			}
		}
		throw new AssertionError("no content.xml in the workbook");
	}

	private static void assertWellFormed(byte[] xml) throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		assertNotNull(factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml)));
	}

	/**
	 * BUG: a vertical tab (U+000B) is what Word leaves behind for a soft line break, and it
	 * survives copy-paste into a roster. XML 1.0 forbids it outright, so {@code escape()} lets
	 * it through and the whole register becomes a file no spreadsheet will open.
	 */
	@Test
	public void aVerticalTabInANameDoesNotCorruptTheRegister() throws Exception {
		OdsWriter ods = new OdsWriter("S");
		ods.addRow(List.of(OdsWriter.Cell.text("Priya\u000BRao", null)));

		assertWellFormed(contentXml(ods));
	}

	/** BUG: the same for the other C0 controls XML forbids, such as U+0001 and U+001F. */
	@Test
	public void otherForbiddenControlsDoNotCorruptTheRegister() throws Exception {
		OdsWriter ods = new OdsWriter("S");
		ods.addRow(List.of(OdsWriter.Cell.text("a\u0001b\u001Fc\u0000d", null)));

		assertWellFormed(contentXml(ods));
	}

	/** BUG: and in a sheet name, which takes a course code that is just as free-form. */
	@Test
	public void aControlCharacterInASheetNameDoesNotCorruptTheRegister() throws Exception {
		OdsWriter ods = new OdsWriter("CS\u000B101");
		ods.addRow(List.of(OdsWriter.Cell.text("x", null)));

		assertWellFormed(contentXml(ods));
	}

	/** The soft line break reads as the space it stood for, not as the two words run together. */
	@Test
	public void aVerticalTabBecomesASpace() throws Exception {
		OdsWriter ods = new OdsWriter("S");
		ods.addRow(List.of(OdsWriter.Cell.text("Priya\u000BRao", null)));

		assertTrue(new String(contentXml(ods), java.nio.charset.StandardCharsets.UTF_8)
				.contains("<text:p>Priya Rao</text:p>"));
	}

	/**
	 * Sheet names are de-duplicated before they are escaped, so a control character has to be
	 * gone by then, or "CS 101" and "CS\u000B101" would pass as different and land as two
	 * sheets of the same name: as unopenable as the control character itself.
	 */
	@Test
	public void aControlCharacterCannotSmuggleInADuplicateSheetName() throws Exception {
		OdsWriter ods = new OdsWriter("CS 101");
		ods.startSheet("CS\u000B101");

		String xml = new String(contentXml(ods), java.nio.charset.StandardCharsets.UTF_8);
		assertTrue(xml, xml.contains("table:name=\"CS 101\""));
		assertTrue(xml, xml.contains("table:name=\"CS 101 (2)\""));
	}

	/** A lone surrogate is not a character, and is replaced like the controls. */
	@Test
	public void aLoneSurrogateDoesNotCorruptTheRegister() throws Exception {
		OdsWriter ods = new OdsWriter("S");
		ods.addRow(List.of(OdsWriter.Cell.text("a\uD83Db", null)));

		assertWellFormed(contentXml(ods));
	}

	/** Infinity used to be shown as Long.MAX_VALUE; it is blank now, and keeps its style. */
	@Test
	public void aNonFiniteNumberIsABlankCellThatKeepsItsStyle() throws Exception {
		OdsWriter ods = new OdsWriter("S");
		ods.defineStyle("ceDefaulter", "#FFC7CE", "#9C0006", false);
		ods.addRow(List.of(OdsWriter.Cell.number(Double.POSITIVE_INFINITY, "ceDefaulter")));

		String xml = new String(contentXml(ods), java.nio.charset.StandardCharsets.UTF_8);
		assertTrue(xml, xml.contains("<table:table-cell table:style-name=\"ceDefaulter\"/>"));
		assertTrue(xml, !xml.contains("9223372036854775807"));
	}

	/** The controls XML does allow must still pass: tab, LF and CR. */
	@Test
	public void tabsAndLineBreaksStayWellFormed() throws Exception {
		OdsWriter ods = new OdsWriter("S");
		ods.addRow(List.of(OdsWriter.Cell.text("a\tb\nc\rd", null)));

		assertWellFormed(contentXml(ods));
	}

	/** A non-BMP character (a surrogate pair) must survive the escape untouched. */
	@Test
	public void astralCharactersSurvive() throws Exception {
		OdsWriter ods = new OdsWriter("S");
		ods.addRow(List.of(OdsWriter.Cell.text("Rao 😀", null)));

		byte[] xml = contentXml(ods);
		assertWellFormed(xml);
		assertTrue(new String(xml, java.nio.charset.StandardCharsets.UTF_8)
				.contains("Rao 😀"));
	}

	/** Non-finite numbers are not valid {@code office:value}s. */
	@Test
	public void aNonFiniteNumberDoesNotReachTheFile() throws Exception {
		OdsWriter ods = new OdsWriter("S");
		ods.addRow(List.of(OdsWriter.Cell.number(Double.NaN, null),
				OdsWriter.Cell.number(Double.POSITIVE_INFINITY, null)));

		String xml = new String(contentXml(ods), java.nio.charset.StandardCharsets.UTF_8);
		assertTrue("NaN or Infinity written as a cell value: " + xml,
				!xml.contains("office:value=\"NaN\"")
						&& !xml.contains("office:value=\"Infinity\""));
	}

	// ── CSV formula defusing ─────────────────────────────────────────────

	private static String csvText(String raw) throws Exception {
		Method m = MarkSheetExporter.class.getDeclaredMethod("csvText", String.class);
		m.setAccessible(true);
		return (String) m.invoke(null, raw);
	}

	@Test
	public void everyFormulaTriggerIsDefused() throws Exception {
		for (String trigger : List.of("=", "+", "-", "@", "\t", "\r")) {
			String cell = csvText(trigger + "HYPERLINK(\"http://x\")");
			String unquoted = cell.startsWith("\"") ? cell.substring(1) : cell;
			assertTrue("not defused: " + cell, unquoted.startsWith("'"));
		}
	}

	/**
	 * A full-width equals sign (U+FF1D) is turned into "=" by some spreadsheet imports.
	 * Characterisation only: the report lists it as an optional hardening.
	 */
	@Test
	public void aFullWidthEqualsIsLeftAsIs() throws Exception {
		assertEquals("＝1+1", csvText("＝1+1"));
	}

	@Test
	public void anEmptyFieldStaysEmpty() throws Exception {
		assertEquals("", csvText(""));
	}
}
