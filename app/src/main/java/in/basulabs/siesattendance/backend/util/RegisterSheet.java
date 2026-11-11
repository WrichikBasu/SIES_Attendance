package in.basulabs.siesattendance.backend.util;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import in.basulabs.siesattendance.backend.pojo.AttendanceRow;
import in.basulabs.siesattendance.backend.pojo.LectureMark;
import in.basulabs.siesattendance.backend.pojo.StudentWithYear;

/**
 * The rows of a register that is being reopened.
 * <p>
 * A register that has been saved is a record of who was in the room, and the roll it was called
 * from has usually moved on since: students leave the college, change batch, or join late. So a
 * saved register is rebuilt from its own marks, not from today's roll.
 * <ul>
 * <li>Everybody who holds a mark is on the sheet with that mark, whether or not they are still
 * on the roll. Leaving them off is how their mark used to be lost: the sheet was built from the
 * roll, and saving it replaced every mark with the sheet.</li>
 * <li>Somebody on the roll with no mark is on the sheet as <em>not marked</em>, and stays that
 * way unless the user marks them. They used to be shown as present, and saved as present, at a
 * class they may have joined after.</li>
 * <li>A register with no marks at all is a fresh sheet: the whole roll, everyone present.</li>
 * </ul>
 */
public final class RegisterSheet {

	private RegisterSheet() {
	}

	/**
	 * @param roll  who is on the lecture's roll now
	 * @param marks every mark stored for the lecture, whoever holds it
	 */
	@NonNull
	public static List<AttendanceRow> rows(@NonNull List<StudentWithYear> roll,
	                                       @NonNull List<LectureMark> marks) {

		List<AttendanceRow> rows = new ArrayList<>();

		if (marks.isEmpty()) {
			for (StudentWithYear student : roll) {
				rows.add(new AttendanceRow(student.studentID, student.name, student.rollNo));
			}
			return rows;
		}

		Set<Long> marked = new HashSet<>();

		for (LectureMark mark : marks) {
			marked.add(mark.studentID);
			rows.add(new AttendanceRow(mark.studentID, mark.name, mark.rollNo, mark.present));
		}

		for (StudentWithYear student : roll) {
			if (!marked.contains(student.studentID)) {
				rows.add(new AttendanceRow(student.studentID, student.name, student.rollNo,
						false, false));
			}
		}

		// One list in roll-number order, as the roll is, so a student who has left sits where
		// they sat when the register was called and not in a group at the end.
		rows.sort(Comparator.comparing((AttendanceRow row) -> row.rollNo)
				.thenComparing(row -> row.name));

		return rows;
	}

	/**
	 * The marks a save writes: one for every row that carries an answer, and none for a row
	 * nobody has marked.
	 */
	@NonNull
	public static List<AttendanceRow> toSave(@NonNull List<AttendanceRow> rows) {

		List<AttendanceRow> answered = new ArrayList<>();

		for (AttendanceRow row : rows) {
			if (row.marked) {
				answered.add(row);
			}
		}
		return answered;
	}
}
