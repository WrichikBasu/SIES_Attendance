package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Three rules about the sync that only a phone can show are broken, pinned at source level.
 * <p>
 * Each of these was found by hand on a handset — a stack trace in the log for having no signal,
 * a stale sentence on a screen, a file beside the database still holding the last account's
 * students — and each would have to be found that way again. They need a radio, a visible
 * screen, or a look inside a file on the device, so neither the unit suites nor the
 * instrumented ones catch a regression: the instrumented tests sync against a server that is
 * always there, over a connection the script has just proved works.
 * <p>
 * Reading the source is a poor test and it is the one available. It is the same trade
 * {@code DialogBuilderContractTest} makes, for the same reason: a trap the compiler cannot see
 * and nothing else is watching. Each test below says what the behaviour is, so that whoever
 * trips it knows what to go and check on a phone rather than only how to make it pass again.
 *
 * @see DialogBuilderContractTest
 */
public class SyncHygieneContractTest {

	private static final Path SOURCE =
			Paths.get("src/main/java/in/basulabs/siesattendance");

	private static List<String> lines(String path) throws IOException {
		return Files.readAllLines(SOURCE.resolve(path), StandardCharsets.UTF_8);
	}

	private static String text(String path) throws IOException {
		return String.join("\n", lines(path));
	}

	/**
	 * Every line where {@code SyncClient} actually touches the network throws
	 * {@link in.basulabs.siesattendance.backend.sync.SyncClient.Unavailable} when it fails.
	 * <p>
	 * {@code openConnection()} opens nothing. The socket is made at {@code getOutputStream()}
	 * for a request with a body and at {@code getResponseCode()} for one without, so those are
	 * the two lines that throw when the phone has no signal. The first of them was not wrapped:
	 * a push with no connection left here as a plain {@code ConnectException}, which
	 * {@code SyncWorker} did not recognise, so the most ordinary event there is — a professor
	 * out of signal — was reported as "Could not sync" with a stack trace in the log, instead
	 * of "Waiting for a connection" and a retry.
	 * <p>
	 * Only {@code call}, which is the one method that reports by throwing. {@code probe} makes
	 * the same two calls and answers with a {@link
	 * in.basulabs.siesattendance.backend.sync.SyncClient.Probe} instead, so there is nothing
	 * for it to wrap.
	 */
	@Test
	public void everyLineThatConnectsReportsNoConnectionAsSuch() throws IOException {

		List<String> source = lines("backend/sync/SyncClient.java");

		int from = -1;
		int to = source.size();

		for (int i = 0; i < source.size(); i++) {
			if (source.get(i).contains("private String call(")) {
				from = i;
			} else if (from >= 0 && source.get(i).equals("\t}")) {
				// The first line that closes a member at the class's own indent: the end of
				// call(), whatever has been added inside it.
				to = i;
				break;
			}
		}

		assertTrue("SyncClient no longer has the one request method", from >= 0);

		List<Integer> connects = new ArrayList<>();

		for (int i = from; i < to; i++) {

			String line = source.get(i);

			// Code only. A comment that names one of these is not a call -- the catch below
			// the first of them explains itself by naming the second, which was enough to
			// have this test read the comment as a site and stop the window short of the
			// throw it was looking for.
			if (line.trim().startsWith("//") || line.trim().startsWith("*")) {
				continue;
			}

			if (line.contains("getOutputStream()") || line.contains("getResponseCode()")) {
				connects.add(i);
			}
		}

		assertFalse("SyncClient.call no longer makes a request", connects.isEmpty());

		for (int k = 0; k < connects.size(); k++) {

			int site = connects.get(k);

			// Each one is read as far as the next one and no further. A fixed number of lines
			// would let one of these borrow the next one's catch and pass on it, which is
			// exactly what happened when this test was first written: the two are twenty lines
			// apart, so the unwrapped write was covered by the wrapping of the read below it.
			int until = k + 1 < connects.size() ? connects.get(k + 1) : to;

			String between = String.join("\n", source.subList(site, until));

			assertTrue("SyncClient.java:" + (site + 1) + " connects, and an IOException from it"
							+ " has to leave as Unavailable so the worker can tell the"
							+ " professor they are offline and retry:\n" + between,
					between.contains("throw new Unavailable"));
		}
	}

	/**
	 * The sync screen re-reads what it shows when a run finishes.
	 * <p>
	 * The two lines about the queue and the last pull are Room queries with observers, so they
	 * keep themselves current. Everything else on that screen — the transport error above all
	 * — lives in the preferences and was read only when the screen was built or came back to
	 * the front. So "Could not sync" sat there through syncs that had since succeeded, and
	 * leaving the screen and returning was the only thing that cleared it.
	 */
	@Test
	public void theSyncScreenRereadsItselfWhenARunFinishes() throws IOException {

		String source = text("frontend/FragmentSync.java");
		int observer = source.indexOf("getSyncRunning().observe");

		assertTrue("FragmentSync no longer watches whether a sync is running", observer > 0);

		// To the end of that observer and no further. The call this is looking for also sits a
		// few lines below it, at the end of onViewCreated, and a window that overran into it
		// passed with the observer emptied out -- which is how this test was first written.
		int ends = source.indexOf("\n\t\t});", observer);

		assertTrue("the observer is no longer a block that ends", ends > observer);

		String body = source.substring(observer, ends);

		assertTrue("the end of a run is the moment the preferences this screen shows have "
						+ "just been written, so it has to read them again there:\n" + body,
				body.contains("refresh()"));
	}

	/**
	 * Signing out takes the rows out of the files as well as out of the tables.
	 * <p>
	 * A {@code DELETE} in WAL mode writes the new pages to the write-ahead log and leaves the
	 * old ones in it until something checkpoints them, so after a sign-out the tables read
	 * empty while {@code sies_attendance.sqlite3-wal} still held the last account's roll
	 * numbers and names — 940 of them, counted in the file on a real phone, which is the whole
	 * of what signing out is supposed to prevent.
	 * <p>
	 * Measured on sqlite3 directly: 2 000 rows deleted leave 2 000 copies of the name in the
	 * {@code -wal} and none in the database, and after {@code VACUUM} plus a truncating
	 * checkpoint the log is zero bytes and neither file holds any.
	 */
	@Test
	public void signingOutEmptiesTheDatabaseFilesAndNotOnlyTheTables() throws IOException {

		String source = text("backend/sync/SignOut.java");

		assertTrue("emptyThePhone deletes the rows; something has to take them out of the "
				+ "files", source.contains("scrubTheFiles(db)"));
		assertTrue("VACUUM is what rebuilds the database without the pages the delete freed",
				source.contains("VACUUM"));
		assertTrue("and a truncating checkpoint is what empties the write-ahead log",
				source.contains("wal_checkpoint(TRUNCATE)"));

		// It cannot be inside the transaction that does the deleting: SQLite refuses both
		// statements there, so a sign-out would end in an exception rather than a scrub.
		int transaction = source.indexOf("db.runInTransaction");
		int scrub = source.indexOf("scrubTheFiles(db)");

		assertTrue(transaction > 0 && scrub > transaction);
		assertFalse("neither statement runs inside a transaction",
				source.substring(transaction, scrub).contains("VACUUM"));
	}
}
