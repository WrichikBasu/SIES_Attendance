package in.basulabs.siesattendance.backend;

import androidx.lifecycle.LiveData;
import androidx.annotation.NonNull;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.RawQuery;
import androidx.sqlite.db.SupportSQLiteQuery;
import androidx.room.Transaction;

import java.util.List;
import in.basulabs.siesattendance.backend.stats.SqlBits;

import in.basulabs.siesattendance.backend.pojo.CourseAttendanceStat;
import in.basulabs.siesattendance.backend.pojo.ExportRow;
import in.basulabs.siesattendance.backend.pojo.LectureMark;
import in.basulabs.siesattendance.backend.pojo.StudentAttendanceStat;

/**
 * Attendance rows: writing sheets, and reading the totals back out.
 * <p>
 * The write side is small on purpose. A sheet is written in one transaction, so there is no
 * path that leaves a lecture half marked: {@link #saveSheet} replaces a lecture's marks whole,
 * and {@link #insertAll} writes over the ones it is given and leaves the rest, which is what
 * saving a reopened register does.
 * <p>
 * The read side counts the marks and nothing else. A register writes a mark, present or
 * absent, for everybody on its sheet, so a student's marks are the lectures held for them, and
 * every report divides by those. Batch membership is never consulted: it is only what is true
 * today, and students change batch during the year. The register export counts this way, and
 * the statistics queries below read {@code SqlBits.MARKS}, which is where the rule is said.
 */
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

	/**
	 * Every mark stored for one lecture, with the student's name and the roll number they held
	 * in the given session, for reopening its register.
	 * <p>
	 * Deliberately unlike the rolls: no test of the leave date and none of batch membership. A
	 * student who has left, or moved to another batch, was still in the room that day, and a
	 * register that did not list them would lose their mark the next time it was saved. The
	 * join is LEFT for the same reason, so a student with no row for that session still
	 * appears, with a blank roll number.
	 */
	@Query("SELECT a.lectureID AS lectureID, a.studentID AS studentID, s.name AS name, " +
			"COALESCE(y.rollNo, '') AS rollNo, a.present AS present " +
			"FROM attendance a " +
			"INNER JOIN students s ON s.studentID = a.studentID " +
			"LEFT JOIN student_year y ON y.studentID = a.studentID " +
			"   AND y.academicYear = :academicYear " +
			"WHERE a.lectureID = :lectureID " +
			"ORDER BY rollNo ASC, s.name ASC")
	@NonNull
	public abstract List<LectureMark> getSheetMarksSync(long lectureID, int academicYear);

	/**
	 * Who was present. Used when reopening a sheet, so the previous marks come back rather than
	 * the screen reverting to everyone present.
	 */
	@Query("SELECT studentID FROM attendance WHERE lectureID = :lectureID AND present = 1")
	public abstract List<Long> getPresentIdsSync(long lectureID);

	/**
	 * Who appears on the sheet at all, present or absent. Read alongside
	 * {@link #getPresentIdsSync} to tell a recorded absence apart from a student who was never
	 * listed.
	 */
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
	 * lectures attended, narrowed by whatever the user asked for.
	 * <p>
	 * A raw query because the shape changes with the filter. See {@code ExportQueryBuilder},
	 * which owns the SQL and binds every user-supplied value as an argument.
	 * <p>
	 * Held is the lectures the student has a mark for, and attended the ones marked present;
	 * batches are not consulted. {@code ExportQueryBuilder} says why.
	 */
	@RawQuery
	@NonNull
	public abstract List<ExportRow> getExportRowsSync(@NonNull SupportSQLiteQuery query);

	/**
	 * Every stored mark for the given lectures, with the student's name and roll number, for
	 * the per-lecture and per-day exports.
	 * <p>
	 * The roll number is the one held in the session the lecture falls in, resolved from the
	 * lecture's own date by the same rule the statistics use — the latest session containing
	 * it, so a merged backup with overlapping sessions cannot list a student twice. Exporting
	 * last year's lecture therefore gives last year's roll numbers, which is what that
	 * register was called by. The join is LEFT so a student with no row for that session is
	 * still exported, with a blank roll number, rather than silently dropped along with their
	 * mark.
	 * <p>
	 * Only saved marks are returned: a student never put on a sheet has no row, and the export
	 * shows a blank for them rather than the "present" a fresh sheet would default to.
	 */
	@Query("SELECT a.lectureID AS lectureID, a.studentID AS studentID, s.name AS name, " +
			"COALESCE(y.rollNo, '') AS rollNo, a.present AS present " +
			"FROM attendance a " +
			"INNER JOIN lectures l ON l.lectureID = a.lectureID " +
			"INNER JOIN students s ON s.studentID = a.studentID " +
			"LEFT JOIN student_year y ON y.studentID = a.studentID AND y.academicYear = (" +
			"   SELECT ay.academicYear FROM academic_years ay " +
			"   WHERE " + SqlBits.EPOCH_START + " BETWEEN ay.startEpoch AND ay.endEpoch " +
			"   ORDER BY ay.academicYear DESC LIMIT 1) " +
			"WHERE a.lectureID IN (:lectureIDs) " +
			"AND y.leaveDate IS NULL " +
			"ORDER BY rollNo ASC, s.name ASC")
	@NonNull
	public abstract List<LectureMark> getMarksSync(@NonNull List<Long> lectureIDs);

	// ── Statistics ───────────────────────────────────────────────────────

	/**
	 * Per-student totals across every lecture they have a mark for, worst attendance first;
	 * see {@code SqlBits.MARKS}.
	 * <p>
	 * The join to attendance is LEFT, so a student with nothing recorded still appears, with
	 * zero of zero. Those students sort last rather than first, which takes an explicit
	 * clause: dividing through {@code NULLIF} yields a null percentage for them, and SQLite
	 * sorts nulls <em>first</em> in ascending order, so on its own it would head the list with
	 * exactly the students the list is not about. {@code COUNT(...) = 0} is 0 or 1, so
	 * ordering on it ascending puts everyone with a record ahead of everyone without, and the
	 * percentage then orders within that group where it is never null.
	 */
	@Query("SELECT s.studentID, s.name, y.rollNo, " +
			"COUNT(a.lectureID) AS totalLectures, " +
			"SUM(CASE WHEN a.present = 1 THEN 1 ELSE 0 END) AS attended " +
			"FROM students s " +
			"INNER JOIN student_year y ON y.studentID = s.studentID " +
			"   AND y.academicYear = :academicYear " +
			"LEFT JOIN " + SqlBits.MARKS + " a ON a.studentID = s.studentID " +
			"GROUP BY s.studentID, s.name, y.rollNo " +
			"ORDER BY (COUNT(a.lectureID) = 0) ASC, " +
			"         (CAST(SUM(CASE WHEN a.present = 1 THEN 1 ELSE 0 END) AS REAL) / " +
			"          NULLIF(COUNT(a.lectureID), 0)) ASC, " +
			"         y.rollNo ASC")
	public abstract LiveData<List<StudentAttendanceStat>> getStudentStats(int academicYear);

	/**
	 * Per-student totals restricted to one course, in roll number order.
	 * <p>
	 * Both joins are INNER here, unlike the query above, so only students with a lecture of
	 * this course held for them appear. That is the intended reading of a per-course list: a
	 * student with nothing against the course is not a student at nought per cent, they are
	 * somebody who does not take it.
	 */
	@Query("SELECT s.studentID, s.name, y.rollNo, " +
			"COUNT(a.lectureID) AS totalLectures, " +
			"SUM(CASE WHEN a.present = 1 THEN 1 ELSE 0 END) AS attended " +
			"FROM students s " +
			"INNER JOIN student_year y ON y.studentID = s.studentID " +
			"   AND y.academicYear = :academicYear " +
			"INNER JOIN " + SqlBits.MARKS + " a ON a.studentID = s.studentID " +
			"INNER JOIN lectures l ON l.lectureID = a.lectureID AND l.courseID = :courseID " +
			"GROUP BY s.studentID, s.name, y.rollNo " +
			"ORDER BY y.rollNo ASC")
	public abstract LiveData<List<StudentAttendanceStat>> getStudentStatsForCourse(
			long courseID, int academicYear);

	/**
	 * Per-course aggregates, one row per course including those with no lectures yet.
	 * <p>
	 * {@code lectureCount} is a DISTINCT count because the join repeats each lecture once per
	 * student it was held for.
	 */
	@Query("SELECT c.courseID, c.courseCode, c.courseTopic, " +
			"COUNT(a.studentID) AS totalRecords, " +
			"SUM(CASE WHEN a.present = 1 THEN 1 ELSE 0 END) AS presentRecords, " +
			"COUNT(DISTINCT l.lectureID) AS lectureCount " +
			"FROM courses c " +
			"LEFT JOIN lectures l ON l.courseID = c.courseID " +
			"LEFT JOIN " + SqlBits.MARKS + " a ON a.lectureID = l.lectureID " +
			"GROUP BY c.courseID, c.courseCode, c.courseTopic " +
			"ORDER BY c.courseCode ASC")
	public abstract LiveData<List<CourseAttendanceStat>> getCourseStats();

	/**
	 * The dashboard's attendance percentage for one session, as a 0 to 100 value.
	 * <p>
	 * The statistics screen's figure for the same session with no other filter: the marks of
	 * students enrolled in each lecture's session, from the day they joined. It used to cover
	 * every session on the phone and count marks from before a student joined, so the
	 * dashboard and the report disagreed (R32).
	 */
	@Query("SELECT CASE WHEN COUNT(*) = 0 THEN 0 ELSE " +
			"(SUM(CASE WHEN a.present = 1 THEN 1 ELSE 0 END) * 100.0) / COUNT(*) END " +
			"FROM " + SqlBits.MARKS + " a " +
			"INNER JOIN lectures l ON l.lectureID = a.lectureID " +
			SqlBits.ENROLMENT_AT_LECTURE +
			"WHERE y.academicYear = :academicYear AND " + SqlBits.COUNTS_FOR_ENROLMENT)
	public abstract LiveData<Double> getOverallPercentage(int academicYear);

	@Query("SELECT COUNT(DISTINCT lectureID) FROM attendance")
	public abstract LiveData<Integer> countMarkedLectures();
}
