package in.basulabs.siesattendance.server.importer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.ZoneId;

import in.basulabs.siesattendance.server.testing.TestPostgres;

/**
 * White-box probe: a preview of a backup must not make the real import that follows it say
 * the file has been imported before.
 * <p>
 * BUG: fails at the time of writing. A dry run is filed in import_run as succeeded, with
 * nothing marking it as a preview, and "seen before" looks for any succeeded run of the
 * same digest.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BackupPreviewProbeTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	private static final int DEVICE = 41231;

	@TempDir
	Path work;

	private TestPostgres postgres;

	@BeforeAll
	void startDatabase() throws Exception {
		assumeTrue(TestPostgres.isAvailable(), "Docker is not available");
		postgres = TestPostgres.start();
	}

	@AfterAll
	void stopDatabase() {
		if (postgres != null) {
			postgres.close();
		}
	}

	@Test
	void aPreviewDoesNotCountAsAnImport() throws Exception {

		postgres.reset();

		Path backup;

		try (DeviceBackup device = DeviceBackup.emptyAtVersion6(work.resolve("device"))) {
			device.migrateToCurrent(IST);
			backup = device.pack(work.resolve("probe.siesbak"), null);
		}

		BackupImporter.Outcome preview = run(backup, true);
		assertTrue(preview.succeeded(), () -> "preview failed: " + preview.failure());
		assertFalse(preview.seenBefore(), "nothing has been imported yet");

		BackupImporter.Outcome real = run(backup, false);
		assertTrue(real.succeeded(), () -> "import failed: " + real.failure());
		assertFalse(real.seenBefore(),
				"only a preview came before this, and a preview writes nothing");
	}

	private BackupImporter.Outcome run(Path backup, boolean dryRun) throws Exception {
		return new BackupImporter(postgres.dataSource()).run(new BackupImporter.Request(
				backup, null, "probe", DEVICE, IST, work.resolve("staging"), dryRun));
	}
}
