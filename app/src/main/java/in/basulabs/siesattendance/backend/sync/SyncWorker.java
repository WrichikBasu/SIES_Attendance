package in.basulabs.siesattendance.backend.sync;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.IOException;

import in.basulabs.siesattendance.backend.MainDatabase;

/**
 * Drains the outbox and applies what the server has, when there is a connection.
 * <p>
 * A {@link Worker} rather than a service or a thread of its own, because the thing being asked
 * for is "do this when there is a network, survive a reboot, back off if it fails, and do not
 * wake the phone for it" — which is the whole of what WorkManager is.
 *
 * <h3>Push first, then pull</h3>
 * In that order, always. A device's own marks are the work somebody has done and not yet got
 * credit for; the server's changes are information. Pulling first would mean a phone with a
 * failing push spending its one connection on reading.
 *
 * <h3>What each outcome means</h3>
 * <ul>
 *     <li>{@code success} — everything that could be sent was sent, and the page applied. If
 *     more is waiting, another run is enqueued rather than looping here: a worker that drained
 *     for ten minutes would be a worker the system kills halfway.</li>
 *     <li>{@code retry} — no signal, a timeout, a 5xx. WorkManager backs off and tries again,
 *     and the queue is untouched.</li>
 *     <li>{@code failure} — the token is no good, or the server refused the request itself.
 *     Neither is fixed by trying again; the queue is kept and the user has to sign in or the
 *     app has to be updated.</li>
 * </ul>
 */
public final class SyncWorker extends Worker {

	private static final String TAG = "SyncWorker";

	/** How many batches one run will send before handing back to WorkManager. */
	private static final int MAX_BATCHES = 5;

	/** How many pages one run will apply. */
	private static final int MAX_PAGES = 5;

	public SyncWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
		super(context, parameters);
	}

	@Override
	@NonNull
	public Result doWork() {

		Context context = getApplicationContext();
		SyncClient client = new SyncClient(context);

		if (!client.isConfigured()) {
			// No server in this build. Not a failure: the app works entirely on its own, which
			// is the state every install is in until somebody points it at a server.
			return Result.success();
		}

		if (!DeviceIdentity.hasToken(context)) {
			// Nobody has signed in. The queue waits; there is nothing to retry.
			return Result.success();
		}

		MainDatabase db = MainDatabase.getInstance(context);
		OutboxDrain drain = new OutboxDrain(db, client);

		try {
			boolean more = pushEverything(drain);
			more |= pullEverything(drain);

			DeviceIdentity.recordSyncError(context, null);

			if (more) {
				// Enqueued rather than continued: a long-running worker is one the system
				// stops without ceremony, and the next run picks up exactly where this left
				// off because the queue and the watermark are both in the database.
				SyncScheduler.requestSync(context);
			}

			return Result.success();

		} catch (SyncClient.Unavailable e) {
			Log.i(TAG, "sync deferred: " + e.getMessage());
			DeviceIdentity.recordSyncError(context, "Waiting for a connection");
			return Result.retry();

		} catch (SyncClient.Unauthenticated e) {
			// The token is gone or expired. Forgetting it is what makes the app ask for a
			// password rather than retrying a credential the server has already refused.
			Log.w(TAG, "the session is no longer valid");
			DeviceIdentity.forgetToken(context);
			DeviceIdentity.recordSyncError(context, "Sign in again to keep syncing");
			return Result.failure();

		} catch (SyncClient.Refused e) {
			Log.e(TAG, "the server refused the request: " + e.getMessage());
			DeviceIdentity.recordSyncError(context,
					"This version of the app cannot sync with the server");
			return Result.failure();

		} catch (IOException | RuntimeException e) {
			// Includes a response this build cannot parse, which SyncJson raises as an
			// IllegalArgumentException. Retried rather than failed: a server mid-deployment
			// can answer with something odd once.
			Log.e(TAG, "sync failed", e);
			DeviceIdentity.recordSyncError(context, "Could not sync");
			return Result.retry();
		}
	}

	/** @return whether anything is still waiting to be sent */
	private boolean pushEverything(@NonNull OutboxDrain drain) throws IOException {

		for (int batch = 0; batch < MAX_BATCHES; batch++) {

			OutboxDrain.Result result = drain.push();

			if (!result.morePending()) {
				return false;
			}

			if (result.sent() == 0) {
				// Nothing moved and something is still pending, which means every intent in
				// this batch was refused. Trying the same batch again in the same run would
				// spin.
				return false;
			}
		}
		return true;
	}

	/** @return whether the server says there is more to fetch */
	private boolean pullEverything(@NonNull OutboxDrain drain) throws IOException {

		for (int page = 0; page < MAX_PAGES; page++) {

			if (!drain.pull().morePending()) {
				return false;
			}
		}
		return true;
	}
}
