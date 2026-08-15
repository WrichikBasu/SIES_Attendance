package in.basulabs.siesattendance.backend;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Represents one attendance record. Each student, per lecture, will get one attendance record.
 */
@Entity(tableName = EntityAttendance.TABLE_NAME,
		primaryKeys = {"lectureID", "studentID"},
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

	public boolean present;

	public EntityAttendance(long lectureID, long studentID, boolean present) {
		this.lectureID = lectureID;
		this.studentID = studentID;
		this.present = present;
	}
}
