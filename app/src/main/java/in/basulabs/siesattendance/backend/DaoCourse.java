package in.basulabs.siesattendance.backend;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.time.ZonedDateTime;
import java.util.List;

@Dao
public abstract class DaoCourse {

	@Insert(onConflict = OnConflictStrategy.ABORT)
	public abstract long insert(EntityCourse course);

	@Update
	public abstract void update(EntityCourse course);

	@Query("SELECT COUNT(*) FROM lectures WHERE courseID = :courseID")
	public abstract int countLectures(long courseID);

	/*
	 * No delete for courses. A course is what every lecture and attendance record is filed
	 * under, so removing one would rewrite history. A course that is no longer offered is
	 * marked with a discontinuation date instead, which the entity already supports.
	 */

	@Query("UPDATE courses SET dateDiscontinued = :discontinuedOn WHERE courseID = :courseID")
	public abstract void setDiscontinued(long courseID, ZonedDateTime discontinuedOn);

	@Query("SELECT * FROM courses ORDER BY courseCode ASC")
	public abstract LiveData<List<EntityCourse>> getAll();

	@Query("SELECT * FROM courses ORDER BY courseCode ASC")
	public abstract List<EntityCourse> getAllSync();

	@Query("SELECT * FROM courses WHERE courseID = :courseID")
	public abstract LiveData<EntityCourse> getById(long courseID);

	@Query("SELECT * FROM courses WHERE courseID = :courseID")
	public abstract EntityCourse getByIdSync(long courseID);

	@Query("SELECT * FROM courses WHERE dateDiscontinued IS NULL ORDER BY courseCode ASC")
	public abstract List<EntityCourse> getActiveSync();

	@Query("SELECT COUNT(*) FROM courses")
	public abstract LiveData<Integer> countCourses();

	@Query("SELECT * FROM courses WHERE year = :year AND stream = :stream " +
			"AND offeringDepartment = :dept ORDER BY semester, courseCode")
	public abstract List<EntityCourse> getForClassSync(Year year, Stream stream, Department dept);

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	public abstract void linkBatch(EntityCourseBatchMap map);

	@Query("DELETE FROM course_batch_map WHERE courseID = :courseID AND batchID = :batchID")
	public abstract void unlinkBatch(long courseID, long batchID);

	/**
	 * The batches that are actually relevant to a course in a given academic year.
	 * <p>
	 * A batch qualifies if at least one of its members is on the course's roll — that is, if
	 * the batch and the course overlap in students. Deriving it this way means a batch created
	 * from the class filter is immediately usable for that class's courses, without anyone
	 * having to link the two by hand. It also keeps unrelated batches of the same year out of
	 * the list, which an unfiltered "batches for this year" query would not.
	 * <p>
	 * Batches explicitly linked through {@code course_batch_map} are included as well, so a
	 * deliberate link still wins even before any student has been added.
	 */
	@Query("SELECT DISTINCT b.* FROM batch_desc b " +
			"WHERE b.academicYear = :academicYear AND (" +
			"   EXISTS (SELECT 1 FROM course_batch_map cbm " +
			"           WHERE cbm.batchID = b.batchID AND cbm.courseID = :courseID) " +
			"   OR EXISTS (" +
			"      SELECT 1 FROM batch_student_map m " +
			"      INNER JOIN student_year y ON y.studentID = m.studentID " +
			"         AND y.academicYear = :academicYear " +
			"      INNER JOIN courses c ON c.courseID = :courseID " +
			"      WHERE m.batchID = b.batchID " +
			"        AND y.year = c.year AND y.stream = c.stream AND (" +
			"           (c.courseType IN ('MAJOR', 'MAJOR_PRACTICALS') " +
			"              AND y.majorDept = c.offeringDepartment) " +
			"           OR (c.courseType IN ('MINOR', 'MINOR_PRACTICALS') " +
			"              AND y.minorDept = c.offeringDepartment) " +
			"           OR (c.courseType = 'OE' AND y.oeDept = c.offeringDepartment) " +
			"           OR c.courseType IN ('AEC', 'IKS')))) " +
			"ORDER BY b.batchName ASC")
	public abstract List<EntityBatchDesc> getBatchesForCourseSync(long courseID,
	                                                              int academicYear);
}
