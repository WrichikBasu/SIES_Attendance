package in.basulabs.siesattendance.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import in.basulabs.siesattendance.backend.EntityStudent;
import in.basulabs.siesattendance.backend.MainDatabase;
import in.basulabs.siesattendance.backend.sync.DeviceIdentity;
import in.basulabs.siesattendance.backend.sync.ServerAddress;
import in.basulabs.siesattendance.backend.sync.SyncScheduler;
import in.basulabs.siesattendance.backend.sync.SyncSignIn;
import in.basulabs.siesattendance.device.support.ServerSync;
import in.basulabs.siesattendance.device.support.StagingFiles;
import in.basulabs.siesattendance.device.support.TwoPhones;

/**
 * The whole path a teacher's change takes, as the app takes it: a write to the live database,
 * the outbox watch asking {@link SyncScheduler} for a sync, the real WorkManager running
 * {@code SyncWorker} when the phone has a network, and the row arriving on the server.
 * Signed in as the administrator, so what is written is not refused.
 * <p>
 * Every other test against the server drains a staging file by calling {@code OutboxDrain}
 * itself; this is the one that goes through the scheduler. It watches each run's state as
 * WorkManager reports it, and checks what {@code requestSync} promises: a run is never
 * cancelled halfway, two never run at once, and a run that sent something is followed by at
 * most one more, which finds nothing to send. The worker's own deletes are writes to the
 * outbox, and while {@code requestSync} was {@code REPLACE} they cancelled the run making
 * them and started another beside it.
 * <p>
 * The live database is the debug package's, on an install of its own that the script removes
 * afterwards.
 */
@RunWith(AndroidJUnit4.class)
public class ScheduledSyncTest extends TwoPhones {

	private static final String ONE_OFF = "sync-now";

	private static final String HOURLY = "sync-hourly";

	/** How long a sync of a handful of rows to a server on this phone's port may take. */
	private static final long SETTLED_WITHIN = 60_000;

	private static Context context;

	private static MainDatabase live;

	private Runs runs;

	@BeforeClass
	public static void signInAndWatchTheLiveOutbox() throws Exception {

		context = StagingFiles.context();
		String server = InstrumentationRegistry.getArguments().getString("siesServer");
		if (server == null) {
			return;
		}

		// The schedule first, while nobody is signed in: an hourly run enqueued for the first
		// time runs at once, and one that finds no token does nothing. Signing in first would
		// put that run beside the ones under test. The application has usually done this when
		// the process started; this covers a build that had no address then.
		ServerAddress.choose(context, ServerAddress.normalise(server));
		SyncScheduler.start(context);
		awaitHourlyIdle();

		ServerSync.signIn("siesEmail", "siesPassword", SyncSignIn.Door.STAFF, null);
		live = MainDatabase.getInstance(context);

		// The first sync after signing in brings everything the account may read, over as
		// many runs as it takes. Done here, so the runs the tests count are their own.
		SyncScheduler.syncNow(context).get();
		awaitSettled();
	}

	@Before
	public void settledAndWatched() throws Exception {
		awaitSettled();
		runs = Runs.watch();
	}

	@After
	public void stopWatching() {
		if (runs != null) {
			runs.stop();
			// What WorkManager did, for whoever reads the log of a passing run.
			Log.i("ScheduledSyncTest", runs.toString());
		}
	}

	@Test
	public void aChangeReachesTheServerAndOneRunFollowsThatSendsNothing() throws Exception {

		long student = live.daoStudent().insertStudent(
				new EntityStudent("SCHEDULED " + tag, "they/them", null));

		awaitSettled();

		runs.assertNoneCancelledAndNoneOverlapped();
		assertTrue(runs.toString(), runs.started() >= 1);
		assertTrue("the run, and at most one after it: " + runs, runs.started() <= 2);
		if (runs.started() == 2) {
			assertEquals("the one after it found nothing to send", 0,
					DeviceIdentity.lastSent(context));
		}
		assertNull(DeviceIdentity.lastSyncError(context));

		ServerSync.pullAll(phoneB);
		assertNotNull("on the server", phoneB.daoStudent().getStudentSync(student));
	}

	@Test
	public void aBurstOfChangesIsSentByAFewRunsOneAfterAnother() throws Exception {

		List<Long> students = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			// One write at a time, as a teacher makes them: ten notifications to the watch.
			students.add(live.daoStudent().insertStudent(
					new EntityStudent("BURST " + i + " " + tag, "they/them", null)));
		}

		awaitSettled();

		runs.assertNoneCancelledAndNoneOverlapped();
		// Each run that sends something is followed by one more, so there is a short chain,
		// never one run per write.
		assertTrue("a few runs, not one per write: " + runs, runs.started() <= 4);
		assertEquals("the last found nothing left", 0, DeviceIdentity.lastSent(context));
		assertNull(DeviceIdentity.lastSyncError(context));

		ServerSync.pullAll(phoneB);
		for (long student : students) {
			assertNotNull(student + " on the server",
					phoneB.daoStudent().getStudentSync(student));
		}
	}

	// ---------------------------------------------------------------------------------------

	/** Until the outbox is empty and no sync is waiting or running. */
	private static void awaitSettled() throws Exception {

		long deadline = System.currentTimeMillis() + SETTLED_WITHIN;
		int quiet = 0;

		while (System.currentTimeMillis() < deadline) {
			boolean idle = live.daoOutbox().countPendingSync() == 0 && noneLive(ONE_OFF);
			// Twice in a row a second apart: the watch's notification for the last delete
			// arrives a moment after the delete, and the run it asks for after that.
			quiet = idle ? quiet + 1 : 0;
			if (quiet == 2) {
				return;
			}
			Thread.sleep(1_000);
		}
		throw new AssertionError("still syncing after " + SETTLED_WITHIN + " ms: outbox "
				+ live.daoOutbox().countPendingSync() + ", "
				+ WorkManager.getInstance(context).getWorkInfosForUniqueWork(ONE_OFF).get()
				+ ", last error " + DeviceIdentity.lastSyncError(context));
	}

	private static void awaitHourlyIdle() throws Exception {

		long deadline = System.currentTimeMillis() + 15_000;
		while (System.currentTimeMillis() < deadline) {
			boolean idle = true;
			for (WorkInfo info : WorkManager.getInstance(context)
					.getWorkInfosForUniqueWork(HOURLY).get()) {
				// After its run a periodic work is enqueued again, an hour away.
				idle &= info.getState() != WorkInfo.State.RUNNING && info.getRunAttemptCount() == 0
						&& info.getNextScheduleTimeMillis() > System.currentTimeMillis() + 60_000;
			}
			if (idle) {
				return;
			}
			Thread.sleep(250);
		}
		throw new AssertionError("the hourly run did not settle: "
				+ WorkManager.getInstance(context).getWorkInfosForUniqueWork(HOURLY).get());
	}

	private static boolean noneLive(@NonNull String name) throws Exception {

		for (WorkInfo info : WorkManager.getInstance(context)
				.getWorkInfosForUniqueWork(name).get()) {
			if (!info.getState().isFinished()) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Every sync run WorkManager reports while a test runs, under both names, from the
	 * LiveData a screen would watch: each state a run passed through, whether one was ever
	 * cancelled, and the most that were running at the same moment.
	 */
	private static final class Runs {

		private final List<LiveData<List<WorkInfo>>> sources = new ArrayList<>();

		private final List<Observer<List<WorkInfo>>> observers = new ArrayList<>();

		/** The latest state of every run seen, by name and id. */
		private final Map<String, WorkInfo.State> states = new HashMap<>();

		/** Every change of state, in order, for the failure message. */
		private final List<String> history = new ArrayList<>();

		private final Set<String> oneOffRuns = new LinkedHashSet<>();

		private boolean cancelled;

		private int mostAtOnce;

		@NonNull
		static Runs watch() {

			Runs runs = new Runs();
			WorkManager manager = WorkManager.getInstance(context);

			InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
				for (String name : new String[]{ONE_OFF, HOURLY}) {
					LiveData<List<WorkInfo>> source =
							manager.getWorkInfosForUniqueWorkLiveData(name);
					Observer<List<WorkInfo>> observer = infos -> runs.saw(name, infos);
					source.observeForever(observer);
					runs.sources.add(source);
					runs.observers.add(observer);
				}
			});
			return runs;
		}

		synchronized void saw(@NonNull String name, @NonNull List<WorkInfo> infos) {

			Set<String> present = new LinkedHashSet<>();
			for (WorkInfo info : infos) {
				present.add(keyOf(name, info));
			}

			// REPLACE does not leave a cancelled run to be seen: WorkManager deletes it from
			// the name at once. A run that disappears while it was running was cancelled
			// halfway; one that disappears while it was waiting was replaced, which costs
			// nothing.
			for (Map.Entry<String, WorkInfo.State> known : new ArrayList<>(states.entrySet())) {
				if (known.getKey().startsWith(name + "/") && !present.contains(known.getKey())) {
					if (known.getValue() == WorkInfo.State.RUNNING) {
						cancelled = true;
						history.add(known.getKey() + " GONE WHILE RUNNING");
					}
					states.remove(known.getKey());
				}
			}

			for (WorkInfo info : infos) {

				String key = keyOf(name, info);
				WorkInfo.State before = states.put(key, info.getState());

				if (before == info.getState()) {
					continue;
				}
				history.add(key + " " + info.getState() + " (attempt "
						+ info.getRunAttemptCount() + ")");

				if (info.getState() == WorkInfo.State.CANCELLED) {
					cancelled = true;
				}
				if (name.equals(ONE_OFF) && info.getState() == WorkInfo.State.RUNNING) {
					oneOffRuns.add(key);
				}
			}

			int running = 0;
			for (WorkInfo.State state : states.values()) {
				if (state == WorkInfo.State.RUNNING) {
					running++;
				}
			}
			mostAtOnce = Math.max(mostAtOnce, running);
		}

		@NonNull
		private static String keyOf(@NonNull String name, @NonNull WorkInfo info) {
			return name + "/" + info.getId().toString().substring(0, 8);
		}

		/** How many one-off runs started while watched. */
		synchronized int started() {
			return oneOffRuns.size();
		}

		synchronized void assertNoneCancelledAndNoneOverlapped() {
			assertFalse("no run cancelled: " + this, cancelled);
			assertTrue("never two at once: " + this, mostAtOnce <= 1);
		}

		void stop() {
			InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
				for (int i = 0; i < sources.size(); i++) {
					sources.get(i).removeObserver(observers.get(i));
				}
			});
		}

		@NonNull
		@Override
		public synchronized String toString() {
			return oneOffRuns.size() + " started, " + history;
		}
	}
}
