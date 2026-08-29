package in.basulabs.siesattendance.backend;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Courses, and the links between courses and batches.
 * <p>
 * Two things are deliberately absent: there is no delete, and the batch lookup does not filter
 * by course. Both are explained in the comments below, since in each case the obvious version
 * was written first and caused a problem.
 */
@Dao
public abstract class DaoCourse {

	/** The raw insert. Callers want {@link #insert}, which issues the ID. */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	protected abstract long insertRow(EntityCourse course);

	/**
	 * Inserts a course, stamping it with an ID first. Nothing in the database generates one:
	 * see {@link IdGenerator}.
	 *
	 * @return the ID the course was written under
	 */
	public long insert(EntityCourse course) {
		course.courseID = IdGenerator.idFor(course.courseID);
		return insertRow(course);
	}

	@Update
	public abstract void update(EntityCourse course);

	@Query("SELECT COUNT(*) FROM lectures WHERE courseID = :courseID")
	public abstract int countLectures(long courseID);

	/*
	 * No delete for courses. A course is what every lecture and attendance record is filed
	 * under, so removing one would rewrite history. A course that is no longer offered is
	 * marked with a discontinuation date instead, which the entity already supports.
	 */

	/**
	 * Marks a course as no longer offered. This is what stands in for deletion: the row and
	 * everything filed under it survive, and only the active listings drop it.
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

	/**
	 * Courses still being offered. This is what the pickers use, so a discontinued course
	 * cannot have new lectures recorded against it while remaining visible in the reports.
	 */
	@Query("SELECT * FROM courses WHERE dateDiscontinued IS NULL ORDER BY courseCode ASC")
	public abstract List<EntityCourse> getActiveSync();

	@Query("SELECT COUNT(*) FROM courses")
	public abstract LiveData<Integer> countCourses();

	@Query("SELECT * FROM courses WHERE year = :year AND stream = :stream " +
			"AND offeringDepartment = :dept ORDER BY semester, courseCode")
	public abstract List<EntityCourse> getForClassSync(Year year, Stream stream, Department dept);

	/**
	 * The course a code names, for an import that arrives keyed by code rather than by ID.
	 * <p>
	 * Compared without regard to case, unlike the unique index on the column, because the code
	 * in an imported file was typed by a person and "phys101" is not a different course from
	 * "PHYS101". The index cannot serve the comparison as a result, which costs nothing over a
	 * table holding one row per paper on the syllabus.
	 */
	@Query("SELECT * FROM courses WHERE courseCode = :courseCode COLLATE NOCASE LIMIT 1")
	@Nullable
	public abstract EntityCourse findByCodeSync(String courseCode);

	/**
	 * The batches this course has been linked to. Used only as a tie-breaker when an import has
	 * to work out which batch a session was held for; see {@code BatchMatcher}. It is not a
	 * restriction, for the reason set out at the foot of this class.
	 */
	@Query("SELECT batchID FROM course_batch_map WHERE courseID = :courseID")
	public abstract List<Long> getLinkedBatchIdsSync(long courseID);

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	public abstract void linkBatch(EntityCourseBatchMap map);

	@Query("DELETE FROM course_batch_map WHERE courseID = :courseID AND batchID = :batchID")
	public abstract void unlinkBatch(long courseID, long batchID);

	/*
	 * The batch dropdown deliberately does not filter by course.
	 *
	 * It used to: a batch appeared only if one of its members was on the selected course's
	 * roll. That coupled the batch list to the roster's department matching, so a course whose
	 * roster came back empty also offered no batches, and the same batch appeared or vanished
	 * depending on the type of the course selected. A batch is just a named group of students
	 * for a session, a practicals division or a tutorial group, and nothing about it belongs to
	 * one course or one department.
	 *
	 * The picker now lists every batch in the session, via DaoBatch.getAllForYearSync. Whoever
	 * is taking attendance knows which batch is in front of them; the app does not need to
	 * guess on their behalf, and guessing wrongly costs them the ability to record anything at
	 * all.
	 */
}
