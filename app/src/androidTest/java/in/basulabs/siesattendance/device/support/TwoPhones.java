package in.basulabs.siesattendance.device.support;

import androidx.annotation.NonNull;

import org.junit.After;
import org.junit.Before;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

import in.basulabs.siesattendance.backend.MainDatabase;

/**
 * Two staging databases standing for two phones of the account a subclass signs in as.
 * Phone A has already pulled what the server lets that account see, as a real phone would
 * have before anybody used it; phone B starts empty, for checking what reached the server.
 * Both are deleted afterwards.
 */
public abstract class TwoPhones {

	protected MainDatabase phoneA;

	protected MainDatabase phoneB;

	/** Unique per test, so rows from one test never collide with another's on the server. */
	protected String tag;

	private String nameA;

	private String nameB;

	@Before
	public final void twoPhonesThatHavePulled() throws IOException {

		ServerSync.assumeSignedIn();

		tag = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);

		nameA = StagingFiles.freshName();
		nameB = StagingFiles.freshName();
		phoneA = MainDatabase.openStaging(StagingFiles.context(), nameA);
		phoneB = MainDatabase.openStaging(StagingFiles.context(), nameB);

		ServerSync.pullAll(phoneA);
	}

	@After
	public final void throwThemAway() {
		if (phoneA != null) {
			phoneA.close();
		}
		if (phoneB != null) {
			phoneB.close();
		}
		StagingFiles.delete(nameA);
		StagingFiles.delete(nameB);
	}

	/** Deletes a row with plain SQL, so the triggers queue the deletion as a teacher's would. */
	protected static void delete(@NonNull MainDatabase db, @NonNull String table,
	                             @NonNull String keyColumn, long key) {
		db.getOpenHelper().getWritableDatabase().execSQL(
				"DELETE FROM `" + table + "` WHERE `" + keyColumn + "` = ?", new Object[]{key});
	}
}
