package in.basulabs.siesattendance.backend;

import android.content.Context;
import android.database.Cursor;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;
import androidx.sqlite.db.SupportSQLiteDatabase;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import in.basulabs.siesattendance.backend.sync.OutboxTriggers;

/**
 * The Room database, and the single place that decides where it lives on disk.
 * <p>
 * Room is built against device-protected storage rather than the usual credential-protected
 * directory, so the app is usable after a reboot before the user has unlocked the device.
 * Everything that has to find the file itself has to resolve it under the same context, which
 * is what {@link #storageContext} is for: an ordinary application context points at a different
 * directory and would report, quite truthfully and quite uselessly, that no database exists
 * there.
 * <p>
 * Beyond the usual singleton, this class carries the handful of operations the backup and
 * restore paths need, none of which fit in a DAO because they act on the database as a file
 * rather than on its contents: {@link #checkpoint}, {@link #databaseFile}, {@link #openStaging}
 * and {@link #closeInstance}.
 */
@Database(entities = {
		EntityStudent.class,
		EntityStudentYear.class,
		EntityCourse.class,
		EntityBatchDesc.class,
		EntityBatchStudentMap.class,
		EntityCourseBatchMap.class,
		EntityCourseInstructor.class,
		EntityLecture.class,
		EntityAttendance.class,
		EntityProfessor.class,
		EntityAcademicYear.class,
		// Not attendance data: the queue of local changes and the device's own sync
		// bookkeeping. They are in this database rather than beside it because the outbox is
		// filled by triggers on the tables above, and a trigger cannot write to another file.
		EntityOutbox.class,
		EntitySyncState.class
}, version = ConstantsAndStatics.DATABASE_VERSION, exportSchema = false)
@TypeConverters({TypeConvertors.class})
public abstract class MainDatabase extends RoomDatabase {

	private static volatile MainDatabase self;

	/**
	 * A single shared pool for every write. Room refuses main-thread writes, and funnelling
	 * them through one executor keeps insert ordering predictable during CSV import.
	 */
	public static final ExecutorService DB_EXECUTOR = Executors.newFixedThreadPool(4);

	/**
	 * The live database, opened on first use. Synchronised rather than double-checked because
	 * this is called once per screen at most, and a restore can null the field out from under a
	 * concurrent caller.
	 */
	@NonNull
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

		// Before the builder, not after: every route to a row runs through here, and a key
		// issued before the node number is known would be a key that does not say which
		// device made it.
		IdGenerator.init(context);

		return Room.databaseBuilder(
						context.createDeviceProtectedStorageContext(),
						MainDatabase.class, fileName)
				// Real migrations replace the destructive fallback that was in place during
				// early development. An upgrade now keeps the data; if a migration is ever
				// missing, Room throws rather than silently wiping the database, which is
				// the behaviour you want once real attendance is in there.
				.addMigrations(Migrations.MIGRATION_1_2, Migrations.MIGRATION_2_3,
						Migrations.MIGRATION_3_4,
						Migrations.MIGRATION_4_5, Migrations.MIGRATION_5_6,
						Migrations.MIGRATION_6_7, Migrations.MIGRATION_7_8,
						Migrations.MIGRATION_8_9, Migrations.MIGRATION_9_10,
						Migrations.MIGRATION_10_11,
						Migrations.MIGRATION_11_12)
				.addCallback(PRIME_IDS)
				.addCallback(INSTALL_SYNC_TRIGGERS);
	}

	/**
	 * Tells {@link IdGenerator} what keys of its own the file already contains, on every open.
	 * <p>
	 * Keys are issued from the clock rather than by SQLite, and the running sequence that keeps
	 * them apart lives in memory only. A restored backup, or a device whose clock has been wound
	 * back, could otherwise hand out an ID a row is already using. Reading the highest one at
	 * open costs a single query and removes that whole class of failure.
	 * <p>
	 * Runs after any migration, so the tables are always the current shape by the time it looks
	 * at them.
	 */
	private static final RoomDatabase.Callback PRIME_IDS = new RoomDatabase.Callback() {
		@Override
		public void onOpen(@NonNull SupportSQLiteDatabase db) {
			IdGenerator.prime(db);
		}
	};

	/**
	 * Creates the outbox triggers, on every open.
	 * <p>
	 * Every open rather than once at creation, and {@code IF NOT EXISTS} rather than a migration
	 * step, because three different files have to end up with the same triggers: one this build
	 * created, one an older build created and this build migrated, and one restored from a backup
	 * written by a phone that may have been on any version at all. "Created when the database was
	 * created" is a claim about a creation this build may never have seen.
	 * <p>
	 * It also means the triggers are re-created after a restore has replaced the file, which is
	 * the case that would otherwise leave a device writing attendance that is never queued.
	 *
	 * @see in.basulabs.siesattendance.backend.sync.OutboxTriggers
	 */
	private static final RoomDatabase.Callback INSTALL_SYNC_TRIGGERS =
			new RoomDatabase.Callback() {
				@Override
				public void onOpen(@NonNull SupportSQLiteDatabase db) {

					// The guard row the triggers read. Without it they still queue -- the
					// condition coalesces -- but the pull would have nothing to set.
					db.execSQL("INSERT OR IGNORE INTO `" + EntitySyncState.TABLE_NAME
							+ "` (`id`, `applying`, `lastPullAt`, `lastPushAt`) "
							+ "VALUES (1, 0, 0, 0)");

					for (String statement : OutboxTriggers.statements()) {
						db.execSQL(statement);
					}
				}
			};

	/**
	 * The context the database actually lives under.
	 * <p>
	 * {@link #getInstance} builds Room against device-protected storage so the app works
	 * before the user unlocks the device after a reboot. Anything that needs to find the file
	 * on disk has to look under the same context. A plain application context resolves to a
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
	 * Folds the write-ahead log of the live database back into the main file.
	 * <p>
	 * Room runs in WAL mode, so recent writes live in a {@code -wal} sidecar until SQLite
	 * decides to checkpoint. Copying the database file without doing this first produces a
	 * backup that is missing the most recent work, which is precisely the part the user
	 * would notice.
	 *
	 * @return false if SQLite could not fold the whole log back, in which case the file on
	 *         disk is not the whole database and must not be copied as though it were
	 */
	public static boolean checkpoint(@NonNull Context context) {
		return checkpoint(getInstance(context).getOpenHelper().getWritableDatabase());
	}

	/**
	 * The same, on a database the caller already has open.
	 * <p>
	 * The cursor has to be stepped. A query is only prepared when it is created and does not
	 * run until something asks it for a row, so building this one and closing it unread — which
	 * is what used to happen here — left the pragma prepared and never executed, and the log
	 * where it was. The first column of the row it returns is the busy flag: 1 means SQLite
	 * gave up waiting for a reader and the log still holds pages the main file does not.
	 */
	public static boolean checkpoint(@NonNull SupportSQLiteDatabase db) {

		try (Cursor cursor = db.query("PRAGMA wal_checkpoint(TRUNCATE)")) {
			return cursor.moveToFirst() && cursor.getInt(0) == 0;
		}
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

	/*
	 * The DAOs. Room generates the implementations; the split between them follows the tables
	 * rather than the screens, so a screen that needs students and lectures asks both.
	 */

	public abstract DaoStudent daoStudent();

	public abstract DaoProfessor daoProfessor();

	public abstract DaoCourse daoCourse();

	public abstract DaoBatch daoBatch();

	public abstract DaoLecture daoLecture();

	public abstract DaoAttendance daoAttendance();

	public abstract DaoStats daoStats();

	public abstract DaoAcademicYear daoAcademicYear();

	/**
	 * The outbox and the sync state. Not an attendance DAO: it reads the queue the triggers
	 * fill and the one row of bookkeeping beside it.
	 */
	public abstract DaoOutbox daoOutbox();
}
