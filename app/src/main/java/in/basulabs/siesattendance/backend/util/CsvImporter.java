package in.basulabs.siesattendance.backend.util;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import in.basulabs.siesattendance.backend.AcademicRules;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.EnumLookup;
import in.basulabs.siesattendance.backend.EntityStudent;
import in.basulabs.siesattendance.backend.MainDatabase;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;

/**
 * Parses a student roster CSV and writes it into the database.
 * <p>
 * Re-importing is the intended way to bulk-edit a roster. A row whose roll number already
 * exists for that academic year updates the existing student rather than inserting a second
 * copy, so the file can be corrected in a spreadsheet and uploaded again as many times as
 * needed. Students absent from a later file are left untouched: an import never removes anyone,
 * because their attendance history depends on them continuing to exist.
 * <p>
 * The expected header row is:
 * <pre>rollNo,name,pronouns,email,year,stream,majorDept,minorDept,oeDept,semester</pre>
 * Column order does not matter, since the header is read to build a name-to-index map. The
 * {@code pronouns}, {@code email}, {@code minorDept}, {@code oeDept} and {@code semester}
 * columns may be blank, and a file may leave them out altogether. The rest are listed in
 * {@link #REQUIRED_COLUMNS}, and a row missing any of them is reported as an error rather than
 * silently skipped.
 * <p>
 * Enum columns accept the constant name case-insensitively, and also tolerate spaces and
 * hyphens in place of underscores, so "Computer Science" resolves to
 * {@link Department#COMPUTER_SCIENCE}.
 * <p>
 * A bad row costs only that row. Parsing and writing happen one row at a time and each failure
 * is caught and recorded with its line number, so a single mistyped department does not abort a
 * file of four hundred students. The consequence is that an import is not atomic: a run that
 * reports failures has already committed the rows that succeeded, and correcting the file and
 * importing it again is the intended fix.
 */
public final class CsvImporter {

	/**
	 * Columns without which a row cannot be filed at all. Written in the normalised form that
	 * {@link #parseHeader} produces, so lower case with separators stripped, and checked once
	 * against the header before any row is read.
	 */
	public static final String[] REQUIRED_COLUMNS =
			{"rollno", "name", "year", "stream", "majordept"};

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
	 * A missing required column stops the run before any row is read, since every row would fail
	 * for the same reason and four hundred identical errors help nobody. Past that point each row
	 * stands alone.
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

			BufferedReader reader =
					new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));

			String headerLine = reader.readLine();

			if (headerLine == null) {
				result.errors.add("The file is empty.");
				result.failed++;
				return result;
			}

			Map<String, Integer> columns = parseHeader(headerLine);

			for (String required : REQUIRED_COLUMNS) {
				if (!columns.containsKey(required)) {
					result.errors.add("Missing required column: " + required);
					result.failed++;
					return result;
				}
			}

			String line;
			int lineNo = 1;

			while ((line = reader.readLine()) != null) {
				lineNo++;

				if (line.trim().isEmpty()) {
					continue;
				}

				try {
					if (importRow(db, splitCsvLine(line), columns, academicYear)) {
						result.updated++;
					} else {
						result.created++;
					}
				} catch (Exception e) {
					result.failed++;
					result.errors.add("Line " + lineNo + ": " + e.getMessage());
				}
			}

		} catch (IOException e) {
			result.errors.add("Read error: " + e.getMessage());
			result.failed++;
		}

		return result;
	}

	/**
	 * Files one row, resolving and validating every value before anything is written.
	 *
	 * @return true if this row updated a student who already existed, false if it created one
	 * @throws IllegalArgumentException with a message naming the offending field, which the
	 *                                  caller turns into that row's error line
	 */
	private static boolean importRow(MainDatabase db, List<String> cells,
	                                 Map<String, Integer> columns, int academicYear) {

		String rollNo = required(cells, columns, "rollno", "roll number");
		String name = required(cells, columns, "name", "name");

		String pronouns = optional(cells, columns, "pronouns");
		if (pronouns == null || pronouns.isEmpty()) {
			pronouns = "they/them";
		}

		String email = optional(cells, columns, "email");
		if (email != null && email.isEmpty()) {
			email = null;
		}

		Year year = parseEnum(Year.class,
				required(cells, columns, "year", "year"), "year");
		Stream stream = parseEnum(Stream.class,
				required(cells, columns, "stream", "stream"), "stream");
		Department majorDept = parseEnum(Department.class,
				required(cells, columns, "majordept", "major department"), "major department");

		// A file saying "COMPUTER_SCIENCE,BSc" means the unaided BSc, because that is the only
		// BSc that department runs, so the label is resolved against the department. A stream
		// the department does not offer at all is reported as a bad row rather than corrected:
		// "COMMERCE,BSc" is a mistake in the file and should be seen, not quietly filed as
		// BCom.
		Stream requested = stream;
		stream = AcademicRules.resolveRequested(majorDept, requested);

		if (stream == null) {
			throw new IllegalArgumentException(majorDept + " is not offered as " + requested);
		}

		Integer semester = parseOptionalInt(optional(cells, columns, "semester"), "semester");

		// The same backstop the forms use, so a file cannot introduce a combination the UI
		// would refuse: a BSc student in Masters Part 1, or a first-year in semester 5.
		// Every value the check covers has to be parsed before it runs, which is why the
		// semester is read here rather than alongside the other optional columns below.
		String problem = AcademicRules.validate(majorDept, stream, year, semester);
		if (problem != null) {
			throw new IllegalArgumentException(problem);
		}

		Department minorDept = parseOptionalEnum(Department.class,
				optional(cells, columns, "minordept"), "minor department");
		Department oeDept = parseOptionalEnum(Department.class,
				optional(cells, columns, "oedept"), "open elective department");

		EntityStudent student = new EntityStudent(name, pronouns, email);

		// Checked before the upsert so the summary can distinguish new entries from edits,
		// which is what tells the user whether a re-import did what they expected.
		boolean existed = db.daoStudent().findStudentIdByRollNo(rollNo, academicYear) != null;

		db.daoStudent().upsertStudentWithYear(student, academicYear, rollNo, year, stream,
				majorDept, minorDept, oeDept, semester);

		return existed;
	}

	// ── Parsing helpers ──────────────────────────────────────────────────

	/**
	 * Maps normalised column names to their position. Names are lowercased with underscores,
	 * spaces and hyphens stripped, so "Major Dept", "major_dept" and "majordept" are all read as
	 * the same column.
	 * <p>
	 * A leading byte order mark is dropped first. Excel and Google Sheets both write one at the
	 * start of a UTF-8 CSV, and it is invisible: it stays attached to the first column name, so
	 * only that column fails to resolve and the file is rejected for a missing {@code rollNo}
	 * whose header plainly says {@code rollNo}.
	 * <p>
	 * Lowercasing is fixed to {@link Locale#ROOT}. These are internal keys compared against
	 * ASCII literals, and the default locale would map "MINORDEPT" to "mınordept" on a Turkish
	 * device, losing the column for that reason instead.
	 */
	private static Map<String, Integer> parseHeader(String headerLine) {

		Map<String, Integer> map = new HashMap<>();
		List<String> cells = splitCsvLine(stripByteOrderMark(headerLine));

		for (int i = 0; i < cells.size(); i++) {
			String key = cells.get(i).trim().toLowerCase(Locale.ROOT)
					.replace("_", "").replace(" ", "").replace("-", "");
			map.put(key, i);
		}
		return map;
	}

	/** Removes the byte order mark a UTF-8 file may open with. */
	@NonNull
	private static String stripByteOrderMark(@NonNull String line) {
		return line.isEmpty() || line.charAt(0) != '\uFEFF' ? line : line.substring(1);
	}

	/**
	 * Splits one CSV line, honouring double-quoted fields so that a name containing a comma
	 * survives intact. A doubled quote inside a quoted field is treated as a literal quote.
	 */
	@NonNull
	public static List<String> splitCsvLine(@NonNull String line) {

		List<String> out = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean inQuotes = false;

		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);

			if (inQuotes) {
				if (c == '"') {
					if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
						current.append('"');
						i++;
					} else {
						inQuotes = false;
					}
				} else {
					current.append(c);
				}
			} else {
				if (c == '"') {
					inQuotes = true;
				} else if (c == ',') {
					out.add(current.toString().trim());
					current.setLength(0);
				} else {
					current.append(c);
				}
			}
		}
		out.add(current.toString().trim());
		return out;
	}

	/**
	 * A cell that must be present and non-blank.
	 *
	 * @param label the human name of the field, used in the error message
	 */
	private static String required(List<String> cells, Map<String, Integer> columns,
	                               String key, String label) {
		String value = optional(cells, columns, key);
		if (value == null || value.isEmpty()) {
			throw new IllegalArgumentException(label + " is blank");
		}
		return value;
	}

	/**
	 * A cell that may be missing. Returns null both when the column is absent from the header and
	 * when the row is short, so a file whose trailing columns are simply not filled in imports
	 * without complaint.
	 */
	@Nullable
	private static String optional(List<String> cells, Map<String, Integer> columns, String key) {
		Integer index = columns.get(key);
		if (index == null || index >= cells.size()) {
			return null;
		}
		return cells.get(index).trim();
	}

	/** As {@link #parseOptionalEnum}, but a blank value is an error rather than a null. */
	private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String label) {
		E value = parseOptionalEnum(type, raw, label);
		if (value == null) {
			throw new IllegalArgumentException(label + " is blank");
		}
		return value;
	}

	/**
	 * Parses an optional whole number. A blank cell is absence, not zero; anything else that is
	 * not a number is an error rather than a silent null, because a typo in a semester column
	 * should be seen.
	 */
	@Nullable
	private static Integer parseOptionalInt(@Nullable String raw, String label) {

		if (raw == null || raw.trim().isEmpty()) {
			return null;
		}

		try {
			return Integer.valueOf(raw.trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Unrecognised " + label + ": " + raw);
		}
	}

	/**
	 * Accepts either the constant name or the display label, loosely matched. A roster typed by
	 * hand is as likely to say "Computer Science" as "COMPUTER_SCIENCE", and both should work.
	 * <p>
	 * A blank cell yields null, which is a legitimate answer for the optional departments. A
	 * value that is present but matches nothing is an error, since it is far more likely to be a
	 * typo than a deliberate blank.
	 */
	@Nullable
	private static <E extends Enum<E>> E parseOptionalEnum(Class<E> type, @Nullable String raw,
	                                                       String label) {
		if (raw == null || raw.trim().isEmpty()) {
			return null;
		}

		E value = EnumLookup.find(type, raw);
		if (value == null) {
			throw new IllegalArgumentException("Unrecognised " + label + ": " + raw);
		}
		return value;
	}

	/**
	 * A ready-to-share template so the user knows exactly what the file should look like. The
	 * second example row leaves the optional department columns empty, which is the part people
	 * most often ask about.
	 */
	@NonNull
	public static String templateCsv() {
		return "rollNo,name,pronouns,email,year,stream,majorDept,minorDept,oeDept,semester\n"
				+ "2024CS032,Aarav Patil,he/him,aarav@sies.edu.in,SY,BSc,COMPUTER_SCIENCE,"
				+ "MATHEMATICS,ENGLISH,3\n"
				+ "2024CS033,Sneha Mehta,she/her,sneha@sies.edu.in,SY,BSc,COMPUTER_SCIENCE,,,3\n";
	}
}
