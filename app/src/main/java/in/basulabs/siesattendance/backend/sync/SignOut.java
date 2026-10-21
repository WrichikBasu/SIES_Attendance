package in.basulabs.siesattendance.backend.sync;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.sqlite.db.SupportSQLiteDatabase;

import java.io.IOException;

import in.basulabs.siesattendance.backend.MainDatabase;

/**
 * Signing out, which empties the phone.
 * <p>
 * What a phone holds is what the server let the signed-in account read: a professor's courses
 * and the roll of every class they teach, a class representative's one course. Leaving it there
 * for whoever signs in next is leaving one person's view of the college open to the next — a
 * student handed a professor's phone could read the professor's students. So signing out deletes
 * every synced row, and the next sign-in starts from a snapshot of what <em>that</em> account
 * may read.
 *
 * <h3>Except what has not been sent</h3>
 * The outbox is the one thing on the phone the server does not have, and deleting it loses
 * attendance. So sign-out sends it first ({@link #sendWhatIsWaiting}) and reports what is left;
 * the screen asks before discarding anything, and only then is {@link #forgetEverything} called.
 *
 * <h3>Deleted without telling the server</h3>
 * The outbox triggers would read every one of these deletions as the user removing the row, and
 * the next sign-in would push the deletion of the whole college. They are deleted with
 * {@code sync_state.applying} set, the same flag a pull writes under, so nothing is queued.
 */
public final class SignOut {

	private static final String TAG = "SignOut";

	/** Enough batches for a phone's queue; a run that needs more is a run with a problem. */
	private static final int MAX_BATCHES = 20;

	private SignOut() {
	}

	/**
	 * Sends what is waiting, as far as the server will take it.
	 *
	 * @return how many changes are still not on the server: refused and kept, or unsent for
	 * want of a connection. Zero means signing out loses nothing
	 */
	public static int sendWhatIsWaiting(@NonNull Context context) {

		MainDatabase db = MainDatabase.getInstance(context);
		OutboxDrain drain = new OutboxDrain(db, new SyncClient(context));

		try {
			for (int batch = 0; batch < MAX_BATCHES; batch++) {

				OutboxDrain.Result result = drain.push();

				if (!result.morePending() || (result.sent() == 0 && result.dropped() == 0)) {
					break;
				}
			}

		} catch (IOException | RuntimeException couldNotSend) {
			// Counted below either way: what did not go is still in the queue.
			Log.i(TAG, "could not send everything before signing out: "
					+ couldNotSend.getMessage());
		}

		return db.daoOutbox().countPendingSync();
	}

	/**
	 * Ends the session and empties the phone of everything that came with it.
	 * <p>
	 * The installation is kept: its ID, the node the server gave it, and so the keys it issues.
	 * Those belong to the phone, not to whoever signed in on it.
	 */
	public static void forgetEverything(@NonNull Context context) {

		try {
			new SyncClient(context).logout();
		} catch (IOException e) {
			// Best effort, as before: the token is forgotten either way.
		}

		// The token first. A pull already under way checks for it before writing its page,
		// so a page that arrives after this point is dropped rather than written back over
		// the empty database.
		DeviceIdentity.forgetToken(context);
		SyncScheduler.cancelPending(context);

		emptyThePhone(context);
		DeviceIdentity.recordLastAccountId(context, 0L);
	}

	/**
	 * Deletes every synced row, the outbox and what the last account's sign-in recorded about it.
	 * <p>
	 * The session's token and the installation are left alone, so this serves a sign-in by
	 * somebody else as well as a sign-out: see {@code SyncSignIn}. Whatever is still in the
	 * outbox is lost, so a caller has either sent it or been told to discard it.
	 */
	static void emptyThePhone(@NonNull Context context) {

		MainDatabase db = MainDatabase.getInstance(context);

		db.runInTransaction(() -> {

			SupportSQLiteDatabase sqlite = db.getOpenHelper().getWritableDatabase();
			db.daoOutbox().setApplyingSync(1);

			try {
				for (String statement : DeviceSql.clearEverything()) {
					sqlite.execSQL(statement);
				}

				db.daoOutbox().forgetWatermarkSync();

			} finally {
				db.daoOutbox().setApplyingSync(0);
			}
		});

		DeviceIdentity.recordSyncError(context, null);
		DeviceIdentity.recordDisplayName(context, null);
		DeviceIdentity.recordAccountKind(context, null);
		DeviceIdentity.recordRepresents(context, null);
		DeviceIdentity.recordRoles(context, null);
		DeviceIdentity.recordImportDepartments(context, null);
		DeviceIdentity.recordLastRun(context, 0, 0);
		DeviceIdentity.clearRefusals(context);
	}
}
