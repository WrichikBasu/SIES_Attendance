package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import java.time.ZonedDateTime;

/**
 * One class session of a course, held at a specific date and time.
 * <p>
 * A lecture is created at the moment attendance is taken rather than scheduled ahead of it, so
 * every row here is a session that actually happened.
 * <p>
 * {@code batchID} is nullable. A null batch means the lecture was held for the course's whole
 * class, meaning the students of that year, stream and department who take the course. A
 * non-null batch narrows it to that batch's members, which is what practicals need.
 * <p>
 * There is no academic year column. A lecture's session is worked out from its start time,
 * matched against the bounds in {@link EntityAcademicYear}, so a lecture cannot end up filed
 * under a session its date contradicts. The cost is that a lecture held outside every defined
 * session belongs to none of them and drops out of the reports entirely, which is what
 * {@code DaoAcademicYear.countOrphanLectures} exists to surface.
 */
@Entity(tableName = EntityLecture.TABLE_NAME,
		indices = {
				@Index(value = {"courseID", "batchID", "startDateTime", "endDateTime"},
						unique = true),
				@Index(value = {"batchID"}),
				@Index(value = {"courseID"})
		},
		foreignKeys = {
				@ForeignKey(entity = EntityBatchDesc.class,
						parentColumns = {"batchID"},
						childColumns = {"batchID"},
						onDelete = ForeignKey.RESTRICT,
						onUpdate = ForeignKey.CASCADE),
				@ForeignKey(entity = EntityCourse.class,
						parentColumns = {"courseID"},
						childColumns = {"courseID"},
						onDelete = ForeignKey.RESTRICT,
						onUpdate = ForeignKey.CASCADE)
		}
)
public class EntityLecture {

	@Ignore
	public static final String TABLE_NAME = "lectures";

	/**
	 * Stamped by {@link IdGenerator} on insert, and unique across every device that syncs into
	 * the central database. It encodes roughly when the row was recorded, which is close to but
	 * not the same as when the lecture was held: a session entered after the fact carries the
	 * time it was typed in, and {@link #startDateTime} remains the only statement of when it
	 * actually took place.
	 */
	@PrimaryKey
	public long lectureID;

	public long courseID;

	// The unique index over course, batch and time span is what stops the same session being
	// recorded twice. It does not catch whole-class lectures on its own, since SQLite treats
	// NULLs in an index as distinct; DaoLecture.findExisting handles that case explicitly.

	/**
	 * Null when the lecture was taken for the whole class rather than a single batch.
	 */
	@Nullable
	public Long batchID;

	/**
	 * Start and end of the session, stored through {@code TypeConvertors} as
	 * "epochSecond|nano|zone". Queries that compare against them have to slice out the leading
	 * integer and cast it, which is what {@code SqlBits.EPOCH_START} is for; comparing the
	 * stored string directly would sort lexicographically.
	 */
	@NonNull
	public ZonedDateTime startDateTime;

	@NonNull
	public ZonedDateTime endDateTime;

	/** Free text about the session itself, such as the topic covered or a room change. */
	@Nullable
	public String notes;

	public EntityLecture(long courseID, @Nullable Long batchID,
	                     @NonNull ZonedDateTime startDateTime,
	                     @NonNull ZonedDateTime endDateTime, @Nullable String notes) {
		this.courseID = courseID;
		this.batchID = batchID;
		this.startDateTime = startDateTime;
		this.endDateTime = endDateTime;
		this.notes = notes;
	}
}
