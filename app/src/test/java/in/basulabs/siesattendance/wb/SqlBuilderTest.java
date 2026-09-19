package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.sqlite.db.SimpleSQLiteQuery;
import androidx.sqlite.db.SupportSQLiteQuery;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import in.basulabs.siesattendance.backend.CourseType;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Division;
import in.basulabs.siesattendance.backend.EntityAcademicYear;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;
import in.basulabs.siesattendance.backend.stats.SqlBits;
import in.basulabs.siesattendance.backend.stats.StatsFilter;
import in.basulabs.siesattendance.backend.stats.StatsGroupBy;
import in.basulabs.siesattendance.backend.stats.StatsQueryBuilder;
import in.basulabs.siesattendance.backend.util.ExportFilter;
import in.basulabs.siesattendance.backend.util.ExportQueryBuilder;

/**
 * The two raw-query builders.
 * <p>
 * Positional binding is the thing that can silently go wrong here: a fragment appended without
 * its argument, or arguments added in the wrong order, produces a query that still runs and
 * still returns rows, just the wrong ones. Every test below counts placeholders against
 * arguments, and the whole set of generated statements is written to
 * {@code build/wb-sql/} for the SQLite harness to execute.
 */
public class SqlBuilderTest {

	/** Placeholders in a statement, ignoring any inside string literals (there are none). */
	private static int placeholders(String sql) {
		int count = 0;
		for (int i = 0; i < sql.length(); i++) {
			if (sql.charAt(i) == '?') {
				count++;
			}
		}
		return count;
	}

	/** SupportSQLiteQuery hides its arguments; this recovers them by binding into a recorder. */
	private static List<Object> argsOf(SupportSQLiteQuery query) {
		ArgRecorder recorder = new ArgRecorder();
		query.bindTo(recorder);
		return recorder.args;
	}

	/**
	 * How a null argument is written into a dump file, and what {@code sql_harness.py} reads it
	 * back as.
	 * <p>
	 * The leading space is what makes it a sentinel rather than a value: a query is free to bind
	 * the four-letter string NULL, and that has to come back as those four letters. Anything but
	 * a plain space here silently stops matching the harness, which would then read a null as
	 * whatever text it found.
	 */
	private static final String NULL_MARKER = " NULL";

	private static void dump(String name, SupportSQLiteQuery query) throws IOException {
		File dir = new File("build/wb-sql");
		//noinspection ResultOfMethodCallIgnored
		dir.mkdirs();

		StringBuilder out = new StringBuilder();
		out.append(query.getSql()).append("\n----ARGS----\n");
		for (Object arg : argsOf(query)) {
			out.append(arg == null ? NULL_MARKER : String.valueOf(arg)).append("\n");
		}

		Files.write(new File(dir, name + ".sql").toPath(),
				out.toString().getBytes(StandardCharsets.UTF_8));
	}

	// ── Export query ─────────────────────────────────────────────────────

	@Test
	public void anUnrestrictedExportBindsExactlyAsManyArgumentsAsItHasPlaceholders() {
		SupportSQLiteQuery query = ExportQueryBuilder.build(2025, new ExportFilter());

		assertEquals("argument count must match placeholder count",
				placeholders(query.getSql()), argsOf(query).size());
		assertEquals(12, argsOf(query).size());
	}

	@Test
	public void everyExportFilterCombinationKeepsArgumentsAndPlaceholdersInStep() {
		// 2^6 combinations of the six restriction kinds. A mismatch in any one of them means
		// SQLite binds a value into the wrong slot and the sheet is quietly wrong.
		for (int mask = 0; mask < 64; mask++) {

			ExportFilter filter = new ExportFilter();

			if ((mask & 1) != 0) {
				filter.from = LocalDate.of(2025, 7, 1);
			}
			if ((mask & 2) != 0) {
				filter.to = LocalDate.of(2025, 12, 31);
			}
			if ((mask & 4) != 0) {
				filter.courseIDs.add(1L);
				filter.courseIDs.add(2L);
				filter.courseIDs.add(3L);
			}
			if ((mask & 8) != 0) {
				filter.departments.add(Department.PHYSICS);
				filter.departments.add(Department.COMPUTER_SCIENCE);
			}
			if ((mask & 16) != 0) {
				filter.years.add(Year.SY);
				filter.streams.add(Stream.BSc);
				filter.streams.add(Stream.BSc_unaided);
			}
			if ((mask & 32) != 0) {
				filter.divisions.add(Division.A);
				filter.divisions.add(Division.B);
			}

			SupportSQLiteQuery query = ExportQueryBuilder.build(2025, filter);

			assertEquals("mask " + mask + " binds the wrong number of arguments",
					placeholders(query.getSql()), argsOf(query).size());
		}
	}

	@Test
	public void exportDateBoundsArriveAsAFlagAndAValueInThatOrder() {
		// A null compared with BETWEEN is never true, so "no bound" is expressed as a flag
		// rather than a null; getting the pair the wrong way round would silently return
		// nothing at all.
		ExportFilter unbounded = new ExportFilter();
		List<Object> args = argsOf(ExportQueryBuilder.build(2025, unbounded));

		// held block: academicYear, fromFlag, fromValue, toFlag, toValue
		assertEquals(2025L, ((Number) args.get(0)).longValue());
		assertEquals(0L, ((Number) args.get(1)).longValue());
		assertEquals(0L, ((Number) args.get(2)).longValue());
		assertEquals(0L, ((Number) args.get(3)).longValue());
		assertEquals(0L, ((Number) args.get(4)).longValue());

		ExportFilter bounded = new ExportFilter();
		bounded.from = LocalDate.of(2025, 7, 1);
		bounded.to = LocalDate.of(2025, 7, 31);
		List<Object> boundedArgs = argsOf(ExportQueryBuilder.build(2025, bounded));

		assertEquals(1L, ((Number) boundedArgs.get(1)).longValue());
		assertTrue(((Number) boundedArgs.get(2)).longValue() > 0);
		assertEquals(1L, ((Number) boundedArgs.get(3)).longValue());
		assertTrue("the upper bound must cover the whole of the final day",
				((Number) boundedArgs.get(4)).longValue()
						> ((Number) boundedArgs.get(2)).longValue());
	}

	@Test
	public void bothExportSubqueriesUseTheSameDateWindow() {
		// Applying the bounds to only one of held and attended would produce a percentage over
		// two different denominators, which is worse than no filter at all.
		ExportFilter filter = new ExportFilter();
		filter.from = LocalDate.of(2025, 7, 1);
		filter.to = LocalDate.of(2025, 7, 31);

		List<Object> args = argsOf(ExportQueryBuilder.build(2025, filter));

		// Arguments 0-4 belong to "held", 5-9 to "attended"; they must be identical.
		assertEquals(args.subList(0, 5).toString(), args.subList(5, 10).toString());
	}

	@Test
	public void exportEnumsAreBoundByConstantNameNeverByLabel() {
		ExportFilter filter = new ExportFilter();
		filter.departments.add(Department.COMPUTER_SCIENCE);
		filter.streams.add(Stream.BSc_unaided);
		filter.years.add(Year.Masters_P1);
		filter.divisions.add(Division.B);

		List<Object> args = argsOf(ExportQueryBuilder.build(2025, filter));
		String bound = args.toString();

		assertTrue(bound.contains("COMPUTER_SCIENCE"));
		assertTrue(bound.contains("BSc_unaided"));
		assertTrue(bound.contains("Masters_P1"));

		// Checked against the list rather than its text, since "B" is a substring of half the
		// other values bound here.
		assertTrue("a division binds as its constant name, not as \"Div B\"",
				args.contains("B"));
		assertFalse(bound.contains("Div B"));
		assertFalse("the display label must never be what is compared against the column",
				bound.contains("BSc (unaided)"));
		assertFalse(bound.contains("Masters Part 1"));
	}

	@Test
	public void anEmptyFilterSetNeverProducesAnEmptyInClause() {
		// IN () is a syntax error in most engines and matches nothing where it is accepted;
		// an empty set has to mean "no restriction" instead.
		String sql = ExportQueryBuilder.build(2025, new ExportFilter()).getSql();

		assertFalse(sql.contains("IN ()"));
		assertFalse(sql.contains("IN ( )"));
		assertFalse(sql.contains("c.courseID IN"));
		assertFalse(sql.contains("y.majorDept IN"));
		assertFalse("an unrestricted export must not narrow to any division",
				sql.contains("y.division IN"));
	}

	@Test
	public void theExportRosterConditionCoversEveryCourseType() {
		// A course type missing from the condition matches nobody, which shows up in the app
		// as a course with no students at all.
		String sql = ExportQueryBuilder.build(2025, new ExportFilter()).getSql();

		for (CourseType type : CourseType.values()) {
			assertTrue("CourseType." + type.name()
							+ " is not accounted for in the export roster condition",
					sql.contains("'" + type.name() + "'"));
		}
	}

	@Test
	public void exportSelectsEveryColumnExportRowExpects() {
		String sql = ExportQueryBuilder.build(2025, new ExportFilter()).getSql();

		for (String alias : new String[]{"studentID", "rollNo", "studentName", "courseID",
				"courseCode", "courseTopic", "held", "attended"}) {
			assertTrue("no column aliased as " + alias, sql.contains("AS " + alias));
		}
	}

	// ── Statistics query ─────────────────────────────────────────────────

	@Test
	public void everyGroupingProducesAConsistentGroupedAndSummaryQuery() {
		for (StatsGroupBy groupBy : StatsGroupBy.values()) {

			StatsFilter filter = new StatsFilter(2025);
			filter.groupBy = groupBy;

			SupportSQLiteQuery grouped = StatsQueryBuilder.grouped(filter);
			SupportSQLiteQuery summary = StatsQueryBuilder.summary(filter);

			assertEquals(groupBy + ": grouped query binds the wrong number of arguments",
					placeholders(grouped.getSql()), argsOf(grouped).size());
			assertEquals(groupBy + ": summary query binds the wrong number of arguments",
					placeholders(summary.getSql()), argsOf(summary).size());

			assertTrue(groupBy + ": no GROUP BY", grouped.getSql().contains("GROUP BY"));
			assertFalse(groupBy + ": the summary must not group",
					summary.getSql().contains("GROUP BY"));
		}
	}

	@Test
	public void groupedAndSummaryShareTheSameWhereClauseAndArguments() {
		// The totals above a report are only trustworthy if they come from the same rows.
		StatsFilter filter = fullyPopulatedFilter();

		SupportSQLiteQuery grouped = StatsQueryBuilder.grouped(filter);
		SupportSQLiteQuery summary = StatsQueryBuilder.summary(filter);

		String groupedWhere = grouped.getSql().substring(grouped.getSql().indexOf(" WHERE "),
				grouped.getSql().indexOf(" GROUP BY "));
		String summaryWhere = summary.getSql()
				.substring(summary.getSql().indexOf(" WHERE "));

		assertEquals(groupedWhere, summaryWhere);
		assertEquals(argsOf(grouped).toString(), argsOf(summary).toString());
	}

	@Test
	public void everyStatsFilterCombinationKeepsArgumentsAndPlaceholdersInStep() {
		for (int mask = 0; mask < 1024; mask++) {

			StatsFilter filter = new StatsFilter((mask & 1) != 0 ? 2025 : null);

			if ((mask & 2) != 0) {
				filter.from = LocalDate.of(2025, 7, 1);
			}
			if ((mask & 4) != 0) {
				filter.to = LocalDate.of(2025, 12, 31);
			}
			if ((mask & 8) != 0) {
				filter.courseIDs.add(7L);
				filter.courseIDs.add(9L);
			}
			if ((mask & 16) != 0) {
				filter.batchID = 3L;
			}
			if ((mask & 32) != 0) {
				filter.professorID = 5L;
			}
			if ((mask & 64) != 0) {
				filter.studentID = 11L;
			}
			if ((mask & 128) != 0) {
				filter.courseTypes.add(CourseType.MAJOR);
				filter.courseTypes.add(CourseType.MAJOR_PRACTICALS);
			}
			if ((mask & 256) != 0) {
				filter.year = Year.SY;
				filter.stream = Stream.BSc;
			}
			if ((mask & 512) != 0) {
				filter.department = Department.PHYSICS;
			}

			SupportSQLiteQuery grouped = StatsQueryBuilder.grouped(filter);
			assertEquals("mask " + mask, placeholders(grouped.getSql()),
					argsOf(grouped).size());
		}
	}

	@Test
	public void severalCoursesAreAskedForWithOnePlaceholderEach() {
		// The report's course filter is a set: the question is usually about a handful of
		// courses read together, and the summary above the rows is then their combined figure.
		StatsFilter filter = new StatsFilter(2025);
		filter.courseIDs.add(4L);
		filter.courseIDs.add(6L);
		filter.courseIDs.add(9L);

		SupportSQLiteQuery grouped = StatsQueryBuilder.grouped(filter);

		assertTrue(grouped.getSql().contains("c.courseID IN (?, ?, ?)"));
		assertEquals("argument count must match placeholder count",
				placeholders(grouped.getSql()), argsOf(grouped).size());
		// Every integer comes back from the recorder as a long, the session included.
		assertEquals(Arrays.asList(2025L, 4L, 6L, 9L), argsOf(grouped));
	}

	@Test
	public void noCoursesChosenMeansEveryCourseRatherThanNone() {
		// An empty IN () is legal in SQLite and matches nothing, which is the opposite of what
		// an untouched filter means, so the condition has to be absent altogether.
		String sql = StatsQueryBuilder.grouped(new StatsFilter(2025)).getSql();

		assertFalse(sql.contains("c.courseID IN"));
		assertFalse(sql.contains("c.courseID = ?"));
	}

	@Test
	public void theCourseSetNarrowsTheSummaryExactlyAsItNarrowsTheRows() {
		// The headline percentage is the combined attendance across the chosen courses, so it
		// has to be computed over the same rows the report lists.
		StatsFilter filter = new StatsFilter(2025);
		filter.courseIDs.add(1L);
		filter.courseIDs.add(2L);

		SupportSQLiteQuery grouped = StatsQueryBuilder.grouped(filter);
		SupportSQLiteQuery summary = StatsQueryBuilder.summary(filter);

		assertTrue(summary.getSql().contains("c.courseID IN (?, ?)"));
		assertEquals(argsOf(grouped), argsOf(summary));
	}

	@Test
	public void severalCourseTypesAreAskedForWithOnePlaceholderEach() {
		// The type filter is a set for the same reason the course filter is: "how are the
		// practicals doing" is one report over every laboratory type, not five reports whose
		// percentages cannot honestly be averaged afterwards.
		StatsFilter filter = new StatsFilter(2025);
		filter.courseTypes.add(CourseType.MAJOR_PRACTICALS);
		filter.courseTypes.add(CourseType.MINOR_PRACTICALS);
		filter.courseTypes.add(CourseType.DSE_PRACTICALS);

		SupportSQLiteQuery grouped = StatsQueryBuilder.grouped(filter);

		assertTrue(grouped.getSql().contains("c.courseType IN (?, ?, ?)"));
		assertEquals("argument count must match placeholder count",
				placeholders(grouped.getSql()), argsOf(grouped).size());
		assertEquals(Arrays.asList(2025L, "MAJOR_PRACTICALS", "MINOR_PRACTICALS",
				"DSE_PRACTICALS"), argsOf(grouped));
	}

	@Test
	public void noCourseTypeChosenMeansEveryTypeRatherThanNone() {
		// Same trap as the empty course set: "IN ()" is legal and matches nothing.
		String sql = StatsQueryBuilder.grouped(new StatsFilter(2025)).getSql();

		assertFalse(sql.contains("c.courseType IN"));
		assertFalse(sql.contains("c.courseType = ?"));
	}

	@Test
	public void theCourseTypeSetNarrowsTheSummaryExactlyAsItNarrowsTheRows() {
		// The headline percentage for "practicals" is the combined attendance across every
		// laboratory type, so it has to be computed over the same rows the report lists.
		StatsFilter filter = new StatsFilter(2025);
		filter.courseTypes.addAll(CourseType.practicals());

		SupportSQLiteQuery grouped = StatsQueryBuilder.grouped(filter);
		SupportSQLiteQuery summary = StatsQueryBuilder.summary(filter);

		assertTrue(summary.getSql().contains("c.courseType IN ("));
		assertEquals(argsOf(grouped), argsOf(summary));
	}

	@Test
	public void theBatchFilterMatchesMembershipRatherThanTheLecturesOwnBatch() {
		// Batches nest: a class is split into A, and A into B and C. Comparing l.batchID meant
		// asking for B returned nothing from a lecture held for A, even though every student
		// in B sat it. The subset case is the one batches exist for, so it has to be the one
		// the filter answers.
		StatsFilter filter = new StatsFilter(2025);
		filter.batchID = 3L;

		String sql = StatsQueryBuilder.grouped(filter).getSql();

		assertTrue("the batch has to be matched through batch_student_map",
				sql.contains("EXISTS (SELECT 1 FROM batch_student_map bm"));
		assertTrue("membership is per session, so it is matched against the resolved one",
				sql.contains("bm.academicYear = ay.academicYear"));
		assertFalse("matching the lecture's own batch is what excluded the subsets",
				sql.contains("l.batchID = ?"));

		assertEquals("argument count must match placeholder count",
				placeholders(sql), argsOf(StatsQueryBuilder.grouped(filter)).size());
	}

	@Test
	public void aBatchFilterStillCountsWholeClassLectures() {
		// The old condition excluded them outright, since a whole-class lecture has no batch.
		// Under membership they count, which is the point: a batch's students attend those too
		// and their percentage is over everything they sat.
		StatsFilter filter = new StatsFilter(2025);
		filter.batchID = 1L;

		String sql = StatsQueryBuilder.grouped(filter).getSql();

		assertFalse("nothing may narrow the lectures themselves to a batch",
				sql.contains("l.batchID IS NOT NULL"));
		assertFalse(sql.contains("l.batchID ="));
	}

	@Test
	public void aNullAcademicYearMeansEverySessionRatherThanNoSession() {
		// This is what makes a year-on-year comparison possible at all.
		StatsFilter allSessions = new StatsFilter(null);
		StatsFilter oneSession = new StatsFilter(2025);

		assertFalse(StatsQueryBuilder.grouped(allSessions).getSql()
				.contains("ay.academicYear = ?"));
		assertTrue(StatsQueryBuilder.grouped(oneSession).getSql()
				.contains("ay.academicYear = ?"));
	}

	@Test
	public void statsEnumsAreBoundByConstantNameNeverByLabel() {
		StatsFilter filter = new StatsFilter(2025);
		filter.courseTypes.add(CourseType.MAJOR_PRACTICALS);
		filter.year = Year.Masters_P2;
		filter.stream = Stream.MSc_unaided;
		filter.department = Department.COMPUTER_SCIENCE;

		String bound = argsOf(StatsQueryBuilder.grouped(filter)).toString();

		assertTrue(bound.contains("MAJOR_PRACTICALS"));
		assertTrue(bound.contains("Masters_P2"));
		assertTrue(bound.contains("MSc_unaided"));
		assertTrue(bound.contains("COMPUTER_SCIENCE"));
		assertFalse(bound.contains("Major practicals"));
		assertFalse(bound.contains("MSc (unaided)"));
	}

	@Test
	public void nothingTheUserTypesIsEverConcatenatedIntoAStatement() {
		StatsFilter filter = fullyPopulatedFilter();
		String sql = StatsQueryBuilder.grouped(filter).getSql();

		// Values appear only as placeholders; the only literals in the statement come from
		// the group-by enum's own compile-time fragments.
		assertFalse(sql.contains("2025 "));
		assertFalse(sql.contains("PHYSICS"));
		assertFalse(sql.contains("Masters"));
		assertTrue(sql.contains("?"));
	}

	@Test
	public void everyGroupingSelectsTheAliasesStatRowIsPopulatedFrom() {
		for (StatsGroupBy groupBy : StatsGroupBy.values()) {

			StatsFilter filter = new StatsFilter(2025);
			filter.groupBy = groupBy;
			String sql = StatsQueryBuilder.grouped(filter).getSql();

			for (String alias : new String[]{"rowId", "primaryLabel", "secondaryLabel",
					"attended", "total", "lectures", "students"}) {
				assertTrue(groupBy + " does not select " + alias,
						sql.contains("AS " + alias));
			}
		}
	}

	@Test
	public void nonAggregateExpressionsAppearInTheGroupByClause() {
		// SQLite will happily pick an arbitrary row's value for a bare column that is not
		// grouped, which is how a report starts showing the wrong label against the right
		// numbers.
		for (StatsGroupBy groupBy : StatsGroupBy.values()) {

			String groupByClause = groupBy.groupByExpr;

			if (groupBy.idExpr.equals("0") || groupBy.idExpr.startsWith("COALESCE")) {
				continue;
			}
			assertTrue(groupBy + ": id expression " + groupBy.idExpr + " is not grouped",
					groupByClause.contains(groupBy.idExpr));
		}
	}

	@Test
	public void bothQueriesCountLecturesAndStudentsDistinctly() {
		// Attendance is one row per student per lecture, so a plain COUNT would multiply each
		// lecture by the size of its sheet.
		StatsFilter filter = new StatsFilter(2025);

		String grouped = StatsQueryBuilder.grouped(filter).getSql();
		String summary = StatsQueryBuilder.summary(filter).getSql();

		assertTrue(grouped.contains("COUNT(DISTINCT l.lectureID) AS lectures"));
		assertTrue(grouped.contains("COUNT(DISTINCT a.studentID) AS students"));
		assertTrue(summary.contains("COUNT(DISTINCT l.lectureID) AS lectures"));
		assertTrue(summary.contains("COUNT(DISTINCT c.courseID) AS courses"));
	}

	@Test
	public void everySortOrderProducesAnOrderByAndABreaksTie() {
		for (StatsFilter.SortOrder order : StatsFilter.SortOrder.values()) {

			StatsFilter filter = new StatsFilter(2025);
			filter.sortOrder = order;

			String sql = StatsQueryBuilder.grouped(filter).getSql();
			String orderBy = sql.substring(sql.indexOf(" ORDER BY "));

			assertTrue(order + " has no ordering", orderBy.length() > " ORDER BY ".length());
			assertTrue(order + " does not fall back to the label, so equal rows reshuffle",
					orderBy.contains("primaryLabel"));
		}
	}

	@Test
	public void theSessionJoinIsWhatDecidesWhichYearALectureBelongsTo() {
		// Lectures carry no session column; everything downstream follows from this join.
		String sql = StatsQueryBuilder.grouped(new StatsFilter(2025)).getSql();

		assertTrue("the session has to be matched on the lecture's own timestamp",
				sql.contains(SqlBits.EPOCH_START
						+ "       BETWEEN ay2.startEpoch AND ay2.endEpoch"));
		assertTrue("the student's roll number must be the one they held at the time",
				sql.contains("AND y.academicYear = ay.academicYear"));
	}

	@Test
	public void theSessionJoinResolvesToExactlyOneSession() {
		// Sessions should not overlap, but a merged backup can introduce one, and a plain
		// BETWEEN join would then match a lecture twice and double every figure. Picking the
		// latest containing session is the same rule DaoAcademicYear.findContainingSync
		// applies everywhere else.
		String sql = StatsQueryBuilder.grouped(new StatsFilter(2025)).getSql();

		assertTrue("the join must select a single session rather than every match",
				sql.contains("INNER JOIN academic_years ay ON ay.academicYear = ("));
		assertTrue(sql.contains("ORDER BY ay2.academicYear DESC LIMIT 1)"));
	}

	@Test
	public void theEpochExtractorMatchesHowTimestampsAreActuallyStored() {
		assertEquals("CAST(SUBSTR(l.startDateTime, 1, INSTR(l.startDateTime, '|') - 1) "
				+ "AS INTEGER)", SqlBits.EPOCH_START);
	}

	// ── Dump for the SQLite harness ──────────────────────────────────────

	@Test
	public void writeGeneratedStatementsForExecution() throws IOException {
		dump("export_unrestricted", ExportQueryBuilder.build(2025, new ExportFilter()));

		ExportFilter windowed = new ExportFilter();
		windowed.from = LocalDate.of(2025, 7, 1);
		windowed.to = LocalDate.of(2025, 7, 31);
		dump("export_windowed", ExportQueryBuilder.build(2025, windowed));

		ExportFilter courseOnly = new ExportFilter();
		courseOnly.courseIDs.add(1L);
		courseOnly.courseIDs.add(3L);
		dump("export_course_only", ExportQueryBuilder.build(2025, courseOnly));

		ExportFilter deptOnly = new ExportFilter();
		deptOnly.departments.add(Department.COMPUTER_SCIENCE);
		dump("export_dept_only", ExportQueryBuilder.build(2025, deptOnly));

		ExportFilter narrowed = new ExportFilter();
		narrowed.courseIDs.add(1L);
		narrowed.departments.add(Department.PHYSICS);
		narrowed.years.add(Year.SY);
		narrowed.streams.add(Stream.BSc);
		dump("export_narrowed", ExportQueryBuilder.build(2025, narrowed));

		ExportFilter oneDivision = new ExportFilter();
		oneDivision.divisions.add(Division.B);
		dump("export_division_only", ExportQueryBuilder.build(2025, oneDivision));

		for (StatsGroupBy groupBy : StatsGroupBy.values()) {
			StatsFilter filter = new StatsFilter(2025);
			filter.groupBy = groupBy;
			dump("stats_grouped_" + groupBy.name(), StatsQueryBuilder.grouped(filter));
		}

		// Batch on its own, for the harness to run against nested batches. Two arguments in
		// this order: the session, then the batch.
		StatsFilter batchOnly = new StatsFilter(2025);
		batchOnly.batchID = 1L;
		dump("stats_batch_only", StatsQueryBuilder.grouped(batchOnly));

		StatsFilter batchAndCourse = new StatsFilter(2025);
		batchAndCourse.courseIDs.add(2L);
		batchAndCourse.batchID = 1L;
		dump("stats_batch_and_course", StatsQueryBuilder.grouped(batchAndCourse));

		dump("stats_summary", StatsQueryBuilder.summary(new StatsFilter(2025)));
		dump("stats_all_sessions", StatsQueryBuilder.grouped(new StatsFilter(null)));
		dump("stats_full_filter", StatsQueryBuilder.grouped(fullyPopulatedFilter()));

		for (StatsFilter.SortOrder order : StatsFilter.SortOrder.values()) {
			StatsFilter filter = new StatsFilter(2025);
			filter.sortOrder = order;
			dump("stats_sort_" + order.name(), StatsQueryBuilder.grouped(filter));
		}

		dump("probe_null_argument",
				new SimpleSQLiteQuery("SELECT ? AS value", new Object[]{null}));

		// The value the sentinel has to stay distinguishable from.
		dump("probe_null_like_string",
				new SimpleSQLiteQuery("SELECT ? AS value", new Object[]{"NULL"}));

		assertTrue(Files.isDirectory(new File("build/wb-sql").toPath()));
	}

	@Test
	public void aNullArgumentSurvivesTheDumpFormat() throws IOException {
		// The builders never bind a null today, so this branch of dump() is only reachable
		// deliberately. It is still worth pinning: the marker used to carry a stray control
		// character instead of its leading space, which no longer matched what the harness
		// parses, and nothing would have noticed until the first query bound a null.
		dump("probe_null_argument",
				new SimpleSQLiteQuery("SELECT ? AS value", new Object[]{null}));

		String written = new String(Files.readAllBytes(
				new File("build/wb-sql/probe_null_argument.sql").toPath()),
				StandardCharsets.UTF_8);

		String args = written.substring(written.indexOf("----ARGS----\n")
				+ "----ARGS----\n".length());

		assertEquals("sql_harness.py reads exactly this line back as None",
				" NULL\n", args);
		assertEquals("the marker is a space, not a control character",
				' ', args.charAt(0));
	}

	private static StatsFilter fullyPopulatedFilter() {
		StatsFilter filter = new StatsFilter(2025);
		filter.groupBy = StatsGroupBy.STUDENT;
		filter.applyDateRange(StatsFilter.DateRange.SESSION_FIRST_HALF,
				EntityAcademicYear.defaultFor(2025));
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

	/** Captures whatever a query binds, in order, without needing a database. */
	private static final class ArgRecorder
			implements androidx.sqlite.db.SupportSQLiteProgram {

		private final List<Object> args = new ArrayList<>();

		private void put(int index, Object value) {
			while (args.size() < index) {
				args.add(null);
			}
			if (args.size() == index) {
				args.add(value);
			} else {
				args.set(index, value);
			}
		}

		@Override
		public void bindNull(int index) {
			put(index - 1, null);
		}

		@Override
		public void bindLong(int index, long value) {
			put(index - 1, value);
		}

		@Override
		public void bindDouble(int index, double value) {
			put(index - 1, value);
		}

		@Override
		public void bindString(int index, String value) {
			put(index - 1, value);
		}

		@Override
		public void bindBlob(int index, byte[] value) {
			put(index - 1, value);
		}

		@Override
		public void clearBindings() {
			args.clear();
		}

		@Override
		public void close() {
		}
	}
}
