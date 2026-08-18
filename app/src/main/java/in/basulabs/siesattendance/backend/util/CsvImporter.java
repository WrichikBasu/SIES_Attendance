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
 * needed. Students absent from a later file are left untouched — an import never removes
 * anyone, because their attendance history depends on them continuing to exist.
 * <p>
 * The expected header row is:
 * <pre>rollNo,name,pronouns,email,year,stream,majorDept,minorDept,oeDept</pre>
 * Column order does not matter — the header is read to build a name-to-index map. The
 * {@code pronouns}, {@code email}, {@code minorDept} and {@code oeDept} columns may be blank.
 * The remaining columns are required and a row missing any of them is reported as an error
 * rather than silently skipped.
 * <p>
 * Enum columns accept the constant name case-insensitively, and also tolerate spaces and
 * hyphens in place of underscores, so "Computer Science" resolves to
 * {@link Department#COMPUTER_SCIENCE}.
 */
public final class CsvImporter {

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

		public int failed;

		public final List<String> errors = new ArrayList<>();

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
	 * Must be called off the main thread.
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
	 * @return true if this row updated a student who already existed, false if it created one.
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

		Department minorDept = parseOptionalEnum(Department.class,
				optional(cells, columns, "minordept"), "minor department");
		Department oeDept = parseOptionalEnum(Department.class,
				optional(cells, columns, "oedept"), "open elective department");

		EntityStudent student = new EntityStudent(name, pronouns, email);

		// Checked before the upsert so the summary can distinguish new entries from edits,
		// which is what tells the user whether a re-import did what they expected.
		boolean existed = db.daoStudent().findStudentIdByRollNo(rollNo, academicYear) != null;

		db.daoStudent().upsertStudentWithYear(student, academicYear, rollNo, year, stream,
				majorDept, minorDept, oeDept);

		return existed;
	}

	// ── Parsing helpers ──────────────────────────────────────────────────

	private static Map<String, Integer> parseHeader(String headerLine) {
		Map<String, Integer> map = new HashMap<>();
		List<String> cells = splitCsvLine(headerLine);
		for (int i = 0; i < cells.size(); i++) {
			String key = cells.get(i).trim().toLowerCase()
					.replace("_", "").replace(" ", "").replace("-", "");
			map.put(key, i);
		}
		return map;
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

	private static String required(List<String> cells, Map<String, Integer> columns,
	                               String key, String label) {
		String value = optional(cells, columns, key);
		if (value == null || value.isEmpty()) {
			throw new IllegalArgumentException(label + " is blank");
		}
		return value;
	}

	@Nullable
	private static String optional(List<String> cells, Map<String, Integer> columns, String key) {
		Integer index = columns.get(key);
		if (index == null || index >= cells.size()) {
			return null;
		}
		return cells.get(index).trim();
	}

	private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String label) {
		E value = parseOptionalEnum(type, raw, label);
		if (value == null) {
			throw new IllegalArgumentException(label + " is blank");
		}
		return value;
	}

	/**
	 * Accepts either the constant name or the display label, loosely matched. A roster typed by
	 * hand is as likely to say "Computer Science" as "COMPUTER_SCIENCE", and both should work.
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
	 * A ready-to-share template so the user knows exactly what the file should look like.
	 */
	@NonNull
	public static String templateCsv() {
		return "rollNo,name,pronouns,email,year,stream,majorDept,minorDept,oeDept\n"
				+ "2024CS032,Aarav Patil,he/him,aarav@sies.edu.in,SY,BSc,COMPUTER_SCIENCE,"
				+ "MATHEMATICS,ENGLISH\n"
				+ "2024CS033,Sneha Mehta,she/her,sneha@sies.edu.in,SY,BSc,COMPUTER_SCIENCE,,\n";
	}
}
