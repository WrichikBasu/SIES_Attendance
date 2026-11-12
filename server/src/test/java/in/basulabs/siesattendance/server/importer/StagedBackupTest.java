package in.basulabs.siesattendance.server.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.ZoneId;

/**
 * What the server does with the database inside an uploaded backup before it reads a row.
 */
class StagedBackupTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	@TempDir
	Path work;

	/**
	 * A backup can carry SQL of its own -- a trigger that fires when the migrations write to the
	 * staged copy, a view standing in for a table -- and only table names used to be checked
	 * (R6). The staged copy has none of either by the time anything reads it.
	 */
	@Test
	void aBackupsOwnTriggersAndViewsAreGoneBeforeAnythingReadsIt() throws Exception {

		Path backup;

		try (DeviceBackup device = DeviceBackup.emptyAtVersion6(work.resolve("device"))) {
			device.migrateToCurrent(IST);
			device.write("CREATE TRIGGER planted AFTER UPDATE ON students BEGIN "
					+ "UPDATE students SET name = 'HIJACKED'; END");
			device.write("CREATE VIEW planted_view AS SELECT 1 AS one");
			backup = device.pack(work.resolve("planted.siesbak"), null);
		}

		try (StagedBackup staged = StagedBackup.open(backup, null, IST,
				work.resolve("staging"));
		     Statement statement = staged.connection().createStatement();
		     ResultSet results = statement.executeQuery("SELECT count(*) FROM sqlite_master "
				     + "WHERE name IN ('planted', 'planted_view')")) {

			results.next();
			assertEquals(0, results.getLong(1), "the backup's own trigger and view survived");
		}
	}
}
