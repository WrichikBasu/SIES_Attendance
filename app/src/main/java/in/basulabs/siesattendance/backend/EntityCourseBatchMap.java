package in.basulabs.siesattendance.backend;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.Ignore;

/**
 * Represents a many-to-many relationship between courses and batches.
 * <p>
 * One course can have multiple batches (e.g. two separate batches can do practicals at separate
 * times). Similarly, one batch can have multiple courses (e.g. students attending a core course
 * of the subject they are majoring in).
 */
@Entity(tableName = EntityCourseBatchMap.TABLE_NAME,
		primaryKeys = {"courseID", "batchID"},
		// courseID leads the composite key; batchID needs its own index for its foreign key.
		indices = {@Index(value = {"batchID"})},
		foreignKeys = {
				@ForeignKey(entity = EntityCourse.class,
						parentColumns = "courseID",
						childColumns = "courseID",
						onDelete = ForeignKey.RESTRICT,
						onUpdate = ForeignKey.CASCADE),
				@ForeignKey(entity = EntityBatchDesc.class,
						parentColumns = "batchID",
						childColumns = "batchID",
						onDelete = ForeignKey.RESTRICT,
						onUpdate = ForeignKey.CASCADE)
		})
public class EntityCourseBatchMap {

	@Ignore
	public static final String TABLE_NAME = "course_batch_map";

	public long courseID;
	public long batchID;

	public EntityCourseBatchMap(long courseID, long batchID) {
		this.courseID = courseID;
		this.batchID = batchID;
	}

}
