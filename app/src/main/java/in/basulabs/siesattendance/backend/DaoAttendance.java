package in.basulabs.siesattendance.backend;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import java.util.List;

import in.basulabs.siesattendance.backend.pojo.CourseAttendanceStat;
import in.basulabs.siesattendance.backend.pojo.StudentAttendanceStat;

@Dao
public abstract class DaoAttendance {

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	public abstract void insertAll(List<EntityAttendance> records);

	@Query("DELETE FROM attendance WHERE lectureID = :lectureID")
	public abstract void clearForLecture(long lectureID);

	/**
	 * Replaces the whole attendance sheet for one lecture. Marking is always all-or-nothing so
	 * a half-saved sheet can never exist.
	 */
	@Transaction
	public void saveSheet(long lectureID, List<EntityAttendance> records) {
		clearForLecture(lectureID);
		insertAll(records);
	}

	@Query("SELECT * FROM attendance WHERE lectureID = :lectureID")
	public abstract List<EntityAttendance> getForLectureSync(long lectureID);

	@Query("SELECT studentID FROM attendance WHERE lectureID = :lectureID AND present = 1")
	public abstract List<Long> getPresentIdsSync(long lectureID);

	// ── Statistics ───────────────────────────────────────────────────────

	/**
	 * Per-student totals across every lecture of every batch they belong to.
	 */
	@Query("SELECT s.studentID, s.name, y.rollNo, " +
			"COUNT(a.lectureID) AS totalLectures, " +
			"SUM(CASE WHEN a.present = 1 THEN 1 ELSE 0 END) AS attended " +
			"FROM students s " +
			"INNER JOIN student_year y ON y.studentID = s.studentID " +
			"   AND y.academicYear = :academicYear " +
			"LEFT JOIN attendance a ON a.studentID = s.studentID " +
			"GROUP BY s.studentID, s.name, y.rollNo " +
			"ORDER BY (CAST(SUM(CASE WHEN a.present = 1 THEN 1 ELSE 0 END) AS REAL) / " +
			"          NULLIF(COUNT(a.lectureID), 0)) ASC")
	public abstract LiveData<List<StudentAttendanceStat>> getStudentStats(int academicYear);

	/**
	 * Per-student totals restricted to one course.
	 */
	@Query("SELECT s.studentID, s.name, y.rollNo, " +
			"COUNT(a.lectureID) AS totalLectures, " +
			"SUM(CASE WHEN a.present = 1 THEN 1 ELSE 0 END) AS attended " +
			"FROM students s " +
			"INNER JOIN student_year y ON y.studentID = s.studentID " +
			"   AND y.academicYear = :academicYear " +
			"INNER JOIN attendance a ON a.studentID = s.studentID " +
			"INNER JOIN lectures l ON l.lectureID = a.lectureID AND l.courseID = :courseID " +
			"GROUP BY s.studentID, s.name, y.rollNo " +
			"ORDER BY y.rollNo ASC")
	public abstract LiveData<List<StudentAttendanceStat>> getStudentStatsForCourse(
			long courseID, int academicYear);

	/**
	 * Per-course aggregates.
	 */
	@Query("SELECT c.courseID, c.courseCode, c.courseTopic, " +
			"COUNT(a.studentID) AS totalRecords, " +
			"SUM(CASE WHEN a.present = 1 THEN 1 ELSE 0 END) AS presentRecords, " +
			"COUNT(DISTINCT l.lectureID) AS lectureCount " +
			"FROM courses c " +
			"LEFT JOIN lectures l ON l.courseID = c.courseID " +
			"LEFT JOIN attendance a ON a.lectureID = l.lectureID " +
			"GROUP BY c.courseID, c.courseCode, c.courseTopic " +
			"ORDER BY c.courseCode ASC")
	public abstract LiveData<List<CourseAttendanceStat>> getCourseStats();

	/**
	 * Overall attendance percentage across the whole database, as a 0-100 value.
	 */
	@Query("SELECT CASE WHEN COUNT(*) = 0 THEN 0 ELSE " +
			"(SUM(CASE WHEN present = 1 THEN 1 ELSE 0 END) * 100.0) / COUNT(*) END " +
			"FROM attendance")
	public abstract LiveData<Double> getOverallPercentage();

	@Query("SELECT COUNT(DISTINCT lectureID) FROM attendance")
	public abstract LiveData<Integer> countMarkedLectures();
}
