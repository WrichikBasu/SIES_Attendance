package in.basulabs.siesattendance.backend;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import java.util.List;

import in.basulabs.siesattendance.backend.pojo.StudentWithYear;

/**
 * Batches, their membership, and the links from batches to courses.
 * <p>
 * Membership is always rewritten wholesale rather than diffed. The join table holds nothing but
 * its key columns, so clearing and reinserting loses no state and saves having to work out which
 * students were added and which removed.
 */
@Dao
public abstract class DaoBatch {

	/** The raw insert. Callers want {@link #insertBatch}, which issues the ID. */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	protected abstract long insertBatchRow(EntityBatchDesc batch);

	/**
	 * Inserts a batch, stamping it with an ID first. Nothing in the database generates one:
	 * see {@link IdGenerator}.
	 *
	 * @return the ID the batch was written under
	 */
	public long insertBatch(EntityBatchDesc batch) {
		batch.batchID = IdGenerator.idFor(batch.batchID);
		return insertBatchRow(batch);
	}

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	public abstract void addStudent(EntityBatchStudentMap map);

	@Query("DELETE FROM batch_student_map WHERE batchID = :batchID AND studentID = :studentID " +
			"AND academicYear = :academicYear")
	public abstract void removeStudent(long batchID, long studentID, int academicYear);

	@Query("SELECT * FROM batch_desc WHERE academicYear = :academicYear ORDER BY batchName")
	public abstract LiveData<List<EntityBatchDesc>> getAllForYear(int academicYear);

	@Query("SELECT * FROM batch_desc ORDER BY academicYear DESC, batchName")
	public abstract List<EntityBatchDesc> getAllSync();

	/**
	 * One session's batches that are linked to any of these courses, as {@link #getAllForYear}
	 * orders them. What a class representative's batch tab lists: see
	 * {@code Permissions.visibleBatches}.
	 */
	@Query("SELECT * FROM batch_desc WHERE academicYear = :academicYear AND batchID IN "
			+ "(SELECT batchID FROM course_batch_map WHERE courseID IN (:courseIDs)) "
			+ "ORDER BY batchName")
	public abstract LiveData<List<EntityBatchDesc>> getForYearLinkedTo(int academicYear,
	                                                                     List<Long> courseIDs);

	/** The batches linked to any of these courses, by ID. */
	@Query("SELECT DISTINCT batchID FROM course_batch_map WHERE courseID IN (:courseIDs)")
	public abstract List<Long> getIdsLinkedToSync(List<Long> courseIDs);

	/**
	 * Every batch in one session. This backs the batch picker when attendance is being taken,
	 * which lists them all rather than only those linked to the selected course; the reasoning
	 * is at the foot of {@code DaoCourse}.
	 */
	@Query("SELECT * FROM batch_desc WHERE academicYear = :academicYear ORDER BY batchName")
	public abstract List<EntityBatchDesc> getAllForYearSync(int academicYear);

	@Query("SELECT * FROM batch_desc WHERE batchID = :batchID")
	public abstract EntityBatchDesc getByIdSync(long batchID);

	@Query("SELECT COUNT(*) FROM batch_student_map WHERE batchID = :batchID")
	public abstract int countStudentsSync(long batchID);

	/**
	 * A batch's members, as students.
	 * <p>
	 * The projection omits {@code semester}, so the field arrives null on every row regardless of
	 * what is stored. Room warns about this at build time. Nothing that displays a batch's
	 * membership shows the semester, so it is left out rather than selected and ignored; anything
	 * that comes to need it should add the column here rather than work around the null.
	 * <p>
	 * {@code division} is selected for exactly that reason: it is part of the class label these
	 * rows are shown with, so leaving it out would print a student's class without the division
	 * on the one screen that omitted the column.
	 */
	@Query("SELECT s.studentID, s.name, s.pronouns, s.email, y.rollNo, y.academicYear, " +
			"y.year, y.stream, y.majorDept, y.minorDept, y.oeDept, y.division, " +
			"s.apaarId, y.joinDate, y.leaveDate " +
			"FROM students s " +
			"INNER JOIN batch_student_map m ON s.studentID = m.studentID " +
			"INNER JOIN student_year y ON y.studentID = s.studentID " +
			"   AND y.academicYear = m.academicYear " +
			"WHERE m.batchID = :batchID AND y.leaveDate IS NULL ORDER BY y.rollNo ASC")
	public abstract LiveData<List<StudentWithYear>> getStudentsInBatch(long batchID);

	/** Blocking form of {@link #getStudentsInBatch}, with the same missing semester column. */
	@Query("SELECT s.studentID, s.name, s.pronouns, s.email, y.rollNo, y.academicYear, " +
			"y.year, y.stream, y.majorDept, y.minorDept, y.oeDept, y.division, " +
			"s.apaarId, y.joinDate, y.leaveDate " +
			"FROM students s " +
			"INNER JOIN batch_student_map m ON s.studentID = m.studentID " +
			"INNER JOIN student_year y ON y.studentID = s.studentID " +
			"   AND y.academicYear = m.academicYear " +
			"WHERE m.batchID = :batchID AND y.leaveDate IS NULL ORDER BY y.rollNo ASC")
	public abstract List<StudentWithYear> getStudentsInBatchSync(long batchID);

	/**
	 * Lectures recorded against this batch. Lectures hold a RESTRICT key onto batches, so this
	 * has to be zero before a batch can be removed.
	 */
	@Query("SELECT COUNT(*) FROM lectures WHERE batchID = :batchID")
	public abstract int countLectures(long batchID);

	@Query("DELETE FROM batch_student_map WHERE batchID = :batchID")
	public abstract void clearMembers(long batchID);

	@Query("DELETE FROM course_batch_map WHERE batchID = :batchID")
	public abstract void clearCourseLinks(long batchID);

	@Query("DELETE FROM batch_desc WHERE batchID = :batchID")
	public abstract void deleteBatchRow(long batchID);

	@Query("UPDATE batch_desc SET batchName = :name, batchDesc = :description, " +
			"department = :department WHERE batchID = :batchID")
	public abstract void renameBatch(long batchID, String name, String description,
	                                 Department department);

	/**
	 * The IDs of a batch's members, for pre-ticking the student list when a batch is opened for
	 * editing.
	 */
	@Query("SELECT studentID FROM batch_student_map WHERE batchID = :batchID " +
			"AND academicYear = :academicYear")
	public abstract List<Long> getMemberIdsSync(long batchID, int academicYear);

	/**
	 * Rewrites a batch's details and its membership in one go.
	 * <p>
	 * Membership is replaced wholesale rather than diffed: the join table holds nothing but the
	 * three key columns, so there is no per-row state to preserve, and clearing then reinserting
	 * avoids having to work out which students were added and which removed.
	 */
	@Transaction
	public void updateBatch(long batchID, String name, String description,
	                        Department department, int academicYear, List<Long> studentIDs) {

		renameBatch(batchID, name, description, department);
		clearMembers(batchID);

		for (long studentID : studentIDs) {
			addStudent(new EntityBatchStudentMap(batchID, studentID, academicYear));
		}
	}

	/** The courses this batch is linked to, for pre-ticking them when it is edited. */
	@Query("SELECT courseID FROM course_batch_map WHERE batchID = :batchID")
	public abstract List<Long> getLinkedCourseIdsSync(long batchID);

	@Query("DELETE FROM course_batch_map WHERE batchID = :batchID "
			+ "AND courseID NOT IN (:courseIDs)")
	abstract void unlinkCoursesExcept(long batchID, List<Long> courseIDs);

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	abstract void linkCourses(List<EntityCourseBatchMap> links);

	/**
	 * Makes these the courses this batch is for.
	 * <p>
	 * Diffed, unlike the membership: a link that stays is left alone, so saving a batch whose
	 * courses did not change sends nothing about them. Every write here is a row the outbox
	 * carries to the server, and clearing and reinserting would send each link twice.
	 */
	@Transaction
	public void setCourseLinks(long batchID, List<Long> courseIDs) {

		unlinkCoursesExcept(batchID, courseIDs);

		List<EntityCourseBatchMap> links = new java.util.ArrayList<>();

		for (long courseID : courseIDs) {
			links.add(new EntityCourseBatchMap(courseID, batchID));
		}
		linkCourses(links);
	}

	/**
	 * Removes a batch along with its membership and course links. Lectures hold a RESTRICT key
	 * onto batches, so a batch that still has lectures is refused with a count.
	 */
	@Transaction
	public void deleteBatchChecked(long batchID) {

		int lectures = countLectures(batchID);
		if (lectures > 0) {
			throw new IllegalStateException("This batch has " + lectures
					+ " lecture(s) scheduled. Delete those first.");
		}

		clearMembers(batchID);
		clearCourseLinks(batchID);
		deleteBatchRow(batchID);
	}

	/**
	 * Creates a batch and fills it with the given students in one transaction. This is the
	 * "SY BSc CS becomes a batch" shortcut, with the caller having resolved the class to a list
	 * of IDs first.
	 *
	 * @return the ID of the new batch
	 */
	@Transaction
	public long createBatchFromStudents(EntityBatchDesc batch, List<Long> studentIDs) {
		long batchID = insertBatch(batch);
		for (long studentID : studentIDs) {
			addStudent(new EntityBatchStudentMap(batchID, studentID, batch.academicYear));
		}
		return batchID;
	}
}
