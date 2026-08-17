package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Dao;
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

	@RawQuery
	int runCount(@NonNull SupportSQLiteQuery query);
}
