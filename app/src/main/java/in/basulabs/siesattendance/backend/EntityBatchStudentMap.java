package in.basulabs.siesattendance.backend;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Ignore;

/**
 * Represents a many-to-many relationship between batches and students.
 * <p>
 * One batch can have multiple students, and one student can be enrolled in multiple batches. There
 * may be more than one such batch for a student in a given academic year.
 */
@Entity(tableName = EntityBatchStudentMap.TABLE_NAME,
		primaryKeys = {"batchID", "studentID", "academicYear"},
		foreignKeys = {
				@ForeignKey(entity = EntityBatchDesc.class,
						parentColumns = "batchID",
						childColumns = "batchID",
						onDelete = ForeignKey.RESTRICT,
						onUpdate = ForeignKey.CASCADE),
				@ForeignKey(entity = EntityStudent.class,
						parentColumns = "studentID",
						childColumns = "studentID",
						onDelete = ForeignKey.RESTRICT,
						onUpdate = ForeignKey.CASCADE)
		})
public class EntityBatchStudentMap {

	@Ignore
	public static final String TABLE_NAME = "batch_student_map";

	public long batchID;

	public long studentID;

	public int academicYear;

	public EntityBatchStudentMap(long batchID, long studentID, int academicYear) {
		this.batchID = batchID;
		this.studentID = studentID;
		this.academicYear = academicYear;
	}

}
