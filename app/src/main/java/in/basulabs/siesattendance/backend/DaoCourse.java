package in.basulabs.siesattendance.backend;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import androidx.room.Update;

import java.util.List;

@Dao
public abstract class DaoCourse {

	@Insert(onConflict = OnConflictStrategy.ABORT)
	public abstract long insert(EntityCourse course);

	@Update
	public abstract void update(EntityCourse course);

	@Delete
	public abstract void delete(EntityCourse course);

	@Query("SELECT COUNT(*) FROM lectures WHERE courseID = :courseID")
	public abstract int countLectures(long courseID);

	@Query("DELETE FROM course_batch_map WHERE courseID = :courseID")
	public abstract void unlinkAllBatches(long courseID);

	/**
	 * Deletes a course once nothing depends on it. Lectures hold a RESTRICT key onto courses,
	 * so those are counted first and reported rather than surfacing as a constraint error.
	 */
	@Transaction
	public void deleteCourseChecked(EntityCourse course) {

		int lectures = countLectures(course.courseID);
		if (lectures > 0) {
			throw new IllegalStateException("This course has " + lectures
					+ " scheduled lecture(s). Delete those first.");
		}

		unlinkAllBatches(course.courseID);
		delete(course);
	}

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

	@Query("SELECT b.* FROM batch_desc b " +
			"INNER JOIN course_batch_map m ON b.batchID = m.batchID " +
			"WHERE m.courseID = :courseID ORDER BY b.batchName")
	public abstract List<EntityBatchDesc> getBatchesForCourseSync(long courseID);
}
