package in.basulabs.siesattendance.device.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import in.basulabs.siesattendance.backend.AccountKind;
import in.basulabs.siesattendance.backend.IdGenerator;
import in.basulabs.siesattendance.backend.MainDatabase;
import in.basulabs.siesattendance.backend.sync.OutboxDrain;
import in.basulabs.siesattendance.backend.sync.ServerAddress;
import in.basulabs.siesattendance.backend.sync.SyncClient;
import in.basulabs.siesattendance.backend.sync.SyncSignIn;

/**
 * What every test against a real server needs: signing in as the account the script made, and
 * draining a staging database the way {@code SyncWorker} drains the live one.
 * <p>
 * The server, the accounts and their passwords come from {@code verify-device-sync.sh} as
 * instrumentation arguments. Without them every test skips (see {@link #assumeSignedIn}), and
 * the script counts a skip as a failure, so a plain {@code connectedDebugAndroidTest} stays
 * green without a server and the gate still cannot pass by skipping.
 * <p>
 * The fixture's keys, which the classes name, are written in the script; see its fixture block.
 */
public final class ServerSync {

	/** {@code ServerAddress.PREFS} and its key, for a client built against a dead address. */
	private static final String ADDRESS_PREFS = "sync_server";

	private static final String ADDRESS_KEY = "address";

	private ServerSync() {
	}

	/** Whether the current class signed in; false when no server was given. */
	private static boolean signedIn;

	/**
	 * Points the app at the script's server and signs in as the account named by the
	 * instrumentation arguments {@code emailArg} and {@code passwordArg}, checking the server
	 * agrees on what kind of account it is.
	 * <p>
	 * With no server given it does nothing, and {@link #assumeSignedIn} then skips each test.
	 * Skipping here, in a {@code @BeforeClass}, would report the whole class as one nameless
	 * test, and the runner would then count fewer tests than it expected and fail the run.
	 */
	public static void signIn(@NonNull String emailArg, @NonNull String passwordArg,
	                          @NonNull SyncSignIn.Door door, @Nullable AccountKind kind)
			throws IOException {

		Bundle args = InstrumentationRegistry.getArguments();
		String server = args.getString("siesServer");
		String email = args.getString(emailArg);
		String password = args.getString(passwordArg);

		signedIn = false;
		if (server == null || email == null || password == null) {
			return;
		}

		Context context = StagingFiles.context();
		ServerAddress.choose(context, ServerAddress.normalise(server));

		SyncSignIn.Session session = SyncSignIn.signIn(context, email, door,
				password.toCharArray(), true);

		assertFalse("the script changes the first password before the phone signs in",
				session.mustRotate());
		assertNotNull("signing in registers the phone and assigns it a node", session.node());
		assertEquals("and keys are issued under that node from now on",
				session.node().intValue(), IdGenerator.nodeID());
		if (kind != null) {
			assertEquals("the server says what kind of account this is", kind, session.kind());
		}
		signedIn = true;
	}

	/** Skips the test when the class had no server to sign in to. */
	public static void assumeSignedIn() {
		assumeTrue("no server or account given: run server/tools/verify-device-sync.sh",
				signedIn);
	}

	/** A whole drain's worth of push results, added up. */
	public static final class Totals {

		public int sent;

		public int rejected;

		public int dropped;

		public final List<String> refusals = new ArrayList<>();

		/** Refused in any way, kept or dropped. */
		public int refused() {
			return rejected + dropped;
		}

		@NonNull
		@Override
		public String toString() {
			return "sent " + sent + ", kept " + rejected + ", dropped " + dropped
					+ ", refusals " + refusals;
		}
	}

	/** Pushes until the queue says there is nothing more, as {@code SyncWorker} does. */
	@NonNull
	public static Totals pushAll(@NonNull MainDatabase db) throws IOException {
		return pushAll(db, new SyncClient(StagingFiles.context()));
	}

	@NonNull
	public static Totals pushAll(@NonNull MainDatabase db, @NonNull SyncClient client)
			throws IOException {

		OutboxDrain drain = new OutboxDrain(db, client);
		Totals totals = new Totals();

		for (int round = 0; round < 50; round++) {

			OutboxDrain.Result result = drain.push();
			totals.sent += result.sent();
			totals.rejected += result.rejected();
			totals.dropped += result.dropped();
			totals.refusals.addAll(result.refusals());

			if (!result.morePending() || !result.didSomething()) {
				return totals;
			}
		}
		throw new AssertionError("the push never finished");
	}

	/** Pulls until the server says there is no more, and returns how many changes applied. */
	public static int pullAll(@NonNull MainDatabase db) throws IOException {

		OutboxDrain drain = new OutboxDrain(db, new SyncClient(StagingFiles.context()));
		int applied = 0;

		for (int round = 0; round < 200; round++) {

			OutboxDrain.Result result = drain.pull();
			applied += result.applied();

			if (!result.morePending()) {
				assertNotNull("a finished pull leaves a watermark",
						db.daoOutbox().stateSync().watermark);
				return applied;
			}
		}
		throw new AssertionError("the pull never finished");
	}

	/**
	 * Runs {@code writes} with the outbox triggers held off, so what it writes is on this phone
	 * and not queued: a row a pull did not bring, as a phone left over from another account or
	 * a hand-edited file might hold one.
	 */
	public static void withoutQueueing(@NonNull MainDatabase db, @NonNull Runnable writes) {

		db.daoOutbox().setApplyingSync(1);
		try {
			writes.run();
		} finally {
			db.daoOutbox().setApplyingSync(0);
		}
	}

	/**
	 * A {@link SyncClient} for {@code address}, leaving the chosen address as it was.
	 * <p>
	 * Written straight into the preference rather than through {@code ServerAddress.choose},
	 * which would also mark the account as belonging to another server.
	 */
	@NonNull
	public static SyncClient clientFor(@NonNull String address) {

		Context context = StagingFiles.context();
		SharedPreferences prefs = context.createDeviceProtectedStorageContext()
				.getSharedPreferences(ADDRESS_PREFS, Context.MODE_PRIVATE);
		String chosen = prefs.getString(ADDRESS_KEY, null);

		assertTrue(prefs.edit().putString(ADDRESS_KEY, address).commit());
		try {
			return new SyncClient(context);
		} finally {
			assertTrue(prefs.edit().putString(ADDRESS_KEY, chosen).commit());
		}
	}
}
