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
 * Represents a course.
 * <p>
 * A course is distinct from a lecture. A course refers to an academic subject or paper offered
 * by a department during a particular year and semester. For example:
 * <ul>
 *     <li>"Accelerator Physics" may be offered by the Department of Physics in a specific year
 *     and semester.</li>
 *     <li>"Data Structures" may be offered by the Department of Computer Science in a
 *     specific year and semester.</li>
 * </ul>
 * <p>
 * A lecture represents a specific class session conducted for a course on a particular date
 * and during a defined time period. The course is listed in the syllabus, while multiple
 * lectures are conducted by the professor to cover and complete the course content.
 * <p>
 * A course is generally designated by an alphanumeric ID, such as "PHYS101" or "CS101". We,
 * however, do not use the alphanumeric ID as the primary key in the database.
 */
@Entity(tableName = EntityCourse.TABLE_NAME,
		indices = {@Index(value = {"courseCode"}, unique = true),
				// Indexed because it is a foreign key: without it, any change to the
				// professors table forces a full scan of courses to check the constraint.
				@Index(value = {"profID"})},
		foreignKeys = {
				@ForeignKey(entity = EntityProfessor.class,
						parentColumns = "profID",
						childColumns = "profID",
						onDelete = ForeignKey.RESTRICT,
						onUpdate = ForeignKey.CASCADE)})
public class EntityCourse {

	@Ignore
	public static final String TABLE_NAME = "courses";

	/**
	 * Local identifier, unrelated to the course code. The code is what the college uses and is
	 * uniquely indexed, but codes get reissued and reworded between syllabus revisions, so
	 * lectures and batch links are hung off this instead.
	 * <p>
	 * Stamped by {@link IdGenerator} on insert, and unique across every device that syncs into
	 * the central database.
	 */
	@PrimaryKey
	public long courseID;

	/** The professor who takes the course. Mandatory, which is why professors cannot be deleted. */
	public long profID;

	/** The college's own identifier, such as "PHYS101". Uniquely indexed. */
	@NonNull
	public String courseCode;

	/** The title as it appears in the syllabus, for instance "Accelerator Physics". */
	@NonNull
	public String courseTopic;

	/*
	 * The four fields below describe the class the course is taught to. They do not decide who
	 * is on its roll: that is the batches linked to it (DaoStudent.getRollForCourseSync).
	 */

	@NonNull
	public Stream stream;

	/**
	 * The department that owns the course, which is not the same as the department of the
	 * professor teaching it.
	 */
	@NonNull
	public Department offeringDepartment;

	@NonNull
	public Year year;

	public int semester;

	@NonNull
	public CourseType courseType;

	/** Free text for anything the structured fields do not cover. */
	@Nullable
	public String remarks;

	/**
	 * When the course was first offered and when it stopped being offered. A discontinued
	 * course is kept rather than deleted, because every lecture and attendance record filed
	 * under it would otherwise lose the thing it describes. A non-null
	 * {@code dateDiscontinued} is what keeps the course out of the active lists.
	 */
	@Nullable
	public ZonedDateTime dateIntroduced;

	@Nullable
	public ZonedDateTime dateDiscontinued;

	public EntityCourse(@NonNull String courseCode, @NonNull String courseTopic,
	                    @NonNull Stream stream, @NonNull Department offeringDepartment,
	                    @NonNull Year year, int semester, @NonNull CourseType courseType,
	                    @Nullable String remarks, @Nullable ZonedDateTime dateIntroduced,
	                    @Nullable ZonedDateTime dateDiscontinued) {
		this.courseCode = courseCode;
		this.courseTopic = courseTopic;
		this.stream = stream;
		this.offeringDepartment = offeringDepartment;
		this.year = year;
		this.semester = semester;
		this.courseType = courseType;
		this.remarks = remarks;
		this.dateIntroduced = dateIntroduced;
		this.dateDiscontinued = dateDiscontinued;
	}

	/**
	 * Convenience constructor for a course that is being offered now: no introduction date
	 * recorded, and not discontinued.
	 */
	@Ignore
	public EntityCourse(@NonNull String courseCode, @NonNull String courseTopic,
	                    @NonNull Stream stream, @NonNull Department offeringDepartment,
	                    @NonNull Year year, int semester, @NonNull CourseType courseType,
	                    @Nullable String remarks) {
		this.courseCode = courseCode;
		this.courseTopic = courseTopic;
		this.stream = stream;
		this.offeringDepartment = offeringDepartment;
		this.year = year;
		this.semester = semester;
		this.courseType = courseType;
		this.remarks = remarks;
		this.dateIntroduced = null;
		this.dateDiscontinued = null;
	}
}
