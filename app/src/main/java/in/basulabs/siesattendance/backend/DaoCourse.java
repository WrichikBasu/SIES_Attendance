package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import androidx.room.Update;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Courses, the links between courses and batches, and the links between courses and the
 * professors who teach them.
 * <p>
 * Two things are deliberately absent: there is no delete, and the batch lookup does not filter
 * by course. Both are explained in the comments below, since in each case the obvious version
 * was written first and caused a problem.
 * <p>
 * One invariant is worth stating up front, because every instructor query depends on it: a
 * course's coordinator — {@link EntityCourse#profID} — always has a row in
 * {@code course_instructor}. {@link #insert}, {@link #update} and {@link #setInstructors} each
 * maintain it, so no caller has to remember, and a read of the link table alone is the whole
 * answer to who teaches a course.
 */
@Dao
public abstract class DaoCourse {

	/** The raw insert. Callers want {@link #insert}, which issues the ID. */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	protected abstract long insertRow(EntityCourse course);

	/**
	 * Inserts a course, stamping it with an ID first. Nothing in the database generates one:
	 * see {@link IdGenerator}.
	 * <p>
	 * The coordinator is written into {@code course_instructor} in the same transaction. A
	 * course created and never touched again therefore has exactly one instructor, which is the
	 * behaviour there was before the table existed.
	 *
	 * @return the ID the course was written under
	 */
	@Transaction
	public long insert(EntityCourse course) {
		course.courseID = IdGenerator.idFor(course.courseID);
		long id = insertRow(course);
		addInstructor(new EntityCourseInstructor(course.courseID, course.profID));
		return id;
	}

	/** The raw update. Callers want {@link #update}, which keeps the coordinator link honest. */
	@Update
	protected abstract void updateRow(EntityCourse course);

	/**
	 * Updates a course, making sure the coordinator is still among its instructors.
	 * <p>
	 * A previous coordinator who has been replaced is left in place rather than removed: by the
	 * time this runs there is no way to tell whether they were on the course only by virtue of
	 * coordinating it, or whether someone added them as an instructor in their own right.
	 * Dropping them would silently discard the second case. Removing an instructor is what
	 * {@link #setInstructors} is for, where the caller states the whole list and means it.
	 */
	@Transaction
	public void update(EntityCourse course) {
		updateRow(course);
		addInstructor(new EntityCourseInstructor(course.courseID, course.profID));
	}

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

	@Query("DELETE FROM course_batch_map WHERE courseID = :courseID "
			+ "AND batchID NOT IN (:batchIDs)")
	abstract void unlinkBatchesExcept(long courseID, List<Long> batchIDs);

	/**
	 * Makes these the batches this course is for. Diffed, as {@code DaoBatch.setCourseLinks} is
	 * from the other side and for the same reason: a link that stays sends nothing.
	 */
	@Transaction
	public void setBatchLinks(long courseID, List<Long> batchIDs) {

		unlinkBatchesExcept(courseID, batchIDs);

		for (long batchID : batchIDs) {
			linkBatch(new EntityCourseBatchMap(courseID, batchID));
		}
	}

	/*
	 * Instructors. A course is taught by one or more professors; courses.profID names only the
	 * coordinator among them. See EntityCourseInstructor for why the column was kept rather
	 * than replaced.
	 */

	/** Who teaches this course, coordinator included, in the order the pickers display. */
	@Query("SELECT p.* FROM professors p "
			+ "JOIN course_instructor ci ON ci.profID = p.profID "
			+ "WHERE ci.courseID = :courseID ORDER BY p.name ASC")
	public abstract List<EntityProfessor> getInstructorsSync(long courseID);

	/** The same, as bare IDs, for the form that only needs to tick boxes. */
	@Query("SELECT profID FROM course_instructor WHERE courseID = :courseID")
	public abstract List<Long> getInstructorIdsSync(long courseID);

	/**
	 * The courses a professor teaches — the link read backwards.
	 * <p>
	 * This is the query that {@code courses.profID} could not answer correctly, and the reason
	 * the table exists: a co-instructor on a team-taught course was invisible to it.
	 */
	@Query("SELECT c.* FROM courses c "
			+ "JOIN course_instructor ci ON ci.courseID = c.courseID "
			+ "WHERE ci.profID = :profID ORDER BY c.courseCode ASC")
	public abstract List<EntityCourse> getCoursesTaughtBySync(long profID);

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	public abstract void addInstructor(EntityCourseInstructor link);

	@Query("DELETE FROM course_instructor "
			+ "WHERE courseID = :courseID AND profID = :profID")
	public abstract void removeInstructor(long courseID, long profID);

	@Query("DELETE FROM course_instructor WHERE courseID = :courseID")
	protected abstract void clearInstructors(long courseID);

	@Query("SELECT profID FROM courses WHERE courseID = :courseID")
	protected abstract Long coordinatorOf(long courseID);

	/**
	 * Replaces a course's instructors with exactly {@code profIDs}, plus the coordinator.
	 * <p>
	 * The coordinator is folded in rather than trusted to be in the list, because they are
	 * chosen in a different field of the form from the rest and an edit that changes only the
	 * coordinator would otherwise leave them off their own course. Folding them in here means
	 * the invariant holds no matter what the caller passes — including an empty list, which
	 * leaves the course with the single instructor it had before this table existed.
	 * <p>
	 * Cleared and rewritten rather than diffed. The row count is the number of people teaching
	 * one course, the pair is the whole row, and a diff would only be a slower way to reach the
	 * same state.
	 */
	@Transaction
	public void setInstructors(long courseID, @NonNull List<Long> profIDs) {

		Long boxed = coordinatorOf(courseID);
		if (boxed == null) {
			// No such course. Writing links for it would violate the foreign key anyway.
			return;
		}

		// Unboxed deliberately: the comparison below is against a Long from the caller's list,
		// and two boxed Longs outside the cache range compare by identity, not by value.
		long coordinator = boxed;

		clearInstructors(courseID);
		addInstructor(new EntityCourseInstructor(courseID, coordinator));

		for (Long profID : profIDs) {
			if (profID != null && profID != coordinator) {
				addInstructor(new EntityCourseInstructor(courseID, profID));
			}
		}
	}

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
