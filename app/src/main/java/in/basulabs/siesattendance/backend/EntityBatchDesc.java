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
 */
@Entity(tableName = EntityBatchDesc.TABLE_NAME,
		indices = {@Index(value = {"batchID", "academicYear"}, unique = true)})
public class EntityBatchDesc {

	@Ignore
	public static final String TABLE_NAME = "batch_desc";

	@PrimaryKey(autoGenerate = true)
	public long batchID;

	@NonNull
	public String batchName;

	@Nullable
	public String batchDesc;

	public int academicYear;

	public EntityBatchDesc(@NonNull String batchName,
	                       int academicYear, @Nullable String batchDesc) {
		this.batchName = batchName;
		this.batchDesc = batchDesc;
		this.academicYear = academicYear;
	}
}
