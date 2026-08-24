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
 * <p>
 * These links are a record of which batches a course is taught to, not a constraint on taking
 * attendance. The batch picker lists every batch in the session regardless of what is linked
 * here, because a batch that has not been linked yet is a batch whose attendance could
 * otherwise not be recorded at all. See the note at the foot of {@code DaoCourse}.
 * <p>
 * There is no academic year column: both sides already belong to one, and a course is only ever
 * linked to batches from the session it is being taught in.
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
