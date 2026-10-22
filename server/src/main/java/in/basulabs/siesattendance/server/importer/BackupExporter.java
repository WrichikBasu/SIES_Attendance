package in.basulabs.siesattendance.server.importer;

import androidx.annotation.NonNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import in.basulabs.siesattendance.backend.sync.DeviceDerived;
import in.basulabs.siesattendance.backend.sync.DeviceSql;
import in.basulabs.siesattendance.backend.sync.DeviceTables;
import in.basulabs.siesattendance.backend.sync.OutboxOp;
import in.basulabs.siesattendance.server.sync.SyncReader;

/**
 * Writes what an account may read into a phone database, which {@link DeviceDatabase#pack}
 * then turns into a {@code .siesbak}.
 * <p>
 * The rows come from {@link SyncReader}'s snapshot, the same pages a phone signing in for the
 * first time receives, and are written with the statements the phone applies them with. So
 * an export is exactly what a phone of this account would hold after its first sync, and
 * {@link BackupImporter} reads it back like any phone's backup.
 * <p>
 * Only the eleven tables a phone holds. Accounts, roles, class representatives, devices and
 * the audit trail are the server's own and have no place in the file.
 */
public final class BackupExporter {

	/** Rows per snapshot page; the most {@link SyncReader} hands out at once. */
	private static final int PAGE = 1000;

	private BackupExporter() {
	}

	/**
	 * Copies everything {@code server}'s actor may read into {@code device}.
	 *
	 * @param server a connection with an actor announced on its transaction
	 * @param device an empty database from {@link DeviceDatabase#createAtCurrentVersion}
	 * @param zone   the zone the phone-only columns, such as a session's epoch bounds, are
	 *               derived in
	 * @return how many rows were written
	 */
	public static int copy(@NonNull Connection server, @NonNull Connection device,
	                       @NonNull ZoneId zone) throws SQLException {
		return copy(server, device, zone, new Progress());
	}

	/** {@link #copy(Connection, Connection, ZoneId)}, saying how far it has got as it goes. */
	public static int copy(@NonNull Connection server, @NonNull Connection device,
	                       @NonNull ZoneId zone, @NonNull Progress progress)
			throws SQLException {

		progress.stage("Counting the rows");
		progress.stage("Copying", SyncReader.snapshotSize(server));

		device.setAutoCommit(false);

		String watermark = SyncReader.FROM_THE_BEGINNING;
		int rows = 0;

		try {
			while (true) {

				SyncReader.Page page = SyncReader.read(server, watermark, PAGE);

				for (SyncReader.Change change : page.changes()) {

					DeviceTables.Table table = DeviceTables.of(change.table());
					progress.rename("Copying " + table.name().replace('_', ' '));
					progress.advance();

					if (change.op() == OutboxOp.DELETE || change.row() == null) {
						// Only from the feed after the snapshot, for a row deleted while this
						// ran; the snapshot itself sends none.
						bind(device, DeviceSql.deleteRow(table), change.key());
						continue;
					}

					Map<String, Object> row = DeviceDerived.fill(table, change.row(), zone);
					String update = DeviceSql.updateRow(table);

					// Updated first, as a phone does, in case the feed after the snapshot
					// sends a row the snapshot already did.
					if (update == null
							|| write(device, update, DeviceSql.updateColumns(table), row) == 0) {
						write(device, DeviceSql.insertRow(table), DeviceSql.insertColumns(table),
								row);
						rows++;
					}
				}

				watermark = page.watermark();

				if (!page.more()) {
					break;
				}
			}

			device.commit();
			return rows;

		} catch (SQLException | RuntimeException e) {
			device.rollback();
			throw e;
		}
	}

	private static int write(@NonNull Connection device, @NonNull String sql,
	                         @NonNull List<DeviceTables.Column> columns,
	                         @NonNull Map<String, Object> row) throws SQLException {

		try (PreparedStatement statement = device.prepareStatement(sql)) {

			for (int at = 0; at < columns.size(); at++) {
				statement.setObject(at + 1, row.get(columns.get(at).room()));
			}
			return statement.executeUpdate();
		}
	}

	private static void bind(@NonNull Connection device, @NonNull String sql,
	                         @NonNull List<Long> key) throws SQLException {

		try (PreparedStatement statement = device.prepareStatement(sql)) {

			for (int at = 0; at < key.size(); at++) {
				statement.setLong(at + 1, key.get(at));
			}
			statement.executeUpdate();
		}
	}
}
