package in.basulabs.siesattendance.frontend.viewmodel;

import android.app.Application;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.EntityAttendance;
import in.basulabs.siesattendance.backend.EntityBatchDesc;
import in.basulabs.siesattendance.backend.EntityCourse;
import in.basulabs.siesattendance.backend.EntityLecture;
import in.basulabs.siesattendance.backend.EntityProfessor;
import in.basulabs.siesattendance.backend.EntityStudent;
import in.basulabs.siesattendance.backend.MainDatabase;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;
import in.basulabs.siesattendance.backend.pojo.AttendanceRow;
import in.basulabs.siesattendance.backend.pojo.ClassKey;
import in.basulabs.siesattendance.backend.pojo.CourseAttendanceStat;
import in.basulabs.siesattendance.backend.pojo.LectureDetails;
import in.basulabs.siesattendance.backend.pojo.StudentAttendanceStat;
import in.basulabs.siesattendance.backend.pojo.StatRow;
import in.basulabs.siesattendance.backend.pojo.StatsSummary;
import in.basulabs.siesattendance.backend.pojo.StudentCourseStat;
import in.basulabs.siesattendance.backend.stats.StatsFilter;
import in.basulabs.siesattendance.backend.stats.StatsQueryBuilder;
import in.basulabs.siesattendance.backend.pojo.StudentWithYear;
import in.basulabs.siesattendance.backend.util.CsvImporter;

/**
 * Shared across every fragment via {@code requireActivity()} scope, so the class filter chosen
 * on the students screen is still there when the user comes back from creating a batch.
 */
public class AttendanceViewModel extends AndroidViewModel {

	private final MainDatabase db;

	/** Current academic year, as the starting calendar year (2025 means 2025-26). */
	private final MutableLiveData<Integer> academicYear = new MutableLiveData<>(currentYear());

	// Class filter state
	private final MutableLiveData<Year> filterYear = new MutableLiveData<>(null);
	private final MutableLiveData<Stream> filterStream = new MutableLiveData<>(null);
	private final MutableLiveData<Department> filterDept = new MutableLiveData<>(null);
	private final MutableLiveData<String> searchQuery = new MutableLiveData<>("");

	private final MutableLiveData<List<StudentWithYear>> filteredStudents =
			new MutableLiveData<>(new ArrayList<>());

	// One-shot event channels
	private final MutableLiveData<CsvImporter.Result> importResult = new MutableLiveData<>();
	private final MutableLiveData<String> toastMessage = new MutableLiveData<>();
	private final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);

	// Attendance sheet being edited
	private final MutableLiveData<List<AttendanceRow>> attendanceRows = new MutableLiveData<>();
	private LectureDetails currentLecture;

	public AttendanceViewModel(@NonNull Application application) {
		super(application);
		db = MainDatabase.getInstance(application);
	}

	private static int currentYear() {
		LocalDate now = LocalDate.now();
		// An academic year starting in June: before June we are still in the previous session.
		return now.getMonthValue() >= 6 ? now.getYear() : now.getYear() - 1;
	}

	// ── Simple accessors ─────────────────────────────────────────────────

	public LiveData<Integer> getAcademicYear() {
		return academicYear;
	}

	public int academicYearValue() {
		Integer value = academicYear.getValue();
		return value == null ? currentYear() : value;
	}

	public void setAcademicYear(int year) {
		academicYear.setValue(year);
		refreshStudents();
	}

	public LiveData<String> getToastMessage() {
		return toastMessage;
	}

	public void clearToast() {
		toastMessage.setValue(null);
	}

	public LiveData<Boolean> isBusy() {
		return busy;
	}

	public LiveData<CsvImporter.Result> getImportResult() {
		return importResult;
	}

	public void clearImportResult() {
		importResult.setValue(null);
	}

	// ── Students and class filtering ─────────────────────────────────────

	public LiveData<List<StudentWithYear>> getFilteredStudents() {
		return filteredStudents;
	}

	public LiveData<List<ClassKey>> getClasses() {
		return Transformations.switchMap(academicYear, db.daoStudent()::getClassesForYear);
	}

	public Year getFilterYear() {
		return filterYear.getValue();
	}

	public Stream getFilterStream() {
		return filterStream.getValue();
	}

	public Department getFilterDept() {
		return filterDept.getValue();
	}

	/**
	 * Applies a full class filter such as SY / BSc / COMPUTER_SCIENCE. Any argument may be null
	 * to leave that dimension open.
	 */
	public void setClassFilter(@Nullable Year year, @Nullable Stream stream,
	                           @Nullable Department dept) {
		filterYear.setValue(year);
		filterStream.setValue(stream);
		filterDept.setValue(dept);
		refreshStudents();
	}

	public void setSearchQuery(@Nullable String query) {
		searchQuery.setValue(query == null ? "" : query.trim());
		refreshStudents();
	}

	public void clearFilters() {
		filterYear.setValue(null);
		filterStream.setValue(null);
		filterDept.setValue(null);
		searchQuery.setValue("");
		refreshStudents();
	}

	public void refreshStudents() {
		final int year = academicYearValue();
		final Year y = filterYear.getValue();
		final Stream s = filterStream.getValue();
		final Department d = filterDept.getValue();
		final String q = searchQuery.getValue() == null ? "" : searchQuery.getValue();

		MainDatabase.DB_EXECUTOR.execute(() -> {
			List<StudentWithYear> all = db.daoStudent().getClassSync(year, y, s, d);
			List<StudentWithYear> out = new ArrayList<>();

			for (StudentWithYear student : all) {
				if (q.isEmpty()
						|| student.name.toLowerCase().contains(q.toLowerCase())
						|| student.rollNo.toLowerCase().contains(q.toLowerCase())) {
					out.add(student);
				}
			}
			filteredStudents.postValue(out);
		});
	}

	public void addStudent(@NonNull EntityStudent student, @NonNull String rollNo,
	                       @NonNull Year year, @NonNull Stream stream,
	                       @NonNull Department majorDept, @Nullable Department minorDept,
	                       @Nullable Department oeDept, @NonNull Runnable onDone) {

		final int acadYear = academicYearValue();

		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				db.daoStudent().upsertStudentWithYear(student, acadYear, rollNo, year, stream,
						majorDept, minorDept, oeDept);
				toastMessage.postValue("Student saved");
				refreshStudents();
				onDone.run();
			} catch (Exception e) {
				toastMessage.postValue("Could not save: " + e.getMessage());
			}
		});
	}

	// ── CSV import ───────────────────────────────────────────────────────

	public void importCsv(@NonNull Uri uri) {
		busy.setValue(true);
		final int acadYear = academicYearValue();

		MainDatabase.DB_EXECUTOR.execute(() -> {
			CsvImporter.Result result =
					CsvImporter.importStudents(getApplication(), uri, acadYear);
			importResult.postValue(result);
			busy.postValue(false);
			refreshStudents();
		});
	}

	// ── Professors ───────────────────────────────────────────────────────

	public LiveData<List<EntityProfessor>> getProfessors() {
		return db.daoProfessor().getAll();
	}

	public void addProfessor(@NonNull EntityProfessor professor, @NonNull Runnable onDone) {
		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				db.daoProfessor().insert(professor);
				toastMessage.postValue("Professor saved");
				onDone.run();
			} catch (Exception e) {
				toastMessage.postValue("Could not save: " + e.getMessage());
			}
		});
	}

	// ── Courses ──────────────────────────────────────────────────────────

	public LiveData<List<EntityCourse>> getCourses() {
		return db.daoCourse().getAll();
	}

	public void addCourse(@NonNull EntityCourse course, @NonNull Runnable onDone) {
		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				db.daoCourse().insert(course);
				toastMessage.postValue("Course saved");
				onDone.run();
			} catch (Exception e) {
				toastMessage.postValue("Could not save: " + e.getMessage());
			}
		});
	}

	public void loadCoursesSync(@NonNull java.util.function.Consumer<List<EntityCourse>> cb) {
		MainDatabase.DB_EXECUTOR.execute(() -> cb.accept(db.daoCourse().getAllSync()));
	}

	public void loadProfessorsSync(
			@NonNull java.util.function.Consumer<List<EntityProfessor>> cb) {
		MainDatabase.DB_EXECUTOR.execute(() -> cb.accept(db.daoProfessor().getAllSync()));
	}

	// ── Batches ──────────────────────────────────────────────────────────

	public LiveData<List<EntityBatchDesc>> getBatches() {
		return Transformations.switchMap(academicYear, db.daoBatch()::getAllForYear);
	}

	public void loadBatchesSync(@NonNull java.util.function.Consumer<List<EntityBatchDesc>> cb) {
		MainDatabase.DB_EXECUTOR.execute(() -> cb.accept(db.daoBatch().getAllSync()));
	}

	/**
	 * Creates a batch from an explicit set of students. They may come from any combination of
	 * years, streams and departments within the academic year.
	 */
	public void createBatch(@NonNull String batchName, @Nullable String description,
	                        @NonNull List<Long> studentIDs, @NonNull Runnable onDone) {

		final int acadYear = academicYearValue();

		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				EntityBatchDesc batch = new EntityBatchDesc(batchName, acadYear, description);
				db.daoBatch().createBatchFromStudents(batch, studentIDs);
				toastMessage.postValue("Batch created with " + studentIDs.size() + " students");
				onDone.run();
			} catch (Exception e) {
				toastMessage.postValue("Could not create batch: " + e.getMessage());
			}
		});
	}

	// ── Lectures ─────────────────────────────────────────────────────────

	public LiveData<List<LectureDetails>> getAllLectures() {
		return db.daoLecture().getAllDetails();
	}

	public LiveData<List<LectureDetails>> getLecturesOn(@NonNull LocalDate date) {
		ZoneId zone = ZoneId.systemDefault();
		long from = date.atStartOfDay(zone).toEpochSecond();
		long to = date.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1;
		return db.daoLecture().getDetailsBetween(from, to);
	}

	public LiveData<Integer> countLecturesToday() {
		ZoneId zone = ZoneId.systemDefault();
		LocalDate today = LocalDate.now();
		long from = today.atStartOfDay(zone).toEpochSecond();
		long to = today.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1;
		return db.daoLecture().countLecturesBetween(from, to);
	}

	/**
	 * Loads the roll for a course without creating anything. Passing a batch narrows it to that
	 * batch's members; passing null takes the course's whole class for the academic year.
	 */
	public void loadRoll(long courseID, @Nullable Long batchID) {

		busy.setValue(true);
		final int acadYear = academicYearValue();

		MainDatabase.DB_EXECUTOR.execute(() -> {
			List<StudentWithYear> students = batchID == null
					? db.daoStudent().getRollForCourseSync(courseID, acadYear)
					: db.daoStudent().getRollForBatchSync(batchID, acadYear);

			List<AttendanceRow> rows = new ArrayList<>();
			for (StudentWithYear student : students) {
				rows.add(new AttendanceRow(student.studentID, student.name, student.rollNo,
						null));
			}

			attendanceRows.postValue(rows);
			busy.postValue(false);

			if (rows.isEmpty()) {
				toastMessage.postValue("No students are enrolled in this course for "
						+ acadYear + "-" + ((acadYear + 1) % 100));
			}
		});
	}

	public void clearRoll() {
		attendanceRows.setValue(new ArrayList<>());
		currentLecture = null;
	}

	/**
	 * Records a lecture and its attendance together.
	 * <p>
	 * The lecture is created here rather than scheduled in advance, so a row in {@code lectures}
	 * always corresponds to a session that actually took place. Both writes share one
	 * transaction: a lecture with no attendance sheet would show up as permanently unmarked.
	 * <p>
	 * Re-recording the same course, batch and time span overwrites the earlier sheet instead of
	 * failing on the unique index, which is what someone correcting a mistake expects.
	 */
	public void recordLectureWithAttendance(long courseID, @Nullable Long batchID,
	                                        @NonNull ZonedDateTime start,
	                                        @NonNull ZonedDateTime end, @Nullable String notes,
	                                        @NonNull Runnable onDone) {

		List<AttendanceRow> rows = attendanceRows.getValue();

		if (rows == null || rows.isEmpty()) {
			toastMessage.setValue("There is no one to mark");
			return;
		}

		if (!end.isAfter(start)) {
			toastMessage.setValue("End time must be after the start time");
			return;
		}

		int unmarked = 0;
		for (AttendanceRow row : rows) {
			if (row.present == null) {
				unmarked++;
			}
		}

		if (unmarked > 0) {
			toastMessage.setValue(unmarked + " student(s) still unmarked");
			return;
		}

		final List<AttendanceRow> snapshot = new ArrayList<>(rows);

		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				db.runInTransaction(() -> {

					Long existing = db.daoLecture().findExisting(courseID, batchID, start, end);

					long lectureID;
					if (existing != null) {
						lectureID = existing;
						db.daoAttendance().clearForLecture(lectureID);
					} else {
						lectureID = db.daoLecture().insert(
								new EntityLecture(courseID, batchID, start, end, notes));
					}

					List<EntityAttendance> records = new ArrayList<>();
					for (AttendanceRow row : snapshot) {
						records.add(new EntityAttendance(lectureID, row.studentID,
								Boolean.TRUE.equals(row.present)));
					}
					db.daoAttendance().insertAll(records);
				});

				toastMessage.postValue("Attendance recorded");
				onDone.run();
			} catch (Exception e) {
				toastMessage.postValue("Could not record: " + e.getMessage());
			}
		});
	}

	// ── Attendance ───────────────────────────────────────────────────────

	public LiveData<List<AttendanceRow>> getAttendanceRows() {
		return attendanceRows;
	}

	public LectureDetails getCurrentLecture() {
		return currentLecture;
	}

	/**
	 * Loads the roll for one lecture. Any attendance already saved for it is pre-applied, so
	 * reopening a marked lecture shows the previous answers rather than a blank sheet.
	 */
	public void loadAttendanceSheet(long lectureID) {
		busy.setValue(true);

		MainDatabase.DB_EXECUTOR.execute(() -> {
			LectureDetails details = db.daoLecture().getDetailsByIdSync(lectureID);
			currentLecture = details;

			if (details == null) {
				toastMessage.postValue("Lecture not found");
				busy.postValue(false);
				return;
			}

			// A lecture with no batch was taken for the course's whole class, so the roll is
			// rebuilt from the course rather than from batch membership.
			List<StudentWithYear> students = details.batchID == null
					? db.daoStudent().getRollForCourseSync(details.courseID, academicYearValue())
					: db.daoBatch().getStudentsInBatchSync(details.batchID);
			List<Long> presentIds = db.daoAttendance().getPresentIdsSync(lectureID);

			List<EntityAttendance> existing = db.daoAttendance().getForLectureSync(lectureID);
			List<Long> markedIds = new ArrayList<>();
			for (EntityAttendance record : existing) {
				markedIds.add(record.studentID);
			}

			List<AttendanceRow> rows = new ArrayList<>();
			for (StudentWithYear student : students) {
				Boolean present = markedIds.contains(student.studentID)
						? presentIds.contains(student.studentID)
						: null;
				rows.add(new AttendanceRow(student.studentID, student.name, student.rollNo,
						present));
			}

			attendanceRows.postValue(rows);
			busy.postValue(false);
		});
	}

	public void setPresence(long studentID, boolean present) {
		List<AttendanceRow> rows = attendanceRows.getValue();
		if (rows == null) {
			return;
		}
		for (AttendanceRow row : rows) {
			if (row.studentID == studentID) {
				row.present = present;
				break;
			}
		}
		attendanceRows.setValue(rows);
	}

	public void setAllPresence(boolean present) {
		List<AttendanceRow> rows = attendanceRows.getValue();
		if (rows == null) {
			return;
		}
		for (AttendanceRow row : rows) {
			row.present = present;
		}
		attendanceRows.setValue(rows);
	}

	/**
	 * Writes the sheet. Unmarked students are refused rather than defaulted, because silently
	 * recording an unanswered student as absent would corrupt the statistics.
	 */
	public void saveAttendance(@NonNull Runnable onDone) {
		List<AttendanceRow> rows = attendanceRows.getValue();

		if (rows == null || currentLecture == null) {
			toastMessage.setValue("Nothing to save");
			return;
		}

		int unmarked = 0;
		for (AttendanceRow row : rows) {
			if (row.present == null) {
				unmarked++;
			}
		}

		if (unmarked > 0) {
			toastMessage.setValue(unmarked + " student(s) still unmarked");
			return;
		}

		final long lectureID = currentLecture.lectureID;
		final List<EntityAttendance> records = new ArrayList<>();
		for (AttendanceRow row : rows) {
			records.add(new EntityAttendance(lectureID, row.studentID, row.present));
		}

		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				db.daoAttendance().saveSheet(lectureID, records);
				toastMessage.postValue("Attendance saved");
				onDone.run();
			} catch (Exception e) {
				toastMessage.postValue("Could not save: " + e.getMessage());
			}
		});
	}

	// ── Student detail, edit and delete ──────────────────────────────────

	public LiveData<StudentWithYear> getStudent(long studentID) {
		return db.daoStudent().getOne(studentID, academicYearValue());
	}

	public LiveData<List<StudentCourseStat>> getStudentCourseBreakdown(long studentID) {
		return db.daoStudent().getCourseBreakdown(studentID);
	}

	public void loadStudentSync(long studentID,
	                            @NonNull java.util.function.Consumer<StudentWithYear> cb) {
		final int acadYear = academicYearValue();
		MainDatabase.DB_EXECUTOR.execute(() ->
				cb.accept(db.daoStudent().getOneSync(studentID, acadYear)));
	}

	public void updateStudent(@NonNull EntityStudent student, @NonNull String rollNo,
	                          @NonNull Year year, @NonNull Stream stream,
	                          @NonNull Department majorDept, @Nullable Department minorDept,
	                          @Nullable Department oeDept, @NonNull Runnable onDone) {

		final int acadYear = academicYearValue();

		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				db.daoStudent().updateStudentWithYear(student, acadYear, rollNo, year, stream,
						majorDept, minorDept, oeDept);
				toastMessage.postValue("Student updated");
				refreshStudents();
				onDone.run();
			} catch (Exception e) {
				toastMessage.postValue("Could not update: " + e.getMessage());
			}
		});
	}

	// ── Professor edit and delete ────────────────────────────────────────

	public void updateProfessor(@NonNull EntityProfessor professor, @NonNull Runnable onDone) {
		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				db.daoProfessor().update(professor);
				toastMessage.postValue("Professor updated");
				onDone.run();
			} catch (Exception e) {
				toastMessage.postValue("Could not update: " + e.getMessage());
			}
		});
	}

	public void loadProfessorSync(long profID,
	                              @NonNull java.util.function.Consumer<EntityProfessor> cb) {
		MainDatabase.DB_EXECUTOR.execute(() ->
				cb.accept(db.daoProfessor().getByIdSync(profID)));
	}

	/**
	 * Courses carry a mandatory professor foreign key, so the add-course screen checks this
	 * first and sends the user to add a professor rather than letting them fill in a form that
	 * cannot be saved.
	 */
	public void countProfessors(@NonNull java.util.function.Consumer<Integer> cb) {
		MainDatabase.DB_EXECUTOR.execute(() -> cb.accept(db.daoProfessor().countSync()));
	}

	// ── Course edit and delete ───────────────────────────────────────────

	public void loadCourseSync(long courseID,
	                           @NonNull java.util.function.Consumer<EntityCourse> cb) {
		MainDatabase.DB_EXECUTOR.execute(() -> cb.accept(db.daoCourse().getByIdSync(courseID)));
	}

	public void updateCourse(@NonNull EntityCourse course, @NonNull Runnable onDone) {
		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				db.daoCourse().update(course);
				toastMessage.postValue("Course updated");
				onDone.run();
			} catch (Exception e) {
				toastMessage.postValue("Could not update: " + e.getMessage());
			}
		});
	}

	/**
	 * Marks a course as no longer offered. This replaces deletion: the lectures and attendance
	 * filed under the course stay intact and its history remains readable.
	 */
	public void discontinueCourse(long courseID, @NonNull Runnable onDone) {
		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				db.daoCourse().setDiscontinued(courseID, ZonedDateTime.now());
				toastMessage.postValue("Course marked as discontinued");
				onDone.run();
			} catch (Exception e) {
				toastMessage.postValue("Could not update: " + e.getMessage());
			}
		});
	}

	// ── Lecture edit and delete ──────────────────────────────────────────

	public void updateLecture(long lectureID, long courseID, @Nullable Long batchID,
	                          @NonNull ZonedDateTime start, @NonNull ZonedDateTime end,
	                          @Nullable String notes, @NonNull Runnable onDone) {

		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				if (!end.isAfter(start)) {
					toastMessage.postValue("End time must be after the start time");
					return;
				}
				db.daoLecture().updateLecture(lectureID, courseID, batchID, start, end, notes);
				toastMessage.postValue("Lecture updated");
				onDone.run();
			} catch (Exception e) {
				toastMessage.postValue("Could not update: " + e.getMessage());
			}
		});
	}

	public void deleteLecture(long lectureID, @NonNull Runnable onDone) {
		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				EntityLecture lecture = db.daoLecture().getByIdSync(lectureID);
				if (lecture == null) {
					toastMessage.postValue("Lecture not found");
					return;
				}
				db.daoLecture().deleteLectureCascade(lecture);
				toastMessage.postValue("Lecture deleted");
				onDone.run();
			} catch (Exception e) {
				toastMessage.postValue(e.getMessage());
			}
		});
	}

	public void loadLectureSync(long lectureID,
	                            @NonNull java.util.function.Consumer<LectureDetails> cb) {
		MainDatabase.DB_EXECUTOR.execute(() ->
				cb.accept(db.daoLecture().getDetailsByIdSync(lectureID)));
	}

	// ── Batch edit and delete ────────────────────────────────────────────

	public void loadBatchSync(long batchID,
	                          @NonNull java.util.function.Consumer<EntityBatchDesc> cb) {
		MainDatabase.DB_EXECUTOR.execute(() -> cb.accept(db.daoBatch().getByIdSync(batchID)));
	}

	/**
	 * Students preselected for a batch about to be created, handed over by whichever screen
	 * started the flow. Consumed once so a later visit starts empty.
	 */
	private List<Long> batchSeed;

	public void setBatchSeed(@Nullable List<Long> studentIDs) {
		batchSeed = studentIDs == null ? null : new ArrayList<>(studentIDs);
	}

	/**
	 * Loads every student of the academic year, across all years, streams and departments,
	 * alongside the IDs already selected. A batch is free to mix classes, so the list is
	 * deliberately unfiltered here and narrowed in the UI instead.
	 * <p>
	 * A {@code batchID} of zero or less means a new batch: there is nothing to load, and the
	 * preselection comes from the seed if one was set.
	 */
	public void loadBatchEditor(long batchID, @NonNull BatchEditorCallback cb) {
		final int acadYear = academicYearValue();

		MainDatabase.DB_EXECUTOR.execute(() -> {
			List<StudentWithYear> all =
					db.daoStudent().getClassSync(acadYear, null, null, null);

			if (batchID <= 0) {
				List<Long> seed = batchSeed == null ? new ArrayList<>() : batchSeed;
				batchSeed = null;
				cb.onLoaded(null, all, seed);
				return;
			}

			EntityBatchDesc batch = db.daoBatch().getByIdSync(batchID);
			List<Long> members = db.daoBatch().getMemberIdsSync(batchID, acadYear);
			cb.onLoaded(batch, all, members);
		});
	}

	/**
	 * Every student of the academic year, used to seed a batch from the students screen's
	 * current class filter.
	 */
	public void loadFilteredStudentIds(@NonNull java.util.function.Consumer<List<Long>> cb) {
		final int acadYear = academicYearValue();
		final Year y = filterYear.getValue();
		final Stream st = filterStream.getValue();
		final Department d = filterDept.getValue();

		MainDatabase.DB_EXECUTOR.execute(() -> {
			List<Long> ids = new ArrayList<>();
			for (StudentWithYear student : db.daoStudent().getClassSync(acadYear, y, st, d)) {
				ids.add(student.studentID);
			}
			cb.accept(ids);
		});
	}

	public interface BatchEditorCallback {
		void onLoaded(@Nullable EntityBatchDesc batch, @NonNull List<StudentWithYear> allStudents,
		              @NonNull List<Long> memberIDs);
	}

	public void updateBatch(long batchID, @NonNull String name, @Nullable String description,
	                        @NonNull List<Long> studentIDs, @NonNull Runnable onDone) {

		final int acadYear = academicYearValue();

		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				db.daoBatch().updateBatch(batchID, name, description, acadYear, studentIDs);
				toastMessage.postValue("Batch updated");
				onDone.run();
			} catch (Exception e) {
				toastMessage.postValue("Could not update: " + e.getMessage());
			}
		});
	}

	public void deleteBatch(long batchID, @NonNull Runnable onDone) {
		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				db.daoBatch().deleteBatchChecked(batchID);
				toastMessage.postValue("Batch deleted");
				onDone.run();
			} catch (Exception e) {
				toastMessage.postValue(e.getMessage());
			}
		});
	}

	/**
	 * Batches that make sense for this course in the current academic year, rather than every
	 * batch that happens to exist for the year.
	 */
	public void loadBatchesForCourse(long courseID,
	                                 @NonNull java.util.function.Consumer<List<EntityBatchDesc>> cb) {
		final int acadYear = academicYearValue();
		MainDatabase.DB_EXECUTOR.execute(() ->
				cb.accept(db.daoCourse().getBatchesForCourseSync(courseID, acadYear)));
	}

	public LiveData<List<StudentWithYear>> getStudentsInBatch(long batchID) {
		return db.daoBatch().getStudentsInBatch(batchID);
	}

	// ── Statistics ───────────────────────────────────────────────────────

	public LiveData<List<StudentAttendanceStat>> getStudentStats() {
		return Transformations.switchMap(academicYear, db.daoAttendance()::getStudentStats);
	}

	public LiveData<List<CourseAttendanceStat>> getCourseStats() {
		return db.daoAttendance().getCourseStats();
	}

	public LiveData<Double> getOverallPercentage() {
		return db.daoAttendance().getOverallPercentage();
	}

	public LiveData<Integer> countStudents() {
		return Transformations.switchMap(academicYear, db.daoStudent()::countStudents);
	}

	public LiveData<Integer> countCourses() {
		return db.daoCourse().countCourses();
	}

	// ── Flexible statistics ──────────────────────────────────────────────

	private final MutableLiveData<List<StatRow>> statRows = new MutableLiveData<>();

	private final MutableLiveData<StatsSummary> statsSummary = new MutableLiveData<>();

	private final MutableLiveData<Integer> statsBelowCount = new MutableLiveData<>(0);

	private StatsFilter statsFilter;

	/**
	 * The live filter object. It is mutated in place by the report screen and re-run through
	 * {@link #runStatsQuery()}, so the screen never has to reassemble it from scratch.
	 */
	@NonNull
	public StatsFilter statsFilter() {
		if (statsFilter == null) {
			statsFilter = new StatsFilter(academicYearValue());
		}
		statsFilter.academicYear = academicYearValue();
		return statsFilter;
	}

	public LiveData<List<StatRow>> getStatRows() {
		return statRows;
	}

	public LiveData<StatsSummary> getStatsSummary() {
		return statsSummary;
	}

	public LiveData<Integer> getStatsBelowCount() {
		return statsBelowCount;
	}

	public void resetStatsFilter() {
		statsFilter().clearFilters();
		runStatsQuery();
	}

	/**
	 * Runs the grouped report, its summary and the below-threshold count together. A snapshot of
	 * the filter is taken first so an edit made while the query is in flight cannot produce a
	 * result that matches neither the old filter nor the new one.
	 */
	public void runStatsQuery() {

		final StatsFilter snapshot = statsFilter().copy();
		busy.setValue(true);

		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				List<StatRow> rows =
						db.daoStats().runGrouped(StatsQueryBuilder.grouped(snapshot));
				StatsSummary summary =
						db.daoStats().runSummary(StatsQueryBuilder.summary(snapshot));
				int below =
						db.daoStats().runCount(StatsQueryBuilder.belowThreshold(snapshot));

				statRows.postValue(rows);
				statsSummary.postValue(summary == null ? new StatsSummary() : summary);
				statsBelowCount.postValue(below);
			} catch (Exception e) {
				toastMessage.postValue("Could not run report: " + e.getMessage());
				statRows.postValue(new ArrayList<>());
				statsSummary.postValue(new StatsSummary());
				statsBelowCount.postValue(0);
			} finally {
				busy.postValue(false);
			}
		});
	}

	/**
	 * Loads the lists the report filters pick from, in one pass so the screen can populate all
	 * of its dropdowns at once.
	 */
	public void loadStatsFilterOptions(@NonNull StatsOptionsCallback cb) {
		MainDatabase.DB_EXECUTOR.execute(() -> cb.onLoaded(
				db.daoCourse().getAllSync(),
				db.daoBatch().getAllSync(),
				db.daoProfessor().getAllSync()));
	}

	public interface StatsOptionsCallback {
		void onLoaded(@NonNull List<EntityCourse> courses, @NonNull List<EntityBatchDesc> batches,
		              @NonNull List<EntityProfessor> professors);
	}
}
