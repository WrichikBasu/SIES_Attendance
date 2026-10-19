package in.basulabs.siesattendance.backend.repo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.sqlite.db.SupportSQLiteQuery;

import java.time.ZonedDateTime;
import java.util.List;

import in.basulabs.siesattendance.backend.CourseType;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Division;
import in.basulabs.siesattendance.backend.EntityAcademicYear;
import in.basulabs.siesattendance.backend.EntityAttendance;
import in.basulabs.siesattendance.backend.EntityBatchDesc;
import in.basulabs.siesattendance.backend.EntityCourse;
import in.basulabs.siesattendance.backend.EntityLecture;
import in.basulabs.siesattendance.backend.EntityProfessor;
import in.basulabs.siesattendance.backend.EntityStudent;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;
import in.basulabs.siesattendance.backend.pojo.AttendanceRow;
import in.basulabs.siesattendance.backend.pojo.ClassKey;
import in.basulabs.siesattendance.backend.pojo.CourseAttendanceStat;
import in.basulabs.siesattendance.backend.pojo.DeletionImpact;
import in.basulabs.siesattendance.backend.pojo.LectureDetails;
import in.basulabs.siesattendance.backend.pojo.SessionImpact;
import in.basulabs.siesattendance.backend.pojo.StatRow;
import in.basulabs.siesattendance.backend.pojo.StatsSummary;
import in.basulabs.siesattendance.backend.pojo.StudentAttendanceStat;
import in.basulabs.siesattendance.backend.pojo.StudentCourseStat;
import in.basulabs.siesattendance.backend.pojo.StudentLectureMark;
import in.basulabs.siesattendance.backend.pojo.StudentWithYear;

/**
 * Everything the app reads and writes, as one interface.
 * <p>
 * The seam. Today there is one implementation, {@link RoomAttendanceRepository}, and it is a thin
 * delegation to the Room DAOs — so extracting it changes no behaviour at all. What it buys is
 * that the twenty-five fragments and the ViewModel above them no longer know where the data is:
 * when the attendance database moves to the server, the implementation behind this interface
 * gains an outbox and a pull, and nothing above it has to be touched.
 *
 * <h3>Two kinds of method, and the difference matters</h3>
 * <ul>
 *     <li><b>{@code observe…}</b> returns Room-backed {@link LiveData}, which updates itself when
 *     the underlying rows change. That self-updating is what will keep the screens correct while
 *     a background sync writes to the same tables, and it is the reason Room stays the thing the
 *     UI reads rather than becoming a cache something has to remember to refresh.</li>
 *     <li><b>Everything else is synchronous</b> and must not be called on the main thread — the
 *     same contract the DAOs underneath already have. The ViewModel owns the two executors and
 *     the callbacks the fragments see; this interface has no threading of its own on purpose,
 *     because an interface that dispatched would dispatch the same way for a local read and a
 *     remote one, and those are not the same problem.</li>
 * </ul>
 *
 * <h3>Nullability</h3>
 * Returns are annotated: a {@code …Sync} method that looks up one row answers {@code null} when
 * there is no such row, and the annotations say which those are. Parameters are annotated only
 * where no caller could pass null. The filter parameters — a batch, a year, a department, a
 * search string — are left bare and mean "no restriction" when null, which is how the queries
 * underneath are written ({@code :x IS NULL OR …}); annotating them one by one here would be a
 * second set of claims about the same queries and a second thing to get out of step.
 *
 * <h3>Why the names differ from the DAOs'</h3>
 * {@code getAllSync} exists on four DAOs and {@code getByIdSync} on five, so the names here say
 * which: {@code coursesSync}, {@code batchSync}, {@code lectureSync}. Nothing else is renamed,
 * and every method below is the same call the ViewModel was already making.
 */
public interface AttendanceRepository {

	// ── Academic sessions ────────────────────────────────────────────────
	@Nullable
	EntityAcademicYear academicYearContainingSync(long epochSecond);

	@Nullable
	EntityAcademicYear academicYearSync(int academicYear);

	@NonNull
	List<EntityAcademicYear> academicYearsSync();

	void deleteAcademicYear(@NonNull EntityAcademicYear year);

	@Nullable
	EntityAcademicYear latestAcademicYearSync();

	@NonNull
	LiveData<List<EntityAcademicYear>> observeAcademicYears();

	@NonNull
	LiveData<Integer> observeOrphanLectureCount();

	@NonNull
	SessionImpact sessionDeletionImpactSync(int academicYear, long startEpoch, long endEpoch);

	void upsertAcademicYear(@NonNull EntityAcademicYear year);


	// ── Professors ───────────────────────────────────────────────────────
	long insertProfessor(@NonNull EntityProfessor professor);

	@NonNull
	LiveData<List<EntityProfessor>> observeProfessors();

	int professorCountSync();

	@Nullable
	EntityProfessor professorSync(long profID);

	@NonNull
	List<EntityProfessor> professorsSync();

	void updateProfessor(@NonNull EntityProfessor professor);


	// ── Courses ──────────────────────────────────────────────────────────
	@NonNull
	List<EntityCourse> activeCoursesSync();

	@NonNull
	List<Long> courseInstructorIdsSync(long courseID);

	@Nullable
	EntityCourse courseSync(long courseID);

	@NonNull
	List<EntityCourse> coursesSync();

	long insertCourse(@NonNull EntityCourse course);

	@NonNull
	LiveData<Integer> observeCourseCount();

	@NonNull
	LiveData<List<EntityCourse>> observeCourses();

	void setCourseDiscontinued(long courseID, ZonedDateTime discontinuedOn);

	void setCourseInstructors(long courseID, @NonNull List<Long> profIDs);

	void updateCourse(@NonNull EntityCourse course);


	// ── Batches ──────────────────────────────────────────────────────────
	@NonNull
	List<Long> batchMemberIdsSync(long batchID, int academicYear);

	@Nullable
	EntityBatchDesc batchSync(long batchID);

	@NonNull
	List<EntityBatchDesc> batchesForYearSync(int academicYear);

	/**
	 * The batches of one session, as the screens observe them.
	 * <p>
	 * Takes the session rather than reading the current one, because the ViewModel reaches this
	 * through {@code Transformations.switchMap}: the session is a {@code LiveData} of its own, and
	 * changing it has to re-point the query rather than leave the old session's rows on screen.
	 */
	@NonNull
	LiveData<List<EntityBatchDesc>> observeBatchesForYear(int academicYear);

	@NonNull
	List<EntityBatchDesc> batchesSync();

	long createBatchFromStudents(@NonNull EntityBatchDesc batch, @NonNull List<Long> studentIDs);

	void deleteBatchChecked(long batchID);

	@NonNull
	LiveData<List<StudentWithYear>> observeStudentsInBatch(long batchID);

	@NonNull
	List<StudentWithYear> studentsInBatchSync(long batchID);

	void updateBatch(long batchID, String name, String description, int academicYear,
	                 @NonNull List<Long> studentIDs);


	// ── Students ─────────────────────────────────────────────────────────
	@NonNull
	List<EntityCourse> attendedCoursesSync(long studentID);

	@NonNull
	List<StudentWithYear> classRollSync(int academicYear, Year year, Stream stream,
	                                    Department dept);

	/** The classes a session has students in, for the chips. Switch-mapped; see above. */
	@NonNull
	LiveData<List<ClassKey>> observeClassesForYear(int academicYear);

	/** How many students are enrolled in a session. Switch-mapped; see above. */
	@NonNull
	LiveData<Integer> observeStudentCount(int academicYear);

	void deleteStudentCascade(long studentID);

	@NonNull
	List<StudentWithYear> filterStudentsSync(int academicYear, Year year, Stream stream,
	                                         Department majorDept, Department minorDept,
	                                         Department oeDept, Division division, Long batchID,
	                                         String query);

	@NonNull
	LiveData<List<StudentCourseStat>> observeCourseBreakdown(
			long studentID, Long fromEpoch, Long toEpoch, boolean everyType,
			@NonNull List<CourseType> courseTypes, boolean everyCourse,
			@NonNull List<Long> courseIDs);

	@NonNull
	LiveData<List<StudentLectureMark>> observeLectureMarks(long studentID, long courseID,
	                                                       Long fromEpoch, Long toEpoch);

	@NonNull
	LiveData<StudentWithYear> observeStudent(long studentID, int academicYear);

	@NonNull
	List<StudentWithYear> rollForBatchSync(long batchID, int academicYear);

	@NonNull
	List<StudentWithYear> rollForCourseSync(long courseID, int academicYear);

	@NonNull
	DeletionImpact studentDeletionImpactSync(long studentID, int academicYear);

	@Nullable
	StudentWithYear studentSync(long studentID, int academicYear);

	void updateStudentWithYear(@NonNull EntityStudent student, int academicYear, String rollNo,
	                           Year year, Stream stream, Department majorDept, Department minorDept,
	                           Department oeDept, Integer semester, Division division);

	long upsertStudentWithYear(@NonNull EntityStudent student, int academicYear, String rollNo,
	                           Year year, Stream stream, Department majorDept, Department minorDept,
	                           Department oeDept, Integer semester, Division division);

	void withdrawFromSession(long studentID, int academicYear);


	// ── Lectures ─────────────────────────────────────────────────────────
	void deleteLectureCascade(@NonNull EntityLecture lecture);

	@Nullable
	Long findLecture(long courseID, Long batchID, ZonedDateTime start, ZonedDateTime end);

	long insertLecture(@NonNull EntityLecture lecture);

	@Nullable
	LectureDetails lectureDetailsSync(long lectureID);

	@Nullable
	EntityLecture lectureSync(long lectureID);

	@NonNull
	LiveData<List<LectureDetails>> observeAllLectures();

	@NonNull
	LiveData<List<LectureDetails>> observeFilteredLectures(Long fromEpoch, Long toEpoch,
	                                                       Long courseID, Long profID,
	                                                       Department department, Long batchID,
	                                                       CourseType courseType);

	@NonNull
	LiveData<Integer> observeLectureCountBetween(long fromEpoch, long toEpoch);

	@NonNull
	LiveData<List<LectureDetails>> observeLecturesBetween(long fromEpoch, long toEpoch);

	void updateLecture(long lectureID, long courseID, Long batchID, ZonedDateTime start,
	                   ZonedDateTime end, String notes);


	// ── Attendance ───────────────────────────────────────────────────────
	@NonNull
	List<EntityAttendance> attendanceForLectureSync(long lectureID);

	void clearAttendanceForLecture(long lectureID);

	void copyAttendance(long fromLectureID, long toLectureID);

	void deleteAttendanceExcept(long lectureID, @NonNull List<Long> keep);

	void insertAttendance(@NonNull List<EntityAttendance> records);

	@NonNull
	List<Long> markedStudentIdsSync(long lectureID);

	@NonNull
	LiveData<List<CourseAttendanceStat>> observeCourseStats();

	@NonNull
	LiveData<Double> observeOverallPercentage();

	/** Per-student attendance for a session, for the report. Switch-mapped; see above. */
	@NonNull
	LiveData<List<StudentAttendanceStat>> observeStudentStats(int academicYear);

	@NonNull
	List<Long> presentStudentIdsSync(long lectureID);

	void saveAttendanceSheet(long lectureID, @NonNull List<EntityAttendance> records);


	// ── Statistics and the report ────────────────────────────────────────
	@NonNull
	List<StatRow> statRowsSync(@NonNull SupportSQLiteQuery query);

	@NonNull
	StatsSummary statsSummarySync(@NonNull SupportSQLiteQuery query);

	// ── Sync, as far as a screen needs to know ──────────────────────

	/**
	 * How many local changes have not reached the server yet.
	 * <p>
	 * The only thing about syncing that belongs on this interface. A screen showing "3 changes
	 * waiting" is telling the user something true and useful; everything else about the queue —
	 * what is in it, when it was tried, why it failed — is the sender's business, and the sender
	 * is below this interface rather than above it.
	 * <p>
	 * Zero from an implementation that has no queue, which is the honest answer for one whose
	 * writes have nowhere to go.
	 */
	@NonNull
	LiveData<Integer> observePendingChangeCount();

	// ── The two writes that are one unit ─────────────────────────────────

	/**
	 * Records a lecture and the sheet marked on it, in one transaction.
	 * <p>
	 * One unit because a lecture with no sheet shows up as permanently unmarked, and a sheet
	 * with no lecture cannot be shown at all. Re-recording the same course, batch and time span
	 * replaces the earlier sheet rather than failing on the unique index, which is what somebody
	 * correcting a mistake expects.
	 * <p>
	 * This and {@link #duplicateLecture} are the only methods here that are not a single DAO
	 * call, and that is why they are on this interface rather than assembled by the caller: a
	 * transport-backed implementation has to send each as one intent, and a caller doing its own
	 * two calls is a caller that can send half of one.
	 *
	 * @param marks the sheet as the screen holds it; only the student and the presence are read
	 * @return the lecture's id, whether it was created or replaced
	 */
	long recordLectureWithMarks(long courseID, @Nullable Long batchID,
	                            @NonNull ZonedDateTime start, @NonNull ZonedDateTime end,
	                            @Nullable String notes, @NonNull List<AttendanceRow> marks);

	/**
	 * Copies a lecture's marks onto a new lecture at another time, in one transaction.
	 * <p>
	 * The course, the batch and the notes come from the caller rather than from the source
	 * lecture, because duplicating a lecture is also how one gets copied onto another batch.
	 *
	 * @return the new lecture's id
	 */
	long duplicateLecture(long sourceLectureID, long courseID, @Nullable Long batchID,
	                      @NonNull ZonedDateTime start, @NonNull ZonedDateTime end,
	                      @Nullable String notes);
}
