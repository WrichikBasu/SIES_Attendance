package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * A batch is a group of students who attend the lectures of a particular course together.
 * <p>
 * A batch may be composed of students from the same department, stream, year, and semester, or
 * different departments, streams, years, and semesters, depending on the course.
 * <p>
 * A batch is identified by a unique batch ID and an academic year.
 * <p>
 * Nothing here ties a batch to a course. A batch is simply a named group of students for one
 * session, a practicals division or a tutorial group, and the same group is commonly taught
 * several different subjects. Courses are attached separately through
 * {@link EntityCourseBatchMap}, and the batch picker when taking attendance deliberately lists
 * every batch in the session rather than guessing which ones belong to the selected course.
 * <p>
 * Batches do not carry forward.
 */
@Entity(tableName = EntityBatchDesc.TABLE_NAME,
		indices = {@Index(value = {"batchID", "academicYear"}, unique = true)})
public class EntityBatchDesc {

	@Ignore
	public static final String TABLE_NAME = "batch_desc";

	/**
	 * Stamped by {@link IdGenerator} on insert, and unique across every device that syncs into
	 * the central database.
	 */
	@PrimaryKey
	public long batchID;

	/** Short label shown wherever a batch has to be picked, for instance "Batch A". */
	@NonNull
	public String batchName;

	/**
	 * Optional longer note, for whatever the name alone does not convey: which laboratory it
	 * meets in, which half of the class it covers.
	 */
	@Nullable
	public String batchDesc;

	/**
	 * The session this batch belongs to, as the starting calendar year. Part of the unique
	 * index alongside the ID, so the same batch name can be reused in a later session without
	 * colliding with this one.
	 */
	public int academicYear;

	public EntityBatchDesc(@NonNull String batchName,
	                       int academicYear, @Nullable String batchDesc) {
		this.batchName = batchName;
		this.batchDesc = batchDesc;
		this.academicYear = academicYear;
	}
}
