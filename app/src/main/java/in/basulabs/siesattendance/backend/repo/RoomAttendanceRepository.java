package in.basulabs.siesattendance.backend.repo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.sqlite.db.SupportSQLiteQuery;

import java.time.ZonedDateTime;
import java.util.ArrayList;
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
import in.basulabs.siesattendance.backend.EntityStudentYear;
import androidx.sqlite.db.SupportSQLiteDatabase;
import in.basulabs.siesattendance.backend.EntitySyncState;
import in.basulabs.siesattendance.backend.sync.DeviceSql;
import in.basulabs.siesattendance.backend.sync.DeviceTables;
import in.basulabs.siesattendance.backend.MainDatabase;
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
 * The repository as it is today: Room, and nothing else.
 * <p>
 * Every method is one DAO call, and the two that are not — {@link #recordLectureWithMarks} and
 * {@link #duplicateLecture} — are the two the ViewModel already ran inside
 * {@code runInTransaction}. That is the point of this class: it is what makes extracting the
 * interface provably inert, so the transport that comes after it is the only thing left that can
 * change behaviour.
 * <p>
 * Nothing here dispatches to a thread. The caller is already on one; see
 * {@link AttendanceRepository}.
 */
public final class RoomAttendanceRepository implements AttendanceRepository {

	@NonNull
	private final MainDatabase db;

	public RoomAttendanceRepository(@NonNull MainDatabase db) {
		this.db = db;
	}

	/**
	 * The database itself, for the whole-file operations that work on the file and not the rows.
	 * <p>
	 * Backup, restore, the roster CSV import and the mark-sheet export take a
	 * {@link MainDatabase} because they walk or replace the local file. They are deliberately not
	 * behind the interface: a backup is a copy of this device's database rather than a question
	 * about the data, and a restore is the one operation that has to go under every abstraction
	 * above the file.
	 */
	@NonNull
	public MainDatabase database() {
		return db;
	}

	// ── Academic sessions ────────────────────────────────────────────────
	@Override
	@Nullable
	public EntityAcademicYear academicYearContainingSync(long epochSecond) {
		return db.daoAcademicYear().findContainingSync(epochSecond);
	}

	@Override
	@Nullable
	public EntityAcademicYear academicYearSync(int academicYear) {
		return db.daoAcademicYear().getByIdSync(academicYear);
	}

	@Override
	@NonNull
	public List<EntityAcademicYear> academicYearsSync() {
		return db.daoAcademicYear().getAllSync();
	}

	@Override
	public void deleteAcademicYear(@NonNull EntityAcademicYear year) {
		db.daoAcademicYear().delete(year);
	}

	@Override
	@Nullable
	public EntityAcademicYear latestAcademicYearSync() {
		return db.daoAcademicYear().getLatestSync();
	}

	@Override
	@NonNull
	public LiveData<List<EntityAcademicYear>> observeAcademicYears() {
		return db.daoAcademicYear().getAll();
	}

	@Override
	@NonNull
	public LiveData<Integer> observeOrphanLectureCount() {
		return db.daoAcademicYear().countOrphanLectures();
	}

	@Override
	@NonNull
	public SessionImpact sessionDeletionImpactSync(int academicYear, long startEpoch,
	                                              long endEpoch) {
		return db.daoAcademicYear().deletionImpactSync(academicYear, startEpoch, endEpoch);
	}

	@Override
	public void upsertAcademicYear(@NonNull EntityAcademicYear year) {
		db.daoAcademicYear().upsert(year);
	}

	// ── Professors ───────────────────────────────────────────────────────
	@Override
	public long insertProfessor(@NonNull EntityProfessor professor) {
		return db.daoProfessor().insert(professor);
	}

	@Override
	@NonNull
	public LiveData<List<EntityProfessor>> observeProfessors() {
		return db.daoProfessor().getAll();
	}

	@Override
	public int professorCountSync() {
		return db.daoProfessor().countSync();
	}

	@Override
	@Nullable
	public EntityProfessor professorSync(long profID) {
		return db.daoProfessor().getByIdSync(profID);
	}

	@Override
	@NonNull
	public List<EntityProfessor> professorsSync() {
		return db.daoProfessor().getAllSync();
	}

	@Override
	public void updateProfessor(@NonNull EntityProfessor professor) {
		db.daoProfessor().update(professor);
	}

	// ── Courses ──────────────────────────────────────────────────────────
	@Override
	@NonNull
	public List<EntityCourse> activeCoursesSync() {
		return db.daoCourse().getActiveSync();
	}

	@Override
	@NonNull
	public List<Long> courseInstructorIdsSync(long courseID) {
		return db.daoCourse().getInstructorIdsSync(courseID);
	}

	@Override
	@Nullable
	public EntityCourse courseSync(long courseID) {
		return db.daoCourse().getByIdSync(courseID);
	}

	@Override
	@NonNull
	public List<EntityCourse> coursesSync() {
		return db.daoCourse().getAllSync();
	}

	@Override
	public long insertCourse(@NonNull EntityCourse course) {
		return db.daoCourse().insert(course);
	}

	@Override
	@NonNull
	public LiveData<Integer> observeCourseCount() {
		return db.daoCourse().countCourses();
	}

	@Override
	@NonNull
	public LiveData<List<EntityCourse>> observeCourses() {
		return db.daoCourse().getAll();
	}

	@Override
	public void setCourseDiscontinued(long courseID, ZonedDateTime discontinuedOn) {
		db.daoCourse().setDiscontinued(courseID, discontinuedOn);
	}

	@Override
	public void setCourseInstructors(long courseID, @NonNull List<Long> profIDs) {
		db.daoCourse().setInstructors(courseID, profIDs);
	}

	@Override
	public void updateCourse(@NonNull EntityCourse course) {
		db.daoCourse().update(course);
	}

	// ── Batches ──────────────────────────────────────────────────────────
	@Override
	@NonNull
	public List<Long> batchMemberIdsSync(long batchID, int academicYear) {
		return db.daoBatch().getMemberIdsSync(batchID, academicYear);
	}

	@Override
	@Nullable
	public EntityBatchDesc batchSync(long batchID) {
		return db.daoBatch().getByIdSync(batchID);
	}

	@Override
	@NonNull
	public List<EntityBatchDesc> batchesForYearSync(int academicYear) {
		return db.daoBatch().getAllForYearSync(academicYear);
	}

	@Override
	@NonNull
	public List<EntityBatchDesc> batchesSync() {
		return db.daoBatch().getAllSync();
	}

	@Override
	public long createBatchFromStudents(@NonNull EntityBatchDesc batch,
	                                    @NonNull List<Long> studentIDs) {
		return db.daoBatch().createBatchFromStudents(batch, studentIDs);
	}

	@Override
	public void deleteBatchChecked(long batchID) {
		db.daoBatch().deleteBatchChecked(batchID);
	}

	@Override
	@NonNull
	public LiveData<List<StudentWithYear>> observeStudentsInBatch(long batchID) {
		return db.daoBatch().getStudentsInBatch(batchID);
	}

	@Override
	@NonNull
	public List<StudentWithYear> studentsInBatchSync(long batchID) {
		return db.daoBatch().getStudentsInBatchSync(batchID);
	}

	@Override
	public void updateBatch(long batchID, String name, String description,
	                        @Nullable Department department, int academicYear,
	                        @NonNull List<Long> studentIDs) {
		db.daoBatch().updateBatch(batchID, name, description, department, academicYear,
				studentIDs);
	}

	@Override
	@NonNull
	public List<Long> batchCourseIdsSync(long batchID) {
		return db.daoBatch().getLinkedCourseIdsSync(batchID);
	}

	@Override
	@NonNull
	public List<Long> courseBatchIdsSync(long courseID) {
		return db.daoCourse().getLinkedBatchIdsSync(courseID);
	}

	@Override
	public void setCourseBatches(long courseID, @NonNull List<Long> batchIDs) {
		db.daoCourse().setBatchLinks(courseID, batchIDs);
	}

	@Override
	public void setBatchCourses(long batchID, @NonNull List<Long> courseIDs) {
		db.daoBatch().setCourseLinks(batchID, courseIDs);
	}

	// ── Students ─────────────────────────────────────────────────────────
	@Override
	@NonNull
	public List<EntityCourse> attendedCoursesSync(long studentID) {
		return db.daoStudent().getAttendedCoursesSync(studentID);
	}

	@Override
	@NonNull
	public List<StudentWithYear> classRollSync(int academicYear, Year year, Stream stream,
	                                           Department dept) {
		return db.daoStudent().getClassSync(academicYear, year, stream, dept);
	}

	@Override
	public void deleteStudentCascade(long studentID) {
		db.daoStudent().deleteStudentCascade(studentID);
	}

	@Override
	@NonNull
	public List<StudentWithYear> filterStudentsSync(int academicYear, Year year, Stream stream,
	                                                Department majorDept, Department minorDept,
	                                                Department oeDept, Division division,
	                                                Long batchID, String query,
	                                                boolean includeLeft) {
		return db.daoStudent().filterStudentsSync(academicYear, year, stream, majorDept, minorDept,
		                     oeDept, division, batchID, query, includeLeft);
	}

	@Override
	@NonNull
	public LiveData<List<StudentCourseStat>> observeCourseBreakdown(
			long studentID, Long fromEpoch, Long toEpoch, boolean everyType,
			@NonNull List<CourseType> courseTypes, boolean everyCourse,
			@NonNull List<Long> courseIDs) {
		return db.daoStudent().getCourseBreakdown(studentID, fromEpoch, toEpoch, everyType,
		                     courseTypes, everyCourse, courseIDs);
	}

	@Override
	@NonNull
	public LiveData<List<StudentLectureMark>> observeLectureMarks(long studentID, long courseID,
	                                                              Long fromEpoch, Long toEpoch) {
		return db.daoStudent().getLectureMarks(studentID, courseID, fromEpoch, toEpoch);
	}

	@Override
	@NonNull
	public LiveData<StudentWithYear> observeStudent(long studentID, int academicYear) {
		return db.daoStudent().getOne(studentID, academicYear);
	}

	@Override
	@NonNull
	public List<StudentWithYear> rollForBatchSync(long batchID, int academicYear) {
		return db.daoStudent().getRollForBatchSync(batchID, academicYear);
	}

	@Override
	@NonNull
	public List<StudentWithYear> rollForCourseSync(long courseID, int academicYear) {
		return db.daoStudent().getRollForCourseSync(courseID, academicYear);
	}

	@Override
	@NonNull
	public DeletionImpact studentDeletionImpactSync(long studentID, int academicYear) {
		return db.daoStudent().deletionImpact(studentID, academicYear);
	}

	@Override
	@Nullable
	public StudentWithYear studentSync(long studentID, int academicYear) {
		return db.daoStudent().getOneSync(studentID, academicYear);
	}

	@Override
	public void updateStudentWithYear(@NonNull EntityStudent student, int academicYear,
	                                  String rollNo, Year year, Stream stream, Department majorDept,
	                                  Department minorDept, Department oeDept, Integer semester,
	                                  Division division, @Nullable java.time.LocalDate joinDate,
	                                  @Nullable java.time.LocalDate leaveDate) {
		db.daoStudent().updateStudentWithYear(student, academicYear, rollNo, year, stream,
		              majorDept, minorDept, oeDept, semester, division, joinDate, leaveDate);
	}

	@Override
	public long upsertStudentWithYear(@NonNull EntityStudent student, int academicYear,
	                                  String rollNo, Year year, Stream stream, Department majorDept,
	                                  Department minorDept, Department oeDept, Integer semester,
	                                  Division division, @Nullable java.time.LocalDate joinDate,
	                                  @Nullable java.time.LocalDate leaveDate) {
		return db.daoStudent().upsertStudentWithYear(student, academicYear, rollNo, year, stream,
		                     majorDept, minorDept, oeDept, semester, division, joinDate,
		                     leaveDate);
	}

	@Override
	@NonNull
	public List<EntityStudentYear> enrolmentsSync(long studentID) {
		return db.daoStudent().getEnrolmentsSync(studentID);
	}

	@Override
	public void withdrawFromSession(long studentID, int academicYear) {
		db.daoStudent().withdrawFromSession(studentID, academicYear);
	}

	// ── Lectures ─────────────────────────────────────────────────────────
	@Override
	public void deleteLectureCascade(@NonNull EntityLecture lecture) {
		db.daoLecture().deleteLectureCascade(lecture);
	}

	@Override
	@Nullable
	public Long findLecture(long courseID, Long batchID, ZonedDateTime start, ZonedDateTime end) {
		return db.daoLecture().findExisting(courseID, batchID, start, end);
	}

	@Override
	public long insertLecture(@NonNull EntityLecture lecture) {
		return db.daoLecture().insert(lecture);
	}

	@Override
	@Nullable
	public LectureDetails lectureDetailsSync(long lectureID) {
		return db.daoLecture().getDetailsByIdSync(lectureID);
	}

	@Override
	@Nullable
	public EntityLecture lectureSync(long lectureID) {
		return db.daoLecture().getByIdSync(lectureID);
	}

	@Override
	@NonNull
	public LiveData<List<LectureDetails>> observeAllLectures() {
		return db.daoLecture().getAllDetails();
	}

	@Override
	@NonNull
	public LiveData<List<LectureDetails>> observeFilteredLectures(Long fromEpoch, Long toEpoch,
	                                                              Long courseID, Long profID,
	                                                              Department department,
	                                                              Long batchID,
	                                                              CourseType courseType) {
		return db.daoLecture().getFilteredDetails(fromEpoch, toEpoch, courseID, profID, department,
		                     batchID, courseType);
	}

	@Override
	@NonNull
	public LiveData<Integer> observeLectureCountBetween(long fromEpoch, long toEpoch) {
		return db.daoLecture().countLecturesBetween(fromEpoch, toEpoch);
	}

	@Override
	@NonNull
	public LiveData<List<LectureDetails>> observeLecturesBetween(long fromEpoch, long toEpoch) {
		return db.daoLecture().getDetailsBetween(fromEpoch, toEpoch);
	}

	@Override
	public void updateLecture(long lectureID, long courseID, Long batchID, ZonedDateTime start,
	                          ZonedDateTime end, String notes) {
		db.daoLecture().updateLecture(lectureID, courseID, batchID, start, end, notes);
	}

	// ── Attendance ───────────────────────────────────────────────────────
	@Override
	@NonNull
	public List<EntityAttendance> attendanceForLectureSync(long lectureID) {
		return db.daoAttendance().getForLectureSync(lectureID);
	}

	@Override
	public void clearAttendanceForLecture(long lectureID) {
		db.daoAttendance().clearForLecture(lectureID);
	}

	@Override
	public void copyAttendance(long fromLectureID, long toLectureID) {
		db.daoAttendance().copyMarks(fromLectureID, toLectureID);
	}

	@Override
	public void deleteAttendanceExcept(long lectureID, @NonNull List<Long> keep) {
		db.daoAttendance().deleteForLectureExcept(lectureID, keep);
	}

	@Override
	public void insertAttendance(@NonNull List<EntityAttendance> records) {
		db.daoAttendance().insertAll(records);
	}

	@Override
	@NonNull
	public List<Long> markedStudentIdsSync(long lectureID) {
		return db.daoAttendance().getMarkedIdsSync(lectureID);
	}

	@Override
	@NonNull
	public LiveData<List<CourseAttendanceStat>> observeCourseStats() {
		return db.daoAttendance().getCourseStats();
	}

	@Override
	@NonNull
	public LiveData<Double> observeOverallPercentage() {
		return db.daoAttendance().getOverallPercentage();
	}

	@Override
	@NonNull
	public List<Long> presentStudentIdsSync(long lectureID) {
		return db.daoAttendance().getPresentIdsSync(lectureID);
	}

	@Override
	public void saveAttendanceSheet(long lectureID, @NonNull List<EntityAttendance> records) {
		db.daoAttendance().saveSheet(lectureID, records);
	}

	// ── Statistics and the report ────────────────────────────────────────
	@Override
	@NonNull
	public List<StatRow> statRowsSync(@NonNull SupportSQLiteQuery query) {
		return db.daoStats().runGrouped(query);
	}

	@Override
	@NonNull
	public StatsSummary statsSummarySync(@NonNull SupportSQLiteQuery query) {
		return db.daoStats().runSummary(query);
	}

	// ── Observers taken by method reference ──────────────────────────────
	//
	// The ViewModel switch-maps these onto its session LiveData, so they take the session as a
	// parameter and are referenced rather than called.

	@Override
	@NonNull
	public LiveData<List<EntityBatchDesc>> observeBatchesForYear(int academicYear) {
		return db.daoBatch().getAllForYear(academicYear);
	}

	@Override
	@NonNull
	public LiveData<List<EntityBatchDesc>> observeBatchesForYearLinkedTo(
			int academicYear, @NonNull List<Long> courseIDs) {
		return db.daoBatch().getForYearLinkedTo(academicYear, courseIDs);
	}

	@Override
	@NonNull
	public List<Long> batchIdsLinkedToSync(@NonNull List<Long> courseIDs) {
		return db.daoBatch().getIdsLinkedToSync(courseIDs);
	}

	@Override
	@NonNull
	public LiveData<List<ClassKey>> observeClassesForYear(int academicYear) {
		return db.daoStudent().getClassesForYear(academicYear);
	}

	@Override
	@NonNull
	public LiveData<Integer> observeStudentCount(int academicYear) {
		return db.daoStudent().countStudents(academicYear);
	}

	@Override
	@NonNull
	public LiveData<List<StudentAttendanceStat>> observeStudentStats(int academicYear) {
		return db.daoAttendance().getStudentStats(academicYear);
	}

	// ── Sync, as far as a screen needs to know ──────────────────────

	@Override
	public LiveData<EntitySyncState> observeSyncState() {
		return db.daoOutbox().observeState();
	}

	@Override
	public LiveData<Integer> observePendingChangeCount() {
		return db.daoOutbox().countPending();
	}

	// ── The two writes that are one unit ─────────────────────────────────

	@Override
	public long recordLectureWithMarks(long courseID, @Nullable Long batchID,
	                                   @NonNull ZonedDateTime start, @NonNull ZonedDateTime end,
	                                   @Nullable String notes,
	                                   @NonNull List<AttendanceRow> marks) {

		long[] lectureID = new long[1];

		db.runInTransaction(() -> {

			Long existing = db.daoLecture().findExisting(courseID, batchID, start, end);

			if (existing != null) {
				lectureID[0] = existing;
				db.daoAttendance().clearForLecture(lectureID[0]);
			} else {
				lectureID[0] = db.daoLecture().insert(
						new EntityLecture(courseID, batchID, start, end, notes));
			}

			List<EntityAttendance> records = new ArrayList<>();

			for (AttendanceRow row : marks) {
				records.add(new EntityAttendance(lectureID[0], row.studentID, row.present));
			}
			db.daoAttendance().insertAll(records);
		});

		return lectureID[0];
	}

	@Override
	public long duplicateLecture(long sourceLectureID, long courseID, @Nullable Long batchID,
	                             @NonNull ZonedDateTime start, @NonNull ZonedDateTime end,
	                             @Nullable String notes) {

		long[] created = new long[1];

		db.runInTransaction(() -> {
			created[0] = db.daoLecture().insert(
					new EntityLecture(courseID, batchID, start, end, notes));
			db.daoAttendance().copyMarks(sourceLectureID, created[0]);
		});

		return created[0];
	}
}
