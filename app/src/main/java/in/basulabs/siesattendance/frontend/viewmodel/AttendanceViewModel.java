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
import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.EntityAttendance;
import in.basulabs.siesattendance.backend.AccountKind;
import in.basulabs.siesattendance.backend.EntityBatchDesc;
import in.basulabs.siesattendance.backend.EntityCourse;
import in.basulabs.siesattendance.backend.EntityLecture;
import in.basulabs.siesattendance.backend.EntityProfessor;
import in.basulabs.siesattendance.backend.EntityStudent;
import in.basulabs.siesattendance.backend.EntityStudentYear;
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
import in.basulabs.siesattendance.backend.IdGenerator;
import in.basulabs.siesattendance.backend.repo.AttendanceRepository;
import in.basulabs.siesattendance.backend.repo.RoomAttendanceRepository;
import in.basulabs.siesattendance.backend.EntitySyncState;
import in.basulabs.siesattendance.backend.sync.DeviceIdentity;
import in.basulabs.siesattendance.backend.sync.SignOut;
import in.basulabs.siesattendance.frontend.util.Permissions;
import in.basulabs.siesattendance.backend.sync.SyncClient;
import in.basulabs.siesattendance.backend.sync.SyncScheduler;
import in.basulabs.siesattendance.backend.sync.SyncSignIn;
import in.basulabs.siesattendance.backend.util.CsvImporter;
import in.basulabs.siesattendance.backend.util.LectureFilter;
import in.basulabs.siesattendance.backend.util.MarkSheetExporter;

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
 * Import and export report through {@code MutableLiveData} used as event
 * channels, each with a matching {@code clear...} method. The clear is not optional: without it
 * the value is redelivered on every configuration change and a rotation replays a dialog the user
 * has already dismissed.
 */
public class AttendanceViewModel extends AndroidViewModel {

	/**
	 * Everything the screens read and write, behind one interface.
	 * <p>
	 * Constructed here rather than injected because the framework constructs this class. When
	 * the data moves to the server, this one line is what changes: a different implementation of
	 * {@link AttendanceRepository}, with an outbox behind it, and nothing else in this file or in
	 * any fragment has to know.
	 */
	private final AttendanceRepository repository;

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

	/**
	 * Whole-file work runs here rather than on the shared database pool: the data export can
	 * occupy a thread for seconds walking every student and course, and the query pool is what
	 * keeps the rest of the UI responsive.
	 */
	private static final ExecutorService FILE_TASK_EXECUTOR =
			Executors.newSingleThreadExecutor();

	// Attendance sheet being edited
	private final MutableLiveData<List<AttendanceRow>> attendanceRows = new MutableLiveData<>();
	private LectureDetails currentLecture;

	public AttendanceViewModel(@NonNull Application application) {
		super(application);
		repository = new RoomAttendanceRepository(MainDatabase.getInstance(application));
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
			EntityAcademicYear session = repository.academicYearContainingSync(now);

			if (session == null) {
				session = repository.latestAcademicYearSync();
			}

			if (session == null) {
				session = EntityAcademicYear.defaultFor(provisionalYear());

				// Saved only on a phone with no server, which has nobody else to set up its
				// calendar. With a server, sessions are an administrator's to add and arrive by
				// the pull; a phone that invented one here -- an empty database after signing
				// out, or before the first pull -- would push it under whoever signs in next.
				// So the default is used, and kept in memory, until the real ones arrive.
				if (!new SyncClient(getApplication()).isConfigured()) {
					repository.upsertAcademicYear(session);
				} else {
					awaitRealSessions();
				}
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
				repository.academicYearContainingSync(when.toEpochSecond());

		return session == null ? academicYearValue() : session.academicYear;
	}

	// ── Academic sessions ────────────────────────────────────────────────

	public LiveData<List<EntityAcademicYear>> getAcademicYears() {
		return repository.observeAcademicYears();
	}

	public void loadAcademicYearsSync(
			@NonNull java.util.function.Consumer<List<EntityAcademicYear>> cb) {
		MainDatabase.DB_EXECUTOR.execute(() -> cb.accept(repository.academicYearsSync()));
	}

	/**
	 * Number of lectures that fall outside every defined session. Anything above zero means
	 * those lectures are invisible to every report, so the screens surface it as a warning
	 * rather than letting the totals quietly under-count.
	 */
	public LiveData<Integer> getOrphanLectureCount() {
		return repository.observeOrphanLectureCount();
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

		// Asked here as well as on the screen, so that no other way in can add a session.
		if (!may(creating ? Permissions.Capability.NEW_SESSION
				: Permissions.Capability.SESSIONS)) {
			sayNotPermitted();
			return;
		}

		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				if (!end.isAfter(start)) {
					toastMessage.postValue("The end date must be after the start date");
					return;
				}

				if (creating) {
					EntityAcademicYear clash = repository.academicYearSync(year);

					if (clash != null) {
						toastMessage.postValue("The " + clash.label()
								+ " session already exists. Edit it instead.");
						return;
					}
				}

				// Overlapping sessions would make a lecture's session ambiguous, and the join
				// would silently pick one, so the clash is refused up front.
				for (EntityAcademicYear existing : repository.academicYearsSync()) {
					if (existing.academicYear == year) {
						continue;
					}
					if (!start.isAfter(existing.endDate) && !end.isBefore(existing.startDate)) {
						toastMessage.postValue("These dates overlap the "
								+ existing.label() + " session");
						return;
					}
				}

				repository.upsertAcademicYear(EntityAcademicYear.of(year, start, end));
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

		MainDatabase.DB_EXECUTOR.execute(() -> cb.accept(repository.sessionDeletionImpactSync(
				year.academicYear, year.startEpoch, year.endEpoch)));
	}

	public void deleteAcademicYear(@NonNull EntityAcademicYear year, @NonNull Runnable onDone) {
		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				repository.deleteAcademicYear(year);
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

	// ── Syncing with the server ──────────────────────────────────────────

	/**
	 * Network work, on a thread of its own.
	 * <p>
	 * Not the database pool, which would mean a sign-in with no signal occupying a query thread
	 * for thirty seconds while the screens wait; and not the file pool, which is single-threaded
	 * so that two restores cannot race. Signing in is rare and user-initiated, and the draining
	 * is WorkManager's business rather than this class's.
	 */
	private static final ExecutorService NETWORK_TASK_EXECUTOR =
			Executors.newSingleThreadExecutor();

	/** How many local changes are waiting to reach the server. */
	public LiveData<Integer> getPendingChanges() {
		return repository.observePendingChangeCount();
	}

	/** Whether this device holds a session at all. Not LiveData: it changes only here. */
	public boolean isSignedIn() {
		return DeviceIdentity.hasToken(getApplication());
	}

	/**
	 * Whether this build has a server at all.
	 * <p>
	 * Empty in a release build until somebody configures one, and what
	 * {@code SignInGuard} consults before insisting on a session: there is no asking somebody to
	 * sign in to nowhere.
	 */
	public boolean hasServer() {
		return new SyncClient(getApplication()).isConfigured();
	}

	/**
	 * What the server would not accept, newest first, until somebody dismisses it.
	 * <p>
	 * Separate from {@link #lastSyncError()}, which is about the connection and is true only
	 * until the next successful run. A refusal is about something somebody did and stays until
	 * they have read it.
	 */
	@NonNull
	public java.util.List<String> refusals() {
		return DeviceIdentity.refusals(getApplication());
	}

	public void dismissRefusals() {
		DeviceIdentity.clearRefusals(getApplication());
	}

	/** The last thing that went wrong while syncing, or null. */
	@Nullable
	public String lastSyncError() {
		return DeviceIdentity.lastSyncError(getApplication());
	}

	/** The node this device issues keys under, or 0 before it has been told one. */
	public int deviceNode() {
		return IdGenerator.nodeID();
	}

	/** Who is signed in, as the server named them, or null. */
	@Nullable
	public String signedInAs() {
		return DeviceIdentity.displayName(getApplication());
	}

	/** The device's own sync bookkeeping, observed: the watermark and the two timestamps. */
	public LiveData<EntitySyncState> getSyncState() {
		return repository.observeSyncState();
	}

	/** Whether a sync is running at this moment. */
	public LiveData<Boolean> getSyncRunning() {
		return SyncScheduler.observeRunning(getApplication());
	}

	/** How many rows the last run sent, and how many it brought down. */
	public int lastSent() {
		return DeviceIdentity.lastSent(getApplication());
	}

	public int lastReceived() {
		return DeviceIdentity.lastReceived(getApplication());
	}

	/**
	 * Signs in, registers this device if the server has not seen it before, and asks for a
	 * sync.
	 * <p>
	 * The password array is wiped by {@code SyncSignIn}, however this ends.
	 *
	 * @param discardUnsent whether changes another account left unsent may be deleted
	 * @param onUnsent      run on a background thread instead of {@code onDone} when they may
	 *                      not, with how many there are and who made them, if known. Nothing
	 *                      has changed; the screen asks, and signs in again with
	 *                      {@code discardUnsent} set once somebody has said yes
	 * @param onDone        run on a background thread with the display name on success, or
	 *                      null on failure. What to show the user goes to
	 *                      {@link #getToastMessage()}, which the activity shows wherever the
	 *                      user has got to by then — the same channel every other background
	 *                      result in this app reports through
	 */
	public void signIn(@NonNull String email, @NonNull SyncSignIn.Door door,
	                   @NonNull char[] password, boolean discardUnsent,
	                   @NonNull java.util.function.BiConsumer<Integer, String> onUnsent,
	                   @NonNull java.util.function.Consumer<String> onDone) {

		busy.setValue(true);

		NETWORK_TASK_EXECUTOR.execute(() -> {
			try {
				SyncSignIn.Session session = SyncSignIn.signIn(getApplication(), email, door,
						password, discardUnsent);

				if (session.somebodyElse()) {
					forgetTheLastAccount();
				}

				// What the server said about the password, so the screen can offer the one
				// thing that works instead of leaving somebody to discover it by syncing and
				// failing. Written on every sign-in, false included: an account that no longer
				// has to rotate must not keep being told it does.
				DeviceIdentity.recordPasswordChangeRequired(getApplication(),
						session.mustRotate());

				// What the screens consult before offering to change a student, a professor
				// or a course. Written on every sign-in, including null, so a phone passed
				// from a professor to a class representative does not keep the first one's
				// permissions.
				DeviceIdentity.recordAccountKind(getApplication(), session.kind());

				// So the screen can say who is signed in. It used to say "device 3", which
				// answered a question nobody asks.
				DeviceIdentity.recordDisplayName(getApplication(), session.displayName());

				// The previous account's appointments go first, whatever happens next: a
				// phone passed from one CR to another must not show the first one's courses.
				DeviceIdentity.recordRepresents(getApplication(), null);
				DeviceIdentity.recordRoles(getApplication(), null);
				DeviceIdentity.recordImportDepartments(getApplication(), null);

				// What the server will let this account change, and which courses a class
				// representative may mark. Asked here rather than guessed from the account
				// kind: a professor who heads a department keeps the roll and one who does not
				// cannot, and only the server knows which. A failure leaves the roll answer
				// alone and the appointments unknown, which shows a student no course until
				// the next sync asks again.
				try {
					DeviceIdentity.recordMe(getApplication(),
							new SyncClient(getApplication()).me(
									DeviceIdentity.mayKeepARoll(getApplication())));

				} catch (java.io.IOException couldNotAsk) {
					// Signing in worked, so this is a flaky moment rather than a state.
				}

				// The node has to reach IdGenerator before the next key is issued, and
				// SyncSignIn has already applied it; this only tells the user.
				toastMessage.postValue(session.mustRotate()
						? "Signed in. This account's password has to be changed before "
								+ "anything else will work."
						: "Signed in as " + session.displayName() + ", device "
								+ session.node());

				SyncScheduler.requestSync(getApplication());
				onDone.accept(session.displayName());

			} catch (SyncSignIn.UnsentFromSomebodyElse unsent) {
				onUnsent.accept(unsent.unsent(), unsent.previous());

			} catch (java.io.IOException e) {
				// The server's own sentence where there is one: every refusal to sign in reads
				// identically by design, and inventing a different one here would undo that.
				toastMessage.postValue(e.getMessage() == null
						? "Could not sign in" : e.getMessage());
				onDone.accept(null);

			} finally {
				busy.postValue(false);
			}
		});
	}

	/**
	 * Ends the session, on the server as well as here, and empties the phone.
	 * <p>
	 * What is waiting to be sent goes first. If any of it cannot be sent -- no connection, or
	 * the server refusing it -- nothing is cleared and {@code onUnsent} is told how many
	 * changes would be lost; the screen asks, and calls again with {@code discardUnsent} set
	 * once somebody has said yes. See {@code SignOut}.
	 */
	public void signOut(boolean discardUnsent,
	                    @NonNull java.util.function.IntConsumer onUnsent,
	                    @NonNull Runnable onDone) {

		NETWORK_TASK_EXECUTOR.execute(() -> {

			if (!discardUnsent) {
				int unsent = SignOut.sendWhatIsWaiting(getApplication());

				if (unsent > 0) {
					onUnsent.accept(unsent);
					return;
				}
			}

			SignOut.forgetEverything(getApplication());
			forgetTheLastAccount();
			toastMessage.postValue("Signed out");
			onDone.run();
		});
	}

	/**
	 * Lets go of everything this ViewModel kept from the account whose data has just been
	 * deleted.
	 * <p>
	 * It outlives a sign-out, because it belongs to the activity, and much of what it holds is
	 * not watching the database: the students list and the report are the results of their
	 * last query, and the filters name courses, batches and sessions that were the last
	 * account's. Left alone, the next account to sign in was shown the previous one's students
	 * until something happened to query again. Any thread.
	 */
	private void forgetTheLastAccount() {

		new Handler(Looper.getMainLooper()).post(() -> {

			studentFilter = null;
			studentYearChosenByUser = false;
			filteredStudents.setValue(new ArrayList<>());

			lectureFilter.clearPeriod();
			lectureFilter.courseID = null;
			lectureFilter.professorID = null;
			lectureFilter.department = null;
			lectureFilter.batchID = null;
			lectureFilter.courseType = null;
			refreshLectures();

			statsHandler.removeCallbacks(statsRunnable);
			statsFilter = null;
			statsYearChosenByUser = false;
			statRows.setValue(new ArrayList<>());
			statsSummary.setValue(null);
			statsBelowCount.setValue(0);

			breakdownFilter = null;
			breakdownStudentID = -1L;

			clearRoll();
			importResult.setValue(null);
			attendancePlan.setValue(null);
			attendanceImportResult.setValue(null);
			exportResult.setValue(null);

			// The session too: the one this phone was using came from the deleted rows.
			stopAwaitingSessions();
			resolveCurrentSession();
		});
	}

	/**
	 * Asks for a sync now, rather than waiting for the hourly one.
	 * <p>
	 * Both directions, as every sync is: the worker drains the outbox and then pulls whatever
	 * the server has had since this device's watermark. There is no separate "fetch" and no
	 * separate "send everything again" — the queue is filled by triggers on every write, so a
	 * row that has not reached the server is in it, and asking twice cannot help.
	 *
	 * @return false when there is no session to sync with, and the person has been told to
	 * sign in again instead
	 */
	public boolean syncNow() {

		if (hasServer() && !isSignedIn()) {
			toastMessage.setValue(getApplication().getString(R.string.message_sign_in_again));
			return false;
		}

		SyncScheduler.requestSync(getApplication());
		toastMessage.setValue("Looking for changes, both ways");
		return true;
	}

	/**
	 * Whether the signed-in account may change that kind of record.
	 * <p>
	 * The screens ask this rather than reading preferences themselves, the same way they ask
	 * {@link #isSignedIn()}: a fragment that reached for the stored kind would be a fragment
	 * with its own copy of the rule. {@code Permissions} is the rule and says what it is not.
	 */
	public boolean may(@NonNull Permissions.Capability what) {
		return Permissions.may(DeviceIdentity.accountKind(getApplication()),
				DeviceIdentity.mayKeepARoll(getApplication()),
				DeviceIdentity.roles(getApplication()), what);
	}

	/** Why a control is missing, for a screen that has to say something. */
	public void sayNotPermitted() {
		toastMessage.setValue(getApplication().getString(R.string.not_permitted_records));
	}

	/** Whether the server has said this account's password must be changed before anything. */
	public boolean passwordChangeRequired() {
		return DeviceIdentity.passwordChangeRequired(getApplication());
	}

	/**
	 * Changes the password of the account this device is signed in as.
	 * <p>
	 * Both arrays are wiped by {@code SyncClient}, however this ends. The form's own text
	 * cannot be — a {@code String} is not clearable — which is the limit of what a dialog can
	 * do and the reason nothing keeps one afterwards.
	 * <p>
	 * On success the session survives: the server revokes every <em>other</em> sign-in and
	 * keeps this one, so the phone carries on syncing with the token it already has. That is
	 * also why a sync is asked for straight afterwards — if the account was under
	 * {@code must_rotate}, every endpoint but this one has been refusing until now, and there
	 * may be a term of attendance waiting behind that refusal.
	 *
	 * @param onDone run on a background thread: true when the password was changed. What to
	 *               show goes to {@link #getToastMessage()}, like every other background
	 *               result here
	 */
	public void changePassword(@NonNull char[] current, @NonNull char[] next,
	                           @NonNull java.util.function.Consumer<Boolean> onDone) {

		busy.setValue(true);

		NETWORK_TASK_EXECUTOR.execute(() -> {
			try {
				int revoked = new SyncClient(getApplication()).changePassword(current, next);

				DeviceIdentity.recordPasswordChangeRequired(getApplication(), false);
				DeviceIdentity.recordSyncError(getApplication(), null);
				SyncScheduler.requestSync(getApplication());

				toastMessage.postValue(revoked == 0
						? "Password changed"
						: "Password changed. " + revoked + " other sign-in"
								+ (revoked == 1 ? "" : "s") + " ended.");
				onDone.accept(true);

			} catch (java.io.IOException e) {
				// The server's own sentence: "That is not the current password for this
				// account", "A password must be at least 12 characters long". Inventing one
				// here would say less and risk saying something untrue.
				toastMessage.postValue(e.getMessage() == null
						? "Could not change the password" : e.getMessage());
				onDone.accept(false);

			} finally {
				busy.postValue(false);
			}
		});
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

	// ── One lecture's or one day's marks ─────────────────────────────────

	/**
	 * The export chosen in the options dialog, held while the system's save dialog is open.
	 * <p>
	 * Held here rather than on the screen because the save dialog is another activity: rotating
	 * the device while it is up rebuilds the screen, and a choice kept on it would be gone by
	 * the time the file comes back. The ViewModel outlives that.
	 */
	@Nullable
	private MarkSheetExporter.Request pendingMarkExport;

	public void setPendingMarkExport(@NonNull MarkSheetExporter.Request request) {
		pendingMarkExport = request;
	}

	/** The save dialog was cancelled. */
	public void clearPendingMarkExport() {
		pendingMarkExport = null;
	}

	/**
	 * Writes the pending export to the file the user just chose, and says what went into it.
	 * <p>
	 * Nothing pending means the app was killed while the save dialog was open, and the
	 * choice went with it. The file has been created by then and is left empty; saying so is
	 * better than quietly leaving an empty file that looks like an export of nothing.
	 */
	public void runPendingMarkExport(@NonNull Uri destination) {

		MarkSheetExporter.Request request = pendingMarkExport;
		pendingMarkExport = null;

		if (request == null) {
			toastMessage.setValue(getApplication().getString(R.string.error_marks_export_lost));
			return;
		}

		busy.setValue(true);

		FILE_TASK_EXECUTOR.execute(() -> {
			try {
				MarkSheetExporter.Summary summary =
						MarkSheetExporter.export(getApplication(), destination, request);
				toastMessage.postValue(describeMarkExport(summary));
			} catch (Exception e) {
				toastMessage.postValue("Export failed: " + e.getMessage());
			} finally {
				busy.postValue(false);
			}
		});
	}

	@NonNull
	private String describeMarkExport(@NonNull MarkSheetExporter.Summary summary) {

		android.content.res.Resources res = getApplication().getResources();

		String message = res.getString(R.string.message_marks_exported,
				res.getQuantityString(R.plurals.count_export_students,
						summary.students, summary.students),
				res.getQuantityString(R.plurals.count_export_lectures,
						summary.lectures, summary.lectures),
				res.getQuantityString(R.plurals.count_export_absences,
						summary.absences, summary.absences));

		if (summary.unmarked > 0) {
			message += " " + res.getQuantityString(R.plurals.message_unmarked_left_out,
					summary.unmarked, summary.unmarked);
		}
		return message;
	}

	/** What the lecture screen needs to know before offering to export its sheet. */
	public static final class LectureExportCheck {

		/** Null when the lecture has gone. */
		@Nullable
		public final LectureDetails lecture;

		/**
		 * True when a mark on screen differs from the one saved. The export is made from the
		 * saved sheet, so those changes would not be in it.
		 */
		public final boolean unsavedChanges;

		LectureExportCheck(@Nullable LectureDetails lecture, boolean unsavedChanges) {
			this.lecture = lecture;
			this.unsavedChanges = unsavedChanges;
		}
	}

	/**
	 * Reads the lecture fresh and compares its saved marks with the sheet on screen.
	 * <p>
	 * Only a student who has a saved mark can differ. One on screen with none saved joined the
	 * class after the lecture was filed and is showing the fresh-sheet default; that is not an
	 * edit, and warning about it would warn on every lecture a new student ever joined.
	 * <p>
	 * The sheet is snapshotted here, on the main thread, since that is where it is edited.
	 */
	public void checkLectureExport(long lectureID,
	                               @NonNull java.util.function.Consumer<LectureExportCheck> cb) {

		Map<Long, Boolean> onScreen = new HashMap<>();
		List<AttendanceRow> rows = attendanceRows.getValue();

		// The rows belong to whichever sheet was loaded last, which is this one on the lecture
		// screen, but a stale list from another lecture must not be compared against this one.
		if (rows != null && currentLecture != null && currentLecture.lectureID == lectureID) {
			for (AttendanceRow row : rows) {
				onScreen.put(row.studentID, row.present);
			}
		}

		MainDatabase.DB_EXECUTOR.execute(() -> {

			LectureDetails lecture = repository.lectureDetailsSync(lectureID);
			boolean unsaved = false;

			for (EntityAttendance stored : repository.attendanceForLectureSync(lectureID)) {
				Boolean shown = onScreen.get(stored.studentID);
				if (shown != null && shown != stored.present) {
					unsaved = true;
					break;
				}
			}

			cb.accept(new LectureExportCheck(lecture, unsaved));
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

		/**
		 * Whether to list students who have left the session as well. Off by default: once a
		 * student has left they come off the list, and this is how to find them again.
		 */
		public boolean includeLeft;

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
				repository::observeClassesForYear);
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
		final boolean left = f.includeLeft;

		MainDatabase.DB_EXECUTOR.execute(() -> filteredStudents.postValue(
				repository.filterStudentsSync(acadYear, y, st, major, minor, oe, div,
						batch, q, left)));
	}

	/**
	 * Batches belonging to the session the students screen is showing, for its batch filter.
	 */
	public void loadBatchesForStudentFilter(
			@NonNull java.util.function.Consumer<List<EntityBatchDesc>> cb) {
		final int acadYear = studentFilter().academicYear;
		MainDatabase.DB_EXECUTOR.execute(() ->
				cb.accept(visibleBatches(repository.batchesForYearSync(acadYear))));
	}

	public void addStudent(@NonNull EntityStudent student, @NonNull String rollNo,
	                       @NonNull Year year, @NonNull Stream stream,
	                       @NonNull Department majorDept, @Nullable Department minorDept,
	                       @Nullable Department oeDept, @Nullable Integer semester,
	                       @Nullable Division division, @Nullable LocalDate joinDate,
	                       @Nullable LocalDate leaveDate, @NonNull Runnable onDone) {

		// New students land in the session the screen is showing, which is what someone
		// entering last year's late admission expects.
		final int acadYear = studentFilter().academicYear;

		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				repository.upsertStudentWithYear(student, acadYear, rollNo, year, stream,
						majorDept, minorDept, oeDept, semester, division, joinDate, leaveDate);
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

		if (!may(Permissions.Capability.IMPORT_STUDENTS)) {
			sayNotPermitted();
			return;
		}

		// Which departments the rows may be in: any, for the office and an administrator and
		// for a phone with no server; a professor's own and the ones they head otherwise.
		Set<String> allowed = null;

		if (DeviceIdentity.accountKind(getApplication()) != null) {

			Set<String> departments = DeviceIdentity.importDepartments(getApplication());

			if (departments == null) {
				// Never recorded: a sign-in from before this build. Guessing would either
				// refuse a whole roster or let rows through that the server will not take.
				toastMessage.setValue(getApplication().getString(
						R.string.message_sync_before_import));
				return;
			}

			if (!departments.contains(SyncClient.ANY_DEPARTMENT)) {
				allowed = departments;
			}
		}

		busy.setValue(true);
		final int acadYear = studentFilter().academicYear;
		final Set<String> departments = allowed;

		MainDatabase.DB_EXECUTOR.execute(() -> {
			CsvImporter.Result result =
					CsvImporter.importStudents(getApplication(), uri, acadYear, departments);
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
		return repository.observeProfessors();
	}

	public void addProfessor(@NonNull EntityProfessor professor, @NonNull Runnable onDone) {
		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				repository.insertProfessor(professor);
				toastMessage.postValue("Professor saved");
				onDone.run();
			} catch (Exception e) {
				toastMessage.postValue("Could not save: " + e.getMessage());
			}
		});
	}

	// ── Courses ──────────────────────────────────────────────────────────

	public LiveData<List<EntityCourse>> getCourses() {
		return Transformations.map(repository.observeCourses(), this::visible);
	}

	/** That list as this account is shown it; see {@link Permissions#visibleCourses}. */
	@NonNull
	private List<EntityCourse> visible(@NonNull List<EntityCourse> courses) {
		return Permissions.visibleCourses(DeviceIdentity.accountKind(getApplication()),
				DeviceIdentity.represents(getApplication()), courses);
	}

	/**
	 * Saves a new course and the professors who teach it.
	 *
	 * @param instructorIDs the co-instructors, which need not include {@code course.profID}:
	 *                      the coordinator is folded in by {@code DaoCourse.setInstructors}
	 */
	public void addCourse(@NonNull EntityCourse course, @NonNull List<Long> instructorIDs,
	                      @Nullable List<Long> batchIDs, @NonNull Runnable onDone) {
		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				repository.insertCourse(course);
				repository.setCourseInstructors(course.courseID, instructorIDs);

				if (batchIDs != null) {
					repository.setCourseBatches(course.courseID, batchIDs);
				}
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
		MainDatabase.DB_EXECUTOR.execute(() -> cb.accept(visible(repository.coursesSync())));
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
		MainDatabase.DB_EXECUTOR.execute(() ->
				cb.accept(visible(repository.activeCoursesSync())));
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

			List<EntityCourse> courses = new ArrayList<>(
					visible(repository.activeCoursesSync()));

			boolean present = false;
			for (EntityCourse course : courses) {
				if (course.courseID == mustInclude) {
					present = true;
					break;
				}
			}

			if (!present) {
				EntityCourse discontinued = repository.courseSync(mustInclude);
				if (discontinued != null && !visible(List.of(discontinued)).isEmpty()) {
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
		MainDatabase.DB_EXECUTOR.execute(() -> cb.accept(repository.professorsSync()));
	}

	// ── Batches ──────────────────────────────────────────────────────────

	public LiveData<List<EntityBatchDesc>> getBatches() {
		return Transformations.switchMap(academicYear, year -> {

			// A student's list is a query of its own rather than a filter over everybody's:
			// it has to stay live, and working out which batches are linked reads the
			// database, which a map on the main thread may not.
			if (DeviceIdentity.accountKind(getApplication()) == AccountKind.STUDENT) {
				Set<Long> courses = DeviceIdentity.represents(getApplication());
				return repository.observeBatchesForYearLinkedTo(year,
						courses == null ? new ArrayList<>() : new ArrayList<>(courses));
			}
			return repository.observeBatchesForYear(year);
		});
	}

	/**
	 * What the register says when there is no batch to take it for.
	 * <p>
	 * Different for a student, who cannot make one: a class representative sees only the
	 * batches linked to the courses they represent, so an empty list means a professor has not
	 * linked one yet, and telling them to create one would send them to a screen they are not
	 * offered.
	 */
	@androidx.annotation.StringRes
	public int noBatchesHelper() {
		return DeviceIdentity.accountKind(getApplication()) == AccountKind.STUDENT
				? R.string.helper_no_linked_batches_cr
				: R.string.helper_no_batches_for_session;
	}

	/**
	 * That list as this account is shown it; see {@link Permissions#visibleBatches}. Reads the
	 * database, so off the main thread only.
	 */
	@NonNull
	private List<EntityBatchDesc> visibleBatches(@NonNull List<EntityBatchDesc> batches) {

		AccountKind who = DeviceIdentity.accountKind(getApplication());

		if (who != AccountKind.STUDENT) {
			return batches;
		}

		Set<Long> courses = DeviceIdentity.represents(getApplication());
		Set<Long> linked = courses == null ? null
				: new HashSet<>(repository.batchIdsLinkedToSync(new ArrayList<>(courses)));

		return Permissions.visibleBatches(who, linked, batches);
	}

	public void loadBatchesSync(@NonNull java.util.function.Consumer<List<EntityBatchDesc>> cb) {
		MainDatabase.DB_EXECUTOR.execute(() ->
				cb.accept(visibleBatches(repository.batchesSync())));
	}

	/**
	 * Creates a batch from an explicit set of students. They may come from any combination of
	 * years, streams and departments within the academic year.
	 */
	public void createBatch(@NonNull String batchName, @Nullable String description,
	                        @NonNull List<Long> studentIDs, @NonNull List<Long> courseIDs,
	                        @NonNull Runnable onDone) {

		final int acadYear = studentFilter().academicYear;

		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				EntityBatchDesc batch = new EntityBatchDesc(batchName, acadYear, description);
				long batchID = repository.createBatchFromStudents(batch, studentIDs);
				repository.setBatchCourses(batchID, courseIDs);
				toastMessage.postValue("Batch created with " + studentIDs.size() + " students");
				onDone.run();
			} catch (Exception e) {
				toastMessage.postValue("Could not create batch: " + e.getMessage());
			}
		});
	}

	// ── Lectures ─────────────────────────────────────────────────────────

	public LiveData<List<LectureDetails>> getAllLectures() {
		return repository.observeAllLectures();
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

			return repository.observeFilteredLectures(f.fromEpoch(), f.toEpoch(), f.courseID,
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
		return repository.observeLecturesBetween(from, to);
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
		return repository.observeLectureCountBetween(from, to);
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
					? repository.rollForCourseSync(courseID, acadYear)
					: repository.rollForBatchSync(batchID, acadYear);

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
				// One repository call, because it is one transaction: see
				// AttendanceRepository.recordLectureWithMarks.
				repository.recordLectureWithMarks(courseID, batchID, start, end, notes,
						snapshot);

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
			LectureDetails details = repository.lectureDetailsSync(lectureID);
			currentLecture = details;

			if (details == null) {
				toastMessage.postValue("Lecture not found");
				busy.postValue(false);
				return;
			}

			// A lecture with no batch was taken for the course's whole class, so the roll is
			// rebuilt from the course rather than from batch membership.
			List<StudentWithYear> students = details.batchID == null
					? repository.rollForCourseSync(details.courseID,
					sessionYearFor(details.startDateTime))
					: repository.studentsInBatchSync(details.batchID);
			List<Long> presentIds = repository.presentStudentIdsSync(lectureID);

			List<EntityAttendance> existing = repository.attendanceForLectureSync(lectureID);
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
				repository.saveAttendanceSheet(lectureID, records);
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
		return repository.observeStudent(studentID, academicYear);
	}

	public LiveData<List<StudentCourseStat>> getStudentCourseBreakdown(
			long studentID, @NonNull BreakdownFilter filter) {

		return repository.observeCourseBreakdown(studentID, filter.fromEpoch(),
				filter.toEpoch(), filter.courseTypes.isEmpty(),
				new ArrayList<>(filter.courseTypes), filter.courseIDs.isEmpty(),
				new ArrayList<>(filter.courseIDs));
	}

	/**
	 * The courses this student has ever been marked in, for the chips that choose which of them
	 * the breakdown covers. Read once when the screen opens rather than derived from the
	 * breakdown itself, which is filtered by the very selection these chips make.
	 */
	public void loadStudentCourses(long studentID,
	                               @NonNull java.util.function.Consumer<List<EntityCourse>> cb) {
		MainDatabase.DB_EXECUTOR.execute(() ->
				cb.accept(repository.attendedCoursesSync(studentID)));
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
		return repository.observeLectureMarks(studentID, courseID, fromEpoch, toEpoch);
	}

	/** Every session a student is enrolled in, newest first, for comparing them across years. */
	public void loadEnrolments(long studentID,
	                           @NonNull java.util.function.Consumer<List<EntityStudentYear>> cb) {
		MainDatabase.DB_EXECUTOR.execute(() -> cb.accept(repository.enrolmentsSync(studentID)));
	}

	public void loadStudentSync(long studentID, int academicYear,
	                            @NonNull java.util.function.Consumer<StudentWithYear> cb) {
		MainDatabase.DB_EXECUTOR.execute(() ->
				cb.accept(repository.studentSync(studentID, academicYear)));
	}

	/**
	 * Counts what deleting this student would cost, for the confirmation dialog. Read against
	 * the session the caller is showing, since that is the one a withdrawal would apply to.
	 */
	public void loadDeletionImpact(long studentID, int academicYear,
	                               @NonNull java.util.function.Consumer<DeletionImpact> cb) {
		MainDatabase.DB_EXECUTOR.execute(() ->
				cb.accept(repository.studentDeletionImpactSync(studentID, academicYear)));
	}

	/**
	 * Takes the student off one session's roster, keeping them and their attendance.
	 */
	public void withdrawStudentFromSession(long studentID, int academicYear,
	                                       @NonNull Runnable onDone) {

		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				repository.withdrawFromSession(studentID, academicYear);
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
				repository.deleteStudentCascade(studentID);
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
	                          @Nullable Division division, @Nullable LocalDate joinDate,
	                          @Nullable LocalDate leaveDate, @NonNull Runnable onDone) {

		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				repository.updateStudentWithYear(student, academicYear, rollNo, year,
						stream, majorDept, minorDept, oeDept, semester, division, joinDate,
						leaveDate);
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
				repository.updateProfessor(professor);
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
				cb.accept(repository.professorSync(profID)));
	}

	/**
	 * Courses carry a mandatory professor foreign key, so the add-course screen checks this
	 * first and sends the user to add a professor rather than letting them fill in a form that
	 * cannot be saved.
	 */
	public void countProfessors(@NonNull java.util.function.Consumer<Integer> cb) {
		MainDatabase.DB_EXECUTOR.execute(() -> cb.accept(repository.professorCountSync()));
	}

	// ── Course edit and delete ───────────────────────────────────────────

	public void loadCourseSync(long courseID,
	                           @NonNull java.util.function.Consumer<EntityCourse> cb) {
		MainDatabase.DB_EXECUTOR.execute(() -> cb.accept(repository.courseSync(courseID)));
	}

	/** @see #addCourse for what {@code instructorIDs} does and does not have to contain */
	public void updateCourse(@NonNull EntityCourse course, @NonNull List<Long> instructorIDs,
	                         @Nullable List<Long> batchIDs, @NonNull Runnable onDone) {
		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				repository.updateCourse(course);
				repository.setCourseInstructors(course.courseID, instructorIDs);

				if (batchIDs != null) {
					repository.setCourseBatches(course.courseID, batchIDs);
				}
				toastMessage.postValue("Course updated");
				onDone.run();
			} catch (Exception e) {
				toastMessage.postValue("Could not update: " + e.getMessage());
			}
		});
	}

	/**
	 * The batches a course may be linked to, and the ones it is linked to now.
	 * <p>
	 * The current session's batches, since that is where a course is being set up, plus any
	 * batch already linked from another session: leaving those out would untick them, and
	 * confirming the dialog would then remove a link nobody chose to remove.
	 *
	 * @param courseID the course, or -1 for one not saved yet, which has no links
	 */
	public void loadCourseBatchOptions(long courseID,
	                                   @NonNull java.util.function.BiConsumer<
			                                   List<EntityBatchDesc>, List<Long>> cb) {

		final int acadYear = academicYearValue();

		MainDatabase.DB_EXECUTOR.execute(() -> {

			List<Long> linked = courseID > 0
					? repository.courseBatchIdsSync(courseID) : new ArrayList<>();

			List<EntityBatchDesc> options = new ArrayList<>(
					repository.batchesForYearSync(acadYear));

			for (long batchID : linked) {

				boolean listed = false;
				for (EntityBatchDesc batch : options) {
					listed |= batch.batchID == batchID;
				}

				EntityBatchDesc other = listed ? null : repository.batchSync(batchID);
				if (other != null) {
					options.add(other);
				}
			}

			cb.accept(options, linked);
		});
	}

	/** Links a course to exactly these batches, for a professor who is not editing the course. */
	public void setCourseBatches(long courseID, @NonNull List<Long> batchIDs) {
		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				repository.setCourseBatches(courseID, batchIDs);
				toastMessage.postValue("Batches updated");
			} catch (Exception e) {
				toastMessage.postValue("Could not update: " + e.getMessage());
			}
		});
	}

	/**
	 * The professors teaching a course, coordinator included, for the edit form to tick.
	 */
	public void loadCourseInstructorIdsSync(long courseID,
	                                        @NonNull java.util.function.Consumer<List<Long>> cb) {
		MainDatabase.DB_EXECUTOR.execute(() ->
				cb.accept(repository.courseInstructorIdsSync(courseID)));
	}

	/**
	 * Marks a course as no longer offered. This replaces deletion: the lectures and attendance
	 * filed under the course stay intact and its history remains readable.
	 */
	public void discontinueCourse(long courseID, @NonNull Runnable onDone) {
		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				repository.setCourseDiscontinued(courseID, ZonedDateTime.now());
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
				EntityLecture source = repository.lectureSync(sourceLectureID);

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

				if (repository.findLecture(source.courseID, source.batchID, start, end)
						!= null) {
					toastMessage.postValue(
							"A lecture is already recorded for that course and time");
					onDone.accept(null);
					return;
				}

				// One transaction: a lecture that exists without its copied marks would look
				// like a session where nobody was recorded, which is not what was asked for.
				long created = repository.duplicateLecture(sourceLectureID, source.courseID,
						source.batchID, start, end, source.notes);

				toastMessage.postValue("Attendance duplicated");
				onDone.accept(created);

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
				cb.accept(repository.lectureSync(lectureID)));
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
			List<Long> marked = repository.markedStudentIdsSync(lectureID);
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
				? repository.rollForCourseSync(courseID, acadYear)
				: repository.rollForBatchSync(batchID, acadYear);

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
				Long clash = repository.findLecture(courseID, batchID, start, end);

				if (clash != null && clash != lectureID) {
					toastMessage.postValue(
							"Another lecture is already recorded for that course and time");
					return;
				}

				repository.updateLecture(lectureID, courseID, batchID, start, end, notes);

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
						repository.clearAttendanceForLecture(lectureID);
					} else {
						repository.deleteAttendanceExcept(lectureID,
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
				repository.clearAttendanceForLecture(lectureID);
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
				EntityLecture lecture = repository.lectureSync(lectureID);
				if (lecture == null) {
					toastMessage.postValue("Lecture not found");
					return;
				}
				repository.deleteLectureCascade(lecture);
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
				cb.accept(repository.lectureDetailsSync(lectureID)));
	}

	// ── Batch edit and delete ────────────────────────────────────────────

	/** Reads one batch. The callback runs off the main thread. */
	public void loadBatchSync(long batchID,
	                          @NonNull java.util.function.Consumer<EntityBatchDesc> cb) {
		MainDatabase.DB_EXECUTOR.execute(() -> cb.accept(repository.batchSync(batchID)));
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
					repository.classRollSync(acadYear, null, null, null);

			if (batchID <= 0) {
				List<Long> seed = batchSeed == null ? new ArrayList<>() : batchSeed;
				batchSeed = null;
				cb.onLoaded(null, all, seed);
				return;
			}

			EntityBatchDesc batch = repository.batchSync(batchID);
			List<Long> members = repository.batchMemberIdsSync(batchID, acadYear);
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
			for (StudentWithYear student : repository.filterStudentsSync(
					f.academicYear, f.year, f.stream, f.majorDept, f.minorDept, f.oeDept,
					f.division, f.batchID, f.query, false)) {
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
		EntityBatchDesc batch = repository.batchSync(batchID);
		return batch == null ? fallbackYear : batch.academicYear;
	}

	/**
	 * The courses a batch may be linked to, and the ones it is linked to now.
	 * <p>
	 * Every course, discontinued ones included: a batch for a course that has since been retired
	 * still says who sat it, and an edit must not drop that link just because the course is no
	 * longer offered. Empty links for a batch being created.
	 */
	public void loadBatchCourseLinks(long batchID,
	                                 @NonNull java.util.function.BiConsumer<List<EntityCourse>,
			                                 List<Long>> cb) {
		MainDatabase.DB_EXECUTOR.execute(() -> cb.accept(visible(repository.coursesSync()),
				batchID > 0 ? repository.batchCourseIdsSync(batchID) : new ArrayList<>()));
	}

	/**
	 * A batch as somebody who may not change it is shown it: its members, and the courses it is
	 * for out of the ones this account is shown.
	 */
	public void loadBatchDetails(long batchID, @NonNull BatchDetailsCallback cb) {
		MainDatabase.DB_EXECUTOR.execute(() -> {

			EntityBatchDesc batch = repository.batchSync(batchID);

			if (batch == null) {
				return;
			}

			List<Long> linked = repository.batchCourseIdsSync(batchID);
			List<EntityCourse> courses = new ArrayList<>();

			for (EntityCourse course : visible(repository.coursesSync())) {
				if (linked.contains(course.courseID)) {
					courses.add(course);
				}
			}

			cb.onLoaded(batch, repository.studentsInBatchSync(batchID), courses);
		});
	}

	public interface BatchDetailsCallback {
		void onLoaded(@NonNull EntityBatchDesc batch, @NonNull List<StudentWithYear> members,
		              @NonNull List<EntityCourse> courses);
	}

	/** A course as somebody who may not change it is shown it: who teaches it, which batches. */
	public void loadCourseDetails(long courseID, @NonNull CourseDetailsCallback cb) {
		MainDatabase.DB_EXECUTOR.execute(() -> {

			EntityCourse course = repository.courseSync(courseID);

			if (course == null) {
				return;
			}

			List<Long> teaching = repository.courseInstructorIdsSync(courseID);
			List<EntityProfessor> instructors = new ArrayList<>();

			for (EntityProfessor professor : repository.professorsSync()) {
				if (teaching.contains(professor.profID)) {
					instructors.add(professor);
				}
			}

			List<EntityBatchDesc> batches = new ArrayList<>();

			for (long batchID : repository.courseBatchIdsSync(courseID)) {
				EntityBatchDesc batch = repository.batchSync(batchID);
				if (batch != null) {
					batches.add(batch);
				}
			}

			cb.onLoaded(course, instructors, batches);
		});
	}

	public interface CourseDetailsCallback {
		void onLoaded(@NonNull EntityCourse course, @NonNull List<EntityProfessor> instructors,
		              @NonNull List<EntityBatchDesc> batches);
	}

	public interface BatchEditorCallback {
		void onLoaded(@Nullable EntityBatchDesc batch, @NonNull List<StudentWithYear> allStudents,
		              @NonNull List<Long> memberIDs);
	}

	public void updateBatch(long batchID, @NonNull String name, @Nullable String description,
	                        @NonNull List<Long> studentIDs, @NonNull List<Long> courseIDs,
	                        @NonNull Runnable onDone) {

		final int fallbackYear = studentFilter().academicYear;

		MainDatabase.DB_EXECUTOR.execute(() -> {
			try {
				int acadYear = batchSessionYear(batchID, fallbackYear);
				repository.updateBatch(batchID, name, description, acadYear, studentIDs);
				repository.setBatchCourses(batchID, courseIDs);
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
				repository.deleteBatchChecked(batchID);
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
				cb.accept(visibleBatches(repository.batchesForYearSync(acadYear))));
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
					new ArrayList<>(visibleBatches(repository.batchesForYearSync(acadYear)));

			// The lecture's own batch stays in the list whoever is looking, linked or not: it
			// is what the record says, and dropping it would show the lecture as something
			// it is not -- the reason this method exists at all.
			if (mustInclude != null) {

				boolean present = false;
				for (EntityBatchDesc batch : batches) {
					if (batch.batchID == mustInclude) {
						present = true;
						break;
					}
				}

				if (!present) {
					EntityBatchDesc older = repository.batchSync(mustInclude);
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
		return repository.observeStudentsInBatch(batchID);
	}

	// ── Statistics ───────────────────────────────────────────────────────

	public LiveData<List<StudentAttendanceStat>> getStudentStats() {
		return Transformations.switchMap(academicYear, repository::observeStudentStats);
	}

	public LiveData<List<CourseAttendanceStat>> getCourseStats() {
		return repository.observeCourseStats();
	}

	public LiveData<Double> getOverallPercentage() {
		return repository.observeOverallPercentage();
	}

	public LiveData<Integer> countStudents() {
		return Transformations.switchMap(academicYear, repository::observeStudentCount);
	}

	public LiveData<Integer> countCourses() {
		return Transformations.map(getCourses(), List::size);
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
		stopAwaitingSessions();
		super.onCleared();
	}

	/** The sessions table, watched while the current session is only a default; see below. */
	@Nullable
	private LiveData<List<EntityAcademicYear>> awaitedSessions;

	@Nullable
	private androidx.lifecycle.Observer<List<EntityAcademicYear>> sessionsArrived;

	/**
	 * Works the current session out again as soon as real sessions arrive.
	 * <p>
	 * For a phone with a server and an empty sessions table, which uses a default it does not
	 * save. The pull brings the real sessions moments later, and nothing else would notice: the
	 * current session is otherwise worked out only when this ViewModel is made or a session is
	 * saved. Once, then it stops watching.
	 */
	private void awaitRealSessions() {

		new Handler(Looper.getMainLooper()).post(() -> {

			if (sessionsArrived != null) {
				return;
			}

			awaitedSessions = repository.observeAcademicYears();
			sessionsArrived = sessions -> {
				if (sessions != null && !sessions.isEmpty()) {
					stopAwaitingSessions();
					resolveCurrentSession();
				}
			};
			awaitedSessions.observeForever(sessionsArrived);
		});
	}

	private void stopAwaitingSessions() {

		if (awaitedSessions != null && sessionsArrived != null) {
			awaitedSessions.removeObserver(sessionsArrived);
		}
		awaitedSessions = null;
		sessionsArrived = null;
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
						repository.statRowsSync(StatsQueryBuilder.grouped(snapshot));
				StatsSummary summary =
						repository.statsSummarySync(StatsQueryBuilder.summary(snapshot));

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
				visible(repository.coursesSync()),
				visibleBatches(repository.batchesSync()),
				repository.professorsSync()));
	}

	public interface StatsOptionsCallback {
		void onLoaded(@NonNull List<EntityCourse> courses, @NonNull List<EntityBatchDesc> batches,
		              @NonNull List<EntityProfessor> professors);
	}
}
