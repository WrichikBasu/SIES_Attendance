package in.basulabs.siesattendance.device;

import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.LocalDate;
import java.time.ZoneId;

import in.basulabs.siesattendance.backend.MainDatabase;
import in.basulabs.siesattendance.backend.migration.MigrationContext;
import in.basulabs.siesattendance.backend.migration.SchemaMigrations;
import in.basulabs.siesattendance.device.support.OldPhone;
import in.basulabs.siesattendance.device.support.StagingFiles;

/**
 * A database file built the way the server's {@code DeviceDatabase.createAtCurrentVersion}
 * builds the ones it packs into backups, opened by Room on a phone.
 * <p>
 * The server starts from its hand-written version 6 and runs the shared migrations to the
 * current version, then stamps {@code user_version}. Room, opening a current-version file that
 * has no {@code room_master_table}, falls back to validating the whole schema against the
 * entities. Whatever the server leaves out, Room notices here; on a teacher's phone it would
 * notice at restore. It runs on {@link OldPhone}'s copy of the server's DDL, not the server's
 * own, so it only speaks for the server while the two copies are kept identical. (The server's
 * once lacked the three {@code student_year} indices, which this caught.)
 */
@RunWith(AndroidJUnit4.class)
public class ServerBuiltFileDeviceTest {

	private String name;

	@Before
	public void buildItAsTheServerDoes() {

		name = StagingFiles.freshName();

		try (OldPhone phone = OldPhone.atVersion6(name)) {
			phone.migrateTo(SchemaMigrations.CURRENT_VERSION,
					new MigrationContext(ZoneId.of("Asia/Kolkata"), LocalDate.of(2026, 3, 1)));
		}
	}

	@After
	public void throwItAway() {
		StagingFiles.delete(name);
	}

	@Test
	public void roomOpensAFileTheServerBuilt() {

		MainDatabase room = MainDatabase.openStaging(StagingFiles.context(), name);

		try {
			assertEquals(SchemaMigrations.CURRENT_VERSION,
					room.getOpenHelper().getWritableDatabase().getVersion());
		} finally {
			room.close();
		}
	}
}
