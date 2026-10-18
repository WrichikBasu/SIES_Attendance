package in.basulabs.siesattendance.server.importer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import in.basulabs.siesattendance.backend.StructuredId;

/**
 * Imports one device's backup into the central database.
 *
 * <h3>What one run does</h3>
 * <ol>
 *     <li>Decrypts the backup, checks it, and brings it to the current schema through the app's
 *     own migrations — all of it before the PostgreSQL transaction opens, so a bad file costs
 *     nothing. See {@link StagedBackup}.</li>
 *     <li>Records the attempt in {@code import_run}, and commits that row immediately, so a
 *     failure is still on record afterwards.</li>
 *     <li>Resolves every table in dependency order inside a single transaction. See
 *     {@link ImportSession}.</li>
 *     <li>Commits, and writes the report back onto the run row.</li>
 * </ol>
 *
 * <h3>Re-running it</h3>
 * Importing the same backup twice changes nothing the second time, and that is a property of
 * how rows are resolved rather than of a check on the file: every row is matched before it is
 * written, by key where the key means something and by natural key otherwise. The digest is
 * recorded so a re-run can <em>say</em> that this is a file it has seen, because an import that
 * reports nothing is otherwise indistinguishable from one that silently did nothing.
 *
 * <h3>One transaction</h3>
 * The whole import either lands or none of it does. V2's constraints will reject historical
 * rows — that is their job — and when one is rejected the right response is to look at the data,
 * not to let the row in. Leaving a half-imported device behind would make that impossible to
 * reason about, so the transaction is the unit and the report says what was being attempted.
 */
public final class BackupImporter {

	/**
	 * What to import, and what to call it.
	 *
	 * @param backup      the {@code .siesbak} file
	 * @param password    its password, or null if it is not encrypted. Wiped once the file is
	 *                    open
	 * @param sourceLabel how this device is referred to in the report, e.g. "Dr Rao's phone"
	 * @param sourceNode  the node number of the device this backup came from. Needed because a
	 *                    legacy key carries no node and the identity map has to be keyed by
	 *                    one. Getting it wrong fuses two devices' legacy rows together, which
	 *                    is why it is asked for rather than guessed
	 * @param zone        the zone the device recorded its local dates at
	 * @param workDir     where the decrypted copy is written
	 * @param dryRun      when true the transaction is rolled back at the end and the report is
	 *                    produced anyway. This is how a device is looked at before it is let in
	 * @param actorAccount the {@code app_account} this import is performed as, or null.
	 *                    Required once V6's policies are in place and the connection is not
	 *                    exempt from them: {@code attendance} and {@code lectures} are under
	 *                    {@code FORCE ROW LEVEL SECURITY}, so not even {@code sies_owner}
	 *                    bypasses them, and an import with no actor fails on the first
	 *                    lecture: the resolution step cannot see what is already there, so it
	 *                    inserts, and {@code WITH CHECK} refuses the insert. The account needs the {@code ADMIN} role, which
	 *                    is the policy that lets it reach every department — and that is the
	 *                    honest shape, because somebody is accountable for loading a device's
	 *                    history whether the rows themselves name an author or not. Null is
	 *                    for a connection that is exempt anyway, which is what the tests use
	 */
	public record Request(@NonNull Path backup, @Nullable char[] password,
	                      @NonNull String sourceLabel, int sourceNode, @NonNull ZoneId zone,
	                      @NonNull Path workDir, boolean dryRun,
	                      @Nullable Long actorAccount) {

		/** The six-argument form, for a connection that bypasses row-level security. */
		public Request(@NonNull Path backup, @Nullable char[] password,
		               @NonNull String sourceLabel, int sourceNode, @NonNull ZoneId zone,
		               @NonNull Path workDir, boolean dryRun) {
			this(backup, password, sourceLabel, sourceNode, zone, workDir, dryRun, null);
		}
	}

	/** What happened. */
	public record Outcome(long runId, @NonNull ImportReport report, boolean succeeded,
	                      @Nullable String failure, boolean seenBefore,
	                      @NonNull String digest, int schemaVersionAsFound) {
	}

	@NonNull
	private final DataSource dataSource;

	public BackupImporter(@NonNull DataSource dataSource) {
		this.dataSource = dataSource;
	}

	/**
	 * Runs one import.
	 *
	 * @throws StagedBackup.RejectedException if the file cannot be imported at all, in which
	 *                                        case nothing was written and no run was recorded —
	 *                                        there is no import to report on
	 * @throws SQLException                   if the database could not be reached. A row the
	 *                                        database refuses does not come out here; it fails
	 *                                        the run, which is recorded and returned
	 */
	@NonNull
	public Outcome run(@NonNull Request request)
			throws StagedBackup.RejectedException, SQLException {

		if (request.sourceNode() < StructuredId.FIRST_ASSIGNABLE_NODE_ID
				|| request.sourceNode() > StructuredId.MAX_NODE_ID) {

			throw new IllegalArgumentException("The source node must be between "
					+ StructuredId.FIRST_ASSIGNABLE_NODE_ID + " and "
					+ StructuredId.MAX_NODE_ID + " (node "
					+ StructuredId.SERVER_NODE + " is the server's own): "
					+ request.sourceNode());
		}

		try (StagedBackup backup = StagedBackup.open(request.backup(), request.password(),
				request.zone(), request.workDir())) {

			try (Connection server = dataSource.getConnection()) {

				boolean seenBefore = hasBeenImported(server, backup.digest());
				long runId = startRun(server, request, backup);

				ServerIds ids = new ServerIds();
				ids.primeFrom(server);

				ImportReport report = new ImportReport();

				server.setAutoCommit(false);

				try {
					// Inside the transaction, because set_config(..., true) is
					// transaction-scoped: set before the BEGIN it would be discarded, and
					// every policy would then see a null actor and match nothing.
					setActor(server, request.actorAccount());

					new ImportSession(backup.connection(), server, ids, request.sourceNode(),
							report).importAll();

					if (request.dryRun()) {
						server.rollback();
					} else {
						server.commit();
					}

					server.setAutoCommit(true);
					finishRun(server, runId, report, true, null);

					return new Outcome(runId, report, true, null, seenBefore,
							backup.digest(), backup.schemaVersionAsFound());

				} catch (SQLException | RuntimeException e) {

					server.rollback();
					server.setAutoCommit(true);

					String failure = e.getMessage() == null
							? e.getClass().getSimpleName()
							: e.getMessage();

					finishRun(server, runId, report, false, failure);

					return new Outcome(runId, report, false, failure, seenBefore,
							backup.digest(), backup.schemaVersionAsFound());
				}
			}
		}
	}

	/**
	 * Announces who is importing, for the row-level security policies.
	 * <p>
	 * All three settings are written, two of them empty, for the same reason the service
	 * writes all three: a policy must never have to ask which of them is populated. The
	 * importer is neither a professor nor a student, so both of those are empty and the
	 * {@code ADMIN} role on the account is what the policies match.
	 * <p>
	 * A null account sets nothing at all, which is correct for a connection that is exempt
	 * from the policies — a superuser, as the tests use. It is not correct for production, and
	 * the symptom there is the whole import failing on its first lecture with "new row
	 * violates row-level security policy", which is at least unambiguous.
	 */
	private static void setActor(@NonNull Connection server, @Nullable Long account)
			throws SQLException {

		if (account == null) {
			return;
		}

		try (PreparedStatement statement = server.prepareStatement(
				"SELECT set_config('app.account_id', ?, true),"
						+ "       set_config('app.prof_id',    '', true),"
						+ "       set_config('app.student_id', '', true)")) {

			statement.setString(1, Long.toString(account));
			statement.execute();
		}
	}

	/**
	 * Whether this exact file has been imported successfully before.
	 * <p>
	 * Not a reason to refuse it: a second run is harmless and is sometimes exactly what
	 * somebody wants to see. It is here so the importer can say so, since an import that
	 * reports nothing looks the same as one that failed to read anything.
	 */
	private boolean hasBeenImported(@NonNull Connection server, @NonNull String digest)
			throws SQLException {

		try (PreparedStatement statement = server.prepareStatement(
				"SELECT 1 FROM import_run WHERE backup_digest = ? AND succeeded LIMIT 1")) {

			statement.setString(1, digest);

			try (ResultSet results = statement.executeQuery()) {
				return results.next();
			}
		}
	}

	/**
	 * Records the attempt and commits it, so that a run which then fails is still on record
	 * with the reason.
	 */
	private long startRun(@NonNull Connection server, @NonNull Request request,
	                      @NonNull StagedBackup backup) throws SQLException {

		String sql = "INSERT INTO import_run (source_label, source_node, backup_digest, "
				+ "backup_created_at, backup_schema) VALUES (?, ?, ?, ?, ?) RETURNING run_id";

		try (PreparedStatement statement = server.prepareStatement(sql)) {

			statement.setString(1, request.sourceLabel());
			statement.setInt(2, request.sourceNode());
			statement.setString(3, backup.digest());

			long createdAt = backup.info().createdAt;

			if (createdAt > 0) {
				statement.setTimestamp(4, new Timestamp(createdAt));
			} else {
				statement.setNull(4, Types.TIMESTAMP_WITH_TIMEZONE);
			}

			statement.setInt(5, backup.schemaVersionAsFound());

			try (ResultSet results = statement.executeQuery()) {

				if (!results.next()) {
					throw new SQLException("import_run did not return a run_id");
				}
				return results.getLong(1);
			}
		}
	}

	/** Writes the outcome onto the run row. */
	private void finishRun(@NonNull Connection server, long runId,
	                       @NonNull ImportReport report, boolean succeeded,
	                       @Nullable String failure) throws SQLException {

		String sql = "UPDATE import_run SET finished_at = now(), succeeded = ?, "
				+ "inserted = ?, updated = ?, skipped = ?, report = ?::jsonb, failure = ? "
				+ "WHERE run_id = ?";

		try (PreparedStatement statement = server.prepareStatement(sql)) {

			statement.setBoolean(1, succeeded);
			statement.setInt(2, report.totalInserted());
			statement.setInt(3, report.totalUpdated());
			statement.setInt(4, report.skipped().size());
			statement.setString(5, report.toJson());
			statement.setString(6, failure);
			statement.setLong(7, runId);

			statement.executeUpdate();
		}
	}

	/**
	 * Every run so far, newest first, for the reconciliation at the end of the migration.
	 */
	@NonNull
	public List<String> history() throws SQLException {

		List<String> rows = new ArrayList<>();

		String sql = "SELECT run_id, source_label, source_node, backup_schema, started_at, "
				+ "succeeded, inserted, updated, skipped FROM import_run "
				+ "ORDER BY started_at DESC, run_id DESC";

		try (Connection server = dataSource.getConnection();
		     PreparedStatement statement = server.prepareStatement(sql);
		     ResultSet results = statement.executeQuery()) {

			while (results.next()) {
				rows.add(String.format("#%d  %-24s node %-6d v%-3d %s  %s  +%d ~%d !%d",
						results.getLong("run_id"),
						results.getString("source_label"),
						results.getInt("source_node"),
						results.getInt("backup_schema"),
						results.getTimestamp("started_at"),
						results.getBoolean("succeeded") ? "ok    " : "FAILED",
						results.getInt("inserted"),
						results.getInt("updated"),
						results.getInt("skipped")));
			}
		}
		return rows;
	}
}
