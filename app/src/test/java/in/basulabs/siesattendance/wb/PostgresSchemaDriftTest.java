package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import in.basulabs.siesattendance.backend.AcademicRules;
import in.basulabs.siesattendance.backend.CourseType;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Division;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;

/**
 * Keeps the PostgreSQL migrations in step with the Java the app runs on.
 * <p>
 * The server schema states, in SQL, several things that currently live only in Java: the set of
 * values each enum column may hold, which streams a department offers, which semesters a year
 * of study covers, and which streams are split into divisions. All four are written out by hand
 * in {@code server/src/main/resources/db/migration/}, because a migration has to be a fixed
 * artefact that is reviewed and then never changes. Generating them at deploy time would mean a
 * schema that quietly rewrites itself when the Java changes, which is the opposite of what a
 * migration is for.
 * <p>
 * The cost of writing them by hand is that they can drift. This test is what makes the drift
 * impossible to miss: every expectation below is derived from the Java at test time and compared
 * against the committed SQL. A mismatch fails with the text that should be in the file.
 *
 * <h3>Why name() and declaration order</h3>
 * {@code TypeConvertors} stores {@code name()} and never {@code toString()}, so the enum values
 * in SQL must be the constant names exactly. Declaration order is held to as well, so that
 * {@code ORDER BY} on one of those columns reproduces the order the app's own dropdowns use, and
 * so that a generated expectation has one correct form rather than any permutation.
 *
 * <h3>What this test does not do</h3>
 * It does not check that the SQL is valid or that it applies. That is
 * {@code server/tools/verify-schema.sh}, which applies both migrations to a real PostgreSQL
 * instance and runs {@code constraint_smoke_test.sql} against them. This test only compares two
 * texts, so it needs no database and runs in the ordinary unit test suite.
 */
public class PostgresSchemaDriftTest {

	/** Java enum class to the PostgreSQL type name it is stored as. */
	private static final Map<Class<? extends Enum<?>>, String> ENUM_TYPES =
			new LinkedHashMap<>();

	static {
		ENUM_TYPES.put(Department.class, "department");
		// Named degree_stream so a column declaration does not read "stream stream NOT NULL".
		ENUM_TYPES.put(Stream.class, "degree_stream");
		ENUM_TYPES.put(Year.class, "study_year");
		ENUM_TYPES.put(Division.class, "class_division");
		ENUM_TYPES.put(CourseType.class, "course_type");
	}

	private static final Pattern CREATE_TYPE = Pattern.compile(
			"CREATE\\s+TYPE\\s+(\\w+)\\s+AS\\s+ENUM\\s*\\(([^)]*)\\)",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern QUOTED = Pattern.compile("'([^']*)'");

	/** Three-column tuples from the department_stream seed. */
	private static final Pattern SEED_ROW = Pattern.compile(
			"\\(\\s*'(\\w+)'\\s*,\\s*'(\\w+)'\\s*,\\s*(\\d+)\\s*\\)");

	// ── Enum types ───────────────────────────────────────────────────────

	@Test
	public void everyEnumTypeExistsWithExactlyTheJavaConstants() throws IOException {

		Map<String, List<String>> declared = enumTypesIn(read("V1__baseline.sql"));

		for (Map.Entry<Class<? extends Enum<?>>, String> entry : ENUM_TYPES.entrySet()) {

			String typeName = entry.getValue();
			List<String> expected = constantNames(entry.getKey());
			List<String> actual = declared.get(typeName);

			if (actual == null) {
				fail("V1__baseline.sql declares no enum type '" + typeName + "' for "
						+ entry.getKey().getSimpleName() + ". It should read:\n\n"
						+ createTypeSql(typeName, expected));
			}

			// Compared as ordered lists: a value appended in the wrong place would reorder
			// every dropdown that reads the column back.
			assertEquals("enum type '" + typeName + "' has drifted from "
							+ entry.getKey().getSimpleName()
							+ ". It should read:\n\n" + createTypeSql(typeName, expected) + "\n",
					expected, actual);
		}
	}

	@Test
	public void theMigrationDeclaresNoEnumTypeTheAppDoesNotHave() throws IOException {

		Map<String, List<String>> declared = enumTypesIn(read("V1__baseline.sql"));
		List<String> known = new ArrayList<>(ENUM_TYPES.values());

		for (String typeName : declared.keySet()) {
			assertTrue("V1__baseline.sql declares enum type '" + typeName
							+ "', which no Java enum maps to. Either map it in ENUM_TYPES or "
							+ "remove it.",
					known.contains(typeName));
		}
	}

	@Test
	public void enumValuesAreConstantNamesAndNotDisplayLabels() throws IOException {

		// The labels are display text and change freely; Stream.BSc and Stream.BSc_unaided
		// both read "BSc", so a schema built from labels could not tell them apart at all.
		Map<String, List<String>> declared = enumTypesIn(read("V1__baseline.sql"));

		assertTrue("degree_stream must distinguish the aided and unaided BSc",
				declared.get("degree_stream").contains("BSc_unaided"));
		assertFalse("degree_stream must not store display labels",
				declared.get("degree_stream").contains("BSc (unaided)"));
		assertFalse("study_year must not store display labels",
				declared.get("study_year").contains("Masters Part 1"));
		assertFalse("course_type must not store display labels",
				declared.get("course_type").contains("Major practicals"));
	}

	// ── department_stream seed ───────────────────────────────────────────

	@Test
	public void departmentStreamSeedMatchesAcademicRules() throws IOException {

		String sql = read("V2__constraints.sql");
		String insert = statementContaining(sql, "INSERT INTO department_stream");

		List<String> actual = new ArrayList<>();
		Matcher matcher = SEED_ROW.matcher(insert);
		while (matcher.find()) {
			actual.add(matcher.group(1) + "|" + matcher.group(2) + "|" + matcher.group(3));
		}

		List<String> expected = new ArrayList<>();
		for (Department department : Department.values()) {
			int ordinal = 1;
			for (Stream stream : AcademicRules.streamsFor(department)) {
				expected.add(department.name() + "|" + stream.name() + "|" + ordinal);
				ordinal++;
			}
		}

		// Sorted before comparing: the seed is grouped by the kind of department it describes
		// for readability, which is not AcademicRules' iteration order and should not have to
		// be.
		List<String> expectedSorted = new ArrayList<>(expected);
		List<String> actualSorted = new ArrayList<>(actual);
		java.util.Collections.sort(expectedSorted);
		java.util.Collections.sort(actualSorted);

		assertEquals("the department_stream seed in V2__constraints.sql has drifted from "
						+ "AcademicRules.STREAMS. Missing rows: "
						+ difference(expectedSorted, actualSorted)
						+ "; rows that should not be there: "
						+ difference(actualSorted, expectedSorted),
				expectedSorted, actualSorted);
	}

	@Test
	public void everyDepartmentOffersAtLeastOneStream() throws IOException {

		// AcademicRules.streamsFor promises never to return empty. If it ever did, the
		// composite foreign keys onto department_stream would make that department unusable:
		// no course and no student could be entered under it at all.
		String insert = statementContaining(read("V2__constraints.sql"),
				"INSERT INTO department_stream");

		for (Department department : Department.values()) {
			assertFalse(department + " offers no stream, so no course or student could be "
							+ "entered under it once the department_stream foreign keys exist",
					AcademicRules.streamsFor(department).isEmpty());
			assertTrue("the department_stream seed never mentions " + department.name(),
					insert.contains("'" + department.name() + "'"));
		}
	}

	// ── CHECK constraints derived from Java ──────────────────────────────

	@Test
	public void semesterCheckMatchesAcademicRules() throws IOException {

		String sql = normalise(read("V2__constraints.sql"));

		// Group the years that cover the same semesters, in first-appearance order.
		Map<List<Integer>, List<Year>> groups = new LinkedHashMap<>();
		for (Year year : Year.values()) {
			List<Integer> semesters = AcademicRules.semestersFor(year);
			groups.computeIfAbsent(semesters, key -> new ArrayList<>()).add(year);
		}

		for (Map.Entry<List<Integer>, List<Year>> group : groups.entrySet()) {

			String fragment = normalise("(" + inClause("year", names(group.getValue()), true)
					+ " AND " + inClause("semester", literals(group.getKey()), false) + ")");

			assertTrue("the semester CHECK constraints in V2__constraints.sql have drifted "
							+ "from AcademicRules.semestersFor(). Expected to find:\n\n  "
							+ fragment + "\n",
					sql.contains(fragment));
		}
	}

	@Test
	public void mastersLevelCheckMatchesTheEnums() throws IOException {

		String sql = normalise(read("V2__constraints.sql"));

		List<String> mastersYears = new ArrayList<>();
		for (Year year : Year.values()) {
			if (year.isMasters()) {
				mastersYears.add(year.name());
			}
		}

		List<String> mastersStreams = new ArrayList<>();
		for (Stream stream : Stream.values()) {
			if (stream.isMasters()) {
				mastersStreams.add(stream.name());
			}
		}

		String fragment = normalise("(" + inClause("year", mastersYears, true) + ") = ("
				+ inClause("stream", mastersStreams, true) + ")");

		assertTrue("the year/stream level CHECK constraints in V2__constraints.sql have "
						+ "drifted from Year.isMasters() or Stream.isMasters(). Expected to "
						+ "find:\n\n  " + fragment + "\n",
				sql.contains(fragment));
	}

	@Test
	public void divisionCheckMatchesStreamHasDivisions() throws IOException {

		String sql = normalise(read("V2__constraints.sql"));

		List<String> divided = new ArrayList<>();
		for (Stream stream : Stream.values()) {
			if (stream.hasDivisions()) {
				divided.add(stream.name());
			}
		}

		String fragment = normalise("division IS NULL OR "
				+ inClause("stream", divided, true));

		assertTrue("student_year_division_needs_divided_stream in V2__constraints.sql has "
						+ "drifted from Stream.hasDivisions(). Expected to find:\n\n  "
						+ fragment + "\n",
				sql.contains(fragment));
	}

	// ── The ID scheme ────────────────────────────────────────────────────

	@Test
	public void idHelperConstantsMatchIdGenerator() throws IOException {

		// The SQL ports IdGenerator's bit layout as three literals. They are spelled out
		// rather than computed, so they are worth pinning: a wrong epoch would re-date every
		// key the server reports on, silently and consistently.
		String sql = read("V1__baseline.sql");

		assertTrue("V1__baseline.sql must use IdGenerator.FIRST_STRUCTURED_ID (1 << 55) as "
						+ "the line between a structured and a legacy key",
				sql.contains(Long.toString(1L << 55)));
		assertTrue("V1__baseline.sql must use IdGenerator's 2024-01-01Z epoch in millis",
				sql.contains("1704067200000"));
		assertTrue("V1__baseline.sql must mask the node field with MAX_NODE_ID",
				sql.contains(Integer.toString((1 << 16) - 1)));
	}

	// ── Helpers ──────────────────────────────────────────────────────────

	/**
	 * Reads a migration. Tests run with the working directory at {@code app/}, but an IDE may
	 * use the repository root, so the directory is searched for upwards rather than assumed.
	 */
	private static String read(String fileName) throws IOException {

		File directory = new File("").getAbsoluteFile();

		for (int depth = 0; depth < 5 && directory != null; depth++) {

			File candidate = new File(directory,
					"server/src/main/resources/db/migration/" + fileName);

			if (candidate.isFile()) {
				return stripComments(new String(Files.readAllBytes(candidate.toPath()),
						StandardCharsets.UTF_8));
			}
			directory = directory.getParentFile();
		}

		throw new IOException("Could not find server/src/main/resources/db/migration/"
				+ fileName + " from " + new File("").getAbsolutePath());
	}

	/**
	 * Removes {@code --} line comments, leaving string literals alone.
	 * <p>
	 * Not decoration: the migrations are heavily commented, and a prose semicolon — "Unaided at
	 * both levels; shown as plain BSc and MSc" — truncated {@link #statementContaining} and
	 * silently hid half the seed from the comparison. Stripping first also means a constraint
	 * fragment can never be "found" in a comment that merely describes it.
	 * <p>
	 * Dollar-quoted bodies are not handled, because neither migration has one. Adding a
	 * function with a {@code $$} body to a migration means teaching this method about it.
	 */
	private static String stripComments(String sql) {

		StringBuilder out = new StringBuilder(sql.length());
		boolean inString = false;

		for (int i = 0; i < sql.length(); i++) {

			char c = sql.charAt(i);

			if (inString) {
				out.append(c);
				if (c == '\'') {
					// '' is an escaped quote inside a literal, not the end of one.
					if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
						out.append(sql.charAt(++i));
					} else {
						inString = false;
					}
				}
				continue;
			}

			if (c == '\'') {
				inString = true;
				out.append(c);
				continue;
			}

			if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
				int newline = sql.indexOf('\n', i);
				if (newline < 0) {
					break;
				}
				i = newline - 1;
				continue;
			}

			out.append(c);
		}
		return out.toString();
	}

	/** Every {@code CREATE TYPE ... AS ENUM} in the file, with its values in order. */
	private static Map<String, List<String>> enumTypesIn(String sql) {

		Map<String, List<String>> types = new LinkedHashMap<>();
		Matcher matcher = CREATE_TYPE.matcher(sql);

		while (matcher.find()) {

			List<String> values = new ArrayList<>();
			Matcher value = QUOTED.matcher(matcher.group(2));

			while (value.find()) {
				values.add(value.group(1));
			}
			types.put(matcher.group(1).toLowerCase(), values);
		}
		return types;
	}

	/**
	 * The one statement starting with the given text, up to its terminating semicolon. Good
	 * enough for the seed, which contains no semicolons or dollar-quoted bodies of its own.
	 */
	private static String statementContaining(String sql, String start) {

		int from = sql.indexOf(start);
		assertTrue("no statement starting '" + start + "' in the migration", from >= 0);

		int to = sql.indexOf(';', from);
		assertTrue("statement starting '" + start + "' is not terminated", to > from);

		return sql.substring(from, to);
	}

	private static List<String> constantNames(Class<? extends Enum<?>> type) {

		List<String> names = new ArrayList<>();
		for (Enum<?> constant : type.getEnumConstants()) {
			names.add(constant.name());
		}
		return names;
	}

	private static List<String> names(List<Year> years) {

		List<String> names = new ArrayList<>();
		for (Year year : years) {
			names.add(year.name());
		}
		return names;
	}

	private static List<String> literals(List<Integer> values) {

		List<String> literals = new ArrayList<>();
		for (Integer value : values) {
			literals.add(String.valueOf(value));
		}
		return literals;
	}

	/**
	 * {@code column = 'x'} for one value, {@code column IN ('x', 'y')} for several — the same
	 * shape the migrations are written in, so a generated expectation matches the file as
	 * committed.
	 */
	private static String inClause(String column, List<String> values, boolean quoted) {

		StringBuilder out = new StringBuilder(column);
		String q = quoted ? "'" : "";

		if (values.size() == 1) {
			return out.append(" = ").append(q).append(values.get(0)).append(q).toString();
		}

		out.append(" IN (");
		for (int i = 0; i < values.size(); i++) {
			if (i > 0) {
				out.append(", ");
			}
			out.append(q).append(values.get(i)).append(q);
		}
		return out.append(')').toString();
	}

	/** Collapses every run of whitespace, so the migrations stay free to align for reading. */
	private static String normalise(String sql) {
		return sql.replaceAll("\\s+", " ");
	}

	private static String createTypeSql(String typeName, List<String> values) {

		StringBuilder out = new StringBuilder("CREATE TYPE ").append(typeName)
				.append(" AS ENUM (\n");

		for (int i = 0; i < values.size(); i++) {
			out.append("    '").append(values.get(i)).append('\'')
					.append(i == values.size() - 1 ? "\n" : ",\n");
		}
		return out.append(");").toString();
	}

	private static List<String> difference(List<String> from, List<String> remove) {
		List<String> out = new ArrayList<>(from);
		out.removeAll(remove);
		return out.isEmpty() ? Arrays.asList("none") : out;
	}
}
