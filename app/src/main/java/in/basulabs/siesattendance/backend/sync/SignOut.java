package in.basulabs.siesattendance.backend.sync;

import android.content.Context;
import android.database.Cursor;
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

		scrubTheFiles(db);

		DeviceIdentity.recordSyncError(context, null);
		DeviceIdentity.recordDisplayName(context, null);
		DeviceIdentity.recordAccountKind(context, null);
		DeviceIdentity.recordRepresents(context, null);
		DeviceIdentity.recordRoles(context, null);
		DeviceIdentity.recordImportDepartments(context, null);
		DeviceIdentity.recordBatchDepartments(context, null);
		DeviceIdentity.recordAppoints(context, null);
		DeviceIdentity.recordReadsOnly(context, null);
		DeviceIdentity.recordCoordinatedBatches(context, null);
		DeviceIdentity.recordLastRun(context, 0, 0);
		DeviceIdentity.clearRefusals(context);

		// The next account starts from a snapshot of its own, so it has nothing to sweep.
		DeviceIdentity.recordAccessFingerprint(context, null);
		DeviceIdentity.recordSweepWanted(context, false);
		DeviceIdentity.recordLastSweepAt(context, 0L);
	}

	/**
	 * Takes the deleted rows out of the database's files, not only out of its tables.
	 * <p>
	 * A {@code DELETE} in WAL mode writes the new page images to
	 * {@code sies_attendance.sqlite3-wal} and leaves them there until something checkpoints
	 * them, and a page the delete frees keeps whatever was written in it, because SQLite does
	 * not blank one unless it is told to. So after a sign-out the tables read empty while the
	 * file beside the database still held the last account's roll numbers and names — 940 of
	 * them, counted in the file on a real phone. Signing out is supposed to be the thing that
	 * makes a phone safe to hand to somebody else.
	 * <p>
	 * {@code VACUUM} rebuilds the database with no free pages in it, and the truncating
	 * checkpoint then takes the write-ahead log down to nothing. Neither can run inside a
	 * transaction, which is why this is not part of the one above.
	 * <p>
	 * Best effort, and deliberately: {@code VACUUM} wants room for a second copy of the
	 * database and can fail on a full phone. The rows are already deleted by the time this
	 * runs, so a failure here is a file that still holds old bytes, not an account still
	 * signed in — worth a line in the log and not worth failing a sign-out over.
	 */
	private static void scrubTheFiles(@NonNull MainDatabase db) {

		try {
			SupportSQLiteDatabase sqlite = db.getOpenHelper().getWritableDatabase();

			sqlite.execSQL("VACUUM");

			// A pragma that answers with a row, so it is run as a query: execSQL is for
			// statements that return nothing and would not step this one to completion.
			try (Cursor checkpointed = sqlite.query("PRAGMA wal_checkpoint(TRUNCATE)")) {
				checkpointed.moveToFirst();
			}

		} catch (RuntimeException e) {
			Log.w(TAG, "could not scrub the database files after emptying them", e);
		}
	}
}
