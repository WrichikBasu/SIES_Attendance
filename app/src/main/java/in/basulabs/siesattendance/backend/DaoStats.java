package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Query;
import androidx.room.RawQuery;
import androidx.sqlite.db.SupportSQLiteQuery;

import java.util.List;

import in.basulabs.siesattendance.backend.pojo.StatRow;
import in.basulabs.siesattendance.backend.pojo.StatsSummary;

/**
 * Statistics are served by raw queries because the grouping, ordering and set of conditions all
 * vary at runtime. A fixed {@code @Query} per combination would run to dozens of near-identical
 * methods; {@link in.basulabs.siesattendance.backend.stats.StatsQueryBuilder} assembles them
 * instead, binding every user-supplied value as an argument.
 */
@Dao
public interface DaoStats {

	@RawQuery
	@NonNull
	List<StatRow> runGrouped(@NonNull SupportSQLiteQuery query);

	@RawQuery
	@Nullable
	StatsSummary runSummary(@NonNull SupportSQLiteQuery query);

	/**
	 * Emits whenever any table the report reads changes: a mark saved, a lecture added, a sync
	 * applying a page. Never any rows; the value is the signal, since Room re-runs a LiveData
	 * query and posts its result on every write to a table it names. The report's own queries
	 * are raw, and a raw query cannot be observed, which is why the report used to show its
	 * last result until a filter was touched (R37).
	 */
	@Query("SELECT 1 FROM attendance, lectures, courses, course_instructor, students, "
			+ "student_year, batch_desc, batch_student_map, professors, academic_years LIMIT 0")
	@NonNull
	LiveData<List<Integer>> observeReportInputs();

}
