package in.basulabs.siesattendance.backend.sync;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.Transformations;
import androidx.room.InvalidationTracker;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import in.basulabs.siesattendance.backend.EntityOutbox;
import in.basulabs.siesattendance.backend.MainDatabase;

/**
 * When to sync: whenever the outbox changes, and once an hour regardless.
 *
 * <h3>Why a database observer and not a call after every write</h3>
 * There are more than twenty places in the app that write something, and the next one will be
 * written by somebody who has not read this file. Room's {@link InvalidationTracker} says when a
 * table changed, so watching the outbox catches every writer there is or will be — including the
 * roster import, the attendance-record import and a restored backup, none of which go through
 * the repository.
 * <p>
 * It is the same argument the triggers make one layer down. The queue is filled by the database
 * because a caller can forget; the sync is requested by the database changing because a caller
 * can forget that too.
 *
 * <h3>The hourly run exists for the empty case</h3>
 * An outbox that never changes still needs a pull: another professor's correction, or a lecture
 * created on the department tablet, reaches this phone only because something asks. An hour is
 * chosen against the battery rather than against the clock — attendance is not urgent to read,
 * and anything a person is waiting for they can ask for.
 */
public final class SyncScheduler {

	/** One unique name, so a burst of writes does not queue a run each. */
	private static final String ONE_OFF = "sync-now";

	private static final String HOURLY = "sync-hourly";

	private static final String TAG = "SyncScheduler";

	private SyncScheduler() {
	}

	/**
	 * Starts watching the outbox and schedules the hourly run. Called once, from the
	 * application.
	 */
	public static void start(@NonNull Context context) {

		Context application = context.getApplicationContext();

		if (!new SyncClient(application).isConfigured()) {
			// No server in this build, so nothing to schedule. The app is a local one, which
			// is what it has always been and what it stays until somebody sets the address.
			return;
		}

		MainDatabase.getInstance(application).getInvalidationTracker().addObserver(
				new InvalidationTracker.Observer(new String[]{EntityOutbox.TABLE_NAME}) {
					@Override
					public void onInvalidated(@NonNull Set<String> tables) {
						requestSync(application);
					}
				});

		scheduleHourly(application);
	}

	/**
	 * Asks for a sync as soon as there is a connection, for the outbox watch and the worker.
	 * <p>
	 * What it does depends on the run already asked for, because the outbox watch fires on
	 * every write to the outbox, and some of those writes are the worker's own: a failed push
	 * counts the attempt against each intent, and a successful one deletes what it sent. This
	 * used to be {@code REPLACE} whatever was there, and {@code REPLACE} cancels work in every
	 * state. A run that could not reach the server wrote its failure, the watch replaced the run
	 * that was waiting out its backoff with one that started at once, and that one failed and
	 * wrote again: about five attempts a second for as long as the server was down, instead of
	 * one after thirty seconds, then a minute, and so on. By the same route, a run that sent
	 * something could be cancelled by its own deletes while a second one started beside it.
	 * {@code SyncSchedulingDeviceTest} checks the first.
	 * <ul>
	 *     <li>Nothing asked for: a run is enqueued.</li>
	 *     <li>A run waiting to start, whether for a connection or out its backoff: left alone.
	 *     It drains whatever the queue holds when it does run, so a write made meanwhile is
	 *     not lost, and a burst of writes still means one run.</li>
	 *     <li>A run in progress: one more is queued behind it, unless one already is. That run
	 *     may have read the queue before this write, and cancelling it would only start a
	 *     second drain beside it.</li>
	 * </ul>
	 * A person asking is {@link #syncNow}, which does not wait out a backoff.
	 *
	 * @return done when the decision has been made and any work enqueued; callers need not
	 * wait for it, and tests do
	 */
	@NonNull
	public static Future<?> requestSync(@NonNull Context context) {
		return ask(context.getApplicationContext(), false);
	}

	/**
	 * Asks for a sync now, because somebody has: the sync button, signing in, a changed
	 * password. As {@link #requestSync}, except that a run waiting out a backoff is replaced with
	 * one that goes as soon as there is a connection. A person who has just asked would
	 * otherwise wait up to the length of the backoff, which after a few hours with no server is
	 * hours, for nothing to happen.
	 */
	@NonNull
	public static Future<?> syncNow(@NonNull Context context) {
		return ask(context.getApplicationContext(), true);
	}

	/**
	 * One thread, so that looking at the run asked for and enqueueing the next one are not
	 * interleaved with another caller doing the same; and not the caller's, because the outbox
	 * watch and the screens are threads that should not wait on WorkManager's database.
	 */
	private static final ExecutorService DECIDING = Executors.newSingleThreadExecutor();

	/** What {@link #ask} does about the run already asked for. */
	enum Move {

		/** Leave it: a run that has not started yet will drain whatever there is. */
		NOTHING,

		/** Enqueue one, replacing whatever is left. */
		ENQUEUE,

		/** Queue one behind the run in progress. */
		AFTER_THE_RUNNING_ONE
	}

	@NonNull
	private static Future<?> ask(@NonNull Context context, boolean byHand) {

		return DECIDING.submit(() -> {

			WorkManager manager = WorkManager.getInstance(context);

			try {
				switch (decide(manager.getWorkInfosForUniqueWork(ONE_OFF).get(), byHand)) {
					case ENQUEUE -> manager.enqueueUniqueWork(ONE_OFF,
							ExistingWorkPolicy.REPLACE, oneOff()).getResult().get();
					// Behind the running run, or in its place if it has finished by now. If it
					// fails for good -- signed out, refused -- this one fails with it, which is
					// right: nothing it could do would go differently.
					case AFTER_THE_RUNNING_ONE -> manager.enqueueUniqueWork(ONE_OFF,
							ExistingWorkPolicy.APPEND_OR_REPLACE, oneOff()).getResult().get();
					case NOTHING -> {
					}
				}

			} catch (ExecutionException e) {
				// WorkManager's own database failed. The hourly run is still scheduled, and the
				// next write asks again.
				Log.e(TAG, "could not ask for a sync", e);

			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
	}

	/**
	 * What to do, given the runs under the one-off name.
	 *
	 * @param byHand whether a person asked, which replaces a run waiting out its backoff
	 */
	@NonNull
	static Move decide(@NonNull List<WorkInfo> infos, boolean byHand) {

		boolean running = false;
		boolean waiting = false;
		boolean backingOff = false;

		for (WorkInfo info : infos) {
			switch (info.getState()) {
				case RUNNING -> running = true;
				// Blocked is the run already queued behind a running one, or behind one backing
				// off: either way a run that has not started yet.
				case BLOCKED -> waiting = true;
				case ENQUEUED -> {
					if (info.getRunAttemptCount() > 0) {
						backingOff = true;
					} else {
						waiting = true;
					}
				}
				default -> {
				}
			}
		}

		if (running) {
			return waiting ? Move.NOTHING : Move.AFTER_THE_RUNNING_ONE;
		}
		if (backingOff) {
			return byHand ? Move.ENQUEUE : Move.NOTHING;
		}
		return waiting ? Move.NOTHING : Move.ENQUEUE;
	}

	@NonNull
	private static OneTimeWorkRequest oneOff() {

		return new OneTimeWorkRequest.Builder(SyncWorker.class)
				.setConstraints(connected())
				// Exponential from thirty seconds: a campus network that has just come back is
				// often busy, and hammering it is how a hundred phones make it worse.
				.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
				.build();
	}

	/**
	 * Whether a sync is running right now, for a screen to say so.
	 * <p>
	 * Asked of WorkManager rather than tracked in a field: the worker runs in its own process
	 * state and may have been started by the hourly schedule, by a write three screens away, or
	 * by a button — and a flag kept here would be wrong after every one of those. The unique
	 * name is this class's own, which is why the question is answered here.
	 * <p>
	 * Both names are watched. A run asked for by hand and the hourly one are the same work
	 * doing the same thing, and a screen that said "not syncing" during the second would be
	 * lying for an hour at a time.
	 */
	/**
	 * Cancels a sync that has been asked for and not finished, for signing out.
	 * <p>
	 * Only the one-off run. The hourly one stays scheduled, because nothing reschedules it
	 * until the app is next started; when it fires with nobody signed in it finds no token and
	 * does nothing, which is all signing out needs of it.
	 */
	public static void cancelPending(@NonNull Context context) {
		WorkManager.getInstance(context).cancelUniqueWork(ONE_OFF);
	}

	@NonNull
	public static LiveData<Boolean> observeRunning(@NonNull Context context) {

		WorkManager manager = WorkManager.getInstance(context.getApplicationContext());

		LiveData<Boolean> onceOff = Transformations.map(
				manager.getWorkInfosForUniqueWorkLiveData(ONE_OFF), SyncScheduler::anyRunning);

		LiveData<Boolean> hourly = Transformations.map(
				manager.getWorkInfosForUniqueWorkLiveData(HOURLY), SyncScheduler::anyRunning);

		MediatorLiveData<Boolean> either = new MediatorLiveData<>();

		either.setValue(false);
		either.addSource(onceOff, running -> either.setValue(
				Boolean.TRUE.equals(running) || Boolean.TRUE.equals(hourly.getValue())));
		either.addSource(hourly, running -> either.setValue(
				Boolean.TRUE.equals(running) || Boolean.TRUE.equals(onceOff.getValue())));

		return either;
	}

	private static boolean anyRunning(@Nullable List<WorkInfo> infos) {

		if (infos == null) {
			return false;
		}

		for (WorkInfo info : infos) {
			if (info.getState() == WorkInfo.State.RUNNING) {
				return true;
			}
		}

		return false;
	}

	private static void scheduleHourly(@NonNull Context context) {

		WorkManager.getInstance(context).enqueueUniquePeriodicWork(HOURLY,
				// KEEP, so reopening the app does not reset the schedule and put the next run
				// an hour away again.
				ExistingPeriodicWorkPolicy.KEEP,
				new PeriodicWorkRequest.Builder(SyncWorker.class, 1, TimeUnit.HOURS)
						.setConstraints(connected())
						.build());
	}

	@NonNull
	private static Constraints connected() {

		return new Constraints.Builder()
				.setRequiredNetworkType(NetworkType.CONNECTED)
				// Nothing about battery or idleness: a register marked at eleven should reach
				// the server before the professor has left the room, and the payload is a few
				// hundred bytes.
				.build();
	}
}
