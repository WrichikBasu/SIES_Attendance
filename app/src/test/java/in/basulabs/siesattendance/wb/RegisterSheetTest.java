package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;
import in.basulabs.siesattendance.backend.pojo.AttendanceRow;
import in.basulabs.siesattendance.backend.pojo.LectureMark;
import in.basulabs.siesattendance.backend.pojo.StudentWithYear;
import in.basulabs.siesattendance.backend.util.RegisterSheet;

/**
 * What reopening a saved register puts on the sheet, and what saving it then writes.
 * <p>
 * The defect these pin: the sheet used to be today's roll with the stored marks laid over it,
 * and a save replaced every mark with the sheet. A student who had left the college or the
 * batch was on no roll, so their mark was deleted by the next save; a student who had joined
 * since was on the roll with no mark, so they were shown, and saved, as present.
 */
public class RegisterSheetTest {

	private static final long LECTURE = 4001;

	@Test
	public void aRegisterWithNoMarksIsAFreshSheetOfTheRoll() {

		List<AttendanceRow> rows = RegisterSheet.rows(
				List.of(onRoll(1, "CS001"), onRoll(2, "CS002")), List.of());

		assertEquals(2, rows.size());

		for (AttendanceRow row : rows) {
			assertTrue("everyone present until somebody says otherwise", row.present);
		}
	}

	@Test
	public void aStudentWhoHasLeftTheRollKeepsTheirRowAndTheirMark() {

		// 2 was marked absent, and has since left: the roll no longer lists them.
		List<AttendanceRow> rows = RegisterSheet.rows(
				List.of(onRoll(1, "CS001"), onRoll(3, "CS003")),
				List.of(mark(1, "CS001", true), mark(2, "CS002", false),
						mark(3, "CS003", true)));

		AttendanceRow left = row(rows, 2);

		assertFalse("their mark, as it was recorded", left.present);
		assertEquals("in roll-number order, where they sat on the day",
				List.of(1L, 2L, 3L), ids(rows));
	}

	@Test
	public void aStudentOnTheRollWithNoMarkIsOnTheSheetAsAbsent() {

		List<AttendanceRow> rows = RegisterSheet.rows(
				List.of(onRoll(1, "CS001"), onRoll(9, "CS009")),
				List.of(mark(1, "CS001", true)));

		// Present or absent, nothing in between: a lecture held for them with no mark is an
		// absence, and the sheet shows it and saves it as one.
		assertFalse(row(rows, 9).present);
		assertEquals(List.of(1L, 9L), ids(rows));
	}

	@Test
	public void aMarkedStudentWithNoEnrolmentForTheSessionIsStillListed() {

		// The LEFT JOIN's case: a mark whose student has no row for the lecture's session.
		List<AttendanceRow> rows = RegisterSheet.rows(List.of(onRoll(1, "CS001")),
				List.of(mark(1, "CS001", true), mark(5, "", true)));

		assertEquals(2, rows.size());
		assertTrue(row(rows, 5).present);
	}

	private static StudentWithYear onRoll(long id, String rollNo) {
		return new StudentWithYear(id, "STUDENT " + id, "they/them", null, rollNo, 2025,
				Year.FY, Stream.values()[0], Department.COMPUTER_SCIENCE, null, null, 1, null);
	}

	private static LectureMark mark(long id, String rollNo, boolean present) {
		LectureMark mark = new LectureMark();
		mark.lectureID = LECTURE;
		mark.studentID = id;
		mark.name = "STUDENT " + id;
		mark.rollNo = rollNo;
		mark.present = present;
		return mark;
	}

	private static AttendanceRow row(List<AttendanceRow> rows, long id) {
		for (AttendanceRow row : rows) {
			if (row.studentID == id) {
				return row;
			}
		}
		throw new AssertionError("student " + id + " is not on the sheet: " + ids(rows));
	}

	private static List<Long> ids(List<AttendanceRow> rows) {
		List<Long> ids = new ArrayList<>();
		for (AttendanceRow row : rows) {
			ids.add(row.studentID);
		}
		return ids;
	}
}
