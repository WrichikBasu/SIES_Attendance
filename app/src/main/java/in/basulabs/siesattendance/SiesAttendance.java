package in.basulabs.siesattendance;

import android.app.Application;

import in.basulabs.siesattendance.backend.sync.SyncScheduler;

/**
 * The application, which exists for one line.
 * <p>
 * There was no {@code Application} subclass before Phase 6 and nothing wanted one: the database
 * opens on first use and the ID generator primes itself when it does. What needs a place to
 * happen once, now, is the watch on the outbox — Room's invalidation tracker has to be told to
 * ask for a sync whenever the queue changes, and that has to be registered before any screen
 * writes anything.
 * <p>
 * Registering it from an activity would tie syncing to somebody having opened the app, which is
 * exactly the case an outbox exists to cover: marks taken this morning should reach the server
 * whether or not the professor opens the app again this afternoon.
 */
public final class SiesAttendance extends Application {

	@Override
	public void onCreate() {

		super.onCreate();

		// Does nothing at all in a build with no server address, which is every build until
		// somebody sets one. See SyncScheduler.
		SyncScheduler.start(this);
	}
}
