package in.basulabs.siesattendance.server.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import in.basulabs.siesattendance.backend.testing.QueryCases;

/**
 * Writes every PostgreSQL statement the builders produce to {@code build/wb-sql-pg/}, for
 * {@code app/src/test/sql/sql_differ.py} to execute.
 * <p>
 * The counterpart of the dump in the app's {@code SqlBuilderTest}, in the same format and under
 * the same file names, generated from the same shared case list. The differ pairs the two
 * directories by file name, runs each statement against its own engine over equivalent
 * fixtures, and compares the rows. Nothing here asserts what the SQL should say — that is the
 * differ's job, and it answers it by running it.
 * <p>
 * The format is a statement, a {@code ----ARGS----} line, then one argument per line. A null is
 * written as a space followed by NULL; the leading space is the whole sentinel, since a query
 * is free to bind the four-letter string NULL and that has to come back as those four letters.
 * This side binds nulls for real — an absent date bound is a null — so unlike the app's dump
 * that branch is not hypothetical.
 */
class SqlDumpTest {

	/** How a null argument is written, and what the differ reads it back as. */
	private static final String NULL_MARKER = " NULL";

	private static final Path DIR = new File("build/wb-sql-pg").toPath();

	private static void dump(String name, BoundStatement statement) throws IOException {

		Files.createDirectories(DIR);

		StringBuilder out = new StringBuilder();
		out.append(statement.sql()).append("\n----ARGS----\n");
		for (Object arg : statement.args()) {
			out.append(arg == null ? NULL_MARKER : String.valueOf(arg)).append("\n");
		}

		Files.write(DIR.resolve(name + ".sql"),
				out.toString().getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void writeGeneratedStatementsForExecution() throws IOException {

		for (QueryCases.ExportCase export : QueryCases.exports()) {
			dump(export.name,
					PgExportQueryBuilder.build(export.academicYear, export.filter));
		}

		for (QueryCases.StatsCase stats : QueryCases.stats()) {
			dump(stats.name, stats.summary
					? PgStatsQueryBuilder.summary(stats.filter)
					: PgStatsQueryBuilder.grouped(stats.filter));
		}

		assertTrue(Files.isDirectory(DIR));
		assertEquals(QueryCases.exports().size() + QueryCases.stats().size(),
				Files.list(DIR).filter(p -> p.toString().endsWith(".sql")).count(),
				"one dump file per case, no leftovers from a renamed case");
	}

	@Test
	void aNullArgumentSurvivesTheDumpFormat() throws IOException {

		// An unrestricted export binds four nulls, two per absent date bound, so this is the
		// real thing rather than a probe. The app's dump test pins the same sentinel from the
		// other side; if the two ever stop agreeing, the differ reads a null as the text.
		dump("probe_null_argument", PgExportQueryBuilder.build(2025,
				new in.basulabs.siesattendance.backend.util.ExportFilter()));

		String written = new String(
				Files.readAllBytes(DIR.resolve("probe_null_argument.sql")),
				StandardCharsets.UTF_8);
		String args = written.substring(
				written.indexOf("----ARGS----\n") + "----ARGS----\n".length());

		assertTrue(args.contains(NULL_MARKER + "\n"),
				"an absent date bound is written as the null sentinel: " + args);
		assertEquals(8, args.lines().filter(line -> line.equals(NULL_MARKER)).count(),
				"two nulls per absent bound, two bounds, in each of the two subqueries");

		Files.delete(DIR.resolve("probe_null_argument.sql"));
	}
}
