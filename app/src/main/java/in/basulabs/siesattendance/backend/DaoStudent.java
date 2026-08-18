package in.basulabs.siesattendance.backend;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import androidx.room.Update;

import java.util.List;

import in.basulabs.siesattendance.backend.pojo.ClassKey;
import in.basulabs.siesattendance.backend.pojo.StudentCourseStat;
import in.basulabs.siesattendance.backend.pojo.StudentWithYear;

@Dao
public abstract class DaoStudent {

	@Insert(onConflict = OnConflictStrategy.ABORT)
	public abstract long insertStudent(EntityStudent student);

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	public abstract void insertStudentYear(EntityStudentYear studentYear);

	@Update
	public abstract void updateStudent(EntityStudent student);

	@Query("SELECT studentID FROM students WHERE email = :email LIMIT 1")
	public abstract Long findStudentIdByEmail(String email);

	@Query("SELECT studentID FROM student_year WHERE rollNo = :rollNo AND academicYear = " +
			":academicYear LIMIT 1")
	public abstract Long findStudentIdByRollNo(String rollNo, int academicYear);

	/**
	 * Inserts a student and their year record together. If a student with the same roll number
	 * already exists for that academic year, the existing student ID is reused and only the year
	 * record is refreshed — this makes CSV import idempotent.
	 */
	@Transaction
	public long upsertStudentWithYear(EntityStudent student, int academicYear, String rollNo,
	                                  Year year, Stream stream, Department majorDept,
	                                  Department minorDept, Department oeDept) {

		Long existing = findStudentIdByRollNo(rollNo, academicYear);

		if (existing == null && student.email != null && !student.email.isEmpty()) {
			existing = findStudentIdByEmail(student.email);
		}

		long studentID;

		if (existing != null) {
			studentID = existing;
			student.studentID = studentID;
			updateStudent(student);
		} else {
			studentID = insertStudent(student);
		}

		insertStudentYear(new EntityStudentYear(studentID, academicYear, rollNo, year, stream,
				majorDept, minorDept, oeDept));

		return studentID;
	}

	@Query("SELECT s.studentID, s.name, s.pronouns, s.email, y.rollNo, y.academicYear, " +
			"y.year, y.stream, y.majorDept, y.minorDept, y.oeDept " +
			"FROM students s INNER JOIN student_year y ON s.studentID = y.studentID " +
			"WHERE y.academicYear = :academicYear " +
			"ORDER BY y.rollNo ASC")
	public abstract LiveData<List<StudentWithYear>> getAllForYear(int academicYear);

	/**
	 * The class-wise lookup. Passing null for any of year/stream/department leaves that
	 * dimension unfiltered, so the same query backs both "SY BSc CS" and broader searches.
	 */
	@Query("SELECT s.studentID, s.name, s.pronouns, s.email, y.rollNo, y.academicYear, " +
			"y.year, y.stream, y.majorDept, y.minorDept, y.oeDept " +
			"FROM students s INNER JOIN student_year y ON s.studentID = y.studentID " +
			"WHERE y.academicYear = :academicYear " +
			"AND (:year IS NULL OR y.year = :year) " +
			"AND (:stream IS NULL OR y.stream = :stream) " +
			"AND (:dept IS NULL OR y.majorDept = :dept) " +
			"AND (:query = '' OR s.name LIKE '%' || :query || '%' " +
			"     OR y.rollNo LIKE '%' || :query || '%') " +
			"ORDER BY y.rollNo ASC")
	public abstract LiveData<List<StudentWithYear>> searchByClass(int academicYear, Year year,
	                                                              Stream stream, Department dept,
	                                                              String query);

	/**
	 * Same as {@link #searchByClass} but synchronous, for when a batch needs to be populated
	 * from a class in one shot.
	 */
	@Query("SELECT s.studentID, s.name, s.pronouns, s.email, y.rollNo, y.academicYear, " +
			"y.year, y.stream, y.majorDept, y.minorDept, y.oeDept " +
			"FROM students s INNER JOIN student_year y ON s.studentID = y.studentID " +
			"WHERE y.academicYear = :academicYear " +
			"AND (:year IS NULL OR y.year = :year) " +
			"AND (:stream IS NULL OR y.stream = :stream) " +
			"AND (:dept IS NULL OR y.majorDept = :dept) " +
			"ORDER BY y.rollNo ASC")
	public abstract List<StudentWithYear> getClassSync(int academicYear, Year year,
	                                                   Stream stream, Department dept);

	/**
	 * The students screen's full filter, in one query.
	 * <p>
	 * Every dimension is optional: a null argument leaves that dimension open, so the same
	 * statement serves "everyone in this session" and "SY BSc Physics majors taking Statistics
	 * as a minor who are in Batch A". Batch membership is tested with EXISTS rather than a join,
	 * so a student in a batch cannot be returned twice.
	 * <p>
	 * The search term matches name or roll number and is passed as an empty string, not null,
	 * when unused.
	 */
	@Query("SELECT s.studentID, s.name, s.pronouns, s.email, y.rollNo, y.academicYear, " +
			"y.year, y.stream, y.majorDept, y.minorDept, y.oeDept " +
			"FROM students s INNER JOIN student_year y ON s.studentID = y.studentID " +
			"WHERE y.academicYear = :academicYear " +
			"AND (:year IS NULL OR y.year = :year) " +
			"AND (:stream IS NULL OR y.stream = :stream) " +
			"AND (:majorDept IS NULL OR y.majorDept = :majorDept) " +
			"AND (:minorDept IS NULL OR y.minorDept = :minorDept) " +
			"AND (:oeDept IS NULL OR y.oeDept = :oeDept) " +
			"AND (:batchID IS NULL OR EXISTS (" +
			"   SELECT 1 FROM batch_student_map m WHERE m.studentID = s.studentID " +
			"   AND m.batchID = :batchID AND m.academicYear = :academicYear)) " +
			"AND (:query = '' OR s.name LIKE '%' || :query || '%' " +
			"     OR y.rollNo LIKE '%' || :query || '%') " +
			"ORDER BY y.rollNo ASC")
	public abstract List<StudentWithYear> filterStudentsSync(int academicYear, Year year,
	                                                         Stream stream, Department majorDept,
	                                                         Department minorDept,
	                                                         Department oeDept, Long batchID,
	                                                         String query);

	/**
	 * Every distinct class present in the database for the given academic year, with a head
	 * count. This drives the class picker chips.
	 */
	@Query("SELECT y.year AS year, y.stream AS stream, y.majorDept AS majorDept, " +
			"COUNT(*) AS studentCount " +
			"FROM student_year y WHERE y.academicYear = :academicYear " +
			"GROUP BY y.year, y.stream, y.majorDept " +
			"ORDER BY y.year, y.stream, y.majorDept")
	public abstract LiveData<List<ClassKey>> getClassesForYear(int academicYear);

	@Query("SELECT COUNT(*) FROM student_year WHERE academicYear = :academicYear")
	public abstract LiveData<Integer> countStudents(int academicYear);

	@Query("SELECT DISTINCT academicYear FROM student_year ORDER BY academicYear DESC")
	public abstract LiveData<List<Integer>> getKnownAcademicYears();

	// ── Single student, edit and delete ──────────────────────────────────

	@Query("SELECT s.studentID, s.name, s.pronouns, s.email, y.rollNo, y.academicYear, " +
			"y.year, y.stream, y.majorDept, y.minorDept, y.oeDept " +
			"FROM students s INNER JOIN student_year y ON s.studentID = y.studentID " +
			"WHERE s.studentID = :studentID AND y.academicYear = :academicYear")
	public abstract LiveData<StudentWithYear> getOne(long studentID, int academicYear);

	@Query("SELECT s.studentID, s.name, s.pronouns, s.email, y.rollNo, y.academicYear, " +
			"y.year, y.stream, y.majorDept, y.minorDept, y.oeDept " +
			"FROM students s INNER JOIN student_year y ON s.studentID = y.studentID " +
			"WHERE s.studentID = :studentID AND y.academicYear = :academicYear")
	public abstract StudentWithYear getOneSync(long studentID, int academicYear);

	@Query("UPDATE student_year SET rollNo = :rollNo, year = :year, stream = :stream, " +
			"majorDept = :majorDept, minorDept = :minorDept, oeDept = :oeDept " +
			"WHERE studentID = :studentID AND academicYear = :academicYear")
	public abstract void updateStudentYear(long studentID, int academicYear, String rollNo,
	                                       Year year, Stream stream, Department majorDept,
	                                       Department minorDept, Department oeDept);

	/**
	 * Applies an edit to both halves of a student record at once, so a failure in either leaves
	 * neither changed.
	 */
	@Transaction
	public void updateStudentWithYear(EntityStudent student, int academicYear, String rollNo,
	                                  Year year, Stream stream, Department majorDept,
	                                  Department minorDept, Department oeDept) {
		updateStudent(student);
		updateStudentYear(student.studentID, academicYear, rollNo, year, stream, majorDept,
				minorDept, oeDept);
	}

	/*
	 * There is deliberately no delete for students.
	 *
	 * A student's roll number is the key their attendance history hangs off, and that history
	 * is the point of the app. Removing a student would either orphan or silently erase every
	 * record they appear in, which would change past statistics after the fact. Records are
	 * corrected by editing instead — individually, or by re-importing the roster CSV.
	 */

	// ── The roll for a course ────────────────────────────────────────────

	/**
	 * Every student who takes this course in the given academic year.
	 * <p>
	 * A course states the year, stream and offering department it belongs to. Which of a
	 * student's three departments has to match depends on how they are taking it: a major
	 * course matches their major department, a minor course their minor, an open elective their
	 * OE choice. AEC and IKS courses are taken by the whole year and stream regardless of
	 * department, so no department condition applies to them.
	 * <p>
	 * Enum columns are stored as their constant names, which is why the course type is compared
	 * against string literals here.
	 */
	@Query("SELECT s.studentID, s.name, s.pronouns, s.email, y.rollNo, y.academicYear, " +
			"y.year, y.stream, y.majorDept, y.minorDept, y.oeDept " +
			"FROM courses c " +
			"INNER JOIN student_year y ON y.year = c.year AND y.stream = c.stream " +
			"   AND y.academicYear = :academicYear " +
			"INNER JOIN students s ON s.studentID = y.studentID " +
			"WHERE c.courseID = :courseID AND (" +
			"   (c.courseType IN ('MAJOR', 'MAJOR_PRACTICALS') " +
			"      AND y.majorDept = c.offeringDepartment) " +
			"   OR (c.courseType IN ('MINOR', 'MINOR_PRACTICALS') " +
			"      AND y.minorDept = c.offeringDepartment) " +
			"   OR (c.courseType = 'OE' AND y.oeDept = c.offeringDepartment) " +
			"   OR c.courseType IN ('AEC', 'IKS')) " +
			"ORDER BY y.rollNo ASC")
	public abstract List<StudentWithYear> getRollForCourseSync(long courseID, int academicYear);

	/**
	 * The roll narrowed to one batch of the course, for practicals and other split sessions.
	 */
	@Query("SELECT s.studentID, s.name, s.pronouns, s.email, y.rollNo, y.academicYear, " +
			"y.year, y.stream, y.majorDept, y.minorDept, y.oeDept " +
			"FROM students s " +
			"INNER JOIN batch_student_map m ON m.studentID = s.studentID " +
			"INNER JOIN student_year y ON y.studentID = s.studentID " +
			"   AND y.academicYear = m.academicYear " +
			"WHERE m.batchID = :batchID AND m.academicYear = :academicYear " +
			"ORDER BY y.rollNo ASC")
	public abstract List<StudentWithYear> getRollForBatchSync(long batchID, int academicYear);

	// ── Per-course breakdown for one student ─────────────────────────────

	@Query("SELECT c.courseID, c.courseCode, c.courseTopic, " +
			"COUNT(a.lectureID) AS totalLectures, " +
			"SUM(CASE WHEN a.present = 1 THEN 1 ELSE 0 END) AS attended " +
			"FROM attendance a " +
			"INNER JOIN lectures l ON l.lectureID = a.lectureID " +
			"INNER JOIN courses c ON c.courseID = l.courseID " +
			"WHERE a.studentID = :studentID " +
			"GROUP BY c.courseID, c.courseCode, c.courseTopic " +
			"ORDER BY c.courseCode ASC")
	public abstract LiveData<List<StudentCourseStat>> getCourseBreakdown(long studentID);
}
