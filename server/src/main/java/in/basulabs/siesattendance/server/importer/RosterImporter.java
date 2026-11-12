package in.basulabs.siesattendance.server.importer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Division;
import in.basulabs.siesattendance.backend.PostgresEnumTypes;
import in.basulabs.siesattendance.backend.SessionYears;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;
import in.basulabs.siesattendance.backend.util.RosterCsv;
import in.basulabs.siesattendance.server.sql.DatabaseRefusal;

/**
 * Loads a roster CSV into {@code students} and {@code student_year}.
 * <p>
 * The server's half of what the app's {@code CsvImporter} does, over the same parser —
 * {@link RosterCsv}, in the shared module — so that a file which imports on a phone imports
 * here, row for row, with the same cells optional and the same spellings resolving. The two
 * halves that differ are the only two that can: where the bytes come from, and what the upsert
 * is written in.
 *
 * <h3>Matching</h3>
 * Roll number within the session first, then email, exactly as {@code DaoStudent
 * .upsertStudentWithYear} does. Roll number first because that is what a roster is keyed by;
 * email second because it survives a promotion, which a roll number does not. A row that matches
 * neither is a new student, and gets a key minted under {@link ServerIds} — the server's node,
 * so that a student the office typed in is distinguishable for ever from one a phone created.
 *
 * <p>
 * An APAAR ID or an email recognises a student carried into a new session under a new roll
 * number. It does not recognise one who is already enrolled <em>this</em> session under another:
 * that is two people sharing an address, siblings most often, and filing the row against the
 * match renamed one of them and gave them the other's roll number (R17). Such a row is refused
 * with its line, and the student on record is left alone.
 *
 * <h3>What a file does not say is kept</h3>
 * A column the file does not have leaves the value on record alone: a file without an email
 * column used to erase every email, and one without a minor column every minor (R18). A blank
 * email never erases one either, as a blank APAAR ID and blank dates already did not. A blank
 * minor, open elective, semester or division in a column the file has is the file saying
 * "none", and is written.
 *
 * <h3>A bad row costs only that row</h3>
 * Each row runs inside a savepoint. A row PostgreSQL refuses — a minor department equal to the
 * major one, an email already held by somebody else — is rolled back on its own and reported with
 * its line number, and the rest of the file still commits as one transaction. That is the shape
 * the app already documents, with the part the app cannot offer: either every surviving row lands
 * or none does, so a connection dropped half way through leaves the roster as it was rather than
 * half rewritten.
 * <p>
 * An import never removes anybody. A student missing from a later file keeps their row, because
 * their attendance history depends on their continuing to exist.
 */
public final class RosterImporter {

	private static final Logger LOG = LoggerFactory.getLogger(RosterImporter.class);

	/**
	 * What a run did.
	 *
	 * @param errors one line per row that could not be filed, prefixed with its line number, or
	 *               a single whole-file line when the header itself was unusable
	 */
	public record Result(int created, int updated, int failed, @NonNull List<String> errors) {

		public int imported() {
			return created + updated;
		}
	}

	private RosterImporter() {
	}

	/**
	 * Reads the CSV and writes it, on the caller's transaction.
	 * <p>
	 * The caller's transaction, not one of this class's, for two reasons: the actor has to have
	 * been announced on it already — nothing here could legitimately announce one — and the
	 * {@code CSV_IMPORT} audit entry has to land in the same unit as the rows it describes.
	 *
	 * @throws IllegalStateException if the connection is in auto-commit mode, where a savepoint
	 *                               per row is meaningless and the "all or none" claim above
	 *                               would be false
	 */
	@NonNull
	public static Result load(@NonNull Connection connection, int academicYear,
	                          @NonNull Reader csv) throws SQLException, IOException {

		if (connection.getAutoCommit()) {
			throw new IllegalStateException("A roster import needs a transaction: the per-row "
					+ "savepoints and the audit entry both depend on one.");
		}

		RosterCsv.Reading reading = RosterCsv.read(csv);

		List<String> errors = new ArrayList<>(reading.errors());

		if (reading.rejected()) {
			return new Result(0, 0, errors.size(), errors);
		}

		ServerIds ids = new ServerIds();
		ids.primeFrom(connection);

		int created = 0;
		int updated = 0;
		int failed = reading.errors().size();

		for (RosterCsv.Row row : reading.rows()) {

			// Each row in a department the caller may enrol in (V27): the office and an
			// administrator anywhere, a head in theirs, a professor in their own. Said here,
			// with the line, rather than left to the policy's "violates row-level security".
			if (!mayEnrolIn(connection, row.majorDept())) {
				failed++;
				errors.add("Line " + row.line() + ": " + row.majorDept()
						+ " is not a department you may add students to.");
				continue;
			}

			Savepoint savepoint = connection.setSavepoint();

			try {
				if (write(connection, ids, row, academicYear)) {
					updated++;
				} else {
					created++;
				}
				connection.releaseSavepoint(savepoint);

			} catch (SQLException e) {
				connection.rollback(savepoint);
				failed++;
				LOG.warn("roster line {} refused ({}): {}", row.line(), e.getSQLState(),
						e.getMessage());
				errors.add("Line " + row.line() + ": " + explain(e, row, academicYear));

			} catch (RowRefused e) {
				connection.rollback(savepoint);
				failed++;
				errors.add("Line " + row.line() + ": " + e.getMessage());
			}
		}

		return new Result(created, updated, failed, errors);
	}

	/**
	 * Files one row.
	 *
	 * @return true if an existing student was updated, false if one was created
	 */
	private static boolean write(@NonNull Connection connection, @NonNull ServerIds ids,
	                             @NonNull RosterCsv.Row row, int academicYear)
			throws SQLException, RowRefused {

		Long existing = findByRollNo(connection, row.rollNo(), academicYear);
		String matchedBy = "roll number";

		// The APAAR ID next: it is the person's for life, so it finds them in a new session
		// under a new roll number. The device's DaoStudent asks in the same order.
		if (existing == null && row.apaarId() != null) {
			existing = findByApaarId(connection, row.apaarId());
			matchedBy = "APAAR ID";
		}

		if (existing == null && row.email() != null) {
			existing = findByEmail(connection, row.email());
			matchedBy = "email";
		}

		boolean updated = existing != null;
		long studentId;

		if (updated && !"roll number".equals(matchedBy)) {

			String held = rollNoIn(connection, existing, academicYear);

			if (held != null && !held.equals(row.rollNo())) {
				// The match is enrolled this session under another roll number, so it is not
				// this row's student being promoted: it is somebody else with that address.
				throw new RowRefused("its " + matchedBy + " belongs to the student enrolled in "
						+ SessionYears.labelFor(academicYear) + " as " + held + ", so the row was "
						+ "not imported. Two students cannot share an " + matchedBy + "; check "
						+ "the file.");
			}
		}

		if (updated) {
			studentId = existing;

			// The policies let a professor or head see a student they may not change: one
			// who takes their department as a minor or OE, or is enrolled in another
			// department in any session (V20, V27, V30). The UPDATE then matches no row,
			// without an error, and carrying on would report a change that did not happen
			// and could enrol the student here for a new session over that department's
			// head. A student on another department's roll is that department's too, so
			// the row is refused.
			if (updateStudent(connection, studentId, row) == 0) {
				throw new RowRefused("its " + matchedBy + " belongs to a student who is "
						+ "also enrolled in a department you cannot change, so the row "
						+ "was not imported. The office or an administrator can make "
						+ "this change.");
			}
		} else {
			studentId = ids.next();
			insertStudent(connection, studentId, row);
		}

		upsertStudentYear(connection, studentId, academicYear, row);
		return updated;
	}

	/** The roll number that student holds in that session, or null when not enrolled. */
	@Nullable
	private static String rollNoIn(@NonNull Connection connection, long studentId,
	                               int academicYear) throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT roll_no FROM student_year WHERE student_id = ? AND academic_year = ?")) {

			statement.setLong(1, studentId);
			statement.setInt(2, academicYear);

			try (ResultSet results = statement.executeQuery()) {
				return results.next() ? results.getString(1) : null;
			}
		}
	}

	private static boolean mayEnrolIn(@NonNull Connection connection,
	                                  @NonNull Department department) throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT actor_may_enrol_in(?::" + PostgresEnumTypes.of(Department.class) + ")")) {

			statement.setString(1, department.name());

			try (ResultSet results = statement.executeQuery()) {
				return results.next() && results.getBoolean(1);
			}
		}
	}

	@Nullable
	private static Long findByRollNo(@NonNull Connection connection, @NonNull String rollNo,
	                                 int academicYear) throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT student_id FROM student_year "
						+ "WHERE roll_no = ? AND academic_year = ? LIMIT 1")) {

			statement.setString(1, rollNo);
			statement.setInt(2, academicYear);
			return firstId(statement);
		}
	}

	/**
	 * Compared case-sensitively, because {@code students_email_uq} is: V1 left the column
	 * exactly as Room had it, and matching more loosely here than the index does would mean
	 * finding no student and then failing to insert one.
	 */
	@Nullable
	private static Long findByEmail(@NonNull Connection connection, @NonNull String email)
			throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT student_id FROM students WHERE email = ? LIMIT 1")) {

			statement.setString(1, email);
			return firstId(statement);
		}
	}

	@Nullable
	private static Long findByApaarId(@NonNull Connection connection, long apaarId)
			throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT student_id FROM students WHERE apaar_id = ? LIMIT 1")) {

			statement.setLong(1, apaarId);
			return firstId(statement);
		}
	}

	private static void setNullableLong(@NonNull PreparedStatement statement, int at,
	                                    @Nullable Long value) throws SQLException {
		if (value == null) {
			statement.setNull(at, Types.BIGINT);
		} else {
			statement.setLong(at, value);
		}
	}

	private static void setNullableDate(@NonNull PreparedStatement statement, int at,
	                                    @Nullable java.time.LocalDate value)
			throws SQLException {
		statement.setObject(at, value, Types.DATE);
	}

	private static void insertStudent(@NonNull Connection connection, long studentId,
	                                  @NonNull RosterCsv.Row row) throws SQLException {

		// created_by and updated_by are left null here, as everywhere else. They were added in
		// V1 for a future that has not arrived, nothing has ever written them, and filling them
		// with an account id now would give a column with no stated meaning a second one. Who
		// did this is in the audit log, which is the answer that is actually enforced.
		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO students (student_id, name, pronouns, email, apaar_id, created_at) "
						+ "VALUES (?, ?, ?, ?, ?, now())")) {

			statement.setLong(1, studentId);
			statement.setString(2, row.name());
			statement.setString(3, row.pronouns());
			statement.setString(4, row.email());
			setNullableLong(statement, 5, row.apaarId());
			statement.executeUpdate();
		}
	}

	/**
	 * @return the number of rows changed: 0 when the policies keep this student from the
	 * caller
	 */
	private static int updateStudent(@NonNull Connection connection, long studentId,
	                                 @NonNull RosterCsv.Row row) throws SQLException {

		// The email and the APAAR ID are kept when the file gives none, and the pronouns when it
		// has no such column: see the class notes.
		String pronouns = row.has("pronouns") ? "?" : "pronouns";

		try (PreparedStatement statement = connection.prepareStatement(
				"UPDATE students SET name = ?, pronouns = " + pronouns + ", "
						+ "email = COALESCE(?, email), "
						+ "apaar_id = COALESCE(?, apaar_id), updated_at = now(), "
						+ "row_version = row_version + 1 WHERE student_id = ?")) {

			int at = 1;
			statement.setString(at++, row.name());
			if (row.has("pronouns")) {
				statement.setString(at++, row.pronouns());
			}
			statement.setString(at++, row.email());
			setNullableLong(statement, at++, row.apaarId());
			statement.setLong(at, studentId);
			return statement.executeUpdate();
		}
	}

	/**
	 * The session row, replaced wholesale on conflict.
	 * <p>
	 * {@code ON CONFLICT DO UPDATE} rather than a delete and an insert, which is Room's
	 * {@code REPLACE} by another name but keeps the row — and therefore keeps every batch
	 * membership and every attendance row that points at it. A delete would be refused by those
	 * foreign keys, which is the correct refusal and the wrong statement to be making.
	 */
	private static void upsertStudentYear(@NonNull Connection connection, long studentId,
	                                      int academicYear, @NonNull RosterCsv.Row row)
			throws SQLException {

		String year = "?::" + PostgresEnumTypes.of(Year.class);
		String stream = "?::" + PostgresEnumTypes.of(Stream.class);
		String department = "?::" + PostgresEnumTypes.of(Department.class);
		String division = "?::" + PostgresEnumTypes.of(Division.class);

		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO student_year (student_id, academic_year, roll_no, year, stream, "
						+ "major_dept, minor_dept, oe_dept, semester, division, join_date, "
						+ "leave_date, created_at) "
						+ "VALUES (?, ?, ?, " + year + ", " + stream + ", " + department + ", "
						+ department + ", " + department + ", ?, " + division + ", ?, ?, now()) "
						+ "ON CONFLICT (student_id, academic_year) DO UPDATE SET "
						+ "  roll_no    = EXCLUDED.roll_no,"
						+ "  year       = EXCLUDED.year,"
						+ "  stream     = EXCLUDED.stream,"
						+ "  major_dept = EXCLUDED.major_dept,"
						+ "  minor_dept = " + keptUnless(row, "minordept", "minor_dept") + ","
						+ "  oe_dept    = " + keptUnless(row, "oedept", "oe_dept") + ","
						+ "  semester   = " + keptUnless(row, "semester", "semester") + ","
						+ "  division   = " + keptUnless(row, "division", "division") + ","
						+ "  join_date  = COALESCE(EXCLUDED.join_date, student_year.join_date),"
						+ "  leave_date = COALESCE(EXCLUDED.leave_date, student_year.leave_date),"
						+ "  updated_at = now(),"
						+ "  row_version = student_year.row_version + 1")) {

			statement.setLong(1, studentId);
			statement.setInt(2, academicYear);
			statement.setString(3, row.rollNo());
			statement.setString(4, row.year().name());
			statement.setString(5, row.stream().name());
			statement.setString(6, row.majorDept().name());
			statement.setString(7, row.minorDept() == null ? null : row.minorDept().name());
			statement.setString(8, row.oeDept() == null ? null : row.oeDept().name());

			if (row.semester() == null) {
				statement.setNull(9, Types.SMALLINT);
			} else {
				statement.setInt(9, row.semester());
			}

			statement.setString(10, row.division() == null ? null : row.division().name());
			setNullableDate(statement, 11, row.joinDate());
			setNullableDate(statement, 12, row.leaveDate());
			statement.executeUpdate();
		}
	}

	/**
	 * The new value when the file has that column, or the one on record when it does not: a
	 * column the file does not have says nothing about the student (R18).
	 */
	@NonNull
	private static String keptUnless(@NonNull RosterCsv.Row row, @NonNull String column,
	                                 @NonNull String postgres) {
		return row.has(column) ? "EXCLUDED." + postgres : "student_year." + postgres;
	}

	@Nullable
	private static Long firstId(@NonNull PreparedStatement statement) throws SQLException {

		try (ResultSet results = statement.executeQuery()) {
			return results.next() ? results.getLong(1) : null;
		}
	}

	/**
	 * A row the database refused, said for the person who wrote the file.
	 * <p>
	 * The unique indexes are the refusals a roster meets in practice, and mostly for a student
	 * the caller cannot see: a professor's lookup by roll number, APAAR ID or email misses a
	 * student who is wholly another department's, the row becomes a new student, and the
	 * index finds the one already there. The sentence names the value from the row, and says
	 * who can sort it out, without saying whose it is: that is the student the caller was not
	 * shown.
	 */
	@NonNull
	static String explain(@NonNull SQLException e, @NonNull RosterCsv.Row row,
	                      int academicYear) {

		String constraint = DatabaseRefusal.constraintOf(e);

		if (DatabaseRefusal.UNIQUE_VIOLATION.equals(e.getSQLState()) && constraint != null) {

			String taken = switch (constraint) {
				case "student_year_roll_no_academic_year_uq" -> "the roll number "
						+ row.rollNo() + " is already another student's in "
						+ SessionYears.labelFor(academicYear);
				case "students_email_uq" -> "the email address " + row.email()
						+ " is already another student's";
				case "students_apaar_id_key" -> "the APAAR ID " + row.apaarId()
						+ " is already another student's";
				default -> null;
			};

			if (taken != null) {
				return taken + ". Check the file; if it is right, that student's record is "
						+ "one you cannot change, and the office or an administrator can.";
			}
		}

		if (DatabaseRefusal.INSUFFICIENT_PRIVILEGE.equals(e.getSQLState())) {
			return "this student's record is not yours to change, so the row was not "
					+ "imported. The office or an administrator can make this change.";
		}

		return DatabaseRefusal.inWords(e) + ", so the row was not imported.";
	}

	/**
	 * A row this importer refuses for a reason it can say in a sentence, rolled back to its
	 * savepoint like a row the database refused.
	 */
	private static final class RowRefused extends Exception {

		private static final long serialVersionUID = 1L;

		RowRefused(@NonNull String message) {
			super(message);
		}
	}
}
