package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import in.basulabs.siesattendance.backend.sync.DeviceTables;

/**
 * The shared description of a device's tables.
 * <p>
 * Most of this is a golden test, and deliberately so. These values were hard-coded inside
 * {@code DatabaseMerger} and were moved here so the server's importer could read the same ones;
 * writing them out a second time is what proves the move changed none of them. After that they
 * go on earning their keep, because a natural key is exactly the kind of thing somebody edits
 * while fixing something else, and nothing else in the suite would notice.
 */
public class DeviceTablesTest {

	/** The natural keys as {@code DatabaseMerger} declared them before they moved. */
	private static Map<String, List<String>> expectedNaturalKeys() {

		Map<String, List<String>> keys = new LinkedHashMap<>();

		keys.put("students", List.of());
		keys.put("professors", List.of());
		keys.put("academic_years", List.of("academicYear"));
		keys.put("student_year", List.of("rollNo", "academicYear"));
		keys.put("courses", List.of("courseCode"));
		keys.put("batch_desc", List.of("batchName", "academicYear"));
		keys.put("batch_student_map", List.of("batchID", "studentID", "academicYear"));
		keys.put("course_batch_map", List.of("courseID", "batchID"));
		keys.put("course_instructor", List.of("courseID", "profID"));
		keys.put("lectures", List.of("courseID", "batchID", "startDateTime", "endDateTime"));
		keys.put("attendance", List.of("lectureID", "studentID"));

		return keys;
	}

	@Test
	public void everyTableIsDescribedAndNoneTwice() {

		List<String> names = new ArrayList<>();

		for (DeviceTables.Table table : DeviceTables.inDependencyOrder()) {
			names.add(table.name());
		}

		assertEquals("one entry per table", names.size(), new HashSet<>(names).size());
		assertEquals(expectedNaturalKeys().keySet(), new java.util.LinkedHashSet<>(names));
	}

	@Test
	public void theNaturalKeysAreTheOnesTheMergeUsed() {

		for (DeviceTables.Table table : DeviceTables.inDependencyOrder()) {
			assertEquals("natural key of " + table.name(),
					expectedNaturalKeys().get(table.name()), table.naturalKey());
		}
	}

	/** The primary keys, as both schemas declare them. */
	private static Map<String, List<String>> expectedPrimaryKeys() {

		Map<String, List<String>> keys = new LinkedHashMap<>();

		keys.put("students", List.of("studentID"));
		keys.put("professors", List.of("profID"));
		keys.put("academic_years", List.of("academicYear"));
		keys.put("student_year", List.of("studentID", "academicYear"));
		keys.put("courses", List.of("courseID"));
		keys.put("batch_desc", List.of("batchID"));
		keys.put("batch_student_map", List.of("batchID", "studentID", "academicYear"));
		keys.put("course_batch_map", List.of("courseID", "batchID"));
		keys.put("course_instructor", List.of("courseID", "profID"));
		keys.put("lectures", List.of("lectureID"));
		keys.put("attendance", List.of("lectureID", "studentID"));

		return keys;
	}

	@Test
	public void thePrimaryKeysAreTheOnesBothSchemasDeclare() {

		for (DeviceTables.Table table : DeviceTables.inDependencyOrder()) {
			assertEquals("primary key of " + table.name(),
					expectedPrimaryKeys().get(table.name()), table.primaryKey());
		}
	}

	/**
	 * A table with a surrogate key has that column as its whole primary key. Anything else
	 * would mean the app-issued key was not actually the identity, and the importer resolves
	 * those tables by key before it looks at anything else.
	 */
	@Test
	public void aSurrogateKeyIsTheWholePrimaryKey() {

		for (DeviceTables.Table table : DeviceTables.inDependencyOrder()) {

			if (table.surrogate() == null) {
				continue;
			}
			assertEquals(table.name(), List.of(table.surrogate()), table.primaryKey());
		}
	}

	/**
	 * {@code student_year} is the one table where the primary key says something the natural
	 * key does not, and the importer depends on knowing that.
	 * <p>
	 * A student has exactly one enrolment per session, identified by the student and the
	 * session. The roll number on it is an attribute: correcting it on a device must update
	 * that row, not insert a second one. Matching only on the roll number did exactly that
	 * until this was added, and the symptom was an import failing on the primary key the
	 * second time a device was taken in.
	 */
	@Test
	public void onlyStudentYearNeedsItsPrimaryKeyAsWellAsItsNaturalKey() {

		List<String> differing = new ArrayList<>();

		for (DeviceTables.Table table : DeviceTables.inDependencyOrder()) {
			if (table.primaryKeyDiffersFromNaturalKey()) {
				differing.add(table.name());
			}
		}
		assertEquals(List.of("student_year"), differing);
	}

	/** A primary key has to be made of columns the table actually has. */
	@Test
	public void everyPrimaryKeyColumnExists() {

		for (DeviceTables.Table table : DeviceTables.inDependencyOrder()) {
			for (String column : table.primaryKey()) {
				assertNotNull(table.name() + "." + column, table.column(column));
			}
		}
	}

	/**
	 * A natural key has to be made of columns the table actually has, or the merge would build
	 * a WHERE clause naming a column that is not there — which SQLite reports at run time and
	 * nothing reports before.
	 */
	@Test
	public void everyNaturalKeyColumnExists() {

		for (DeviceTables.Table table : DeviceTables.inDependencyOrder()) {
			for (String column : table.naturalKey()) {
				assertNotNull(table.name() + "." + column, table.column(column));
			}
		}
	}

	/**
	 * The point of the ordering. A table's foreign keys are rewritten through the identity maps
	 * of the tables it points at, so every one of those has to have been resolved already.
	 */
	@Test
	public void dependenciesComeBeforeTheTablesThatPointAtThem() {

		Set<String> resolved = new HashSet<>();

		for (DeviceTables.Table table : DeviceTables.inDependencyOrder()) {

			for (Map.Entry<String, String> fk : table.foreignKeys().entrySet()) {
				assertTrue(table.name() + "." + fk.getKey() + " points at "
								+ fk.getValue() + ", which has not been resolved yet",
						resolved.contains(fk.getValue()));
			}
			resolved.add(table.name());
		}
	}

	@Test
	public void everyForeignKeyNamesAColumnAndATableThatExist() {

		for (DeviceTables.Table table : DeviceTables.inDependencyOrder()) {
			for (Map.Entry<String, String> fk : table.foreignKeys().entrySet()) {
				assertNotNull(table.name() + "." + fk.getKey(), table.column(fk.getKey()));
				assertNotNull("parent " + fk.getValue(), DeviceTables.of(fk.getValue()));
			}
		}
	}

	/**
	 * The five tables whose keys {@code IdGenerator} issues, and only those. These are the ones
	 * whose IDs mean nothing in the world and so the ones an import may have to renumber.
	 */
	@Test
	public void theIssuedKeysAreTheFiveTablesIdGeneratorStamps() {
		assertEquals(List.of("students", "professors", "courses", "batch_desc", "lectures"),
				DeviceTables.tablesWithIssuedKeys());
	}

	/** Two tables cannot be recognised from their own columns, and it is these two. */
	@Test
	public void onlyStudentsAndProfessorsAreMatchedByHand() {

		List<String> byHand = new ArrayList<>();

		for (DeviceTables.Table table : DeviceTables.inDependencyOrder()) {
			if (table.isMatchedByHand()) {
				byHand.add(table.name());
			}
		}
		assertEquals(List.of("students", "professors"), byHand);
	}

	/**
	 * Exactly two columns are dropped on the way to the server, and they are the derived epoch
	 * bounds on the session table. Anything else going missing would be data loss.
	 */
	@Test
	public void theOnlyColumnsNotCarriedAreTheDerivedEpochBounds() {

		List<String> dropped = new ArrayList<>();

		for (DeviceTables.Table table : DeviceTables.inDependencyOrder()) {
			for (DeviceTables.Column column : table.columns()) {
				if (!column.isCarried()) {
					dropped.add(table.name() + "." + column.room());
				}
			}
		}
		assertEquals(List.of("academic_years.startEpoch", "academic_years.endEpoch"), dropped);
	}

	/**
	 * A timestamp becomes two PostgreSQL columns and an enum needs its type; both are easy to
	 * leave half-filled, and the importer would then write a null zone or fail to cast.
	 */
	@Test
	public void everyColumnCarriesWhatItsKindNeeds() {

		for (DeviceTables.Table table : DeviceTables.inDependencyOrder()) {
			for (DeviceTables.Column column : table.columns()) {

				String where = table.name() + "." + column.room();

				switch (column.kind()) {
					case ENUM -> {
						assertNotNull(where + " is an enum column with no enum type",
								column.enumType());
						assertNull(where, column.zoneColumn());
					}
					case ZONED_DATE_TIME -> {
						assertNotNull(where + " is a timestamp with no zone column",
								column.zoneColumn());
						assertNull(where, column.enumType());
					}
					default -> {
						assertNull(where, column.enumType());
						assertNull(where, column.zoneColumn());
					}
				}
			}
		}
	}

	/**
	 * The server's column names, written out.
	 * <p>
	 * Mostly they are the Room names in snake_case, and the test below checks that much
	 * mechanically. Four are not, and listing them is the point: the two lecture timestamps
	 * become {@code start_at}/{@code end_at} rather than {@code start_date_time}, because on
	 * the server the column holds an instant and the wall-clock reading moved to the
	 * {@code _zone} column beside it. A name that still said "date time" would be describing
	 * the device's storage rather than this one's.
	 */
	private static Map<String, String> renamedOnTheServer() {

		Map<String, String> renamed = new LinkedHashMap<>();

		renamed.put("lectures.startDateTime", "start_at");
		renamed.put("lectures.endDateTime", "end_at");

		return renamed;
	}

	@Test
	public void theServerNamesAreTheRoomNamesInSnakeCaseExceptWhereListed() {

		Set<String> renamesSeen = new HashSet<>();

		for (DeviceTables.Table table : DeviceTables.inDependencyOrder()) {
			for (DeviceTables.Column column : table.columns()) {

				if (!column.isCarried()) {
					continue;
				}

				String where = table.name() + "." + column.room();
				String renamed = renamedOnTheServer().get(where);

				if (renamed != null) {
					assertEquals(where, renamed, column.postgres());
					renamesSeen.add(where);
				} else {
					assertEquals(where, snakeCase(column.room()), column.postgres());
				}
			}
		}

		assertEquals("every listed rename should exist",
				renamedOnTheServer().keySet(), renamesSeen);
	}

	/** {@code offeringDepartment} to {@code offering_department}, and {@code ID} to {@code _id}. */
	private static String snakeCase(String camel) {

		StringBuilder out = new StringBuilder();

		for (int i = 0; i < camel.length(); i++) {

			char c = camel.charAt(i);

			if (!Character.isUpperCase(c)) {
				out.append(c);
				continue;
			}

			// "studentID" has to become "student_id", not "student_i_d", so a capital only
			// starts a new word when the one before it was not a capital.
			if (i > 0 && !Character.isUpperCase(camel.charAt(i - 1))) {
				out.append('_');
			}
			out.append(Character.toLowerCase(c));
		}
		return out.toString();
	}

	/**
	 * Every timestamp has a zone column beside it, and the name of that column follows the
	 * table's own convention rather than one rule.
	 * <p>
	 * {@code courses} suffixes the instant's name ({@code date_introduced_zone}) while
	 * {@code lectures} replaces the suffix ({@code start_at} to {@code start_zone}). That is
	 * an inconsistency in the schema and it is recorded here rather than tidied: the queries,
	 * the two dialects and {@code session_year_of} all name these columns, and renaming one for
	 * neatness would be a migration against nothing but taste.
	 */
	@Test
	public void everyTimestampHasItsZoneColumn() {

		Map<String, String> expected = new LinkedHashMap<>();
		expected.put("lectures.startDateTime", "start_zone");
		expected.put("lectures.endDateTime", "end_zone");
		expected.put("courses.dateIntroduced", "date_introduced_zone");
		expected.put("courses.dateDiscontinued", "date_discontinued_zone");

		Map<String, String> found = new LinkedHashMap<>();

		for (DeviceTables.Table table : DeviceTables.inDependencyOrder()) {
			for (DeviceTables.Column column : table.columns()) {
				if (column.kind() == DeviceTables.Kind.ZONED_DATE_TIME) {
					found.put(table.name() + "." + column.room(), column.zoneColumn());
				}
			}
		}

		assertEquals(expected, found);
	}

	/** A nullable column on the device must be nullable on the server, or the import stops. */
	@Test
	public void theTablesAgreeWithTheNullabilityTheMergeRelliesOn() {

		DeviceTables.Table lectures = DeviceTables.of("lectures");

		// A whole-class lecture has no batch, which is why the merge matches natural keys
		// with IS rather than = and why this column has to stay nullable.
		assertTrue("lectures.batchID", lectures.column("batchID").nullable());
		assertTrue("student_year.minorDept",
				DeviceTables.of("student_year").column("minorDept").nullable());
		assertFalse("attendance.present",
				DeviceTables.of("attendance").column("present").nullable());
	}
}
