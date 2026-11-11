package in.basulabs.siesattendance.backend;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Ignore;
import androidx.room.Index;

/**
 * Represents one attendance record. Each student, per lecture, will get one attendance record.
 * <p>
 * The composite primary key of lecture and student is what makes marking idempotent: saving a
 * sheet twice overwrites the same rows rather than doubling them up. Sheets are always written
 * whole, through {@code DaoAttendance.saveSheet}, so a partially recorded lecture cannot exist.
 * <p>
 * Attendance is present or absent and nothing else. A row is missing only because nobody has
 * written one, and both the export and the statistics count a lecture held for a student's
 * batch as an absence when it has no row for them; see {@code SqlBits.MARKS}.
 */
@Entity(tableName = EntityAttendance.TABLE_NAME,
		primaryKeys = {"lectureID", "studentID"},
		// The composite primary key indexes lectureID first, so a lookup by studentID alone
		// could not use it. Statistics join and filter on studentID constantly, hence the
		// standalone index.
		indices = {@Index(value = {"studentID"})},
		foreignKeys = {
				@ForeignKey(entity = EntityLecture.class,
						parentColumns = {"lectureID"},
						childColumns = {"lectureID"},
						onDelete = ForeignKey.RESTRICT),
				@ForeignKey(entity = EntityStudent.class,
						parentColumns = {"studentID"},
						childColumns = {"studentID"},
						onDelete = ForeignKey.RESTRICT)
		})
public class EntityAttendance {

	@Ignore
	public static final String TABLE_NAME = "attendance";

	public long lectureID;

	public long studentID;

	/**
	 * True when the student was marked present. There is no third state: a sheet starts with
	 * everyone present and the professor taps the absentees, so every student on a saved sheet
	 * carries a definite answer.
	 */
	public boolean present;

	public EntityAttendance(long lectureID, long studentID, boolean present) {
		this.lectureID = lectureID;
		this.studentID = studentID;
		this.present = present;
	}
}
