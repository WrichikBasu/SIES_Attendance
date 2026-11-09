package in.basulabs.siesattendance.server.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import in.basulabs.siesattendance.backend.util.CanonicalCode;
import in.basulabs.siesattendance.server.testing.TestPostgres;

/**
 * V57: the SQL {@code canonical_code()} against the Java {@link CanonicalCode}, and the trigger
 * and CHECK that hold {@code student_year.roll_no} and {@code courses.course_code} to it.
 * <p>
 * Two copies of one rule, which is what this test exists to keep as one: if they disagreed, a
 * value the app looked up in its canonical form would miss a row the trigger had stored in
 * another.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CanonicalCodeSqlTest {

	private static TestPostgres postgres;

	@BeforeAll
	void startDatabase() throws Exception {

		assumeTrue(TestPostgres.isAvailable(), "Docker is not available");
		postgres = TestPostgres.start();
	}

	@AfterAll
	void stopDatabase() {
		if (postgres != null) {
			postgres.close();
		}
	}

	@BeforeEach
	void buildFixture() throws Exception {

		assumeTrue(postgres != null, "no database");

		postgres.reset();
		postgres.asSuperuser("""
				INSERT INTO academic_years (academic_year, start_date, end_date)
				    VALUES (2025, '2025-06-01', '2026-05-31');
				INSERT INTO professors (prof_id, name, email, department, designation)
				    VALUES (1001, 'A CS', 'a.cs@sies.edu.in', 'COMPUTER_SCIENCE', 'Professor');
				INSERT INTO students (student_id, name, pronouns, email) VALUES
				    (2001, 'R RAO',  'she/her',   'r.rao@sies.edu.in'),
				    (2002, 'S SHAH', 'they/them', 's.shah@sies.edu.in');
				""");
	}

	@Test
	void theSqlRuleIsTheJavaRule() throws Exception {

		List<String> inputs = List.of("cs-fy-001", " CS/FY/001 ", "CS FY_001.", "2024cs032",
				"Phys 101", "", " -/._ ", "bio-i1", "cafée", "CS१२",
				"ＣＳ" + "1", "a\tb\nc", "ÄÖÜ-ß-42", "z9");

		try (Connection connection = postgres.connection();
		     PreparedStatement statement = connection.prepareStatement(
				     "SELECT canonical_code(?)")) {

			for (String input : inputs) {
				statement.setString(1, input);

				try (ResultSet results = statement.executeQuery()) {
					results.next();
					assertEquals(CanonicalCode.of(input), results.getString(1),
							() -> "the two rules disagree on '" + input + "'");
				}
			}
		}
	}

	@Test
	void aRollNumberIsStoredCanonicallyWhoeverWritesIt() throws Exception {

		postgres.asSuperuser("""
				INSERT INTO student_year
				    (student_id, academic_year, roll_no, year, stream, major_dept, semester)
				    VALUES (2001, 2025, 'cs-fy-001', 'FY', 'BSc_unaided', 'COMPUTER_SCIENCE', 1);
				""");

		assertEquals("CSFY001", text("SELECT roll_no FROM student_year WHERE student_id = 2001"));

		postgres.asSuperuser("UPDATE student_year SET roll_no = 'cs/fy/009' "
				+ "WHERE student_id = 2001");

		assertEquals("CSFY009", text("SELECT roll_no FROM student_year WHERE student_id = 2001"));
	}

	@Test
	void aCourseCodeIsStoredCanonicallyWhoeverWritesIt() throws Exception {

		postgres.asSuperuser("""
				INSERT INTO courses
				    (course_id, prof_id, course_code, course_topic, stream,
				     offering_department, year, semester, course_type)
				    VALUES (3001, 1001, 'cs-101', 'Programming', 'BSc_unaided',
				            'COMPUTER_SCIENCE', 'FY', 1, 'MAJOR');
				""");

		assertEquals("CS101", text("SELECT course_code FROM courses WHERE course_id = 3001"));

		postgres.asSuperuser("UPDATE courses SET course_code = 'cs 102' WHERE course_id = 3001");

		assertEquals("CS102", text("SELECT course_code FROM courses WHERE course_id = 3001"));
	}

	/** Two spellings of one roll number in one session are one roll number, so the second fails. */
	@Test
	void twoSpellingsOfOneRollNumberAreOne() throws Exception {

		postgres.asSuperuser("""
				INSERT INTO student_year
				    (student_id, academic_year, roll_no, year, stream, major_dept, semester)
				    VALUES (2001, 2025, 'CS-FY-001', 'FY', 'BSc_unaided', 'COMPUTER_SCIENCE', 1);
				""");

		SQLException refused = assertThrows(SQLException.class, () -> postgres.asSuperuser("""
				INSERT INTO student_year
				    (student_id, academic_year, roll_no, year, stream, major_dept, semester)
				    VALUES (2002, 2025, 'csfy001', 'FY', 'BSc_unaided', 'COMPUTER_SCIENCE', 1);
				"""));

		assertEquals("23505", refused.getSQLState(), refused::getMessage);
	}

	/** The trigger turns a value with no letters or digits into '', which the CHECK refuses. */
	@Test
	void aValueWithNoLettersOrDigitsIsRefused() {

		SQLException roll = assertThrows(SQLException.class, () -> postgres.asSuperuser("""
				INSERT INTO student_year
				    (student_id, academic_year, roll_no, year, stream, major_dept, semester)
				    VALUES (2001, 2025, '--', 'FY', 'BSc_unaided', 'COMPUTER_SCIENCE', 1);
				"""));

		assertEquals("23514", roll.getSQLState(), roll::getMessage);

		SQLException code = assertThrows(SQLException.class, () -> postgres.asSuperuser("""
				INSERT INTO courses
				    (course_id, prof_id, course_code, course_topic, stream,
				     offering_department, year, semester, course_type)
				    VALUES (3001, 1001, '/', 'Programming', 'BSc_unaided',
				            'COMPUTER_SCIENCE', 'FY', 1, 'MAJOR');
				"""));

		assertEquals("23514", code.getSQLState(), code::getMessage);
	}

	// ── V57 on a database that already holds rows ────────────────────────
	//
	// Every other test here starts from an empty, fully migrated schema, where V57's rewrite
	// and its clash check have nothing to do. These two put back the state before V57 (its
	// objects dropped, rows written as typed), apply the real file, and roll the lot back.

	private static final String UNDO_V57 = """
			DROP TRIGGER student_year_canonical_roll_no ON student_year;
			DROP TRIGGER courses_canonical_code ON courses;
			DROP FUNCTION student_year_canonical_roll_no();
			DROP FUNCTION courses_canonical_code();
			ALTER TABLE student_year DROP CONSTRAINT student_year_roll_no_canonical;
			ALTER TABLE courses DROP CONSTRAINT courses_course_code_canonical;
			DROP FUNCTION canonical_code(text);
			""";

	private static String v57() throws Exception {
		return Files.readString(Paths.get("src/main/resources/db/migration")
				.resolve("V57__a_roll_number_is_letters_and_digits.sql"));
	}

	@Test
	void theMigrationRewritesWhatIsStoredAndTellsTheDevices() throws Exception {

		try (Connection connection = postgres.connection()) {

			connection.setAutoCommit(false);

			try (Statement statement = connection.createStatement()) {

				statement.execute(UNDO_V57);
				statement.execute("""
						INSERT INTO student_year
						    (student_id, academic_year, roll_no, year, stream, major_dept, semester)
						    VALUES (2001, 2025, 'cs-fy-001', 'FY', 'BSc_unaided',
						            'COMPUTER_SCIENCE', 1),
						           (2002, 2025, 'CSFY002',   'FY', 'BSc_unaided',
						            'COMPUTER_SCIENCE', 1);
						INSERT INTO courses
						    (course_id, prof_id, course_code, course_topic, stream,
						     offering_department, year, semester, course_type)
						    VALUES (3001, 1001, 'cs-101', 'Programming', 'BSc_unaided',
						            'COMPUTER_SCIENCE', 'FY', 1, 'MAJOR');
						""");

				statement.execute(v57());

				try (ResultSet results = statement.executeQuery("""
						SELECT (SELECT roll_no     FROM student_year WHERE student_id = 2001),
						       (SELECT row_version FROM student_year WHERE student_id = 2001),
						       (SELECT row_version FROM student_year WHERE student_id = 2002),
						       (SELECT course_code FROM courses      WHERE course_id  = 3001),
						       (SELECT count(*) FROM audit_log
						        WHERE action = 'UPDATE' AND table_name = 'student_year'
						          AND after_image ->> 'roll_no' = 'CSFY001'),
						       (SELECT count(*) FROM audit_log
						        WHERE action = 'UPDATE' AND table_name = 'courses'
						          AND after_image ->> 'course_code' = 'CS101')
						""")) {

					results.next();
					assertEquals("CSFY001", results.getString(1));
					assertEquals(2, results.getLong(2), "a rewritten row moves on a version");
					assertEquals(1, results.getLong(3), "a row already canonical is left alone");
					assertEquals("CS101", results.getString(4));
					assertEquals(1, results.getLong(5),
							"the rewrite is in the feed, so every device pulls it");
					assertEquals(1, results.getLong(6));
				}
			} finally {
				connection.rollback();
			}
		}
	}

	@Test
	void theMigrationStopsAndNamesTwoRollNumbersThatBecomeOne() throws Exception {

		try (Connection connection = postgres.connection()) {

			connection.setAutoCommit(false);

			try (Statement statement = connection.createStatement()) {

				statement.execute(UNDO_V57);
				statement.execute("""
						INSERT INTO student_year
						    (student_id, academic_year, roll_no, year, stream, major_dept, semester)
						    VALUES (2001, 2025, 'CS-FY-1', 'FY', 'BSc_unaided',
						            'COMPUTER_SCIENCE', 1),
						           (2002, 2025, 'csfy1',   'FY', 'BSc_unaided',
						            'COMPUTER_SCIENCE', 1);
						""");

				String migration = v57();
				SQLException refused = assertThrows(SQLException.class,
						() -> statement.execute(migration));

				// Both spellings named, in whatever order the database's collation sorts them.
				assertTrue(refused.getMessage().contains("CSFY1 in 2025 (from "),
						refused::getMessage);
				assertTrue(refused.getMessage().contains("'CS-FY-1'")
						&& refused.getMessage().contains("'csfy1'"), refused::getMessage);
			} finally {
				connection.rollback();
			}
		}
	}

	private String text(String sql) throws SQLException {

		try (Connection connection = postgres.connection();
		     Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(sql)) {

			results.next();
			return results.getString(1);
		}
	}
}
