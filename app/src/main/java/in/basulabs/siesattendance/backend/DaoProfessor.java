package in.basulabs.siesattendance.backend;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import androidx.room.Update;

import java.util.List;

@Dao
public abstract class DaoProfessor {

	@Insert(onConflict = OnConflictStrategy.ABORT)
	public abstract long insert(EntityProfessor professor);

	@Update
	public abstract void update(EntityProfessor professor);

	@Delete
	public abstract void delete(EntityProfessor professor);

	@Query("SELECT * FROM professors ORDER BY name ASC")
	public abstract LiveData<List<EntityProfessor>> getAll();

	@Query("SELECT * FROM professors ORDER BY name ASC")
	public abstract List<EntityProfessor> getAllSync();

	@Query("SELECT * FROM professors WHERE name LIKE '%' || :query || '%' " +
			"OR email LIKE '%' || :query || '%' ORDER BY name ASC")
	public abstract LiveData<List<EntityProfessor>> search(String query);

	@Query("SELECT * FROM professors WHERE profID = :profID")
	public abstract EntityProfessor getByIdSync(long profID);

	@Query("SELECT COUNT(*) FROM professors")
	public abstract int countSync();

	@Query("SELECT COUNT(*) FROM courses WHERE profID = :profID")
	public abstract int countCourses(long profID);

	/**
	 * Courses hold a RESTRICT key onto professors, so a professor who still teaches something
	 * cannot be removed. The count is reported so the user knows what to reassign.
	 */
	@Transaction
	public void deleteProfessorChecked(EntityProfessor professor) {

		int courses = countCourses(professor.profID);
		if (courses > 0) {
			throw new IllegalStateException("This professor teaches " + courses
					+ " course(s). Reassign or delete those first.");
		}
		delete(professor);
	}
}
