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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import in.basulabs.siesattendance.backend.AcademicRules;
import in.basulabs.siesattendance.backend.CourseType;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Division;
import in.basulabs.siesattendance.backend.PostgresEnumTypes;
import in.basulabs.siesattendance.backend.StructuredId;
import in.basulabs.siesattendance.backend.migration.SchemaMigrations;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;
import in.basulabs.siesattendance.backend.sync.DeviceTables;

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
	/*
	 * The map moved to the shared module when the server arrived: the server needs the same
	 * type names to cast its bound parameters with, and two copies of this would drift. This
	 * test is what holds the shared map and the migration together.
	 */
	private static final Map<Class<? extends Enum<?>>, String> ENUM_TYPES =
			PostgresEnumTypes.all();

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

		Map<String, List<String>> declared = everyEnumType();

		for (Map.Entry<Class<? extends Enum<?>>, String> entry : ENUM_TYPES.entrySet()) {

			String typeName = entry.getValue();
			List<String> expected = constantNames(entry.getKey());
			List<String> actual = declared.get(typeName);

			if (actual == null) {
				fail("no migration declares an enum type '" + typeName + "' for "
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

		Map<String, List<String>> declared = everyEnumType();
		List<String> known = new ArrayList<>(ENUM_TYPES.values());

		for (String typeName : declared.keySet()) {
			assertTrue("the migrations declare enum type '" + typeName
							+ "', which no Java enum maps to. Either map it in ENUM_TYPES or "
							+ "remove it.",
					known.contains(typeName));
		}
	}

	@Test
	public void enumValuesAreConstantNamesAndNotDisplayLabels() throws IOException {

		// The labels are display text and change freely; Stream.BSc and Stream.BSc_unaided
		// both read "BSc", so a schema built from labels could not tell them apart at all.
		Map<String, List<String>> declared = everyEnumType();

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

	// ── The roll, defined once in V23 ────────────────────────────────────

	/*
	 * course_admits() is the one definition of a course's roll on the server: the members of
	 * the batches linked to the course for the session when it has any, and the course-type
	 * rules -- a translation of DaoStudent.getRollForCourseSync -- when it has none. Until V23
	 * the rules were written twice, in roll_for_course() and actor_reaches_enrolment(), and
	 * held together by these tests. Now both of those call course_admits(), and the tests check
	 * that they do as well as what it says.
	 *
	 * The thing that can still go wrong is a course type the rules never mention: it matches
	 * nobody, so a course of that type with no batch has an empty roll, no CR can be designated
	 * for it, and nothing fails loudly.
	 */

	private static String courseAdmits() throws IOException {
		return normalise(statementContaining(read("V23__a_batch_is_the_roll.sql"),
				"CREATE FUNCTION course_admits"));
	}

	@Test
	public void theRollAccountsForEveryCourseType() throws IOException {

		String body = courseAdmits();

		for (CourseType type : CourseType.values()) {
			assertTrue("course_admits in V23__a_batch_is_the_roll.sql never mentions "
							+ type.name() + ", so a course of that type with no batch would "
							+ "have an empty roll and no CR could be designated for it",
					body.contains("'" + type.name() + "'"));
		}
	}

	@Test
	public void theRollMatchesTheDepartmentTheAppDoes() throws IOException {

		String body = courseAdmits();

		// The types that follow the offering department are matched on the student's major,
		// and V4's exemption list is the same list. If these ever disagree, one of the two is
		// describing a college that does not exist.
		List<String> quoted = new ArrayList<>();
		for (CourseType type : CourseType.followingOfferingDepartment()) {
			quoted.add("'" + type.name() + "'");
		}

		assertTrue("the major-department branch of course_admits should match "
						+ "CourseType.followingOfferingDepartment(), which reads "
						+ String.join(", ", quoted) + "\nThe function reads: " + body,
				body.contains("c.course_type IN (" + String.join(", ", quoted) + ")"));

		// The minor branch deliberately admits the major as well -- see the roster query's
		// javadoc.
		assertTrue("the minor branch must admit the student's major department too, because "
						+ "the minor field on the student form is optional and often blank",
				body.contains("e_minor = c.offering_department")
						&& body.contains("e_major = c.offering_department"));
		assertTrue("the open-elective branch must match the enrolment's oe department",
				body.contains("e_oe = c.offering_department"));
	}

	@Test
	public void aCourseWithBatchesTakesItsRollFromThem() throws IOException {

		String body = courseAdmits();

		assertTrue("course_admits must ask whether the course has batches for the session "
						+ "before falling back to the rules",
				body.contains("WHEN course_has_batches(for_course, session_year)"));
		assertTrue("and, when it does, admit exactly their members in that session",
				body.contains("m.student_id = e_student")
						&& body.contains("m.academic_year = session_year"));
	}

	@Test
	public void bothAskersOfTheRollCallTheOneDefinition() throws IOException {

		String migration = read("V23__a_batch_is_the_roll.sql");

		String roll = normalise(statementContaining(migration,
				"CREATE OR REPLACE FUNCTION roll_for_course"));
		String reach = normalise(statementContaining(migration,
				"CREATE FUNCTION actor_reaches_enrolment"));

		assertTrue("roll_for_course must list course_admits(), not a copy of it: " + roll,
				roll.contains("course_admits("));
		assertTrue("actor_reaches_enrolment must ask course_admits(), not a copy of it: "
						+ reach, reach.contains("course_admits(")
						&& !reach.contains("c.course_type"));
	}

	@Test
	public void theSyncedTablesAreEveryDeviceTableInDependencyOrder() throws IOException {

		String body = normalise(statementContaining(read("V11__roster_reachability.sql"),
				"CREATE OR REPLACE FUNCTION synced_tables"));

		List<String> expected = new ArrayList<>();
		for (DeviceTables.Table table : DeviceTables.inDependencyOrder()) {
			expected.add("'" + table.name() + "'");
		}

		// Order, not just membership. SyncReader reads this array in order to build a first
		// snapshot, so a device applying that page as it arrives depends on it: a lecture
		// before its course is a foreign key violation on the phone.
		assertTrue("synced_tables() must be every table a device holds, in "
						+ "DeviceTables.inDependencyOrder(), which reads "
						+ String.join(", ", expected) + "\nThe function reads: " + body,
				body.contains("ARRAY[" + String.join(", ", expected) + "]"));
	}

	// ── The cross-department exemption in V4 ─────────────────────────────

	/*
	 * V2 required every course's stream to be one its offering department offers. That holds
	 * only for the types whose roll is the offering department's own majors; for a minor, an
	 * open elective, or the types a whole year takes, the offering department is somebody
	 * else's and the stream is the class being taught. V4 narrows the foreign key to the four
	 * types it is true of, through a generated column that is null for the rest.
	 *
	 * These three tests are what stop the four drifting away from the enum, which is the one
	 * way this could go wrong quietly: a course type added to CourseType and not thought about
	 * here would keep the lenient behaviour, and a type whose flag was flipped without the SQL
	 * following would be enforced on the wrong rows.
	 */

	@Test
	public void theGeneratedStreamColumnCoversExactlyTheTypesFollowingTheDepartment()
			throws IOException {

		String add = statementContaining(normalise(read("V4__cross_department_courses.sql")),
				"ALTER TABLE courses ADD COLUMN own_department_stream");

		List<String> quoted = new ArrayList<>();
		for (CourseType type : CourseType.followingOfferingDepartment()) {
			quoted.add("'" + type.name() + "'");
		}
		String wanted = "course_type IN (" + String.join(", ", quoted) + ")";

		assertTrue("V4__cross_department_courses.sql exempts a different set of course types "
						+ "from courses_department_offers_stream_fkey than "
						+ "CourseType.followingOfferingDepartment() does.\nIt should read: "
						+ wanted + "\nThe statement is: " + add,
				add.contains(wanted));
	}

	@Test
	public void theDepartmentStreamForeignKeyIsOnTheGeneratedColumn() throws IOException {

		String v2 = normalise(read("V2__constraints.sql"));
		String v4 = normalise(read("V4__cross_department_courses.sql"));

		// V2 is left as it was written. A migration already applied somewhere is a fixed
		// artefact, so the correction is a new file rather than an edit to that one.
		assertTrue("V2__constraints.sql should still add the strict form that V4 corrects",
				v2.contains("FOREIGN KEY (offering_department, stream) "
						+ "REFERENCES department_stream (department, stream)"));

		assertTrue("V4 must drop V2's constraint before putting the corrected one in its place",
				v4.contains("DROP CONSTRAINT courses_department_offers_stream_fkey"));

		// On the generated column, not on stream: PostgreSQL has no partial foreign key, and a
		// key whose column is null is not checked at all, which is what exempts the other
		// nine types.
		assertTrue("the replacement foreign key must reference own_department_stream",
				v4.contains("FOREIGN KEY (offering_department, own_department_stream) "
						+ "REFERENCES department_stream (department, stream)"));
	}

	@Test
	public void theImporterNeverWritesTheGeneratedColumn() {

		// GENERATED ALWAYS rejects an explicit value, so a column named in DeviceTables would
		// fail every course INSERT the importer builds. It is derived and belongs to nobody.
		for (DeviceTables.Column column : DeviceTables.of("courses").columns()) {
			assertFalse("DeviceTables must not carry courses.own_department_stream: it is a "
							+ "generated column, and naming it would make the importer supply "
							+ "a value PostgreSQL refuses",
					"own_department_stream".equals(column.postgres()));
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

	/**
	 * The node numbers the server hands out have to come from the half of the space
	 * {@link StructuredId} reserves for it, and the ones a device may report having picked for
	 * itself from the other half.
	 * <p>
	 * Three literals in V8, and they are the whole of the agreement. Widen the sequence past
	 * {@code MAX_ASSIGNED_NODE_ID} and the server starts issuing numbers an unregistered
	 * install may already have chosen; narrow the provisional range and a phone reporting its
	 * own node is refused for telling the truth.
	 */
	@Test
	public void theDeviceNodeRangesMatchStructuredId() throws IOException {

		String sql = read("V8__the_wire.sql");

		assertTrue("device_node_seq must run from StructuredId.FIRST_ASSIGNABLE_NODE_ID ("
						+ StructuredId.FIRST_ASSIGNABLE_NODE_ID + ") to MAX_ASSIGNED_NODE_ID ("
						+ StructuredId.MAX_ASSIGNED_NODE_ID + ")",
				sql.contains("MINVALUE " + StructuredId.FIRST_ASSIGNABLE_NODE_ID
						+ " MAXVALUE " + StructuredId.MAX_ASSIGNED_NODE_ID));

		assertTrue("device_registration.node must be constrained to the assignable half",
				sql.contains("node BETWEEN " + StructuredId.FIRST_ASSIGNABLE_NODE_ID
						+ " AND " + StructuredId.MAX_ASSIGNED_NODE_ID));

		assertTrue("a provisional node must be from the half a device picks for itself, "
						+ (StructuredId.MAX_ASSIGNED_NODE_ID + 1) + " to "
						+ StructuredId.MAX_NODE_ID,
				sql.contains("provisional_node BETWEEN "
						+ (StructuredId.MAX_ASSIGNED_NODE_ID + 1) + " AND "
						+ StructuredId.MAX_NODE_ID));
	}

	/**
	 * The tables {@code id_remap} will accept are exactly the five whose keys the app issues.
	 * <p>
	 * A sixth such table added in Java would need an entry here too, and the failure without
	 * this test would be an import stopping on a check constraint months later.
	 */
	@Test
	public void idRemapAcceptsExactlyTheTablesWithIssuedKeys() throws IOException {

		String sql = normalise(read("V3__import_bookkeeping.sql"));

		String expected = "CHECK (table_name IN ("
				+ String.join(", ", quoted(DeviceTables.tablesWithIssuedKeys())) + "))";

		assertTrue("V3__import_bookkeeping.sql must restrict id_remap.table_name to "
						+ DeviceTables.tablesWithIssuedKeys() + ", as:\n  " + expected
						+ "\nnormalised SQL was:\n  " + sql,
				sql.contains(expected));
	}

	/**
	 * {@code id_remap} promises every key in it is one the server minted, which means
	 * structured. The constant has to be the same one {@link StructuredId} uses.
	 */
	@Test
	public void idRemapUsesTheStructuredIdFloor() throws IOException {

		String sql = read("V3__import_bookkeeping.sql");

		assertTrue("V3__import_bookkeeping.sql must use StructuredId.FIRST_STRUCTURED_ID ("
						+ StructuredId.FIRST_STRUCTURED_ID + ") as the floor for server_id",
				sql.contains(String.valueOf(StructuredId.FIRST_STRUCTURED_ID)));

		assertTrue("the node range must stop at StructuredId.MAX_NODE_ID ("
						+ StructuredId.MAX_NODE_ID + ")",
				sql.contains("BETWEEN " + StructuredId.FIRST_ASSIGNABLE_NODE_ID
						+ " AND " + StructuredId.MAX_NODE_ID));
	}

	/**
	 * The schema versions {@code import_run} will record have to be the ones the shared
	 * migrations can actually bring forward. A backup claiming anything else is refused before
	 * a row is written, so a row outside the range would mean the two disagree.
	 */
	@Test
	public void importRunAcceptsExactlyTheSchemaVersionsTheMigrationsCover() throws IOException {

		// Across every migration, not just V3's, and the LAST one wins: a range cannot be
		// edited in place once V3 has been applied somewhere, so widening it means a new
		// migration that drops the constraint and adds it again. Reading only V3 would fail
		// the day the device schema moves, which is exactly the day this test should pass.
		String expected = "CHECK (backup_schema BETWEEN "
				+ SchemaMigrations.FIRST_SUPPORTED_VERSION + " AND "
				+ SchemaMigrations.CURRENT_VERSION + ")";

		String last = null;

		for (String file : migrationFileNames()) {

			String sql = normalise(read(file));

			if (sql.contains("CHECK (backup_schema BETWEEN ")) {
				last = file;
			}
		}

		assertTrue("no migration constrains import_run.backup_schema at all", last != null);

		assertTrue("the last migration to set import_run.backup_schema (" + last + ") must "
						+ "span " + SchemaMigrations.FIRST_SUPPORTED_VERSION + ".."
						+ SchemaMigrations.CURRENT_VERSION + ", as:\n  " + expected,
				normalise(read(last)).contains(expected));
	}

	/** Each value wrapped in single quotes, as the SQL writes them. */
	private static List<String> quoted(List<String> values) {

		List<String> out = new ArrayList<>();

		for (String value : values) {
			out.add("'" + value + "'");
		}
		return out;
	}

	/**
	 * Every column {@code DeviceTables} says the server keeps really is declared in the
	 * baseline, under exactly that name.
	 * <p>
	 * The importer builds its inserts from {@code DeviceTables}, so a column named wrongly
	 * there produces SQL that is perfectly well-formed and fails against the real database. A
	 * database is not needed to catch it: the migration is right here, and the two can simply
	 * be compared.
	 */
	@Test
	public void everyCarriedColumnExistsInTheBaseline() throws IOException {

		String sql = read("V1__baseline.sql");
		String later = everyMigrationAfterTheBaseline();

		for (DeviceTables.Table table : DeviceTables.inDependencyOrder()) {

			String create = statementContaining(sql, "CREATE TABLE " + table.name() + " (");
			String body = normalise(create);

			for (DeviceTables.Column column : table.columns()) {

				if (!column.isCarried()) {
					continue;
				}

				// In the baseline, or added since by a migration of its own: a migration that
				// has been applied anywhere is never edited, so a new column arrives as an
				// ALTER TABLE ... ADD COLUMN in a later file.
				assertTrue("no migration declares the column " + table.name() + "."
								+ column.postgres() + ", which DeviceTables says is carried",
						declaresColumn(body, column.postgres())
								|| addsColumn(later, table.name(), column.postgres()));

				if (column.zoneColumn() != null) {
					assertTrue("V1__baseline.sql declares no zone column " + table.name()
									+ "." + column.zoneColumn(),
							declaresColumn(body, column.zoneColumn()));
				}
			}
		}
	}

	/**
	 * The reverse direction, for the data columns: a column in the baseline that
	 * {@code DeviceTables} does not know about would be one the importer silently never fills.
	 * <p>
	 * The provenance columns are excluded because nothing on a device corresponds to them —
	 * they are nullable precisely so an import can leave them unset — and so is every column
	 * the description marks as deliberately not carried.
	 */
	@Test
	public void theBaselineHasNoDataColumnTheImporterWouldNeverFill() throws IOException {

		Set<String> provenance = new HashSet<>(Arrays.asList(
				"created_at", "created_by", "updated_at", "updated_by", "row_version"));

		String sql = read("V1__baseline.sql");

		for (DeviceTables.Table table : DeviceTables.inDependencyOrder()) {

			Set<String> described = new HashSet<>();

			for (DeviceTables.Column column : table.columns()) {
				if (column.isCarried()) {
					described.add(column.postgres());
					if (column.zoneColumn() != null) {
						described.add(column.zoneColumn());
					}
				}
			}

			String create = statementContaining(sql, "CREATE TABLE " + table.name() + " (");

            for (String declared : columnNamesIn(create)) {
				if (provenance.contains(declared)) {
					continue;
				}
				assertTrue(table.name() + "." + declared + " is in V1__baseline.sql but not "
								+ "in DeviceTables, so an import would leave it unset",
						described.contains(declared));
			}
		}
	}

	/** Every migration after V1, one after another, for the columns added since. */
	private static String everyMigrationAfterTheBaseline() throws IOException {

		StringBuilder all = new StringBuilder();

		for (int version = 2; ; version++) {

			String found = null;

			for (String name : migrationNames()) {
				if (name.startsWith("V" + version + "__")) {
					found = name;
				}
			}

			if (found == null) {
				return all.toString();
			}
			all.append(read(found)).append('\n');
		}
	}

	/** The file names in the migration directory, found the way {@link #read} finds it. */
	private static List<String> migrationNames() throws IOException {

		File directory = new File("").getAbsoluteFile();

		for (int depth = 0; depth < 5 && directory != null; depth++) {

			File candidate = new File(directory, "server/src/main/resources/db/migration");
			String[] names = candidate.list();

			if (names != null) {
				return Arrays.asList(names);
			}
			directory = directory.getParentFile();
		}
		throw new IOException("no migration directory above " + new File("").getAbsolutePath());
	}

	/** Whether these migrations add this column to this table with ALTER TABLE. */
	private static boolean addsColumn(String sql, String table, String column) {
		return Pattern.compile("ALTER TABLE\\s+" + table + "\\b[^;]*?ADD COLUMN\\s+" + column
				+ "\\b", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(sql).find();
	}

	/** Whether a normalised CREATE TABLE body declares a column of this name. */
	private static boolean declaresColumn(String body, String column) {
		return body.contains("( " + column + " ") || body.contains(", " + column + " ");
	}

	/**
	 * The column names a CREATE TABLE declares, which is every line of its body that starts
	 * with an identifier rather than with a constraint keyword.
	 */
	private static List<String> columnNamesIn(String create) {

		List<String> names = new ArrayList<>();

		int open = create.indexOf('(');
		String body = create.substring(open + 1);

		int depth = 0;
		StringBuilder current = new StringBuilder();
		List<String> parts = new ArrayList<>();

		for (char c : body.toCharArray()) {
			if (c == '(') {
				depth++;
			} else if (c == ')') {
				if (depth == 0) {
					break;
				}
				depth--;
			}
			if (c == ',' && depth == 0) {
				parts.add(current.toString());
				current.setLength(0);
			} else {
				current.append(c);
			}
		}
		parts.add(current.toString());

		for (String part : parts) {

			String trimmed = part.trim();

			if (trimmed.isEmpty()) {
				continue;
			}

			String first = trimmed.split("\\s+")[0].toUpperCase(Locale.ROOT);

			if (first.equals("CONSTRAINT") || first.equals("PRIMARY") || first.equals("UNIQUE")
					|| first.equals("FOREIGN") || first.equals("CHECK")
					|| first.equals("EXCLUDE")) {
				continue;
			}
			names.add(trimmed.split("\\s+")[0]);
		}
		return names;
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
	/**
	 * Every enum type any migration declares, merged.
	 * <p>
	 * V1 held all of them until V5 added the identity types, and a test that reads one file by
	 * name would simply stop covering whatever a later migration adds — failing open, which is
	 * the one way a drift test must not fail.
	 */
	private static Map<String, List<String>> everyEnumType() throws IOException {

		Map<String, List<String>> all = new LinkedHashMap<>();

		for (String file : migrationFileNames()) {
			for (Map.Entry<String, List<String>> entry : enumTypesIn(read(file)).entrySet()) {
				List<String> clash = all.put(entry.getKey(), entry.getValue());
				assertTrue("enum type '" + entry.getKey() + "' is declared by more than one "
						+ "migration", clash == null);
			}
		}
		return all;
	}

	/** The committed migrations, in the order Flyway applies them. */
	private static List<String> migrationFileNames() throws IOException {

		File directory = new File("").getAbsoluteFile();

		for (int depth = 0; depth < 5 && directory != null; depth++) {

			File candidate = new File(directory, "server/src/main/resources/db/migration");

			if (candidate.isDirectory()) {

				String[] names = candidate.list((dir, name) ->
						name.startsWith("V") && name.endsWith(".sql"));

				assertTrue("no migrations in " + candidate, names != null && names.length > 0);

				List<String> sorted = new ArrayList<>(Arrays.asList(names));
				// V10 would sort before V2 as text, so compare the version numbers.
				sorted.sort((a, b) -> Integer.compare(versionOf(a), versionOf(b)));
				return sorted;
			}
			directory = directory.getParentFile();
		}
		throw new IOException("no server/src/main/resources/db/migration above "
				+ new File("").getAbsolutePath());
	}

	private static int versionOf(String fileName) {
		Matcher matcher = Pattern.compile("^V(\\d+)__").matcher(fileName);
		assertTrue(fileName + " is not named V<n>__<name>.sql", matcher.find());
		return Integer.parseInt(matcher.group(1));
	}

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
