package in.basulabs.siesattendance.backend;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import java.util.List;

import in.basulabs.siesattendance.backend.pojo.StudentWithYear;

@Dao
public abstract class DaoBatch {

	@Insert(onConflict = OnConflictStrategy.ABORT)
	public abstract long insertBatch(EntityBatchDesc batch);

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	public abstract void addStudent(EntityBatchStudentMap map);

	@Query("DELETE FROM batch_student_map WHERE batchID = :batchID AND studentID = :studentID " +
			"AND academicYear = :academicYear")
	public abstract void removeStudent(long batchID, long studentID, int academicYear);

	@Query("SELECT * FROM batch_desc WHERE academicYear = :academicYear ORDER BY batchName")
	public abstract LiveData<List<EntityBatchDesc>> getAllForYear(int academicYear);

	@Query("SELECT * FROM batch_desc ORDER BY academicYear DESC, batchName")
	public abstract List<EntityBatchDesc> getAllSync();

	@Query("SELECT * FROM batch_desc WHERE batchID = :batchID")
	public abstract EntityBatchDesc getByIdSync(long batchID);

	@Query("SELECT COUNT(*) FROM batch_student_map WHERE batchID = :batchID")
	public abstract int countStudentsSync(long batchID);

	@Query("SELECT s.studentID, s.name, s.pronouns, s.email, y.rollNo, y.academicYear, " +
			"y.year, y.stream, y.majorDept, y.minorDept, y.oeDept " +
			"FROM students s " +
			"INNER JOIN batch_student_map m ON s.studentID = m.studentID " +
			"INNER JOIN student_year y ON y.studentID = s.studentID " +
			"   AND y.academicYear = m.academicYear " +
			"WHERE m.batchID = :batchID ORDER BY y.rollNo ASC")
	public abstract LiveData<List<StudentWithYear>> getStudentsInBatch(long batchID);

	@Query("SELECT s.studentID, s.name, s.pronouns, s.email, y.rollNo, y.academicYear, " +
			"y.year, y.stream, y.majorDept, y.minorDept, y.oeDept " +
			"FROM students s " +
			"INNER JOIN batch_student_map m ON s.studentID = m.studentID " +
			"INNER JOIN student_year y ON y.studentID = s.studentID " +
			"   AND y.academicYear = m.academicYear " +
			"WHERE m.batchID = :batchID ORDER BY y.rollNo ASC")
	public abstract List<StudentWithYear> getStudentsInBatchSync(long batchID);

	/**
	 * Creates a batch and fills it with every student of a given class in one transaction.
	 * This is the "SY BSc CS becomes a batch" shortcut.
	 */
	@Query("SELECT COUNT(*) FROM lectures WHERE batchID = :batchID")
	public abstract int countLectures(long batchID);

	@Query("DELETE FROM batch_student_map WHERE batchID = :batchID")
	public abstract void clearMembers(long batchID);

	@Query("DELETE FROM course_batch_map WHERE batchID = :batchID")
	public abstract void clearCourseLinks(long batchID);

	@Query("DELETE FROM batch_desc WHERE batchID = :batchID")
	public abstract void deleteBatchRow(long batchID);

	@Query("UPDATE batch_desc SET batchName = :name, batchDesc = :description " +
			"WHERE batchID = :batchID")
	public abstract void renameBatch(long batchID, String name, String description);

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

	@Transaction
	public long createBatchFromStudents(EntityBatchDesc batch, List<Long> studentIDs) {
		long batchID = insertBatch(batch);
		for (long studentID : studentIDs) {
			addStudent(new EntityBatchStudentMap(batchID, studentID, batch.academicYear));
		}
		return batchID;
	}
}
