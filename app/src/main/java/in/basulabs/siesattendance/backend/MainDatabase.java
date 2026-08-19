package in.basulabs.siesattendance.backend;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Database(entities = {
		EntityStudent.class,
		EntityStudentYear.class,
		EntityCourse.class,
		EntityBatchDesc.class,
		EntityBatchStudentMap.class,
		EntityCourseBatchMap.class,
		EntityLecture.class,
		EntityAttendance.class,
		EntityProfessor.class,
		EntityAcademicYear.class
}, version = ConstantsAndStatics.DATABASE_VERSION, exportSchema = false)
@TypeConverters({TypeConvertors.class})
public abstract class MainDatabase extends RoomDatabase {

	private static volatile MainDatabase self;

	/**
	 * A single shared pool for every write. Room refuses main-thread writes, and funnelling
	 * them through one executor keeps insert ordering predictable during CSV import.
	 */
	public static final ExecutorService DB_EXECUTOR = Executors.newFixedThreadPool(4);

	public static synchronized MainDatabase getInstance(@NonNull Context context) {

		if (self == null) {
			self = builderFor(context, ConstantsAndStatics.DATABASE_NAME).build();
		}
		return self;
	}

	/**
	 * Opens a second, throwaway database beside the live one.
	 * <p>
	 * Used by the merge restore, which needs a backup brought up to the current schema before
	 * its rows can be read. Going through Room rather than raw SQL means the existing
	 * migrations do that work, so a backup from any supported version merges without the merge
	 * code knowing anything about older layouts.
	 * <p>
	 * The caller owns the returned instance and must close it.
	 */
	@NonNull
	public static MainDatabase openStaging(@NonNull Context context, @NonNull String fileName) {
		return builderFor(context, fileName).build();
	}

	@NonNull
	private static RoomDatabase.Builder<MainDatabase> builderFor(@NonNull Context context,
	                                                             @NonNull String fileName) {

		return Room.databaseBuilder(
						context.createDeviceProtectedStorageContext(),
						MainDatabase.class, fileName)
				// Real migrations replace the destructive fallback that was in place during
				// early development. An upgrade now keeps the data; if a migration is ever
				// missing, Room throws rather than silently wiping the database, which is
				// the behaviour you want once real attendance is in there.
				.addMigrations(Migrations.MIGRATION_1_2, Migrations.MIGRATION_2_3,
						Migrations.MIGRATION_3_4,
						Migrations.MIGRATION_4_5, Migrations.MIGRATION_5_6);
	}

	/**
	 * The context the database actually lives under.
	 * <p>
	 * {@link #getInstance} builds Room against device-protected storage so the app works
	 * before the user unlocks the device after a reboot. Anything that needs to find the file
	 * on disk has to look under the same context — a plain application context resolves to a
	 * different directory and would silently report that no database exists.
	 */
	@NonNull
	private static Context storageContext(@NonNull Context context) {
		return context.createDeviceProtectedStorageContext();
	}

	/**
	 * The live SQLite file. Used by the backup code, which copies it whole rather than
	 * re-exporting rows.
	 */
	@NonNull
	public static File databaseFile(@NonNull Context context) {
		return storageContext(context).getDatabasePath(ConstantsAndStatics.DATABASE_NAME);
	}

	/**
	 * Folds the write-ahead log back into the main file.
	 * <p>
	 * Room runs in WAL mode, so recent writes live in a {@code -wal} sidecar until SQLite
	 * decides to checkpoint. Copying the database file without doing this first produces a
	 * backup that is missing the most recent work, which is precisely the part the user
	 * would notice.
	 */
	public static void checkpoint(@NonNull Context context) {
		getInstance(context).getOpenHelper().getWritableDatabase()
				.query("PRAGMA wal_checkpoint(TRUNCATE)").close();
	}

	/** The {@code user_version} of the live database, which is what drives migrations. */
	public static int schemaVersion(@NonNull Context context) {
		return getInstance(context).getOpenHelper().getReadableDatabase().getVersion();
	}

	/**
	 * Closes the open database and forgets the singleton, so the file underneath can be
	 * replaced.
	 * <p>
	 * Only the restore path should call this. Every DAO, LiveData and cursor handed out so far
	 * belongs to the instance being closed, and the app is expected to restart immediately
	 * afterwards rather than carry on with references to a database that is gone.
	 */
	public static synchronized void closeInstance() {

		if (self != null) {
			if (self.isOpen()) {
				self.close();
			}
			self = null;
		}
	}

	public abstract DaoStudent daoStudent();

	public abstract DaoProfessor daoProfessor();

	public abstract DaoCourse daoCourse();

	public abstract DaoBatch daoBatch();

	public abstract DaoLecture daoLecture();

	public abstract DaoAttendance daoAttendance();

	public abstract DaoStats daoStats();

	public abstract DaoAcademicYear daoAcademicYear();
}
