package in.basulabs.siesattendance.backend;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * The academic sessions, and the queries that check nothing has fallen outside them.
 * <p>
 * These rows are what give a lecture's timestamp a session to belong to. Since lectures carry no
 * session column of their own, a gap in this table is not a cosmetic problem: any lecture falling
 * in it joins to no session and disappears from every report. {@link #countOrphanLecturesSync}
 * and its LiveData counterpart exist so that condition is visible rather than silent.
 */
@Dao
public interface DaoAcademicYear {

	/**
	 * Inserts a session or replaces the one with the same starting year. Used when seeding, where
	 * re-running the seed should not fail on sessions that already exist.
	 */
	@Insert(onConflict = OnConflictStrategy.REPLACE)
	void upsert(EntityAcademicYear year);

	@Update
	void update(EntityAcademicYear year);

	/**
	 * Removes a session. No constraint stops this, because nothing holds a foreign key onto
	 * academic_years, so the caller is responsible for checking that no lecture would be
	 * stranded by it.
	 */
	@Delete
	void delete(EntityAcademicYear year);

	@Query("SELECT * FROM academic_years ORDER BY academicYear DESC")
	LiveData<List<EntityAcademicYear>> getAll();

	@Query("SELECT * FROM academic_years ORDER BY academicYear DESC")
	List<EntityAcademicYear> getAllSync();

	@Query("SELECT * FROM academic_years WHERE academicYear = :academicYear")
	@Nullable
	EntityAcademicYear getByIdSync(int academicYear);

	/**
	 * The session containing a given instant, or null if the date falls in a gap between
	 * defined sessions.
	 */
	@Query("SELECT * FROM academic_years WHERE :epochSecond BETWEEN startEpoch AND endEpoch "
			+ "ORDER BY academicYear DESC LIMIT 1")
	@Nullable
	EntityAcademicYear findContainingSync(long epochSecond);

	/** The most recent session, used as a fallback when today falls outside all of them. */
	@Query("SELECT * FROM academic_years ORDER BY academicYear DESC LIMIT 1")
	@Nullable
	EntityAcademicYear getLatestSync();

	@Query("SELECT COUNT(*) FROM academic_years")
	int countSync();

	/**
	 * Lectures whose start falls outside every defined session. Statistics join lectures to
	 * sessions, so any lecture counted here is invisible to every report, and the sessions need
	 * extending or a new one adding to cover it.
	 */
	@Query("SELECT COUNT(*) FROM lectures l WHERE NOT EXISTS ("
			+ "SELECT 1 FROM academic_years ay WHERE "
			+ "CAST(SUBSTR(l.startDateTime, 1, INSTR(l.startDateTime, '|') - 1) AS INTEGER) "
			+ "BETWEEN ay.startEpoch AND ay.endEpoch)")
	int countOrphanLecturesSync();

	@Query("SELECT COUNT(*) FROM lectures l WHERE NOT EXISTS ("
			+ "SELECT 1 FROM academic_years ay WHERE "
			+ "CAST(SUBSTR(l.startDateTime, 1, INSTR(l.startDateTime, '|') - 1) AS INTEGER) "
			+ "BETWEEN ay.startEpoch AND ay.endEpoch)")
	LiveData<Integer> countOrphanLectures();
}
