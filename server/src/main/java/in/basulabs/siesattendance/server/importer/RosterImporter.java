package in.basulabs.siesattendance.server.importer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;
import in.basulabs.siesattendance.backend.util.RosterCsv;

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
				errors.add("Line " + row.line() + ": " + firstLine(e));
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
			throws SQLException {

		Long existing = findByRollNo(connection, row.rollNo(), academicYear);

		// The APAAR ID next: it is the person's for life, so it finds them in a new session
		// under a new roll number. The device's DaoStudent asks in the same order.
		if (existing == null && row.apaarId() != null) {
			existing = findByApaarId(connection, row.apaarId());
		}

		if (existing == null && row.email() != null) {
			existing = findByEmail(connection, row.email());
		}

		boolean updated = existing != null;
		long studentId;

		if (updated) {
			studentId = existing;
			updateStudent(connection, studentId, row);
		} else {
			studentId = ids.next();
			insertStudent(connection, studentId, row);
		}

		upsertStudentYear(connection, studentId, academicYear, row);
		return updated;
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

	private static void updateStudent(@NonNull Connection connection, long studentId,
	                                  @NonNull RosterCsv.Row row) throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				"UPDATE students SET name = ?, pronouns = ?, email = ?, "
						+ "apaar_id = COALESCE(?, apaar_id), updated_at = now(), "
						+ "row_version = row_version + 1 WHERE student_id = ?")) {

			statement.setString(1, row.name());
			statement.setString(2, row.pronouns());
			statement.setString(3, row.email());
			setNullableLong(statement, 4, row.apaarId());
			statement.setLong(5, studentId);
			statement.executeUpdate();
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
						+ "  minor_dept = EXCLUDED.minor_dept,"
						+ "  oe_dept    = EXCLUDED.oe_dept,"
						+ "  semester   = EXCLUDED.semester,"
						+ "  division   = EXCLUDED.division,"
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

	@Nullable
	private static Long firstId(@NonNull PreparedStatement statement) throws SQLException {

		try (ResultSet results = statement.executeQuery()) {
			return results.next() ? results.getLong(1) : null;
		}
	}

	/**
	 * The first line of a database error, which is the part a person can act on.
	 * <p>
	 * PostgreSQL's messages carry a DETAIL and sometimes a HINT across several lines, and the
	 * whole thing in a per-row error list makes four hundred rows unreadable. The server's own
	 * log keeps the exception entire.
	 */
	@NonNull
	private static String firstLine(@NonNull SQLException e) {

		String message = e.getMessage();

		if (message == null) {
			return e.getClass().getSimpleName();
		}

		int newline = message.indexOf('\n');
		return newline < 0 ? message : message.substring(0, newline);
	}
}
