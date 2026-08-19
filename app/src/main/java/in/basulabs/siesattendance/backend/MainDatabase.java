package in.basulabs.siesattendance.backend;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;

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
}, version = 5, exportSchema = false)
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
			self = Room.databaseBuilder(
							context.createDeviceProtectedStorageContext(),
							MainDatabase.class, ConstantsAndStatics.DATABASE_NAME)
					// Real migrations replace the destructive fallback that was in place during
					// early development. An upgrade now keeps the data; if a migration is ever
					// missing, Room throws rather than silently wiping the database, which is
					// the behaviour you want once real attendance is in there.
					.addMigrations(Migrations.MIGRATION_1_2, Migrations.MIGRATION_2_3,
							Migrations.MIGRATION_3_4,
							Migrations.MIGRATION_4_5)
					.build();
		}
		return self;
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
