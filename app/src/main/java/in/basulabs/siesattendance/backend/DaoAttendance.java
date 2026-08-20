package in.basulabs.siesattendance.backend;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import java.util.List;

import in.basulabs.siesattendance.backend.pojo.CourseAttendanceStat;
import in.basulabs.siesattendance.backend.pojo.ExportRow;
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

	@Query("SELECT studentID FROM attendance WHERE lectureID = :lectureID")
	public abstract List<Long> getMarkedIdsSync(long lectureID);

	/**
	 * Copies every mark from one lecture onto another.
	 * <p>
	 * Done in SQL rather than by reading rows into memory and writing them back, so the copy
	 * is a single statement inside the caller's transaction and cannot half-happen.
	 */
	@Query("INSERT INTO attendance (lectureID, studentID, present) " +
			"SELECT :toLectureID, studentID, present FROM attendance " +
			"WHERE lectureID = :fromLectureID")
	public abstract void copyMarks(long fromLectureID, long toLectureID);

	/**
	 * Drops marks for students who are not in {@code keep}.
	 * <p>
	 * Used when a lecture is moved to a different course: anyone recorded against it who is not
	 * on the new course's roll would otherwise keep a mark for a course they do not take, which
	 * counts towards their percentage while being invisible on every sheet.
	 */
	@Query("DELETE FROM attendance WHERE lectureID = :lectureID AND studentID NOT IN (:keep)")
	public abstract void deleteForLectureExcept(long lectureID, List<Long> keep);

	// ── Export ───────────────────────────────────────────────────────────

	/**
	 * Every student paired with every course they are on the roll for, with lectures held and
	 * lectures attended.
	 * <p>
	 * Two things make this longer than the statistics queries above.
	 * <p>
	 * First, the denominator counts lectures <em>held</em> rather than attendance rows. A
	 * lecture counts for a student when it was a whole-class lecture, or a lecture for a batch
	 * they belong to. Counting their attendance rows instead would shrink the denominator for
	 * any student who was missed off a sheet, flattering precisely the record that is under
	 * scrutiny.
	 * <p>
	 * Second, the course list per student uses the same roll test as
	 * {@code DaoStudent.getRollForCourseSync}, so the sheet shows a student against the courses
	 * they actually take and leaves the rest blank rather than printing zeroes for courses
	 * that were never theirs.
	 * <p>
	 * Lectures are confined to the session by the academic year's date range, since a lecture
	 * row carries a timestamp rather than a session.
	 */
	@Query("SELECT s.studentID AS studentID, y.rollNo AS rollNo, s.name AS studentName, " +
			"c.courseID AS courseID, c.courseCode AS courseCode, " +
			"c.courseTopic AS courseTopic, " +
			"(SELECT COUNT(DISTINCT l.lectureID) FROM lectures l " +
			"   WHERE l.courseID = c.courseID " +
			"     AND (l.batchID IS NULL OR l.batchID IN " +
			"          (SELECT m.batchID FROM batch_student_map m " +
			"           WHERE m.studentID = s.studentID " +
			"             AND m.academicYear = :academicYear)) " +
			"     AND CAST(SUBSTR(l.startDateTime, 1, " +
			"              INSTR(l.startDateTime, '|') - 1) AS INTEGER) " +
			"         BETWEEN ay.startEpoch AND ay.endEpoch) AS held, " +
			"(SELECT COUNT(*) FROM attendance a " +
			"   INNER JOIN lectures l2 ON l2.lectureID = a.lectureID " +
			"   WHERE a.studentID = s.studentID AND a.present = 1 " +
			"     AND l2.courseID = c.courseID " +
			"     AND (l2.batchID IS NULL OR l2.batchID IN " +
			"          (SELECT m2.batchID FROM batch_student_map m2 " +
			"           WHERE m2.studentID = s.studentID " +
			"             AND m2.academicYear = :academicYear)) " +
			"     AND CAST(SUBSTR(l2.startDateTime, 1, " +
			"              INSTR(l2.startDateTime, '|') - 1) AS INTEGER) " +
			"         BETWEEN ay.startEpoch AND ay.endEpoch) AS attended " +
			"FROM students s " +
			"INNER JOIN student_year y ON y.studentID = s.studentID " +
			"   AND y.academicYear = :academicYear " +
			"INNER JOIN academic_years ay ON ay.academicYear = :academicYear " +
			"INNER JOIN courses c ON c.year = y.year AND c.stream = y.stream AND (" +
			"   (c.courseType IN ('MAJOR', 'MAJOR_PRACTICALS') " +
			"      AND y.majorDept = c.offeringDepartment) " +
			"   OR (c.courseType IN ('MINOR', 'MINOR_PRACTICALS') " +
			"      AND (y.minorDept = c.offeringDepartment " +
			"           OR y.majorDept = c.offeringDepartment)) " +
			"   OR (c.courseType = 'OE' AND y.oeDept = c.offeringDepartment) " +
			"   OR c.courseType IN ('AEC', 'IKS', 'VSC', 'VSC_PRACTICALS', " +
			"                       'SEC', 'SEC_PRACTICALS')) " +
			"ORDER BY y.rollNo ASC, c.courseCode ASC")
	public abstract List<ExportRow> getExportRowsSync(int academicYear);

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
