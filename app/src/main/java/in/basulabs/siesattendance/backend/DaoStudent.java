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
import in.basulabs.siesattendance.backend.pojo.DeletionImpact;
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
	                                  Department minorDept, Department oeDept,
	                                  Integer semester) {

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
				majorDept, minorDept, oeDept, semester));

		return studentID;
	}

	@Query("SELECT s.studentID, s.name, s.pronouns, s.email, y.rollNo, y.academicYear, " +
			"y.year, y.stream, y.majorDept, y.minorDept, y.oeDept, y.semester " +
			"FROM students s INNER JOIN student_year y ON s.studentID = y.studentID " +
			"WHERE y.academicYear = :academicYear " +
			"ORDER BY y.rollNo ASC")
	public abstract LiveData<List<StudentWithYear>> getAllForYear(int academicYear);

	/**
	 * The class-wise lookup. Passing null for any of year/stream/department leaves that
	 * dimension unfiltered, so the same query backs both "SY BSc CS" and broader searches.
	 */
	@Query("SELECT s.studentID, s.name, s.pronouns, s.email, y.rollNo, y.academicYear, " +
			"y.year, y.stream, y.majorDept, y.minorDept, y.oeDept, y.semester " +
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
			"y.year, y.stream, y.majorDept, y.minorDept, y.oeDept, y.semester " +
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
			"y.year, y.stream, y.majorDept, y.minorDept, y.oeDept, y.semester " +
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
			"y.year, y.stream, y.majorDept, y.minorDept, y.oeDept, y.semester " +
			"FROM students s INNER JOIN student_year y ON s.studentID = y.studentID " +
			"WHERE s.studentID = :studentID AND y.academicYear = :academicYear")
	public abstract LiveData<StudentWithYear> getOne(long studentID, int academicYear);

	@Query("SELECT s.studentID, s.name, s.pronouns, s.email, y.rollNo, y.academicYear, " +
			"y.year, y.stream, y.majorDept, y.minorDept, y.oeDept, y.semester " +
			"FROM students s INNER JOIN student_year y ON s.studentID = y.studentID " +
			"WHERE s.studentID = :studentID AND y.academicYear = :academicYear")
	public abstract StudentWithYear getOneSync(long studentID, int academicYear);

	@Query("UPDATE student_year SET rollNo = :rollNo, year = :year, stream = :stream, " +
			"majorDept = :majorDept, minorDept = :minorDept, oeDept = :oeDept, " +
			"semester = :semester " +
			"WHERE studentID = :studentID AND academicYear = :academicYear")
	public abstract void updateStudentYear(long studentID, int academicYear, String rollNo,
	                                       Year year, Stream stream, Department majorDept,
	                                       Department minorDept, Department oeDept,
	                                       Integer semester);

	/**
	 * Applies an edit to both halves of a student record at once, so a failure in either leaves
	 * neither changed.
	 */
	@Transaction
	public void updateStudentWithYear(EntityStudent student, int academicYear, String rollNo,
	                                  Year year, Stream stream, Department majorDept,
	                                  Department minorDept, Department oeDept,
	                                  Integer semester) {
		updateStudent(student);
		updateStudentYear(student.studentID, academicYear, rollNo, year, stream, majorDept,
				minorDept, oeDept, semester);
	}

	/*
	 * Deleting a student.
	 *
	 * A student's records are the point of the app, so this is the one genuinely destructive
	 * thing it can do and the two ways of doing it are kept apart deliberately.
	 *
	 * Withdrawing from a session removes the enrolment and any batch memberships for that
	 * year, and keeps the attendance. That is what actually happened: the student sat in those
	 * classes, then left. Past percentages for the classes they were in stay correct, and the
	 * student survives in their other sessions.
	 *
	 * Deleting outright removes the attendance too, because the foreign keys are RESTRICT and,
	 * more to the point, an attendance row naming nobody is not a record of anything. This is
	 * for entries that should never have existed — a duplicate, a typo, a test row — not for
	 * students who have left.
	 *
	 * Both run in a transaction and delete children before parents, since the constraints
	 * refuse it in any other order.
	 */

	/**
	 * Everything that would be lost, for the confirmation dialog to quote back.
	 *
	 * @param academicYear the session being looked at, for the per-session count
	 */
	@Query("SELECT " +
			"(SELECT COUNT(*) FROM attendance WHERE studentID = :studentID) " +
			"   AS attendanceRecords, " +
			"(SELECT COUNT(*) FROM student_year WHERE studentID = :studentID) " +
			"   AS enrolments, " +
			"(SELECT COUNT(*) FROM batch_student_map WHERE studentID = :studentID) " +
			"   AS batchMemberships, " +
			"(SELECT COUNT(*) FROM attendance a " +
			"   INNER JOIN lectures l ON l.lectureID = a.lectureID " +
			"   INNER JOIN academic_years y ON y.academicYear = :academicYear " +
			"   WHERE a.studentID = :studentID " +
			"   AND CAST(substr(l.startDateTime, 1, instr(l.startDateTime, '|') - 1) AS INTEGER) " +
			"       BETWEEN y.startEpoch AND y.endEpoch) AS attendanceThisSession")
	public abstract DeletionImpact deletionImpact(long studentID, int academicYear);

	@Query("DELETE FROM batch_student_map WHERE studentID = :studentID " +
			"AND academicYear = :academicYear")
	public abstract void deleteBatchMembershipsForYear(long studentID, int academicYear);

	@Query("DELETE FROM student_year WHERE studentID = :studentID " +
			"AND academicYear = :academicYear")
	public abstract void deleteEnrolment(long studentID, int academicYear);

	@Query("DELETE FROM batch_student_map WHERE studentID = :studentID")
	public abstract void deleteAllBatchMemberships(long studentID);

	@Query("DELETE FROM student_year WHERE studentID = :studentID")
	public abstract void deleteAllEnrolments(long studentID);

	@Query("DELETE FROM attendance WHERE studentID = :studentID")
	public abstract void deleteAllAttendance(long studentID);

	@Query("DELETE FROM students WHERE studentID = :studentID")
	public abstract void deleteStudentRow(long studentID);

	/**
	 * Takes the student off one session's roster, keeping the student and their attendance.
	 * <p>
	 * Refuses when it is their only enrolment: the student row would survive with nothing
	 * pointing at it, which shows up nowhere in the app and cannot be edited back into
	 * existence. That case wants an outright delete, and the caller is told so rather than
	 * left with an invisible record.
	 */
	@Transaction
	public void withdrawFromSession(long studentID, int academicYear) {

		if (countEnrolments(studentID) <= 1) {
			throw new IllegalStateException(
					"This is the student's only session. Delete the student instead.");
		}

		deleteBatchMembershipsForYear(studentID, academicYear);
		deleteEnrolment(studentID, academicYear);
	}

	@Query("SELECT COUNT(*) FROM student_year WHERE studentID = :studentID")
	public abstract int countEnrolments(long studentID);

	/**
	 * Removes the student and everything that names them.
	 * <p>
	 * Children first: attendance and batch memberships hold RESTRICT keys onto students, so
	 * the student row cannot go until nothing refers to it.
	 */
	@Transaction
	public void deleteStudentCascade(long studentID) {
		deleteAllAttendance(studentID);
		deleteAllBatchMemberships(studentID);
		deleteAllEnrolments(studentID);
		deleteStudentRow(studentID);
	}

	// ── The roll for a course ────────────────────────────────────────────

	/**
	 * Every student who takes this course in the given academic year.
	 * <p>
	 * A course states the year, stream and offering department it belongs to. Which of a
	 * student's three departments has to match depends on how they are taking it: a major
	 * course matches their major department, an open elective their OE choice. AEC, IKS, VSC
	 * and SEC courses, and the practicals belonging to the latter two, are taken by the whole
	 * year and stream regardless of department, so no department condition applies to them.
	 * Note that this is the opposite of how major and minor practicals behave, which follow
	 * the same department as the course they belong to.
	 * <p>
	 * A course type missing from every branch below matches nobody, which reads in the app as
	 * a course with no students and no batches. Any type added to {@link CourseType} has to be
	 * accounted for here.
	 * <p>
	 * A minor course matches either the student's minor department or their major. Matching
	 * the minor alone left minor courses with an empty roll, because the minor field on the
	 * student form is optional and is frequently blank — and an empty roll means attendance
	 * cannot be taken at all, which is worse than a roll that is slightly too wide. The
	 * department still has to match, so this never reaches across departments.
	 * <p>
	 * Enum columns are stored as their constant names, which is why the course type is compared
	 * against string literals here.
	 */
	@Query("SELECT s.studentID, s.name, s.pronouns, s.email, y.rollNo, y.academicYear, " +
			"y.year, y.stream, y.majorDept, y.minorDept, y.oeDept, y.semester " +
			"FROM courses c " +
			"INNER JOIN student_year y ON y.year = c.year AND y.stream = c.stream " +
			"   AND y.academicYear = :academicYear " +
			"INNER JOIN students s ON s.studentID = y.studentID " +
			"WHERE c.courseID = :courseID AND (" +
			"   (c.courseType IN ('MAJOR', 'MAJOR_PRACTICALS') " +
			"      AND y.majorDept = c.offeringDepartment) " +
			"   OR (c.courseType IN ('MINOR', 'MINOR_PRACTICALS') " +
			"      AND (y.minorDept = c.offeringDepartment " +
			"           OR y.majorDept = c.offeringDepartment)) " +
			"   OR (c.courseType = 'OE' AND y.oeDept = c.offeringDepartment) " +
			"   OR c.courseType IN ('AEC', 'IKS', 'VSC', 'VSC_PRACTICALS', " +
			"                        'SEC', 'SEC_PRACTICALS')) " +
			"ORDER BY y.rollNo ASC")
	public abstract List<StudentWithYear> getRollForCourseSync(long courseID, int academicYear);

	/**
	 * The roll narrowed to one batch of the course, for practicals and other split sessions.
	 */
	@Query("SELECT s.studentID, s.name, s.pronouns, s.email, y.rollNo, y.academicYear, " +
			"y.year, y.stream, y.majorDept, y.minorDept, y.oeDept, y.semester " +
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
