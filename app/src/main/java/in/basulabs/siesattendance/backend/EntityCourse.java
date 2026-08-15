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
		indices = {@Index(value = {"courseCode"}, unique = true)},
		foreignKeys = {
				@ForeignKey(entity = EntityProfessor.class,
						parentColumns = "profID",
						childColumns = "profID",
						onDelete = ForeignKey.RESTRICT,
						onUpdate = ForeignKey.CASCADE)})
public class EntityCourse {

	@Ignore
	public static final String TABLE_NAME = "courses";

	@PrimaryKey(autoGenerate = true)
	public long courseID;

	public long profID;

	@NonNull
	public String courseCode;

	@NonNull
	public String courseTopic;

	@NonNull
	public Stream stream;

	@NonNull
	public Department offeringDepartment;

	@NonNull
	public Year year;

	public int semester;

	@NonNull
	public CourseType courseType;

	@Nullable
	public String remarks;

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
