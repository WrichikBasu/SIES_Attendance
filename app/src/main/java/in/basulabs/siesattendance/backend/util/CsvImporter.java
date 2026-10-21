package in.basulabs.siesattendance.backend.util;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import in.basulabs.siesattendance.backend.EntityStudent;
import in.basulabs.siesattendance.backend.MainDatabase;

/**
 * Writes a student roster CSV into the database.
 * <p>
 * The reading is {@link RosterCsv}'s, in the shared module, because the server imports the same
 * file into PostgreSQL and the two must agree about what it said — which cells are optional,
 * which spellings resolve, which combinations are refused. What is left here is the half that is
 * genuinely the app's: opening a content URI, and the upsert against Room.
 * <p>
 * Re-importing is the intended way to bulk-edit a roster. A row whose roll number already exists
 * for that academic year updates the existing student rather than inserting a second copy, so
 * the file can be corrected in a spreadsheet and uploaded again as many times as needed.
 * Students absent from a later file are left untouched: an import never removes anyone, because
 * their attendance history depends on them continuing to exist.
 * <p>
 * A bad row costs only that row. Each row is written on its own and each failure is recorded
 * with its line number, so a single mistyped department does not abort a file of four hundred
 * students. The consequence is that an import is not atomic: a run that reports failures has
 * already committed the rows that succeeded, and correcting the file and importing it again is
 * the intended fix.
 *
 * @see RosterCsv for the file format and every parsing rule
 */
public final class CsvImporter {

	/** @see RosterCsv#REQUIRED_COLUMNS */
	public static final String[] REQUIRED_COLUMNS = RosterCsv.REQUIRED_COLUMNS;

	/**
	 * Outcome of an import run. Rows that fail are collected rather than aborting the whole
	 * file, so one malformed row does not cost the user the other four hundred.
	 */
	public static class Result {

		/** Rows that created a student who was not already on file. */
		public int created;

		/** Rows that matched an existing student and overwrote their details. */
		public int updated;

		/** Rows that could not be filed, each of which contributes a line to {@link #errors}. */
		public int failed;

		/**
		 * One message per failed row, prefixed with its line number in the file so the user can
		 * go and correct it. Also carries the whole-file failures, such as a missing column, in
		 * which case it holds a single entry and nothing was imported.
		 * <p>
		 * Rows that could not be <em>read</em> are listed before rows that could not be
		 * <em>written</em>, rather than all of them in file order, because the reading is now a
		 * pass of its own.
		 */
		public final List<String> errors = new ArrayList<>();

		/** Rows that were filed successfully, whether they created a student or updated one. */
		public int imported() {
			return created + updated;
		}

		public boolean hasErrors() {
			return failed > 0;
		}
	}

	private CsvImporter() {
	}

	/**
	 * Reads the CSV at {@code uri} and inserts every valid row for the given academic year.
	 * <p>
	 * A missing required column stops the run before any row is written, since every row would
	 * fail for the same reason and four hundred identical errors help nobody. Past that point
	 * each row stands alone.
	 * <p>
	 * Must be called off the main thread: it reads a file and writes to the database.
	 *
	 * @param academicYear the session the roster is being imported into, since the rows carry no
	 *                     session of their own
	 * @return counts and per-row errors, never null, even when nothing could be read
	 */
	@NonNull
	public static Result importStudents(@NonNull Context context, @NonNull Uri uri,
	                                    int academicYear) {

		Result result = new Result();
		MainDatabase db = MainDatabase.getInstance(context);

		try (InputStream in = context.getContentResolver().openInputStream(uri)) {

			if (in == null) {
				result.errors.add("Could not open the selected file.");
				result.failed++;
				return result;
			}

			RosterCsv.Reading reading = RosterCsv.read(
					new InputStreamReader(in, StandardCharsets.UTF_8));

			result.errors.addAll(reading.errors());
			result.failed += reading.errors().size();

			if (reading.rejected()) {
				return result;
			}

			for (RosterCsv.Row row : reading.rows()) {

				try {
					if (write(db, row, academicYear)) {
						result.updated++;
					} else {
						result.created++;
					}
				} catch (Exception e) {
					result.failed++;
					result.errors.add("Line " + row.line() + ": " + e.getMessage());
				}
			}

		} catch (IOException e) {
			result.errors.add("Read error: " + e.getMessage());
			result.failed++;
		}

		return result;
	}

	/**
	 * Files one parsed row.
	 *
	 * @return true if this row updated a student who already existed, false if it created one
	 */
	private static boolean write(@NonNull MainDatabase db, @NonNull RosterCsv.Row row,
	                             int academicYear) {

		EntityStudent student = new EntityStudent(row.name(), row.pronouns(), row.email());
		student.apaarId = row.apaarId();

		// Checked before the upsert so the summary can distinguish new entries from edits,
		// which is what tells the user whether a re-import did what they expected.
		boolean existed = db.daoStudent().findStudentIdByRollNo(row.rollNo(), academicYear)
				!= null;

		db.daoStudent().upsertStudentWithYear(student, academicYear, row.rollNo(), row.year(),
				row.stream(), row.majorDept(), row.minorDept(), row.oeDept(), row.semester(),
				row.division(), row.joinDate(), row.leaveDate());

		return existed;
	}

	/**
	 * @see RosterCsv#splitCsvLine
	 */
	@NonNull
	public static List<String> splitCsvLine(@NonNull String line) {
		return RosterCsv.splitCsvLine(line);
	}

	/**
	 * @see RosterCsv#templateCsv
	 */
	@NonNull
	public static String templateCsv() {
		return RosterCsv.templateCsv();
	}

	/**
	 * Kept as a private method of this class because {@code CsvParsingTest} reaches it by
	 * reflection, and the test is about the header rules rather than about where they live.
	 *
	 * @see RosterCsv#parseHeader
	 */
	@NonNull
	private static Map<String, Integer> parseHeader(@NonNull String headerLine) {
		return RosterCsv.parseHeader(headerLine);
	}
}
