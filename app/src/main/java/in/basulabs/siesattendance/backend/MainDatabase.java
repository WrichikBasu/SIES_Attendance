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
		EntityProfessor.class
}, version = 2, exportSchema = false)
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
					.fallbackToDestructiveMigration(true)
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
}
