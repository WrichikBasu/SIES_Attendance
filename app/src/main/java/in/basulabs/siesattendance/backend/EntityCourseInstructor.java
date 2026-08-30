package in.basulabs.siesattendance.backend;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Ignore;
import androidx.room.Index;

/**
 * Represents a many-to-many relationship between courses and professors: who actually teaches a
 * course.
 * <p>
 * {@link EntityCourse#profID} names one professor and cannot name more, which is wrong for a
 * course that is team-taught, and wrong again for practical batches whose demonstrator is not
 * the person delivering the lectures. That column is kept, but as the course's
 * <em>coordinator</em> — the single person answerable for it — and no longer as the whole answer
 * to who teaches it. This table is the whole answer.
 * <p>
 * The coordinator is always one of the rows here. Nothing has to remember to add them: every
 * write goes through {@link DaoCourse#setInstructors}, which puts them in, and the migration
 * that created this table seeded one row per existing course from {@code courses.profID}. So a
 * query asking who teaches a course can read this table alone and never join {@code courses} to
 * catch a coordinator who would otherwise be missing.
 * <p>
 * There is no academic year column, and no role column. Both sides already belong to a year
 * through the course, and a lecturer is not yet distinguished from a lab demonstrator because
 * nothing in the app asks the difference — the pair is what fixes the multi-instructor gap, and
 * a role can be added by a later migration when something needs to tell the two apart.
 */
@Entity(tableName = EntityCourseInstructor.TABLE_NAME,
		primaryKeys = {"courseID", "profID"},
		// courseID leads the composite key; profID needs its own index for its foreign key,
		// and for the "which courses does this professor teach" lookup that reads it backwards.
		indices = {@Index(value = {"profID"})},
		foreignKeys = {
				@ForeignKey(entity = EntityCourse.class,
						parentColumns = "courseID",
						childColumns = "courseID",
						onDelete = ForeignKey.RESTRICT,
						onUpdate = ForeignKey.CASCADE),
				@ForeignKey(entity = EntityProfessor.class,
						parentColumns = "profID",
						childColumns = "profID",
						onDelete = ForeignKey.RESTRICT,
						onUpdate = ForeignKey.CASCADE)
		})
public class EntityCourseInstructor {

	@Ignore
	public static final String TABLE_NAME = "course_instructor";

	public long courseID;

	public long profID;

	public EntityCourseInstructor(long courseID, long profID) {
		this.courseID = courseID;
		this.profID = profID;
	}

}
