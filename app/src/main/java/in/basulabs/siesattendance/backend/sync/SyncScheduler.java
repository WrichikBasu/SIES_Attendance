package in.basulabs.siesattendance.backend.sync;

import android.content.Context;

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
	 * Asks for a sync as soon as there is a connection.
	 * <p>
	 * {@code REPLACE} rather than appending: the worker drains whatever the queue holds when it
	 * runs, so ten changes in a minute want one run and not ten. Replacing a run that has not
	 * started loses nothing, and one that has started is not replaced.
	 */
	public static void requestSync(@NonNull Context context) {

		WorkManager.getInstance(context).enqueueUniqueWork(ONE_OFF,
				ExistingWorkPolicy.REPLACE,
				new OneTimeWorkRequest.Builder(SyncWorker.class)
						.setConstraints(connected())
						// Exponential from thirty seconds: a campus network that has just come
						// back is often busy, and hammering it is how a hundred phones make it
						// worse.
						.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
						.build());
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
