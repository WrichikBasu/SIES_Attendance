package in.basulabs.siesattendance.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.database.sqlite.SQLiteDatabase;

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
 * A file from a newer release than this one.
 * <p>
 * There is no {@code fallbackToDestructiveMigrationOnDowngrade} in the builder, on purpose. A
 * teacher who installs an older APK over a newer one must get an error, not an empty database,
 * and the file must be left exactly as it was so the newer APK can open it again.
 */
@RunWith(AndroidJUnit4.class)
public class DowngradeDeviceTest {

	private String name;

	@Before
	public void buildAFileFromANewerRelease() {

		name = StagingFiles.freshName();

		try (OldPhone phone = OldPhone.atVersion6(name)) {
			phone.migrateTo(SchemaMigrations.CURRENT_VERSION,
					new MigrationContext(ZoneId.of("Asia/Kolkata"), LocalDate.of(2026, 3, 1)));
			phone.student(1, "KEEP ME", "she/her", null);
			phone.claimVersion(SchemaMigrations.CURRENT_VERSION + 1);
		}
	}

	@After
	public void throwItAway() {
		StagingFiles.delete(name);
	}

	@Test
	public void roomRefusesItAndLeavesItAlone() {

		MainDatabase room = MainDatabase.openStaging(StagingFiles.context(), name);

		try {
			assertThrows(RuntimeException.class,
					() -> room.getOpenHelper().getWritableDatabase());
		} finally {
			room.close();
		}

		SQLiteDatabase raw = StagingFiles.openRaw(name);
		try {
			assertEquals(SchemaMigrations.CURRENT_VERSION + 1, raw.getVersion());
			assertEquals("KEEP ME", StagingFiles.textOf(raw,
					"SELECT name FROM students WHERE studentID = 1"));
		} finally {
			raw.close();
		}
	}
}
