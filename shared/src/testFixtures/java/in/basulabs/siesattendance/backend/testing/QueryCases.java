package in.basulabs.siesattendance.backend.testing;

import androidx.annotation.NonNull;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import in.basulabs.siesattendance.backend.CourseType;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Division;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;
import in.basulabs.siesattendance.backend.stats.StatsFilter;
import in.basulabs.siesattendance.backend.stats.StatsGroupBy;
import in.basulabs.siesattendance.backend.util.ExportFilter;

/**
 * The filters every statistics and export statement is generated from, named.
 * <p>
 * One list, read by two dump tests in two modules: {@code SqlBuilderTest} in the app writes the
 * SQLite statement for each case, {@code SqlDumpTest} in the server writes the PostgreSQL one,
 * and {@code sql_differ.py} runs the two against their own engines and compares the rows.
 * <p>
 * It lives here, as a test fixture of the shared module, for one reason. The comparison is only
 * worth anything if both sides really were asked the same question, and the surest way to get
 * that wrong is to type the filter out twice — a date a month apart, a batch id of 2 instead of
 * 1, and the differ reports a mismatch that is its own fault, or worse, agrees for the wrong
 * reason. Here there is one filter per case, constructed once.
 * <p>
 * Each case returns a fresh filter, because {@link StatsFilter} is deliberately mutable and a
 * caller is free to adjust one.
 */
public final class QueryCases {

	/** The session every case is written against, matching the harness fixtures. */
	public static final int SESSION = 2025;

	/** One named statistics statement. */
	public static final class StatsCase {

		/** The dump file's base name, identical on both sides. */
		@NonNull
		public final String name;

		@NonNull
		public final StatsFilter filter;

		/** True for the headline figures, false for the grouped report. */
		public final boolean summary;

		StatsCase(@NonNull String name, @NonNull StatsFilter filter, boolean summary) {
			this.name = name;
			this.filter = filter;
			this.summary = summary;
		}
	}

	/** One named export statement. */
	public static final class ExportCase {

		@NonNull
		public final String name;

		public final int academicYear;

		@NonNull
		public final ExportFilter filter;

		ExportCase(@NonNull String name, int academicYear, @NonNull ExportFilter filter) {
			this.name = name;
			this.academicYear = academicYear;
			this.filter = filter;
		}
	}

	private QueryCases() {
	}

	/**
	 * Every statistics case: one per grouping, one per sort order, the two batch cases the
	 * nested-batch fixture needs, the summary, the all-sessions comparison, and a filter with
	 * every field set at once.
	 */
	@NonNull
	public static List<StatsCase> stats() {

		List<StatsCase> cases = new ArrayList<>();

		for (StatsGroupBy groupBy : StatsGroupBy.values()) {
			StatsFilter filter = new StatsFilter(SESSION);
			filter.groupBy = groupBy;
			cases.add(new StatsCase("stats_grouped_" + groupBy.name(), filter, false));
		}

		// Batch on its own, for the harness to run against nested batches. Two arguments in
		// this order: the session, then the batch.
		StatsFilter batchOnly = new StatsFilter(SESSION);
		batchOnly.batchID = 1L;
		cases.add(new StatsCase("stats_batch_only", batchOnly, false));

		StatsFilter batchAndCourse = new StatsFilter(SESSION);
		batchAndCourse.courseIDs.add(2L);
		batchAndCourse.batchID = 1L;
		cases.add(new StatsCase("stats_batch_and_course", batchAndCourse, false));

		cases.add(new StatsCase("stats_summary", new StatsFilter(SESSION), true));

		// A null session means every session, which is what lets one report compare this year
		// against last.
		cases.add(new StatsCase("stats_all_sessions", new StatsFilter(null), false));

		cases.add(new StatsCase("stats_full_filter", fullyPopulatedFilter(), false));

		for (StatsFilter.SortOrder order : StatsFilter.SortOrder.values()) {
			StatsFilter filter = new StatsFilter(SESSION);
			filter.sortOrder = order;
			cases.add(new StatsCase("stats_sort_" + order.name(), filter, false));
		}

		return Collections.unmodifiableList(cases);
	}

	/** Every export case: unrestricted, and one per kind of narrowing. */
	@NonNull
	public static List<ExportCase> exports() {

		List<ExportCase> cases = new ArrayList<>();

		cases.add(export("export_unrestricted", new ExportFilter()));

		ExportFilter windowed = new ExportFilter();
		windowed.from = LocalDate.of(2025, 7, 1);
		windowed.to = LocalDate.of(2025, 7, 31);
		cases.add(export("export_windowed", windowed));

		ExportFilter courseOnly = new ExportFilter();
		courseOnly.courseIDs.add(1L);
		courseOnly.courseIDs.add(3L);
		cases.add(export("export_course_only", courseOnly));

		ExportFilter deptOnly = new ExportFilter();
		deptOnly.departments.add(Department.COMPUTER_SCIENCE);
		cases.add(export("export_dept_only", deptOnly));

		ExportFilter narrowed = new ExportFilter();
		narrowed.courseIDs.add(1L);
		narrowed.departments.add(Department.PHYSICS);
		narrowed.years.add(Year.SY);
		narrowed.streams.add(Stream.BSc);
		cases.add(export("export_narrowed", narrowed));

		ExportFilter oneDivision = new ExportFilter();
		oneDivision.divisions.add(Division.B);
		cases.add(export("export_division_only", oneDivision));

		return Collections.unmodifiableList(cases);
	}

	/**
	 * Every field set at once, which is the case that catches a fragment appended without its
	 * argument: with one filter active a misordered binding still looks right.
	 */
	@NonNull
	public static StatsFilter fullyPopulatedFilter() {

		StatsFilter filter = new StatsFilter(SESSION);
		filter.groupBy = StatsGroupBy.STUDENT;

		// The default June-to-May session, the same bounds EntityAcademicYear.defaultFor
		// computes. Spelled out here because this module knows nothing about Room entities.
		filter.applyDateRange(StatsFilter.DateRange.SESSION_FIRST_HALF,
				LocalDate.of(SESSION, 6, 1), LocalDate.of(SESSION + 1, 5, 31));

		filter.courseIDs.add(1L);
		filter.courseIDs.add(2L);
		filter.batchID = 1L;
		filter.professorID = 1L;
		filter.studentID = 1L;
		filter.courseTypes.add(CourseType.MAJOR);
		filter.year = Year.SY;
		filter.stream = Stream.BSc;
		filter.department = Department.PHYSICS;
		return filter;
	}

	@NonNull
	private static ExportCase export(@NonNull String name, @NonNull ExportFilter filter) {
		return new ExportCase(name, SESSION, filter);
	}
}
