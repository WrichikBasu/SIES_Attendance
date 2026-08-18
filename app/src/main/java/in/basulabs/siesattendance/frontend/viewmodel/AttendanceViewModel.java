package in.basulabs.siesattendance.frontend.viewmodel;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
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
import in.basulabs.siesattendance.backend.EntityAcademicYear;
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
	private final MutableLiveData<Integer> academicYear =
			new MutableLiveData<>(provisionalYear());

	/** The session row backing {@link #academicYear}, once the database has been read. */
	@Nullable
	private volatile EntityAcademicYear currentSession;

	// Class filter state

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
		resolveCurrentSession();
	}

	/**
	 * A provisional guess used only before the sessions table has been read. The real answer
	 * comes from {@link #resolveCurrentSession()}, which asks the database which session
	 * contains today.
	 */
	private static int provisionalYear() {
		return EntityAcademicYear.defaultYearFor(LocalDate.now());
	}

	/**
	 * Works out the current session from the {@code academic_years} table and publishes it.
	 * <p>
	 * If today falls outside every defined session — during a summer break, say, or before the
	 * next year has been set up — the most recent session is used rather than inventing one, so
	 * the app keeps showing real data instead of an empty year. Only a completely empty table
	 * causes a session to be created, seeded on the June default.
	 */
	public void resolveCurrentSession() {
		MainDatabase.DB_EXECUTOR.execute(() -> {

			long now = System.currentTimeMillis() / 1000L;
			EntityAcademicYear session = db.daoAcademicYear().findContainingSync(now);

			if (session == null) {
				session = db.daoAcademicYear().getLatestSync();
			}

			if (session == null) {
				session = EntityAcademicYear.defaultFor(provisionalYear());
				db.daoAcademicYear().upsert(session);
			}

			currentSession = session;
			academicYear.postValue(session.academicYear);

			// The report is seeded before the database has been read, so unless the user has
			// since chosen a session themselves, point it at the real one and refresh. The
			// debounce merges this with the report's own initial load.
			if (statsFilter != null && !statsYearChosenByUser) {
				statsFilter.academicYear = session.academicYear;
				scheduleStatsQuery();
			}

			if (!studentYearChosenByUser) {
				studentFilter().academicYear = session.academicYear;
				studentFilterYear.postValue(session.academicYear);
				refreshStudents();
			}
		});
	}

	// ── Simple accessors ─────────────────────────────────────────────────

	public LiveData<Integer> getAcademicYear() {
		return academicYear;
	}

	public int academicYearValue() {
		Integer value = academicYear.getValue();
		return value == null ? provisionalYear() : value;
	}

	@Nullable
	public EntityAcademicYear currentSession() {
		return currentSession;
	}

	// ── Academic sessions ────────────────────────────────────────────────

	public LiveData<List<EntityAcademicYear>> getAcademicYears() {
		return db.daoAcademicYear().getAll();
	}

	public void loadAcademicYearsSync(
			@NonNull java.util.function.Consumer<List<EntityAcademicYear>> cb) {
		MainDatabase.DB_EXECUTOR.execute(() -> cb.accept(db.daoAcademicYear().getAllSync()));
	}

	/**
	 * Number of lectures that fall outside every defined session. Anything above zero means
	 * those lectures are invisible to every report, so the screens surface it as a warning
	 * rather than letting the totals quietly under-count.
	 */
	public LiveData<Integer> getOrphanLectureCount() {
		return db.daoAcademicYear().countOrphanLectures();
	}

	public void saveAcademicYear(int year, @NonNull LocalDate start, @NonNull LocalDate end,
	                             @NonNull Runnable onDone) {

		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				if (!end.isAfter(start)) {
					toastMessage.postValue("The end date must be after the start date");
					return;
				}

				// Overlapping sessions would make a lecture's session ambiguous, and the join
				// would silently pick one, so the clash is refused up front.
				for (EntityAcademicYear existing : db.daoAcademicYear().getAllSync()) {
					if (existing.academicYear == year) {
						continue;
					}
					if (!start.isAfter(existing.endDate) && !end.isBefore(existing.startDate)) {
						toastMessage.postValue("These dates overlap the "
								+ existing.label() + " session");
						return;
					}
				}

				db.daoAcademicYear().upsert(EntityAcademicYear.of(year, start, end));
				toastMessage.postValue("Session saved");
				resolveCurrentSession();
				onDone.run();
			} catch (Exception e) {
				toastMessage.postValue("Could not save: " + e.getMessage());
			}
		});
	}

	public void deleteAcademicYear(@NonNull EntityAcademicYear year, @NonNull Runnable onDone) {
		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				db.daoAcademicYear().delete(year);
				toastMessage.postValue("Session removed");
				resolveCurrentSession();
				onDone.run();
			} catch (Exception e) {
				toastMessage.postValue("Could not remove: " + e.getMessage());
			}
		});
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

	/**
	 * The students screen's filter. Held as one object so a screen can change any dimension
	 * without the others needing separate setters.
	 */
	public static class StudentFilter {

		/**
		 * Which session's roll to show. Separate from the app-wide academic year on purpose:
		 * browsing last year's students should not silently change the session that new
		 * attendance gets filed under.
		 */
		public int academicYear;

		@Nullable
		public Year year;

		@Nullable
		public Stream stream;

		@Nullable
		public Department majorDept;

		@Nullable
		public Department minorDept;

		@Nullable
		public Department oeDept;

		@Nullable
		public Long batchID;

		@NonNull
		public String query = "";

		public StudentFilter(int academicYear) {
			this.academicYear = academicYear;
		}

		/** True when anything beyond the session is narrowing the list. */
		public boolean hasActiveFilters() {
			return year != null || stream != null || majorDept != null || minorDept != null
					|| oeDept != null || batchID != null;
		}

		public void clear() {
			year = null;
			stream = null;
			majorDept = null;
			minorDept = null;
			oeDept = null;
			batchID = null;
			query = "";
		}
	}

	private StudentFilter studentFilter;

	/** True once the user has picked a session on the students screen themselves. */
	private boolean studentYearChosenByUser;

	@NonNull
	public StudentFilter studentFilter() {
		if (studentFilter == null) {
			studentFilter = new StudentFilter(academicYearValue());
		}
		return studentFilter;
	}

	public LiveData<List<StudentWithYear>> getFilteredStudents() {
		return filteredStudents;
	}

	/** Mirrors {@link StudentFilter#academicYear} so the class chips can react to it. */
	private final MutableLiveData<Integer> studentFilterYear =
			new MutableLiveData<>(provisionalYear());

	public LiveData<Integer> getStudentFilterYear() {
		return studentFilterYear;
	}

	/**
	 * The classes present in whichever session the students screen is showing, which is not
	 * necessarily the current one.
	 */
	public LiveData<List<ClassKey>> getClasses() {
		return Transformations.switchMap(studentFilterYear,
				db.daoStudent()::getClassesForYear);
	}

	public void setStudentAcademicYear(int academicYear) {
		studentFilter().academicYear = academicYear;
		studentYearChosenByUser = true;

		// The batch and class filters name things that belong to one session, so they cannot
		// survive a switch to another.
		studentFilter().batchID = null;
		studentFilterYear.setValue(academicYear);
		refreshStudents();
	}

	public Year getFilterYear() {
		return studentFilter().year;
	}

	public Stream getFilterStream() {
		return studentFilter().stream;
	}

	public Department getFilterDept() {
		return studentFilter().majorDept;
	}

	/**
	 * Applies a full class filter such as SY / BSc / COMPUTER_SCIENCE. Any argument may be null
	 * to leave that dimension open.
	 */
	public void setClassFilter(@Nullable Year year, @Nullable Stream stream,
	                           @Nullable Department dept) {
		studentFilter().year = year;
		studentFilter().stream = stream;
		studentFilter().majorDept = dept;
		refreshStudents();
	}

	public void setSearchQuery(@Nullable String query) {
		studentFilter().query = query == null ? "" : query.trim();
		refreshStudents();
	}

	public void clearFilters() {
		studentFilter().clear();
		refreshStudents();
	}

	/**
	 * Runs the students query. All narrowing now happens in SQL rather than by filtering the
	 * result in Java, which matters once minor department, open elective and batch are in play.
	 */
	public void refreshStudents() {

		final StudentFilter f = studentFilter();
		final int acadYear = f.academicYear;
		final Year y = f.year;
		final Stream st = f.stream;
		final Department major = f.majorDept;
		final Department minor = f.minorDept;
		final Department oe = f.oeDept;
		final Long batch = f.batchID;
		final String q = f.query;

		MainDatabase.DB_EXECUTOR.execute(() -> filteredStudents.postValue(
				db.daoStudent().filterStudentsSync(acadYear, y, st, major, minor, oe,
						batch, q)));
	}

	/**
	 * Batches belonging to the session the students screen is showing, for its batch filter.
	 */
	public void loadBatchesForStudentFilter(
			@NonNull java.util.function.Consumer<List<EntityBatchDesc>> cb) {
		final int acadYear = studentFilter().academicYear;
		MainDatabase.DB_EXECUTOR.execute(() ->
				cb.accept(db.daoBatch().getAllForYearSync(acadYear)));
	}

	public void addStudent(@NonNull EntityStudent student, @NonNull String rollNo,
	                       @NonNull Year year, @NonNull Stream stream,
	                       @NonNull Department majorDept, @Nullable Department minorDept,
	                       @Nullable Department oeDept, @NonNull Runnable onDone) {

		// New students land in the session the screen is showing, which is what someone
		// entering last year's late admission expects.
		final int acadYear = studentFilter().academicYear;

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
		final int acadYear = studentFilter().academicYear;

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

		final int acadYear = studentFilter().academicYear;

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

	// ── Lecture list filtering ───────────────────────────────────────────

	/**
	 * The window the lectures tab is showing. Held here rather than in the fragment so it
	 * survives the tab being swiped away and back.
	 */
	private final MutableLiveData<LectureWindow> lectureWindow =
			new MutableLiveData<>(new LectureWindow(null, null, LectureWindow.Kind.ALL, null));

	/**
	 * A date window for the lectures list, plus enough context to label itself.
	 */
	public static class LectureWindow {

		public enum Kind {
			ALL,
			SESSION,
			MONTH,
			CUSTOM
		}

		@Nullable
		public final LocalDate from;

		@Nullable
		public final LocalDate to;

		@NonNull
		public final Kind kind;

		/** What to show in the filter bar. */
		@Nullable
		public final String label;

		public LectureWindow(@Nullable LocalDate from, @Nullable LocalDate to,
		                     @NonNull Kind kind, @Nullable String label) {
			this.from = from;
			this.to = to;
			this.kind = kind;
			this.label = label;
		}
	}

	public LiveData<LectureWindow> getLectureWindow() {
		return lectureWindow;
	}

	public void setLectureWindow(@NonNull LectureWindow window) {
		lectureWindow.setValue(window);
	}

	/**
	 * Lectures inside the current window, re-queried whenever the window changes. An unbounded
	 * window falls back to the full list rather than binding absurd sentinel epochs.
	 */
	public LiveData<List<LectureDetails>> getFilteredLectures() {
		return Transformations.switchMap(lectureWindow, window -> {

			if (window == null || window.from == null || window.to == null) {
				return db.daoLecture().getAllDetails();
			}

			ZoneId zone = ZoneId.systemDefault();
			long from = window.from.atStartOfDay(zone).toEpochSecond();
			long to = window.to.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1;

			return db.daoLecture().getDetailsBetween(from, to);
		});
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
		final int acadYear = studentFilter().academicYear;
		MainDatabase.DB_EXECUTOR.execute(() ->
				cb.accept(db.daoStudent().getOneSync(studentID, acadYear)));
	}

	public void updateStudent(@NonNull EntityStudent student, @NonNull String rollNo,
	                          @NonNull Year year, @NonNull Stream stream,
	                          @NonNull Department majorDept, @Nullable Department minorDept,
	                          @Nullable Department oeDept, @NonNull Runnable onDone) {

		final int acadYear = studentFilter().academicYear;

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

		final int fallbackYear = studentFilter().academicYear;

		MainDatabase.DB_EXECUTOR.execute(() -> {

			// A batch belongs to one session. For an existing batch that is the batch's own
			// session; for a new one it is whichever session the students screen was showing,
			// so a batch seeded from a past year lists that year's students. Resolved here
			// rather than at the call site because it reads the database.
			int acadYear = batchID > 0 ? batchSessionYear(batchID, fallbackYear) : fallbackYear;

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

		final StudentFilter f = studentFilter();

		MainDatabase.DB_EXECUTOR.execute(() -> {
			List<Long> ids = new ArrayList<>();
			for (StudentWithYear student : db.daoStudent().filterStudentsSync(
					f.academicYear, f.year, f.stream, f.majorDept, f.minorDept, f.oeDept,
					f.batchID, f.query)) {
				ids.add(student.studentID);
			}
			cb.accept(ids);
		});
	}

	/**
	 * The session a batch belongs to, falling back to the supplied year if the row cannot be
	 * read. Reads the database, so it must be called off the main thread.
	 */
	private int batchSessionYear(long batchID, int fallbackYear) {
		EntityBatchDesc batch = db.daoBatch().getByIdSync(batchID);
		return batch == null ? fallbackYear : batch.academicYear;
	}

	public interface BatchEditorCallback {
		void onLoaded(@Nullable EntityBatchDesc batch, @NonNull List<StudentWithYear> allStudents,
		              @NonNull List<Long> memberIDs);
	}

	public void updateBatch(long batchID, @NonNull String name, @Nullable String description,
	                        @NonNull List<Long> studentIDs, @NonNull Runnable onDone) {

		final int fallbackYear = studentFilter().academicYear;

		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				int acadYear = batchSessionYear(batchID, fallbackYear);
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
	 * True once the user has picked a session explicitly. Until then the filter tracks whatever
	 * {@link #resolveCurrentSession()} works out, so the report does not sit on the provisional
	 * guess made before the database was read.
	 */
	private boolean statsYearChosenByUser;

	/** Debounce for report refreshes; see {@link #scheduleStatsQuery()}. */
	private final Handler statsHandler = new Handler(Looper.getMainLooper());

	private final Runnable statsRunnable = this::runStatsQuery;

	private static final long STATS_DEBOUNCE_MS = 250L;

	/**
	 * The live filter object. It is mutated in place by the report screen and re-run through
	 * {@link #runStatsQuery()}, so the screen never has to reassemble it from scratch.
	 */
	/**
	 * The live filter object, mutated in place by the report screen.
	 * <p>
	 * The academic year is seeded once and then left alone: overwriting it on every access, as
	 * an earlier version did, would have made it impossible to select a past session or to
	 * compare years.
	 */
	@NonNull
	public StatsFilter statsFilter() {
		if (statsFilter == null) {
			statsFilter = new StatsFilter(academicYearValue());
		}
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

	/**
	 * Clears the filters but keeps the selected session, which is a filter the user chose
	 * deliberately rather than one they accumulated by drilling down.
	 */
	public void resetStatsFilter() {
		Integer year = statsFilter().academicYear;
		statsFilter().clearFilters();
		statsFilter().academicYear = year;
		scheduleStatsQuery();
	}

	public void setStatsAcademicYear(@Nullable Integer year) {
		statsFilter().academicYear = year;
		statsYearChosenByUser = true;
	}

	/**
	 * Coalesces rapid filter changes into a single query.
	 * <p>
	 * Each refresh is two full aggregate scans, so firing one per dropdown tap made scrolling
	 * through a list of courses noticeably slow. Anything within the debounce window collapses
	 * into one run, which also merges the report's initial load with the re-run that follows the
	 * session being resolved from the database.
	 */
	public void scheduleStatsQuery() {
		statsHandler.removeCallbacks(statsRunnable);
		statsHandler.postDelayed(statsRunnable, STATS_DEBOUNCE_MS);
	}

	@Override
	protected void onCleared() {
		statsHandler.removeCallbacks(statsRunnable);
		super.onCleared();
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

				// The groups partition the same rows, so counting the ones below the threshold
				// here is exactly what a third aggregate query would have returned, without
				// scanning the table again.
				int below = 0;
				for (StatRow row : rows) {
					if (row.isBelow(snapshot.threshold)) {
						below++;
					}
				}

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
