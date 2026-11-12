package in.basulabs.siesattendance.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.work.NetworkType;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.testing.TestDriver;
import androidx.work.testing.WorkManagerTestInitHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import in.basulabs.siesattendance.backend.EntityStudent;
import in.basulabs.siesattendance.backend.MainDatabase;
import in.basulabs.siesattendance.backend.sync.DeviceIdentity;
import in.basulabs.siesattendance.backend.sync.SyncScheduler;
import in.basulabs.siesattendance.device.support.StagingFiles;

/**
 * When a sync happens: {@link SyncScheduler}, and what {@code SyncWorker}'s outcomes do to it,
 * under a test WorkManager whose constraints the test decides. {@code SyncSchedulerDecideTest}
 * has the whole table of what a request does; this is WorkManager agreeing with it.
 * <p>
 * The database here is the live one, unlike every other device test's staging file: the
 * outbox watch is registered on {@code MainDatabase.getInstance} and nothing else. It is the
 * debug package's, which the instrumented run installs and uninstalls again, so nothing of
 * anybody's is in it. Each test empties the outbox before and after.
 * <p>
 * No server: where a run has to fail, the address is a dead port on this phone, written
 * straight into the preference and put back afterwards, and the token is a made-up one, put
 * back the same way.
 */
@RunWith(AndroidJUnit4.class)
public class SyncSchedulingDeviceTest {

	/** {@code SyncScheduler}'s unique names, which are private to it. */
	private static final String ONE_OFF = "sync-now";

	private static final String HOURLY = "sync-hourly";

	/** {@code ServerAddress}'s preference, for pointing at a dead port. */
	private static final String ADDRESS_PREFS = "sync_server";

	private static final String ADDRESS_KEY = "address";

	/** Nothing listens on the discard port, so a connection is refused at once. */
	private static final String DEAD_ADDRESS = "http://127.0.0.1:9/api/v1";

	private static Context context;

	private static MainDatabase db;

	private WorkManager manager;

	private TestDriver driver;

	@Nullable
	private String addressBefore;

	@Nullable
	private String tokenBefore;

	@BeforeClass
	public static void watchTheLiveOutbox() {

		context = StagingFiles.context();
		db = MainDatabase.getInstance(context);

		// The application registered the watch when the process started, but only if the
		// build had an address then. Registering it again here makes the tests independent
		// of that; a second watch only asks for the same unique run twice.
		pointAt(DEAD_ADDRESS, () -> SyncScheduler.start(context));
	}

	@Before
	public void aTestWorkManager() {

		// Replaces whatever WorkManager the process has, the real one included, for every
		// later call to getInstance: the scheduler's, the worker's and the observer's.
		WorkManagerTestInitHelper.initializeTestWorkManager(context);
		manager = WorkManager.getInstance(context);
		driver = WorkManagerTestInitHelper.getTestDriver(context);
		assertNotNull(driver);

		addressBefore = addressPrefs().getString(ADDRESS_KEY, null);
		tokenBefore = DeviceIdentity.token(context);
		DeviceIdentity.forgetToken(context);
		DeviceIdentity.recordSyncError(context, null);
		emptyTheOutbox();
	}

	@After
	public void putThingsBack() {

		emptyTheOutbox();
		SharedPreferences.Editor editor = addressPrefs().edit();
		if (addressBefore == null) {
			editor.remove(ADDRESS_KEY);
		} else {
			editor.putString(ADDRESS_KEY, addressBefore);
		}
		assertTrue(editor.commit());

		if (tokenBefore == null) {
			DeviceIdentity.forgetToken(context);
		} else {
			DeviceIdentity.storeToken(context, tokenBefore);
		}
		DeviceIdentity.recordSyncError(context, null);
	}

	@Test
	public void aSyncAskedForWaitsForANetwork() throws Exception {

		SyncScheduler.requestSync(context).get();

		WorkInfo run = theOnlyLive(ONE_OFF);
		assertEquals(WorkInfo.State.ENQUEUED, run.getState());
		assertEquals(NetworkType.CONNECTED, run.getConstraints().getRequiredNetworkType());
		assertFalse("not the battery", run.getConstraints().requiresBatteryNotLow());
		assertFalse("not charging", run.getConstraints().requiresCharging());
		assertEquals("and no delay", 0L, run.getInitialDelayMillis());
	}

	@Test
	public void aBurstOfRequestsLeavesOneRun() throws Exception {

		SyncScheduler.requestSync(context).get();
		UUID first = theOnlyLive(ONE_OFF).getId();

		for (int i = 0; i < 4; i++) {
			SyncScheduler.requestSync(context).get();
			SyncScheduler.syncNow(context).get();
		}

		// The run waiting for a connection drains whatever is queued when it goes, so it is
		// kept rather than replaced, by the watch and by a person alike.
		assertEquals("the first is the one still waiting", first, theOnlyLive(ONE_OFF).getId());
	}

	@Test
	public void withANetworkAndNobodySignedInARunDoesNothingAndSucceeds() throws Exception {

		pointAt(DEAD_ADDRESS);
		SyncScheduler.requestSync(context).get();
		UUID id = theOnlyLive(ONE_OFF).getId();

		driver.setAllConstraintsMet(id);

		assertEquals(WorkInfo.State.SUCCEEDED, manager.getWorkInfoById(id).get().getState());
		assertEquals("nothing tried", null, DeviceIdentity.lastSyncError(context));
	}

	@Test
	public void aWriteToTheOutboxAsksForASync() throws Exception {

		assertTrue(live(infos(ONE_OFF)).isEmpty());

		db.daoStudent().insertStudent(new EntityStudent("SCHEDULED", "they/them", null));

		assertTrue("the write queued an intent", db.daoOutbox().countPendingSync() > 0);
		WorkInfo run = awaitLive(ONE_OFF);
		assertEquals(WorkInfo.State.ENQUEUED, run.getState());
	}

	@Test
	public void theHourlyRunIsScheduledOnceAndKeptOnEveryStart() throws Exception {

		pointAt(DEAD_ADDRESS, () -> SyncScheduler.start(context));
		WorkInfo first = theOnlyLive(HOURLY);

		assertNotNull(first.getPeriodicityInfo());
		assertEquals(TimeUnit.HOURS.toMillis(1),
				first.getPeriodicityInfo().getRepeatIntervalMillis());
		assertEquals(NetworkType.CONNECTED, first.getConstraints().getRequiredNetworkType());

		// Opening the app again must not push the next run an hour away.
		pointAt(DEAD_ADDRESS, () -> SyncScheduler.start(context));
		pointAt(DEAD_ADDRESS, () -> SyncScheduler.start(context));

		List<WorkInfo> all = infos(HOURLY);
		assertEquals(1, all.size());
		assertEquals("the same schedule, kept", first.getId(), all.get(0).getId());
	}

	@Test
	public void signingOutCancelsTheAskedForRunAndKeepsTheHourly() throws Exception {

		pointAt(DEAD_ADDRESS, () -> SyncScheduler.start(context));
		SyncScheduler.requestSync(context).get();
		UUID oneOff = theOnlyLive(ONE_OFF).getId();

		SyncScheduler.cancelPending(context);

		assertEquals(WorkInfo.State.CANCELLED,
				manager.getWorkInfoById(oneOff).get().getState());
		assertEquals(WorkInfo.State.ENQUEUED, theOnlyLive(HOURLY).getState());
	}

	/**
	 * A run that cannot reach the server is retried after thirty seconds, then a minute, and
	 * so on, rather than at once: a hundred phones on a campus network that has just come back
	 * should not all hammer it.
	 * <p>
	 * The run itself writes to the outbox — it counts the failed attempt against each intent —
	 * and the outbox watch asks for a sync whenever the outbox changes. That request must not
	 * replace the run that is backing off with one that starts at once, which is what it did
	 * while {@code requestSync} was {@code REPLACE}: about five attempts a second, measured on
	 * this phone, for as long as the server was down.
	 * <p>
	 * A person pressing the sync button is different: they are not made to wait out the
	 * backoff.
	 */
	@Test
	public void aRunThatCannotReachTheServerBacksOff() throws Exception {

		pointAt(DEAD_ADDRESS);
		DeviceIdentity.storeToken(context, "not-a-real-token");

		db.daoStudent().insertStudent(new EntityStudent("UNSENT", "they/them", null));
		UUID first = awaitLive(ONE_OFF).getId();
		// Let the watch's notification for that write land before the run starts.
		Thread.sleep(1_000);
		first = theOnlyLive(ONE_OFF).getId();

		driver.setAllConstraintsMet(first);

		assertEquals("one failed attempt counted", 1L, mostAttempts());

		// The watch reacts to that write asynchronously; give it the time it takes.
		Thread.sleep(2_000);

		// Its own write, if the watch saw it while the run was still going, queued one run
		// behind it, which waits for it; nothing else is live.
		WorkInfo failed = null;
		for (WorkInfo info : live(infos(ONE_OFF))) {
			if (info.getId().equals(first)) {
				failed = info;
			} else {
				assertEquals(String.valueOf(info), WorkInfo.State.BLOCKED, info.getState());
			}
		}
		assertNotNull("the run that failed is still the one to retry: " + infos(ONE_OFF),
				failed);
		assertEquals(WorkInfo.State.ENQUEUED, failed.getState());
		assertEquals(1, failed.getRunAttemptCount());
		assertTrue("and it waits before trying again",
				failed.getNextScheduleTimeMillis() >= System.currentTimeMillis() + 20_000);

		// More writes during the outage change nothing.
		db.daoStudent().insertStudent(new EntityStudent("ALSO UNSENT", "they/them", null));
		Thread.sleep(1_000);
		SyncScheduler.requestSync(context).get();
		assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(first).get().getState());
		assertEquals(1, manager.getWorkInfoById(first).get().getRunAttemptCount());

		// The sync button does not wait: a fresh run, ready as soon as there is a connection.
		SyncScheduler.syncNow(context).get();

		WorkInfo asked = theOnlyLive(ONE_OFF);
		assertNotEquals(first, asked.getId());
		assertEquals(WorkInfo.State.ENQUEUED, asked.getState());
		assertEquals(0, asked.getRunAttemptCount());
	}

	// ---------------------------------------------------------------------------------------

	private static void emptyTheOutbox() {
		db.getOpenHelper().getWritableDatabase().execSQL("DELETE FROM outbox");
	}

	/** The most times any queued intent has failed to send. */
	private static long mostAttempts() {

		try (Cursor cursor = db.getOpenHelper().getReadableDatabase()
				.query("SELECT MAX(attempts) FROM outbox")) {
			assertTrue(cursor.moveToFirst());
			return cursor.getLong(0);
		}
	}

	@NonNull
	private static SharedPreferences addressPrefs() {
		return context.createDeviceProtectedStorageContext()
				.getSharedPreferences(ADDRESS_PREFS, Context.MODE_PRIVATE);
	}

	private static void pointAt(@NonNull String address) {
		assertTrue(addressPrefs().edit().putString(ADDRESS_KEY, address).commit());
	}

	/** Runs {@code then} with the address set, for {@code start}, which needs one. */
	private static void pointAt(@NonNull String address, @NonNull Runnable then) {

		String before = addressPrefs().getString(ADDRESS_KEY, null);
		pointAt(address);
		try {
			then.run();
		} finally {
			SharedPreferences.Editor editor = addressPrefs().edit();
			if (before == null) {
				editor.remove(ADDRESS_KEY);
			} else {
				editor.putString(ADDRESS_KEY, before);
			}
			assertTrue(editor.commit());
		}
	}

	@NonNull
	private List<WorkInfo> infos(@NonNull String name)
			throws ExecutionException, InterruptedException {
		return manager.getWorkInfosForUniqueWork(name).get();
	}

	@NonNull
	private static List<WorkInfo> live(@NonNull List<WorkInfo> infos) {

		List<WorkInfo> live = new ArrayList<>();
		for (WorkInfo info : infos) {
			if (!info.getState().isFinished()) {
				live.add(info);
			}
		}
		return live;
	}

	@NonNull
	private WorkInfo theOnlyLive(@NonNull String name) throws Exception {

		List<WorkInfo> live = live(infos(name));
		assertEquals(name + ": " + infos(name), 1, live.size());
		return live.get(0);
	}

	/** Waits for the outbox watch, which Room notifies on a thread of its own. */
	@NonNull
	private WorkInfo awaitLive(@NonNull String name) throws Exception {

		long deadline = System.currentTimeMillis() + 5_000;
		while (System.currentTimeMillis() < deadline) {
			List<WorkInfo> live = live(infos(name));
			if (!live.isEmpty()) {
				return theOnlyLive(name);
			}
			Thread.sleep(50);
		}
		throw new AssertionError("no sync was asked for: " + infos(name));
	}
}
