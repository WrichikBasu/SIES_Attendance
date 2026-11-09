package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Locale;

import in.basulabs.siesattendance.backend.util.AttendanceCsvParser;
import in.basulabs.siesattendance.backend.util.AttendanceCsvParser.Sheet;
import in.basulabs.siesattendance.backend.util.CanonicalCode;
import in.basulabs.siesattendance.backend.util.RosterCsv;

/**
 * The rule that a roll number or a course code is ASCII letters and digits, in capitals, and
 * the two parsers that apply it.
 */
public class CanonicalCodeTest {

	@Test
	public void separatorsAndCaseAreDropped() {
		assertEquals("CSFY001", CanonicalCode.of("cs-fy-001"));
		assertEquals("CSFY001", CanonicalCode.of(" CS/FY/001 "));
		assertEquals("CSFY001", CanonicalCode.of("CS FY_001."));
		assertEquals("2024CS032", CanonicalCode.of("2024cs032"));
		assertEquals("PHYS101", CanonicalCode.of("Phys 101"));
	}

	@Test
	public void nothingLeftIsEmpty() {
		assertEquals("", CanonicalCode.of(null));
		assertEquals("", CanonicalCode.of(""));
		assertEquals("", CanonicalCode.of(" -/._ "));
	}

	/**
	 * ASCII only. A Devanagari digit or a full-width letter looks like the same roll number to a
	 * person and is not the same to an index, so it is dropped rather than kept.
	 */
	@Test
	public void onlyAsciiLettersAndDigitsAreKept() {
		assertEquals("CS", CanonicalCode.of("CS१२"));          // CS१२
		assertEquals("1", CanonicalCode.of("ＣＳ" + "1"));       // ＣＳ1
		assertEquals("CAFE", CanonicalCode.of("cafée"));            // café + e
	}

	/** Locale.ROOT, so a Turkish device does not turn i into a dotted capital. */
	@Test
	public void theDefaultLocaleDoesNotMatter() {
		Locale before = Locale.getDefault();
		try {
			Locale.setDefault(Locale.forLanguageTag("tr-TR"));
			assertEquals("BIOI1", CanonicalCode.of("bio-i1"));
		} finally {
			Locale.setDefault(before);
		}
	}

	@Test
	public void canonicalIsWhatOfReturns() {
		for (String raw : List.of("cs-fy-001", "PHYS101", "a", "9 9")) {
			assertTrue(raw, CanonicalCode.isCanonical(CanonicalCode.of(raw)));
		}
		assertFalse(CanonicalCode.isCanonical(""));
		assertFalse(CanonicalCode.isCanonical(null));
		assertFalse(CanonicalCode.isCanonical("cs101"));
		assertFalse(CanonicalCode.isCanonical("CS-101"));
	}

	// ── The roster ───────────────────────────────────────────────────────

	@Test
	public void aRosterStoresTheCanonicalRollNumber() throws IOException {
		RosterCsv.Reading reading = RosterCsv.read(new StringReader(
				"rollNo,name,year,stream,majorDept\n"
						+ "2024-cs-032,Aarav Patil,FY,BSc_unaided,COMPUTER_SCIENCE\n"));

		assertTrue(reading.errors().toString(), reading.errors().isEmpty());
		assertEquals("2024CS032", reading.rows().get(0).rollNo());
	}

	@Test
	public void aRosterRefusesARollNumberWithNoLettersOrDigits() throws IOException {
		RosterCsv.Reading reading = RosterCsv.read(new StringReader(
				"rollNo,name,year,stream,majorDept\n"
						+ "--,Nobody,FY,BSc_unaided,COMPUTER_SCIENCE\n"));

		assertEquals(0, reading.rows().size());
		assertTrue(reading.errors().toString(),
				reading.errors().get(0).contains("no letters or digits"));
	}

	// ── The attendance sheet ─────────────────────────────────────────────

	@Test
	public void aSheetCarriesTheCanonicalCodeAndRollNumber() {
		Sheet sheet = AttendanceCsvParser.parse(List.of(
				"Course code,cs-101",
				"Date,21/07/2025",
				"Start time,09:00",
				"End time,10:00",
				"cs/fy/001,P"));

		assertTrue(sheet.errors.toString(), sheet.isUsable());
		assertEquals("CS101", sheet.columns.get(0).courseCode);
		assertEquals("CSFY001", sheet.rows.get(0).rollNo);
	}

	/** Any two spellings of one roll number are one student listed twice. */
	@Test
	public void everySpellingOfOneStudentIsListedTwice() {
		for (String other : List.of("cs-fy-001", "CS/FY/001", "CS FY 001", "CS_FY_001",
				"CS.FY.001", "csfy001")) {
			Sheet sheet = AttendanceCsvParser.parse(List.of(
					"Course code,CS101",
					"Date,21/07/2025",
					"Start time,09:00",
					"End time,10:00",
					"CSFY001,P",
					other + ",AB"));

			assertFalse(other + " has to be caught as CSFY001 again", sheet.errors.isEmpty());
		}
	}

	@Test
	public void aSheetRefusesARollNumberWithNoLettersOrDigits() {
		Sheet sheet = AttendanceCsvParser.parse(List.of(
				"Course code,CS101",
				"Date,21/07/2025",
				"Start time,09:00",
				"End time,10:00",
				"CSFY001,P",
				"???,AB"));

		assertTrue(sheet.errors.toString(), sheet.errors.get(0).contains("no letters or digits"));
	}

	@Test
	public void aColumnWhoseCodeHasNoLettersOrDigitsIsRefused() {
		Sheet sheet = AttendanceCsvParser.parse(List.of(
				"Course code,--",
				"Date,21/07/2025",
				"Start time,09:00",
				"End time,10:00",
				"CSFY001,P"));

		assertFalse(sheet.columns.get(0).isUsable());
		assertTrue(sheet.columns.get(0).problems.toString(),
				sheet.columns.get(0).problems.get(0).contains("no letters or digits"));
	}

	/** Refusing an empty result is the callers' job; the rule itself never throws. */
	@Test
	public void ofNeverThrows() {
		assertEquals("", CanonicalCode.of("\u0000￿"));
	}
}
