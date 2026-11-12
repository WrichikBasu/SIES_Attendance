package in.basulabs.siesattendance.device.support;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.util.UUID;

import in.basulabs.siesattendance.backend.ConstantsAndStatics;

/**
 * Throwaway database files beside the app's own, never the app's own.
 * <p>
 * Every test here runs inside the app's process, with the app's data directory. The live
 * database ({@link ConstantsAndStatics#DATABASE_NAME}) is therefore one wrong call away, so
 * nothing in this package goes through {@code MainDatabase.getInstance}: each test makes a file
 * with a random name, opens it through {@code MainDatabase.openStaging} (the same builder,
 * migrations and callbacks as the live one) and deletes it afterwards.
 * <p>
 * The files live under device-protected storage because that is where
 * {@code MainDatabase.builderFor} points Room; a file made anywhere else would not be the file
 * Room then opens.
 */
public final class StagingFiles {

	private StagingFiles() {
	}

	/** The app under test, not the test APK. */
	@NonNull
	public static Context context() {
		return InstrumentationRegistry.getInstrumentation().getTargetContext();
	}

	/** A name no other run, and certainly not the live database, is using. */
	@NonNull
	public static String freshName() {

		String name = "wbdevice-" + UUID.randomUUID() + ".sqlite3";

		if (name.equals(ConstantsAndStatics.DATABASE_NAME)) {
			throw new AssertionError("refusing to touch the live database");
		}
		return name;
	}

	/** Where Room will look for {@code name}. */
	@NonNull
	public static File file(@NonNull String name) {
		return context().createDeviceProtectedStorageContext().getDatabasePath(name);
	}

	/**
	 * Opens {@code name} with the framework's own SQLite and no Room at all, for building a
	 * file at an old schema version or inspecting one Room refused to open.
	 */
	@NonNull
	public static SQLiteDatabase openRaw(@NonNull String name) {

		File file = file(name);
		File parent = file.getParentFile();

		if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
			throw new IllegalStateException("could not create " + parent);
		}
		return SQLiteDatabase.openOrCreateDatabase(file, null);
	}

	/** Deletes the file and its {@code -wal}, {@code -shm} and {@code -journal} sidecars. */
	public static void delete(@Nullable String name) {

		if (name == null || name.equals(ConstantsAndStatics.DATABASE_NAME)) {
			return;
		}
		SQLiteDatabase.deleteDatabase(file(name));
	}

	/** The first column of the first row, as a long; null for SQL NULL. */
	@Nullable
	public static Long longOf(@NonNull SQLiteDatabase db, @NonNull String sql,
	                          @NonNull String... args) {

		try (Cursor cursor = db.rawQuery(sql, args)) {
			if (!cursor.moveToFirst() || cursor.isNull(0)) {
				return null;
			}
			return cursor.getLong(0);
		}
	}

	/** The first column of the first row, as text; null for SQL NULL or no row. */
	@Nullable
	public static String textOf(@NonNull SQLiteDatabase db, @NonNull String sql,
	                            @NonNull String... args) {

		try (Cursor cursor = db.rawQuery(sql, args)) {
			if (!cursor.moveToFirst() || cursor.isNull(0)) {
				return null;
			}
			return cursor.getString(0);
		}
	}
}
