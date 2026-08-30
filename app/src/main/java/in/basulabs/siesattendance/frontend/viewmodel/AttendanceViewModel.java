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
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Division;
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
import in.basulabs.siesattendance.backend.pojo.DeletionImpact;
import in.basulabs.siesattendance.backend.pojo.AttendanceRow;
import in.basulabs.siesattendance.backend.pojo.ClassKey;
import in.basulabs.siesattendance.backend.pojo.CourseAttendanceStat;
import in.basulabs.siesattendance.backend.pojo.LectureDetails;
import in.basulabs.siesattendance.backend.pojo.StudentAttendanceStat;
import in.basulabs.siesattendance.backend.pojo.SessionImpact;
import in.basulabs.siesattendance.backend.pojo.StatRow;
import in.basulabs.siesattendance.backend.pojo.StatsSummary;
import in.basulabs.siesattendance.backend.pojo.StudentCourseStat;
import in.basulabs.siesattendance.backend.pojo.StudentLectureMark;
import in.basulabs.siesattendance.backend.stats.StatsFilter;
import in.basulabs.siesattendance.backend.stats.StatsQueryBuilder;
import in.basulabs.siesattendance.backend.pojo.StudentWithYear;
import in.basulabs.siesattendance.backend.util.AttendanceExporter;
import in.basulabs.siesattendance.backend.util.AttendanceRecordImporter;
import in.basulabs.siesattendance.backend.util.BreakdownFilter;
import in.basulabs.siesattendance.backend.util.CourseGroup;
import in.basulabs.siesattendance.backend.util.ExportFilter;
import in.basulabs.siesattendance.backend.util.CsvImporter;
import in.basulabs.siesattendance.backend.util.DatabaseBackup;
import in.basulabs.siesattendance.backend.util.LectureFilter;

/**
 * The single ViewModel behind every screen.
 * <p>
 * Shared across every fragment via {@code requireActivity()} scope, so the class filter chosen on
 * the students screen is still there when the user comes back from creating a batch, and so work
 * started on one screen can still report its result after that screen has gone.
 *
 * <h3>Threading</h3>
 * Nothing here touches the database on the main thread. Work goes to one of two pools:
 * {@code MainDatabase.DB_EXECUTOR} for ordinary queries and writes, and
 * {@link #FILE_TASK_EXECUTOR} for the whole-file operations that would otherwise occupy a query
 * thread for seconds at a time.
 * <p>
 * Results come back one of two ways. Anything a screen observes is Room-backed
 * {@code LiveData}, which updates itself when the underlying rows change. Anything a screen needs
 * once, at a moment of its choosing, goes through a method named {@code ...Sync} taking a
 * callback. Those callbacks fire on a background thread, so a fragment receiving one has to post
 * to the main thread itself before touching a view; the fragments do this with
 * {@code Ui.onMain(this, …)}, which posts only while the fragment is still attached because the
 * user may have navigated away in the meantime.
 *
 * <h3>The academic session</h3>
 * {@link #academicYear} is the session new work is filed under, resolved from the
 * {@code academic_years} table rather than assumed from the calendar. The students screen and the
 * report each keep a session of their own, so browsing last year does not quietly change where
 * this morning's attendance goes.
 *
 * <h3>One-shot results</h3>
 * Import, export, backup and restore report through {@code MutableLiveData} used as event
 * channels, each with a matching {@code clear...} method. The clear is not optional: without it
 * the value is redelivered on every configuration change and a rotation replays a dialog the user
 * has already dismissed.
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

	private final MutableLiveData<DatabaseBackup.Result> backupResult = new MutableLiveData<>();
	private final MutableLiveData<DatabaseBackup.Result> restoreResult = new MutableLiveData<>();

	/**
	 * Whole-file work runs here rather than on the shared database pool: backups, restores and
	 * the data export. Any one of them can occupy a thread for seconds (key stretching, then
	 * compressing the whole file; or walking every student and course), and the query pool is
	 * what keeps the rest of the UI responsive. Single-threaded because two concurrent restores
	 * would race for the same database file.
	 */
	private static final ExecutorService FILE_TASK_EXECUTOR =
			Executors.newSingleThreadExecutor();

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
	 * If today falls outside every defined session, during a summer break or before the next year
	 * has been set up, the most recent session is used rather than one being invented, so the app
	 * keeps showing real data instead of an empty year. Only a completely empty table causes a
	 * session to be created, seeded on the June default.
	 * <p>
	 * Also corrects the students screen and the report, both of which are seeded with a guess
	 * before the database has been read. Neither is touched once the user has chosen a session
	 * themselves, which is what the two {@code ChosenByUser} flags record.
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

	/** The session new work is filed under, as the starting calendar year. */
	public LiveData<Integer> getAcademicYear() {
		return academicYear;
	}

	/**
	 * The same value, read directly. Falls back to the calendar guess when the session has not
	 * been resolved yet, so a caller during startup gets a usable answer rather than a null.
	 */
	public int academicYearValue() {
		Integer value = academicYear.getValue();
		return value == null ? provisionalYear() : value;
	}

	/**
	 * The full session row, with the dates the report needs for its half-session presets. Null
	 * until {@link #resolveCurrentSession()} has finished its first pass.
	 */
	@Nullable
	public EntityAcademicYear currentSession() {
		return currentSession;
	}

	/**
	 * The session a moment in time falls in, which is the session anything about a lecture has
	 * to be read against.
	 * <p>
	 * {@link #academicYearValue()} is the session <em>new</em> work is filed under, and it is
	 * the wrong answer for a lecture that already exists: a lecture from a past session has to
	 * be looked at through that session's roll numbers and enrolments, not through this year's.
	 * Using the current value instead would rebuild a past lecture's roll from students who
	 * were never in the room.
	 * <p>
	 * Falls back to the current session when the timestamp lands in a gap between defined
	 * sessions, which is the same lecture {@code DaoAcademicYear.countOrphanLectures} exists to
	 * surface. Reads the database, so it must be called off the main thread.
	 */
	private int sessionYearFor(@NonNull ZonedDateTime when) {

		EntityAcademicYear session =
				db.daoAcademicYear().findContainingSync(when.toEpochSecond());

		return session == null ? academicYearValue() : session.academicYear;
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

	/**
	 * Creates or edits a session.
	 *
	 * @param creating true when this is a new session rather than an edit. The upsert
	 *                 underneath replaces on a matching key, so without this an "add" that
	 *                 named an existing year would silently rewrite that session's dates
	 *                 instead of being refused. Those dates are what every lecture's session
	 *                 is decided by, so overwriting them by accident moves attendance between
	 *                 sessions.
	 */
	public void saveAcademicYear(int year, @NonNull LocalDate start, @NonNull LocalDate end,
	                             boolean creating, @NonNull Runnable onDone) {

		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				if (!end.isAfter(start)) {
					toastMessage.postValue("The end date must be after the start date");
					return;
				}

				if (creating) {
					EntityAcademicYear clash = db.daoAcademicYear().getByIdSync(year);

					if (clash != null) {
						toastMessage.postValue("The " + clash.label()
								+ " session already exists. Edit it instead.");
						return;
					}
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

	/**
	 * What removing a session would strand, for the confirmation to quote back. The callback
	 * runs off the main thread.
	 */
	public void loadSessionDeletionImpact(@NonNull EntityAcademicYear year,
	                                      @NonNull java.util.function.Consumer<SessionImpact> cb) {

		MainDatabase.DB_EXECUTOR.execute(() -> cb.accept(db.daoAcademicYear()
				.deletionImpactSync(year.academicYear, year.startEpoch, year.endEpoch)));
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

	/**
	 * Messages from background work, shown by the activity rather than by whichever fragment
	 * started the work; see {@code ActivityHome.observeToasts}. Cleared once shown.
	 */
	public LiveData<String> getToastMessage() {
		return toastMessage;
	}

	public void clearToast() {
		toastMessage.setValue(null);
	}

	/** True while a long-running file operation is in flight, so screens can show a spinner. */
	public LiveData<Boolean> isBusy() {
		return busy;
	}

	public LiveData<CsvImporter.Result> getImportResult() {
		return importResult;
	}

	public void clearImportResult() {
		importResult.setValue(null);
	}

	// ── Attendance sheet export ──────────────────────────────────────────

	private final MutableLiveData<AttendanceExporter.Summary> exportResult =
			new MutableLiveData<>();

	public LiveData<AttendanceExporter.Summary> getExportResult() {
		return exportResult;
	}

	public void clearExportResult() {
		exportResult.setValue(null);
	}

	/**
	 * The session an export should cover.
	 * <p>
	 * The export screen is opened from the report, and the report has a session picker of its
	 * own precisely so a past year can be examined; taking the app-wide value instead would
	 * hand back a spreadsheet for a different year than the one on screen, with a file name
	 * agreeing with the data so that nothing contradicted it. A spreadsheet has to name one
	 * session, so "every session" on the report falls back to the current one.
	 */
	public int exportAcademicYear() {
		Integer reportYear = statsFilter().academicYear;
		return reportYear == null ? academicYearValue() : reportYear;
	}

	/**
	 * Writes the register for one session as a spreadsheet.
	 *
	 * @param academicYear     the session to cover; see {@link #exportAcademicYear()}
	 * @param thresholdPercent overall attendance below which a student is shaded as a
	 *                         defaulter, or {@link AttendanceExporter#NO_THRESHOLD} for none
	 * @param groups           extra sheets, each totalling only its own courses. Copied here
	 *                         because the caller's list belongs to a dialog that is about to
	 *                         close.
	 */
	public void exportAttendanceSheet(@NonNull Uri destination, int academicYear,
	                                  int thresholdPercent,
	                                  @NonNull List<CourseGroup> groups,
	                                  @NonNull ExportFilter filter) {

		busy.setValue(true);

		// Off the query pool: building the sheet walks every student and every course, and
		// should not sit in front of the screen's own queries.
		FILE_TASK_EXECUTOR.execute(() -> {
			try {
				AttendanceExporter.Summary summary = AttendanceExporter.export(
						getApplication(), destination, academicYear, thresholdPercent,
						new ArrayList<>(groups), filter);
				exportResult.postValue(summary);
			} catch (Exception e) {
				toastMessage.postValue("Export failed: " + e.getMessage());
			} finally {
				busy.postValue(false);
			}
		});
	}

	// ── Backup and restore ───────────────────────────────────────────────

	public LiveData<DatabaseBackup.Result> getBackupResult() {
		return backupResult;
	}

	public void clearBackupResult() {
		backupResult.setValue(null);
	}

	public LiveData<DatabaseBackup.Result> getRestoreResult() {
		return restoreResult;
	}

	public void clearRestoreResult() {
		restoreResult.setValue(null);
	}

	/**
	 * Writes the whole database to the chosen file.
	 *
	 * @param password {@code null} for a plain backup; otherwise the file is encrypted and
	 *                 this password is needed to restore it. Wiped by the backup code.
	 */
	public void exportBackup(@NonNull Uri destination, @Nullable char[] password) {

		busy.setValue(true);

		// Not on DB_EXECUTOR: a large backup holds its thread for a while, and that pool is
		// what every screen's queries go through. Key derivation alone is several hundred
		// milliseconds by design.
		FILE_TASK_EXECUTOR.execute(() -> {
			DatabaseBackup.Result result = DatabaseBackup.export(getApplication(), destination,
					password);
			busy.postValue(false);
			backupResult.postValue(result);
		});
	}

	/**
	 * Reads the header of a candidate file so the caller knows whether to ask for a password.
	 * Cheap: it touches only the first few dozen bytes.
	 */
	public void inspectBackup(@NonNull Uri source,
	                          @NonNull java.util.function.Consumer<DatabaseBackup.Info> cb) {

		FILE_TASK_EXECUTOR.execute(() -> {
			DatabaseBackup.Info info = DatabaseBackup.inspect(getApplication(), source);
			new Handler(Looper.getMainLooper()).post(() -> cb.accept(info));
		});
	}

	/**
	 * Replaces the database with the contents of the chosen file.
	 * <p>
	 * A successful restore leaves this ViewModel holding a closed database, so the observer is
	 * expected to restart the app rather than continue. Nothing here refreshes its own state
	 * on success for that reason.
	 */
	public void importBackup(@NonNull Uri source, @Nullable char[] password,
	                         @NonNull DatabaseBackup.Mode mode) {

		busy.setValue(true);

		FILE_TASK_EXECUTOR.execute(() -> {
			DatabaseBackup.Result result = DatabaseBackup.restore(getApplication(), source,
					password, mode);
			busy.postValue(false);
			restoreResult.postValue(result);
		});
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

		/**
		 * Narrows to one division of a class. Only ever set on the streams that have divisions,
		 * since no student on any other stream carries one to match.
		 */
		@Nullable
		public Division division;

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
					|| oeDept != null || division != null || batchID != null;
		}

		/**
		 * Clears every dimension and the search term, but leaves {@link #academicYear} alone:
		 * the session is which roll is being looked at, not a narrowing of it.
		 */
		public void clear() {
			year = null;
			stream = null;
			majorDept = null;
			minorDept = null;
			oeDept = null;
			division = null;
			batchID = null;
			query = "";
		}
	}

	private StudentFilter studentFilter;

	/** True once the user has picked a session on the students screen themselves. */
	private boolean studentYearChosenByUser;

	/**
	 * The students screen's filter, created on first use. Mutated in place by the screen, which
	 * then calls {@link #refreshStudents()}; there are no per-field setters.
	 */
	@NonNull
	public StudentFilter studentFilter() {
		if (studentFilter == null) {
			studentFilter = new StudentFilter(academicYearValue());
		}
		return studentFilter;
	}

	/** The result of the last {@link #refreshStudents()}. Not Room-backed, so it does not self-update. */
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

	/**
	 * Points the students screen at a different session. Marks the choice as the user's, so
	 * {@link #resolveCurrentSession()} will not override it later.
	 */
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

	public Division getFilterDivision() {
		return studentFilter().division;
	}

	/**
	 * Applies a full class filter such as SY / BSc / COMPUTER_SCIENCE / Div B. Any argument may
	 * be null to leave that dimension open, which for the division is also what every stream
	 * that has none is filtered by.
	 */
	public void setClassFilter(@Nullable Year year, @Nullable Stream stream,
	                           @Nullable Department dept, @Nullable Division division) {
		studentFilter().year = year;
		studentFilter().stream = stream;
		studentFilter().majorDept = dept;
		studentFilter().division = division;
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
		final Division div = f.division;
		final Long batch = f.batchID;
		final String q = f.query;

		MainDatabase.DB_EXECUTOR.execute(() -> filteredStudents.postValue(
				db.daoStudent().filterStudentsSync(acadYear, y, st, major, minor, oe, div,
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
	                       @Nullable Department oeDept, @Nullable Integer semester,
	                       @Nullable Division division, @NonNull Runnable onDone) {

		// New students land in the session the screen is showing, which is what someone
		// entering last year's late admission expects.
		final int acadYear = studentFilter().academicYear;

		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				db.daoStudent().upsertStudentWithYear(student, acadYear, rollNo, year, stream,
						majorDept, minorDept, oeDept, semester, division);
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

	// ── Attendance record import ─────────────────────────────────────────

	/**
	 * The plan built from the chosen file, held between reading it and writing it.
	 * <p>
	 * It lives here rather than in the fragment because it is the thing the user is being asked
	 * to approve: a rotation between the review screen and the confirmation must not quietly
	 * substitute a different plan, and the work that produced it takes long enough that redoing
	 * it on every configuration change would be visible.
	 */
	private final MutableLiveData<AttendanceRecordImporter.Plan> attendancePlan =
			new MutableLiveData<>();

	private final MutableLiveData<AttendanceRecordImporter.Result> attendanceImportResult =
			new MutableLiveData<>();

	public LiveData<AttendanceRecordImporter.Plan> getAttendancePlan() {
		return attendancePlan;
	}

	public void clearAttendancePlan() {
		attendancePlan.setValue(null);
	}

	public LiveData<AttendanceRecordImporter.Result> getAttendanceImportResult() {
		return attendanceImportResult;
	}

	public void clearAttendanceImportResult() {
		attendanceImportResult.setValue(null);
	}

	/**
	 * Reads an attendance sheet and works out what it would create, without writing anything.
	 * <p>
	 * Split from the write so the user sees the sessions, the batch chosen for each and what
	 * would be overwritten before any of it happens; {@link #commitAttendanceImport} is the
	 * other half.
	 */
	public void planAttendanceImport(@NonNull Uri uri) {

		busy.setValue(true);
		attendanceImportResult.setValue(null);

		// Off the query pool for the same reason the export is: resolving a term's worth of
		// columns walks the roll of every course and the membership of every batch.
		FILE_TASK_EXECUTOR.execute(() -> {
			try {
				attendancePlan.postValue(
						AttendanceRecordImporter.plan(getApplication(), uri));
			} catch (Exception e) {
				toastMessage.postValue("Could not read the file: " + e.getMessage());
			} finally {
				busy.postValue(false);
			}
		});
	}

	/**
	 * Writes the plan the user has approved.
	 * <p>
	 * The plan is taken from the LiveData rather than passed in, so what is written is
	 * necessarily what was reviewed. It is cleared once the write returns, since the counts it
	 * carries describe a file that has now been imported and the review screen has nothing left
	 * to offer about it.
	 */
	public void commitAttendanceImport() {

		AttendanceRecordImporter.Plan plan = attendancePlan.getValue();

		if (plan == null || !plan.canImport()) {
			toastMessage.setValue("There is nothing to import");
			return;
		}

		busy.setValue(true);

		FILE_TASK_EXECUTOR.execute(() -> {
			try {
				attendanceImportResult.postValue(
						AttendanceRecordImporter.commit(getApplication(), plan));
				attendancePlan.postValue(null);
			} catch (Exception e) {
				toastMessage.postValue("Could not import: " + e.getMessage());
			} finally {
				busy.postValue(false);
			}
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

	/**
	 * Every course on record, discontinued ones included.
	 * <p>
	 * This is for the screens that look backwards: the export and the report both have to
	 * cover a retired course's history, since the lectures filed under it really happened.
	 * Anything that is about to record new work wants {@link #loadActiveCoursesSync} instead.
	 */
	public void loadCoursesSync(@NonNull java.util.function.Consumer<List<EntityCourse>> cb) {
		MainDatabase.DB_EXECUTOR.execute(() -> cb.accept(db.daoCourse().getAllSync()));
	}

	/**
	 * The courses still being offered, for the pickers that create new lectures.
	 * <p>
	 * This is what makes {@link #discontinueCourse} mean anything: without it a retired course
	 * stays in the take-attendance dropdown and can go on collecting lectures, which is the
	 * whole thing marking it discontinued is supposed to stop.
	 */
	public void loadActiveCoursesSync(
			@NonNull java.util.function.Consumer<List<EntityCourse>> cb) {
		MainDatabase.DB_EXECUTOR.execute(() -> cb.accept(db.daoCourse().getActiveSync()));
	}

	/**
	 * The active courses, plus the one a lecture is already filed under even if it has since
	 * been discontinued.
	 * <p>
	 * The edit screen cannot simply offer the active list. A lecture filed under a course that
	 * was retired afterwards would find no match, leave the field blank, and refuse to save
	 * for want of a course the user never removed. Keeping it in the list shows the record as
	 * it stands while still steering any change towards a course that is still running.
	 *
	 * @param mustInclude the lecture's current course; ignored when it is already active
	 */
	public void loadCoursesForEdit(long mustInclude,
	                               @NonNull java.util.function.Consumer<List<EntityCourse>> cb) {

		MainDatabase.DB_EXECUTOR.execute(() -> {

			List<EntityCourse> courses = new ArrayList<>(db.daoCourse().getActiveSync());

			boolean present = false;
			for (EntityCourse course : courses) {
				if (course.courseID == mustInclude) {
					present = true;
					break;
				}
			}

			if (!present) {
				EntityCourse discontinued = db.daoCourse().getByIdSync(mustInclude);
				if (discontinued != null) {
					courses.add(discontinued);
					// getActiveSync orders by code; keep the appended row in that order
					// rather than leaving it stranded at the end of the dropdown.
					courses.sort((a, b) -> a.courseCode.compareToIgnoreCase(b.courseCode));
				}
			}

			cb.accept(courses);
		});
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
	 * What the lectures tab is showing. Held here rather than in the fragment so it survives the
	 * tab being swiped away and back.
	 * <p>
	 * The filter object is mutated in place by the screen and then handed back through
	 * {@link #refreshLectures()}, which is what re-runs the query. Mutable rather than replaced
	 * wholesale because the screen edits one dimension at a time and rebuilding it per keystroke
	 * would only move the same state somewhere less obvious.
	 */
	private final LectureFilter lectureFilter = new LectureFilter();

	private final MutableLiveData<LectureFilter> lectureFilterLive =
			new MutableLiveData<>(new LectureFilter());

	/**
	 * The live filter, for the screen to read and mutate. Every change has to be followed by
	 * {@link #refreshLectures()}; there are no per-field setters, in keeping with
	 * {@link #studentFilter()}.
	 */
	@NonNull
	public LectureFilter lectureFilter() {
		return lectureFilter;
	}

	/**
	 * Publishes the current filter, which re-queries {@link #getFilteredLectures()} through its
	 * switchMap. A snapshot is published rather than the live object so a query in flight cannot
	 * see an edit made while it runs.
	 */
	public void refreshLectures() {
		lectureFilterLive.setValue(lectureFilter.copy());
	}

	/** The published filter, which the bar labels itself from. */
	public LiveData<LectureFilter> getLectureFilterLive() {
		return lectureFilterLive;
	}

	/**
	 * Lectures matching the current filter, re-queried whenever it changes.
	 * <p>
	 * Room-backed, so the list also updates itself when a lecture is added or deleted underneath
	 * it. Nulls are passed straight through as "no condition" rather than being turned into
	 * sentinel epochs, which is what lets one query serve the unfiltered list too.
	 */
	public LiveData<List<LectureDetails>> getFilteredLectures() {
		return Transformations.switchMap(lectureFilterLive, filter -> {

			LectureFilter f = filter == null ? new LectureFilter() : filter;

			return db.daoLecture().getFilteredDetails(f.fromEpoch(), f.toEpoch(), f.courseID,
					f.professorID, f.department, f.batchID, f.courseType);
		});
	}

	/**
	 * Lectures held on one calendar day, at the device's zone. Used by the dashboard and the
	 * attendance picker.
	 */
	public LiveData<List<LectureDetails>> getLecturesOn(@NonNull LocalDate date) {
		ZoneId zone = ZoneId.systemDefault();
		long from = date.atStartOfDay(zone).toEpochSecond();
		long to = date.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1;
		return db.daoLecture().getDetailsBetween(from, to);
	}

	/**
	 * Today's lecture count for the dashboard. Computed against today as it was when this was
	 * called, so a screen left open across midnight keeps counting the previous day.
	 */
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
		loadRoll(courseID, batchID, false);
	}

	/**
	 * Loads the roll, optionally preserving what has already been marked.
	 * <p>
	 * Carrying marks over is what the course changing while the same group stays selected needs:
	 * the same people are in the room, so re-reading the roll must not throw away the work
	 * already done on it. Students new to this roll start present, as they would on a fresh
	 * sheet, and marks for students no longer on it are dropped because there is nowhere left to
	 * show them.
	 *
	 * @param keepMarks carry existing marks over to students who appear in the new roll too
	 */
	public void loadRoll(long courseID, @Nullable Long batchID, boolean keepMarks) {

		busy.setValue(true);
		final int acadYear = academicYearValue();

		// Read on the main thread before handing off, since the LiveData's value is only safe
		// to touch here.
		final Map<Long, Boolean> existing = new HashMap<>();

		if (keepMarks) {
			List<AttendanceRow> current = attendanceRows.getValue();
			if (current != null) {
				for (AttendanceRow row : current) {
					existing.put(row.studentID, row.present);
				}
			}
		}

		MainDatabase.DB_EXECUTOR.execute(() -> {
			List<StudentWithYear> students = batchID == null
					? db.daoStudent().getRollForCourseSync(courseID, acadYear)
					: db.daoStudent().getRollForBatchSync(batchID, acadYear);

			List<AttendanceRow> rows = new ArrayList<>();
			for (StudentWithYear student : students) {
				Boolean carried = existing.get(student.studentID);
				rows.add(carried == null
						? new AttendanceRow(student.studentID, student.name, student.rollNo)
						: new AttendanceRow(student.studentID, student.name, student.rollNo,
						carried));
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
								row.present));
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

	/**
	 * The sheet currently being marked. Held here rather than in the fragment so a partly marked
	 * roll survives a rotation or a trip to another screen.
	 */
	public LiveData<List<AttendanceRow>> getAttendanceRows() {
		return attendanceRows;
	}

	/** The lecture the sheet belongs to, for the header. Null while taking a new one. */
	public LectureDetails getCurrentLecture() {
		return currentLecture;
	}

	/**
	 * Loads the roll for one lecture. Any attendance already saved for it is pre-applied, so
	 * reopening a marked lecture shows the previous answers rather than a blank sheet.
	 * <p>
	 * The roll is read against the session the lecture itself falls in, not the session new
	 * work is being filed under. Those differ whenever a past year's lecture is opened, and
	 * taking the current one would list this year's students instead: the marks actually
	 * stored would have nobody to show them against, and saving the sheet would replace them
	 * wholesale.
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
					? db.daoStudent().getRollForCourseSync(details.courseID,
					sessionYearFor(details.startDateTime))
					: db.daoBatch().getStudentsInBatchSync(details.batchID);
			List<Long> presentIds = db.daoAttendance().getPresentIdsSync(lectureID);

			List<EntityAttendance> existing = db.daoAttendance().getForLectureSync(lectureID);
			List<Long> markedIds = new ArrayList<>();
			for (EntityAttendance record : existing) {
				markedIds.add(record.studentID);
			}

			List<AttendanceRow> rows = new ArrayList<>();
			for (StudentWithYear student : students) {
				// A student with no stored row joined the class after this lecture was
				// filed. There is nothing recorded either way, so they take the same
				// default as a fresh sheet.
				boolean present = !markedIds.contains(student.studentID)
						|| presentIds.contains(student.studentID);
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
	 * Writes the sheet.
	 * <p>
	 * Every row carries an answer by construction, present unless someone marked otherwise, so
	 * there is nothing to refuse here. An untouched sheet saves as a full house.
	 */
	public void saveAttendance(@NonNull Runnable onDone) {
		List<AttendanceRow> rows = attendanceRows.getValue();

		if (rows == null || currentLecture == null) {
			toastMessage.setValue("Nothing to save");
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

	/**
	 * One student as they were in one session.
	 * <p>
	 * The session is passed in rather than taken from the app-wide value, because the detail
	 * screen is reached from two places that mean different years — the students list, which
	 * has its own session, and a drill-down from the report, which has another. Reading the
	 * current session here while every action on the same screen read the session being
	 * browsed left the header blank for any student who is not enrolled in both.
	 */
	public LiveData<StudentWithYear> getStudent(long studentID, int academicYear) {
		return db.daoStudent().getOne(studentID, academicYear);
	}

	public LiveData<List<StudentCourseStat>> getStudentCourseBreakdown(
			long studentID, @NonNull BreakdownFilter filter) {

		return db.daoStudent().getCourseBreakdown(studentID, filter.fromEpoch(),
				filter.toEpoch(), filter.courseType);
	}

	/**
	 * The filter narrowing the breakdown on the student detail screen.
	 * <p>
	 * Held here so it survives a rotation and the round trip into the per-course dates and back,
	 * which is the whole point of setting one: a window chosen to look at a run of absences
	 * should still be in force when you come back from looking at them.
	 * <p>
	 * Reset when the screen opens on a different student. The alternative, carrying it across,
	 * would mean opening a second student's record and being shown a partial one for reasons
	 * belonging to the student before them.
	 */
	private BreakdownFilter breakdownFilter;

	private long breakdownStudentID = -1L;

	@NonNull
	public BreakdownFilter breakdownFilter(long studentID) {

		if (breakdownFilter == null || breakdownStudentID != studentID) {
			breakdownFilter = new BreakdownFilter();
			breakdownStudentID = studentID;
		}
		return breakdownFilter;
	}

	/**
	 * The lectures behind one course of that breakdown, each with the mark this student carries
	 * for it. Backs the drill-down that says which dates they were present and absent on.
	 */
	public LiveData<List<StudentLectureMark>> getStudentLectureMarks(long studentID,
	                                                                 long courseID,
	                                                                 @Nullable Long fromEpoch,
	                                                                 @Nullable Long toEpoch) {
		return db.daoStudent().getLectureMarks(studentID, courseID, fromEpoch, toEpoch);
	}

	public void loadStudentSync(long studentID, int academicYear,
	                            @NonNull java.util.function.Consumer<StudentWithYear> cb) {
		MainDatabase.DB_EXECUTOR.execute(() ->
				cb.accept(db.daoStudent().getOneSync(studentID, academicYear)));
	}

	/**
	 * Counts what deleting this student would cost, for the confirmation dialog. Read against
	 * the session the caller is showing, since that is the one a withdrawal would apply to.
	 */
	public void loadDeletionImpact(long studentID, int academicYear,
	                               @NonNull java.util.function.Consumer<DeletionImpact> cb) {
		MainDatabase.DB_EXECUTOR.execute(() ->
				cb.accept(db.daoStudent().deletionImpact(studentID, academicYear)));
	}

	/**
	 * Takes the student off one session's roster, keeping them and their attendance.
	 */
	public void withdrawStudentFromSession(long studentID, int academicYear,
	                                       @NonNull Runnable onDone) {

		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				db.daoStudent().withdrawFromSession(studentID, academicYear);
				toastMessage.postValue("Student removed from this session");
				refreshStudents();
				onDone.run();
			} catch (Exception e) {
				// The DAO refuses a withdrawal that would leave the student in no session at
				// all, and says why; that message is worth more than a generic failure.
				toastMessage.postValue(e.getMessage());
			}
		});
	}

	/** Removes the student and every record naming them. */
	public void deleteStudent(long studentID, @NonNull Runnable onDone) {

		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				db.daoStudent().deleteStudentCascade(studentID);
				toastMessage.postValue("Student deleted");
				refreshStudents();
				onDone.run();
			} catch (Exception e) {
				toastMessage.postValue("Could not delete: " + e.getMessage());
			}
		});
	}

	/**
	 * Applies an edit to a student's record for one session. The session is passed in so that
	 * the form writes back to the same year it read from, whichever screen opened it.
	 */
	public void updateStudent(@NonNull EntityStudent student, int academicYear,
	                          @NonNull String rollNo,
	                          @NonNull Year year, @NonNull Stream stream,
	                          @NonNull Department majorDept, @Nullable Department minorDept,
	                          @Nullable Department oeDept, @Nullable Integer semester,
	                          @Nullable Division division, @NonNull Runnable onDone) {

		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				db.daoStudent().updateStudentWithYear(student, academicYear, rollNo, year,
						stream, majorDept, minorDept, oeDept, semester, division);
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

	/**
	 * Copies a recorded lecture to a new date and time, marks and all.
	 * <p>
	 * For the case the take-attendance screen cannot cover: the same class met again, or the
	 * second hour of a double period was written up later, and calling the whole roll a second
	 * time is wasted effort when the room was substantially the same. The copy is a real
	 * lecture from the moment it is made, so it can be opened, corrected and saved like any
	 * other, and deleted if it turns out to be unwanted.
	 * <p>
	 * The course and batch come from the source and are not asked for again; only the clock
	 * moves. Changing the course afterwards is what the edit screen is for.
	 *
	 * @param onDone receives the new lecture's ID, or null if it could not be made
	 */
	public void duplicateLecture(long sourceLectureID, @NonNull LocalDate date,
	                             @NonNull LocalTime startTime, @NonNull LocalTime endTime,
	                             @NonNull java.util.function.Consumer<Long> onDone) {

		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				EntityLecture source = db.daoLecture().getByIdSync(sourceLectureID);

				if (source == null) {
					toastMessage.postValue("That lecture no longer exists");
					onDone.accept(null);
					return;
				}

				ZoneId zone = ZoneId.systemDefault();
				ZonedDateTime start = ZonedDateTime.of(date, startTime, zone);
				ZonedDateTime end = ZonedDateTime.of(date, endTime, zone);

				if (!end.isAfter(start)) {
					toastMessage.postValue("End time must be after the start time");
					onDone.accept(null);
					return;
				}

				if (db.daoLecture().findExisting(source.courseID, source.batchID, start, end)
						!= null) {
					toastMessage.postValue(
							"A lecture is already recorded for that course and time");
					onDone.accept(null);
					return;
				}

				// One transaction: a lecture that exists without its copied marks would look
				// like a session where nobody was recorded, which is not what was asked for.
				final long[] created = new long[1];

				db.runInTransaction(() -> {
					created[0] = db.daoLecture().insert(new EntityLecture(
							source.courseID, source.batchID, start, end, source.notes));
					db.daoAttendance().copyMarks(sourceLectureID, created[0]);
				});

				toastMessage.postValue("Attendance duplicated");
				onDone.accept(created[0]);

			} catch (Exception e) {
				toastMessage.postValue("Could not duplicate: " + e.getMessage());
				onDone.accept(null);
			}
		});
	}

	/** Loads a lecture so its details can be pre-filled into the edit form. */
	public void loadLectureForEdit(long lectureID,
	                               @NonNull java.util.function.Consumer<EntityLecture> cb) {
		MainDatabase.DB_EXECUTOR.execute(() ->
				cb.accept(db.daoLecture().getByIdSync(lectureID)));
	}

	/**
	 * How many students already marked on this lecture would not be on the roll if it were
	 * moved to {@code courseID} / {@code batchID} at {@code start}.
	 * <p>
	 * Asked before the change is committed, because moving a lecture to another course is the
	 * one edit that can leave marks stranded: the attendance rows stay valid as far as the
	 * database is concerned, but they record a student against a course they do not take, and
	 * no sheet will ever show them again.
	 * <p>
	 * {@code start} is the time the lecture is being moved to, not the one it currently holds,
	 * because that is what decides which session's roll it will be measured against. Counting
	 * against a different session from the one {@link #updateLecture} then acts on would put a
	 * number in front of the user that the edit does not honour.
	 */
	public void countStrandedMarks(long lectureID, long courseID, @Nullable Long batchID,
	                               @NonNull ZonedDateTime start,
	                               @NonNull java.util.function.Consumer<Integer> cb) {

		MainDatabase.DB_EXECUTOR.execute(() -> {
			List<Long> marked = db.daoAttendance().getMarkedIdsSync(lectureID);
			Set<Long> onRoll = rollIds(courseID, batchID, sessionYearFor(start));

			int stranded = 0;
			for (Long id : marked) {
				if (!onRoll.contains(id)) {
					stranded++;
				}
			}
			cb.accept(stranded);
		});
	}

	@NonNull
	private Set<Long> rollIds(long courseID, @Nullable Long batchID, int acadYear) {

		List<StudentWithYear> roll = batchID == null
				? db.daoStudent().getRollForCourseSync(courseID, acadYear)
				: db.daoStudent().getRollForBatchSync(batchID, acadYear);

		Set<Long> ids = new HashSet<>();
		for (StudentWithYear student : roll) {
			ids.add(student.studentID);
		}
		return ids;
	}

	/**
	 * Changes a lecture's course, batch, date, times or notes.
	 *
	 * @param dropStranded remove marks for students who are not on the new roll. Ignored when
	 *                     the course and batch are unchanged, since nothing can be stranded.
	 */
	public void updateLecture(long lectureID, long courseID, @Nullable Long batchID,
	                          @NonNull ZonedDateTime start, @NonNull ZonedDateTime end,
	                          @Nullable String notes, boolean dropStranded,
	                          @NonNull Runnable onDone) {

		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				if (!end.isAfter(start)) {
					toastMessage.postValue("End time must be after the start time");
					return;
				}

				// The unique index would reject this anyway, but as a constraint violation
				// rather than something the user can act on.
				Long clash = db.daoLecture().findExisting(courseID, batchID, start, end);

				if (clash != null && clash != lectureID) {
					toastMessage.postValue(
							"Another lecture is already recorded for that course and time");
					return;
				}

				db.daoLecture().updateLecture(lectureID, courseID, batchID, start, end, notes);

				if (dropStranded) {
					// Read against the session the lecture is being moved into, matching
					// what countStrandedMarks put in front of the user. Taking the current
					// session instead would find none of a past lecture's students on the
					// roll and delete every mark it has.
					Set<Long> onRoll = rollIds(courseID, batchID, sessionYearFor(start));

					// Room writes an empty list as NOT IN (), which SQLite accepts and
					// evaluates as true for every row, so leaving it to the statement would
					// clear the sheet by accident rather than by decision. Doing it
					// explicitly is what an empty roll actually means.
					if (onRoll.isEmpty()) {
						db.daoAttendance().clearForLecture(lectureID);
					} else {
						db.daoAttendance().deleteForLectureExcept(lectureID,
								new ArrayList<>(onRoll));
					}
				}

				toastMessage.postValue("Lecture updated");
				onDone.run();
			} catch (Exception e) {
				toastMessage.postValue("Could not update: " + e.getMessage());
			}
		});
	}

	/**
	 * Wipes the marks for a lecture but keeps the lecture itself.
	 * <p>
	 * The lecture stays on the timetable as an unmarked session, which is the honest state
	 * when a sheet was filled in wrongly: the class did happen, and the record of who was
	 * there is simply gone until it is taken again.
	 */
	public void clearAttendanceForLecture(long lectureID, @NonNull Runnable onDone) {
		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				db.daoAttendance().clearForLecture(lectureID);
				toastMessage.postValue("Attendance record deleted");
				onDone.run();
			} catch (Exception e) {
				toastMessage.postValue("Could not delete: " + e.getMessage());
			}
		});
	}

	/**
	 * Removes a lecture and its attendance together. Unlike
	 * {@link #clearAttendanceForLecture(long, Runnable)}, this treats the session as never having
	 * happened, so it is the wrong choice for a sheet that was simply filled in wrongly.
	 */
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

	/** Reads one lecture with its course and batch names. The callback runs off the main thread. */
	public void loadLectureSync(long lectureID,
	                            @NonNull java.util.function.Consumer<LectureDetails> cb) {
		MainDatabase.DB_EXECUTOR.execute(() ->
				cb.accept(db.daoLecture().getDetailsByIdSync(lectureID)));
	}

	// ── Batch edit and delete ────────────────────────────────────────────

	/** Reads one batch. The callback runs off the main thread. */
	public void loadBatchSync(long batchID,
	                          @NonNull java.util.function.Consumer<EntityBatchDesc> cb) {
		MainDatabase.DB_EXECUTOR.execute(() -> cb.accept(db.daoBatch().getByIdSync(batchID)));
	}

	/**
	 * Students preselected for a batch about to be created, handed over by whichever screen
	 * started the flow. Consumed once so a later visit starts empty.
	 */
	private List<Long> batchSeed;

	/**
	 * Preselects students for the next batch to be created, or clears the seed when passed null.
	 * The batch list clears it before opening the editor for a new batch, so a seed left by an
	 * earlier flow cannot leak into an unrelated one.
	 */
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
					f.division, f.batchID, f.query)) {
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

	/**
	 * Removes a batch, its membership and its course links. Refused while any lecture still
	 * points at it, in which case the reason is reported as a message rather than thrown.
	 */
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
	 * Every batch in the current academic year.
	 * <p>
	 * The course is not consulted. A batch is a named group of students for a session, not a
	 * property of a course, and filtering the list by the selected course previously made
	 * batches disappear for minor and open-elective courses whose roster matching failed.
	 */
	public void loadBatchesForSession(
			@NonNull java.util.function.Consumer<List<EntityBatchDesc>> cb) {
		final int acadYear = academicYearValue();
		MainDatabase.DB_EXECUTOR.execute(() ->
				cb.accept(db.daoBatch().getAllForYearSync(acadYear)));
	}

	/**
	 * The current session's batches, plus the one a lecture is already filed under even if it
	 * belongs to an earlier session.
	 * <p>
	 * Batches do not carry forward, so a lecture from a past year names a batch the current
	 * session's list does not contain. Without it the edit form found no match, fell back to
	 * the first entry and showed "Whole class" for a lecture that has a batch — telling the
	 * user something untrue about the record they are editing.
	 *
	 * @param mustInclude the lecture's current batch, or null for a whole-class lecture
	 */
	public void loadBatchesForEdit(@Nullable Long mustInclude,
	                               @NonNull java.util.function.Consumer<List<EntityBatchDesc>> cb) {

		final int acadYear = academicYearValue();

		MainDatabase.DB_EXECUTOR.execute(() -> {

			List<EntityBatchDesc> batches =
					new ArrayList<>(db.daoBatch().getAllForYearSync(acadYear));

			if (mustInclude != null) {

				boolean present = false;
				for (EntityBatchDesc batch : batches) {
					if (batch.batchID == mustInclude) {
						present = true;
						break;
					}
				}

				if (!present) {
					EntityBatchDesc older = db.daoBatch().getByIdSync(mustInclude);
					if (older != null) {
						batches.add(older);
						batches.sort((a, b) -> a.batchName.compareToIgnoreCase(b.batchName));
					}
				}
			}

			cb.accept(batches);
		});
	}

	/**
	 * A batch's members. Note that the underlying query does not select the semester, so it
	 * arrives null on every row; see {@code DaoBatch.getStudentsInBatch}.
	 */
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
	 * The live filter object, mutated in place by the report screen and re-run through
	 * {@link #runStatsQuery()}, so the screen never has to reassemble it from scratch.
	 * <p>
	 * The academic year is seeded once and then left alone: overwriting it on every access, as an
	 * earlier version did, would have made it impossible to select a past session or to compare
	 * years.
	 */
	@NonNull
	public StatsFilter statsFilter() {
		if (statsFilter == null) {
			statsFilter = new StatsFilter(academicYearValue());
		}
		return statsFilter;
	}

	/** The grouped report rows from the last run. */
	public LiveData<List<StatRow>> getStatRows() {
		return statRows;
	}

	/** The headline figures for the same run, computed over the same rows. */
	public LiveData<StatsSummary> getStatsSummary() {
		return statsSummary;
	}

	/**
	 * How many rows fall below the filter's threshold. Tallied in Java from the rows already in
	 * hand rather than by a third query; see the note in {@code StatsQueryBuilder}.
	 */
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

	/**
	 * Points the report at a session, or at all of them when null, which is what a year-on-year
	 * comparison needs. Marks the choice as the user's so it is not overwritten later.
	 */
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
