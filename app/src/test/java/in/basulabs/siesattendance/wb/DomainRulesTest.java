package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import in.basulabs.siesattendance.backend.AcademicRules;
import in.basulabs.siesattendance.backend.CourseType;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Division;
import in.basulabs.siesattendance.backend.EnumLookup;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;

/**
 * White-box tests for the enum resolution and the academic rules that every write path
 * validates against.
 */
public class DomainRulesTest {

	// ── EnumLookup ───────────────────────────────────────────────────────

	@Test
	public void exactConstantNameBeatsEverythingElse() {
		assertEquals(Stream.BSc, EnumLookup.find(Stream.class, "BSc"));
		assertEquals(Stream.BSc_unaided, EnumLookup.find(Stream.class, "BSc_unaided"));
		assertEquals(Department.COMPUTER_SCIENCE,
				EnumLookup.find(Department.class, "COMPUTER_SCIENCE"));
	}

	@Test
	public void exactLabelMatchesBeforeLooseMatches() {
		// "BSc (unaided)" is BSc_unaided's label. A loose-first implementation would have
		// matched Stream.BSc on its base label and returned the wrong constant.
		assertEquals(Stream.BSc_unaided, EnumLookup.find(Stream.class, "BSc (unaided)"));
		assertEquals(Department.COMPUTER_SCIENCE, EnumLookup.find(Department.class, "CS"));
	}

	@Test
	public void looseMatchingIgnoresCaseSpacingAndPunctuation() {
		assertEquals(Department.COMPUTER_SCIENCE,
				EnumLookup.find(Department.class, "computer science"));
		assertEquals(Department.COMPUTER_SCIENCE,
				EnumLookup.find(Department.class, "Computer-Science"));
		assertEquals(Department.POLITICAL_SCIENCE,
				EnumLookup.find(Department.class, "pol. sc."));
		assertEquals(Stream.BSc_unaided, EnumLookup.find(Stream.class, "bsc unaided"));
		assertEquals(Year.Masters_P2, EnumLookup.find(Year.class, "masters part 2"));
		assertEquals(CourseType.MAJOR_PRACTICALS,
				EnumLookup.find(CourseType.class, "major practicals"));
	}

	@Test
	public void blankAndUnknownYieldNull() {
		assertNull(EnumLookup.find(Department.class, null));
		assertNull(EnumLookup.find(Department.class, ""));
		assertNull(EnumLookup.find(Department.class, "   "));
		assertNull(EnumLookup.find(Department.class, "Astrology"));
	}

	@Test
	public void everyConstantResolvesFromItsOwnNameAndLabel() {
		for (Department d : Department.values()) {
			assertEquals(d, Department.fromAny(d.name()));
			assertEquals(d, Department.fromAny(d.toString()));
		}
		for (Year y : Year.values()) {
			assertEquals(y, Year.fromAny(y.name()));
			assertEquals(y, Year.fromAny(y.toString()));
		}
		for (CourseType c : CourseType.values()) {
			assertEquals(c, CourseType.fromAny(c.name()));
			assertEquals(c, CourseType.fromAny(c.toString()));
		}
		// Stream is the one enum whose labels are NOT unique across constants once the
		// qualifier is stripped, so only the name round-trip is guaranteed.
		for (Stream s : Stream.values()) {
			assertEquals(s, Stream.fromAny(s.name()));
		}
	}

	@Test
	public void streamLabelsAreUniqueSoLabelLookupIsUnambiguous() {
		// The whole reason BSc_unaided is labelled "BSc (unaided)": two constants reading
		// "BSc" would make every dropdown and every CSV ambiguous.
		for (Stream a : Stream.values()) {
			for (Stream b : Stream.values()) {
				if (a != b) {
					assertFalse(a + " and " + b + " share a display label",
							a.toString().equals(b.toString()));
				}
			}
		}
	}

	// ── Department / stream pairing ──────────────────────────────────────

	@Test
	public void everyDepartmentOffersAtLeastOneStream() {
		for (Department d : Department.values()) {
			assertFalse(d + " offers no stream", AcademicRules.streamsFor(d).isEmpty());
		}
	}

	@Test
	public void unaidedDepartmentsGetTheUnaidedStreams() {
		assertEquals(Arrays.asList(Stream.BSc_unaided, Stream.MSc_unaided),
				AcademicRules.streamsFor(Department.COMPUTER_SCIENCE));
		assertEquals(Arrays.asList(Stream.BSc, Stream.MSc),
				AcademicRules.streamsFor(Department.PHYSICS));
	}

	@Test
	public void singleStreamDepartmentsAutoSelect() {
		assertEquals(Stream.BCom, AcademicRules.soleStreamFor(Department.COMMERCE));
		assertEquals(Stream.BMS, AcademicRules.soleStreamFor(Department.MANAGEMENT_STUDIES));
		assertEquals(Stream.BAMMC, AcademicRules.soleStreamFor(Department.MASS_MEDIA));
		assertNull("Physics has a genuine choice",
				AcademicRules.soleStreamFor(Department.PHYSICS));
		assertNull(AcademicRules.soleStreamFor(null));
	}

	@Test
	public void departmentsForIsTheExactMirrorOfStreamsFor() {
		for (Stream s : Stream.values()) {
			for (Department d : AcademicRules.departmentsFor(s)) {
				assertTrue(d + " listed for " + s + " but does not offer it",
						AcademicRules.streamsFor(d).contains(s));
			}
			for (Department d : Department.values()) {
				if (AcademicRules.streamsFor(d).contains(s)) {
					assertTrue(d + " offers " + s + " but is not listed for it",
							AcademicRules.departmentsFor(s).contains(d));
				}
			}
		}
	}

	@Test
	public void hasMastersFollowsTheStreamList() {
		assertTrue(AcademicRules.hasMasters(Department.PHYSICS));
		assertTrue(AcademicRules.hasMasters(Department.COMPUTER_SCIENCE));
		assertFalse(AcademicRules.hasMasters(Department.COMMERCE));
		assertFalse(AcademicRules.hasMasters(Department.MANAGEMENT_STUDIES));
		assertFalse(AcademicRules.hasMasters(Department.MASS_MEDIA));
	}

	// ── validate() ───────────────────────────────────────────────────────

	@Test
	public void validateAcceptsSoundCombinations() {
		assertNull(AcademicRules.validate(Department.PHYSICS, Stream.BSc, Year.SY, 3));
		assertNull(AcademicRules.validate(Department.COMPUTER_SCIENCE, Stream.BSc_unaided,
				Year.FY, 1));
		assertNull(AcademicRules.validate(Department.PHYSICS, Stream.MSc, Year.Masters_P2, 4));
		assertNull(AcademicRules.validate(Department.COMMERCE, Stream.BCom, Year.TY, 6));
	}

	@Test
	public void validateRejectsADepartmentStreamMismatch() {
		assertNotNull(AcademicRules.validate(Department.COMMERCE, Stream.BSc, null, null));
		assertNotNull(AcademicRules.validate(Department.PHYSICS, Stream.BCom, null, null));
		// The aided/unaided split is a real constraint, not just a label.
		assertNotNull(AcademicRules.validate(Department.COMPUTER_SCIENCE, Stream.BSc,
				null, null));
		assertNotNull(AcademicRules.validate(Department.PHYSICS, Stream.BSc_unaided,
				null, null));
	}

	@Test
	public void validateRejectsUndergraduateYearOnAPostgraduateStream() {
		assertNotNull(AcademicRules.validate(Department.PHYSICS, Stream.MSc, Year.SY, null));
		assertNotNull(AcademicRules.validate(Department.PHYSICS, Stream.BSc,
				Year.Masters_P1, null));
	}

	@Test
	public void validateRejectsAMastersYearForADepartmentWithNoMastersCourse() {
		assertNotNull(AcademicRules.validate(Department.MANAGEMENT_STUDIES, null,
				Year.Masters_P1, null));
		assertNotNull(AcademicRules.validate(Department.COMMERCE, null,
				Year.Masters_P2, null));
	}

	@Test
	public void validateRejectsASemesterTheYearDoesNotCover() {
		assertNotNull(AcademicRules.validate(Department.PHYSICS, Stream.BSc, Year.FY, 5));
		assertNotNull(AcademicRules.validate(Department.PHYSICS, Stream.MSc,
				Year.Masters_P1, 3));
		assertNotNull(AcademicRules.validate(null, null, Year.TY, 1));
	}

	@Test
	public void validateRejectsASemesterNoMastersCourseHas() {
		assertNotNull(AcademicRules.validate(null, Stream.MSc, null, 5));
		assertNotNull(AcademicRules.validate(null, Stream.MA, null, 6));
		assertNull("semester 4 is a real master's semester",
				AcademicRules.validate(null, Stream.MSc, null, 4));
	}

	@Test
	public void validateTreatsUnansweredFieldsAsNotYetContradictory() {
		assertNull(AcademicRules.validate(null, null, null, null));
		assertNull(AcademicRules.validate(Department.PHYSICS, null, null, null));
		assertNull(AcademicRules.validate(null, Stream.BSc, null, null));
	}

	// ── Semesters ────────────────────────────────────────────────────────

	@Test
	public void semestersRunConsecutivelyAndRestartForMasters() {
		assertEquals(Arrays.asList(1, 2), AcademicRules.semestersFor(Year.FY));
		assertEquals(Arrays.asList(3, 4), AcademicRules.semestersFor(Year.SY));
		assertEquals(Arrays.asList(5, 6), AcademicRules.semestersFor(Year.TY));
		assertEquals(Arrays.asList(1, 2), AcademicRules.semestersFor(Year.Masters_P1));
		assertEquals(Arrays.asList(3, 4), AcademicRules.semestersFor(Year.Masters_P2));
		assertEquals(Arrays.asList(1, 2, 3, 4, 5, 6), AcademicRules.semestersFor((Year) null));
	}

	@Test
	public void semesterToYearIsTheInverseOfYearToSemester() {
		for (int semester = 1; semester <= 6; semester++) {
			List<Year> years = AcademicRules.yearsForSemester(semester);
			assertFalse("no year covers semester " + semester, years.isEmpty());
			for (Year y : years) {
				assertTrue(y + " does not cover semester " + semester,
						AcademicRules.semestersFor(y).contains(semester));
			}
		}
		// 1 and 2 are genuinely ambiguous, which is why the inverse returns a list.
		assertEquals(Arrays.asList(Year.FY, Year.Masters_P1), AcademicRules.yearsForSemester(1));
		assertEquals(Arrays.asList(Year.TY), AcademicRules.yearsForSemester(5));
	}

	@Test
	public void mastersStreamHasNoFifthOrSixthSemester() {
		assertEquals(Arrays.asList(1, 2, 3, 4), AcademicRules.semestersFor(null, Stream.MSc));
		assertEquals(Arrays.asList(1, 2, 3, 4, 5, 6),
				AcademicRules.semestersFor(null, Stream.BSc));
		// A chosen year always wins over the stream.
		assertEquals(Arrays.asList(5, 6), AcademicRules.semestersFor(Year.TY, Stream.BSc));
	}

	@Test
	public void reconcileSemesterDropsWhatTheNewYearCannotHold() {
		assertEquals(Integer.valueOf(3),
				AcademicRules.reconcileSemester(Year.SY, Stream.BSc, 3));
		assertNull(AcademicRules.reconcileSemester(Year.FY, Stream.BSc, 5));
		assertNull(AcademicRules.reconcileSemester(null, null, null));
		assertEquals(Integer.valueOf(5),
				AcademicRules.reconcileSemester(null, null, 5));
	}

	// ── reconcile() vs resolveRequested() ────────────────────────────────

	@Test
	public void reconcileKeepsAValidStream() {
		assertEquals(Stream.BSc, AcademicRules.reconcile(Department.PHYSICS, Stream.BSc));
		assertEquals(Stream.MSc_unaided,
				AcademicRules.reconcile(Department.COMPUTER_SCIENCE, Stream.MSc_unaided));
	}

	@Test
	public void reconcileSubstitutesTheEquivalentAtTheSameLevel() {
		// Physics + MSc, then switch to Computer Science: should land on unaided MSc rather
		// than falling back to undergraduate.
		assertEquals(Stream.MSc_unaided,
				AcademicRules.reconcile(Department.COMPUTER_SCIENCE, Stream.MSc));
		assertEquals(Stream.BSc,
				AcademicRules.reconcile(Department.PHYSICS, Stream.BSc_unaided));
	}

	@Test
	public void reconcileTakesTheSoleStreamWhenThereIsOnlyOne() {
		assertEquals(Stream.BCom, AcademicRules.reconcile(Department.COMMERCE, Stream.BSc));
		assertEquals(Stream.BMS,
				AcademicRules.reconcile(Department.MANAGEMENT_STUDIES, Stream.MSc));
	}

	@Test
	public void reconcileYieldsNullWhenNoEquivalentExists() {
		// English offers BA and MA; a master's stream has an equivalent, so use a case with
		// none: a department with only undergraduate streams asked for a master's, where the
		// department has more than one stream is impossible, so check the null-input path.
		assertNull(AcademicRules.reconcile(null, null));
		assertEquals(Stream.BSc, AcademicRules.reconcile(null, Stream.BSc));
	}

	@Test
	public void resolveRequestedMatchesOnBaseLabelSoAFileNeedNotKnowAboutUnaided() {
		assertEquals(Stream.BSc_unaided,
				AcademicRules.resolveRequested(Department.COMPUTER_SCIENCE, Stream.BSc));
		assertEquals(Stream.MSc_unaided,
				AcademicRules.resolveRequested(Department.COMPUTER_SCIENCE, Stream.MSc));
		assertEquals(Stream.BSc,
				AcademicRules.resolveRequested(Department.PHYSICS, Stream.BSc_unaided));
	}

	@Test
	public void resolveRequestedRefusesRatherThanCorrectingAWrongStream() {
		// This is the difference from reconcile(): "COMMERCE,BSc" in a CSV is a mistake in
		// the file and must be reported, not quietly filed as BCom.
		assertNull(AcademicRules.resolveRequested(Department.COMMERCE, Stream.BSc));
		assertNull(AcademicRules.resolveRequested(Department.PHYSICS, Stream.BCom));
		assertEquals(Stream.BCom, AcademicRules.reconcile(Department.COMMERCE, Stream.BSc));
	}

	@Test
	public void resolveRequestedAlwaysProducesAValidPairingWhenItProducesAnything() {
		for (Department d : Department.values()) {
			for (Stream requested : Stream.values()) {
				Stream resolved = AcademicRules.resolveRequested(d, requested);
				if (resolved != null) {
					assertNull(d + " + " + requested + " resolved to an invalid " + resolved,
							AcademicRules.validate(d, resolved, null, null));
				}
			}
		}
	}

	@Test
	public void yearsForNarrowsToTheRightHalfOfTheYearList() {
		assertEquals(Arrays.asList(Year.FY, Year.SY, Year.TY),
				AcademicRules.yearsFor(Department.PHYSICS, Stream.BSc));
		assertEquals(Arrays.asList(Year.Masters_P1, Year.Masters_P2),
				AcademicRules.yearsFor(Department.PHYSICS, Stream.MSc));
		assertEquals(Arrays.asList(Year.FY, Year.SY, Year.TY),
				AcademicRules.yearsFor(Department.COMMERCE, null));
		assertEquals(Arrays.asList(Year.FY, Year.SY, Year.TY, Year.Masters_P1, Year.Masters_P2),
				AcademicRules.yearsFor(Department.PHYSICS, null));
	}

	@Test
	public void everyYearOfferedByADepartmentActuallyValidates() {
		for (Department d : Department.values()) {
			for (Stream s : AcademicRules.streamsFor(d)) {
				for (Year y : AcademicRules.yearsFor(d, s)) {
					assertNull(d + "/" + s + "/" + y + " is offered but does not validate",
							AcademicRules.validate(d, s, y, null));
					for (Integer sem : AcademicRules.semestersFor(y, s)) {
						assertNull(d + "/" + s + "/" + y + "/" + sem
										+ " is offered but does not validate",
								AcademicRules.validate(d, s, y, sem));
					}
				}
			}
		}
	}

	// ── Divisions ────────────────────────────────────────────────────────

	@Test
	public void onlyTheAidedBScAndBAAreSplitIntoDivisions() {
		assertTrue(Stream.BSc.hasDivisions());
		assertTrue(Stream.BA.hasDivisions());

		// The one place the aided and unaided split means something beyond a label.
		assertFalse(Stream.BSc_unaided.hasDivisions());

		for (Stream s : Arrays.asList(Stream.BCom, Stream.BMS, Stream.BAMMC,
				Stream.MA, Stream.MSc, Stream.MSc_unaided)) {
			assertFalse(s + " must not offer divisions", s.hasDivisions());
		}
	}

	@Test
	public void divisionsForFollowsTheStreamAndRunsFromAToD() {
		assertEquals(Arrays.asList(Division.A, Division.B, Division.C, Division.D),
				AcademicRules.divisionsFor(Stream.BSc));
		assertEquals(Arrays.asList(Division.A, Division.B, Division.C, Division.D),
				AcademicRules.divisionsFor(Stream.BA));

		assertTrue(AcademicRules.divisionsFor(Stream.BSc_unaided).isEmpty());
		assertTrue(AcademicRules.divisionsFor(Stream.BCom).isEmpty());
		assertTrue(AcademicRules.divisionsFor(Stream.MSc).isEmpty());
	}

	@Test
	public void divisionsForOffersEverythingWhenTheStreamIsNotYetKnown() {
		// A filter with no stream chosen is not the same as a stream with no divisions: a
		// student of any divided stream could still be in Div C.
		assertEquals(Arrays.asList(Division.values()), AcademicRules.divisionsFor(null));
	}

	@Test
	public void divisionsForAgreesWithTheStreamItWasAskedAbout() {
		for (Stream s : Stream.values()) {
			assertEquals(s + " must offer divisions exactly when it has them",
					s.hasDivisions(), !AcademicRules.divisionsFor(s).isEmpty());
		}
	}

	@Test
	public void validateRejectsADivisionOnAStreamThatHasNone() {
		assertNotNull(AcademicRules.validate(Department.COMPUTER_SCIENCE, Stream.BSc_unaided,
				Year.SY, 3, Division.A));
		assertNotNull(AcademicRules.validate(Department.COMMERCE, Stream.BCom, Year.FY, 1,
				Division.B));
		assertNotNull(AcademicRules.validate(Department.PHYSICS, Stream.MSc,
				Year.Masters_P1, 1, Division.A));
	}

	@Test
	public void validateAcceptsADivisionOnADividedStreamAndABlankOneAnywhere() {
		assertNull(AcademicRules.validate(Department.PHYSICS, Stream.BSc, Year.SY, 3,
				Division.D));
		assertNull(AcademicRules.validate(Department.ENGLISH, Stream.BA, Year.FY, 1,
				Division.A));

		// Blank is always allowed: most classes have no division, and a row predating the
		// column has none either.
		for (Stream s : Stream.values()) {
			assertNull(s + " must accept a blank division",
					AcademicRules.validate(null, s, null, null, null));
		}
	}

	@Test
	public void validateStillReportsTheEarlierProblemFirst() {
		// A division on a stream that has none is the last thing checked, so a row that is
		// wrong in two ways is reported by the fault the user should fix first.
		String problem = AcademicRules.validate(Department.COMMERCE, Stream.BSc, Year.FY, 1,
				Division.A);
		assertNotNull(problem);
		assertTrue("the department mismatch should be reported before the division: " + problem,
				problem.contains("not offered"));
	}

	@Test
	public void reconcileDivisionDropsWhatTheNewStreamCannotHold() {
		assertEquals(Division.B, AcademicRules.reconcileDivision(Stream.BSc, Division.B));
		assertEquals(Division.B, AcademicRules.reconcileDivision(null, Division.B));

		assertNull(AcademicRules.reconcileDivision(Stream.BSc_unaided, Division.B));
		assertNull(AcademicRules.reconcileDivision(Stream.BCom, Division.A));
		assertNull(AcademicRules.reconcileDivision(Stream.BSc, null));
	}

	@Test
	public void everyDivisionOfferedForAStreamActuallyValidates() {
		for (Department d : Department.values()) {
			for (Stream s : AcademicRules.streamsFor(d)) {
				for (Division division : AcademicRules.divisionsFor(s)) {
					assertNull(d + "/" + s + "/" + division
									+ " is offered but does not validate",
							AcademicRules.validate(d, s, null, null, division));
				}
			}
		}
	}

	@Test
	public void divisionsResolveFromWhateverARosterWritesThem() {
		assertEquals(Division.B, Division.fromAny("B"));
		assertEquals(Division.B, Division.fromAny("b"));
		assertEquals(Division.B, Division.fromAny(" B "));

		// A roster writes the prefix as often as not.
		assertEquals(Division.C, Division.fromAny("Div C"));
		assertEquals(Division.C, Division.fromAny("div. C"));
		assertEquals(Division.C, Division.fromAny("Division C"));

		assertNull(Division.fromAny(null));
		assertNull(Division.fromAny(""));
		assertNull(Division.fromAny("   "));

		// E does not exist, and a bare prefix names no division at all.
		assertNull(Division.fromAny("E"));
		assertNull(Division.fromAny("Div"));
	}
}
