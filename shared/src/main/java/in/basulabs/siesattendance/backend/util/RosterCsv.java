package in.basulabs.siesattendance.backend.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import in.basulabs.siesattendance.backend.AcademicRules;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Division;
import in.basulabs.siesattendance.backend.EnumLookup;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;

/**
 * Reads a student roster CSV into validated rows, and nothing else.
 * <p>
 * Parsing only: no database, no files, no Android. Two things write the rows this produces — the
 * app's {@code CsvImporter}, which puts them into Room, and the server's {@code RosterImporter},
 * which puts them into PostgreSQL — and they have to agree about what the file said down to
 * which cells are optional and which spellings of a department resolve. One parser is the only
 * way that stays true; two would be the same class of bug as two copies of
 * {@link AcademicRules}.
 *
 * <h3>The file</h3>
 * The expected header row is:
 * <pre>rollNo,name,pronouns,email,year,stream,majorDept,minorDept,oeDept,semester,division</pre>
 * Column order does not matter, because the header is read into a name-to-index map. The
 * {@code pronouns}, {@code email}, {@code minorDept}, {@code oeDept}, {@code semester} and
 * {@code division} columns may be blank or left out of the file altogether; the rest are in
 * {@link #REQUIRED_COLUMNS} and a row missing any of them is reported rather than skipped
 * quietly.
 * <p>
 * Enum cells accept the constant name or the display label, loosely matched, so "Computer
 * Science" and "COMPUTER_SCIENCE" are the same department. That tolerance is deliberate: a
 * roster is typed by a person, in a spreadsheet.
 *
 * <h3>A bad row costs only that row</h3>
 * Every row is parsed on its own and a failure is recorded with its line number, so one mistyped
 * department does not cost the other four hundred students. A whole-file problem — no header, a
 * missing required column — is different and stops the read before any row is examined, because
 * four hundred identical errors help nobody. {@link Reading#rejected} is how the two are told
 * apart.
 */
public final class RosterCsv {

	/**
	 * Columns without which a row cannot be filed at all, in the normalised form
	 * {@link #parseHeader} produces: lower case, separators stripped.
	 */
	public static final String[] REQUIRED_COLUMNS =
			{"rollno", "name", "year", "stream", "majordept"};

	/** What a row says, once every cell has been resolved and the combination checked. */
	public record Row(int line, @NonNull String rollNo, @NonNull String name,
	                  @NonNull String pronouns, @Nullable String email, @NonNull Year year,
	                  @NonNull Stream stream, @NonNull Department majorDept,
	                  @Nullable Department minorDept, @Nullable Department oeDept,
	                  @Nullable Integer semester, @Nullable Division division) {
	}

	/**
	 * Everything one file yielded: the rows that parsed, and a line per row that did not.
	 *
	 * @param rejected true when the file itself could not be read — no header, or a missing
	 *                 required column — in which case {@link #rows} is empty and nothing should
	 *                 be written. A file that merely contained some bad rows is not rejected
	 */
	public record Reading(@NonNull List<Row> rows, @NonNull List<String> errors,
	                      boolean rejected) {

		public boolean hasErrors() {
			return !errors.isEmpty();
		}
	}

	private RosterCsv() {
	}

	/**
	 * Reads a whole roster.
	 * <p>
	 * The reader is not closed: whoever opened it knows whether it wraps a request body, a
	 * content URI or a file.
	 */
	@NonNull
	public static Reading read(@NonNull Reader source) throws IOException {

		BufferedReader reader = source instanceof BufferedReader buffered
				? buffered : new BufferedReader(source);

		List<Row> rows = new ArrayList<>();
		List<String> errors = new ArrayList<>();

		String headerLine = reader.readLine();

		if (headerLine == null) {
			errors.add("The file is empty.");
			return new Reading(List.of(), errors, true);
		}

		Map<String, Integer> columns = parseHeader(headerLine);

		for (String required : REQUIRED_COLUMNS) {
			if (!columns.containsKey(required)) {
				errors.add("Missing required column: " + required);
				return new Reading(List.of(), errors, true);
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
				rows.add(parseRow(lineNo, splitCsvLine(line), columns));
			} catch (RuntimeException e) {
				errors.add("Line " + lineNo + ": " + e.getMessage());
			}
		}

		return new Reading(rows, errors, false);
	}

	/**
	 * Resolves and validates one row's cells.
	 *
	 * @throws IllegalArgumentException with a message naming the offending field, which the
	 *                                 caller turns into that row's error line
	 */
	@NonNull
	public static Row parseRow(int line, @NonNull List<String> cells,
	                           @NonNull Map<String, Integer> columns) {

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

		Year year = parseEnum(Year.class, required(cells, columns, "year", "year"), "year");
		Stream requested = parseEnum(Stream.class,
				required(cells, columns, "stream", "stream"), "stream");
		Department majorDept = parseEnum(Department.class,
				required(cells, columns, "majordept", "major department"), "major department");

		// A file saying "COMPUTER_SCIENCE,BSc" means the unaided BSc, because that is the only
		// BSc that department runs, so the label is resolved against the department. A stream
		// the department does not offer at all is reported as a bad row rather than corrected:
		// "COMMERCE,BSc" is a mistake in the file and should be seen, not quietly filed as
		// BCom.
		Stream stream = AcademicRules.resolveRequested(majorDept, requested);

		if (stream == null) {
			throw new IllegalArgumentException(majorDept + " is not offered as " + requested);
		}

		Integer semester = parseOptionalInt(optional(cells, columns, "semester"), "semester");
		Division division = parseDivision(optional(cells, columns, "division"));

		// The same backstop the forms use, so a file cannot introduce a combination the UI
		// would refuse: a BSc student in Masters Part 1, a first-year in semester 5, or a
		// division on a stream that has none. Every value the check covers has to be parsed
		// before it runs, which is why the semester and division are read here rather than
		// alongside the other optional cells below.
		String problem = AcademicRules.validate(majorDept, stream, year, semester, division);

		if (problem != null) {
			throw new IllegalArgumentException(problem);
		}

		Department minorDept = parseOptionalEnum(Department.class,
				optional(cells, columns, "minordept"), "minor department");
		Department oeDept = parseOptionalEnum(Department.class,
				optional(cells, columns, "oedept"), "open elective department");

		// The three departments have to be three different departments. This is not the forms'
		// rule, because the forms cannot offer the same department twice -- it is
		// student_year_minor_not_major, _oe_not_major and _oe_not_minor from V2, which a CSV is
		// the only way to reach. Checked here rather than left to PostgreSQL so that the app
		// refuses the row for the same reason the server would, and says so with the line
		// number instead of a constraint name.
		if (minorDept == majorDept) {
			throw new IllegalArgumentException(
					"the minor department is the same as the major department (" + majorDept
							+ ")");
		}

		if (oeDept != null && (oeDept == majorDept || oeDept == minorDept)) {
			throw new IllegalArgumentException("the open elective department is the same as the "
					+ (oeDept == majorDept ? "major" : "minor") + " department (" + oeDept + ")");
		}

		return new Row(line, rollNo, name, pronouns, email, year, stream, majorDept, minorDept,
				oeDept, semester, division);
	}

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
	@NonNull
	public static Map<String, Integer> parseHeader(@NonNull String headerLine) {

		Map<String, Integer> map = new HashMap<>();
		List<String> cells = splitCsvLine(stripByteOrderMark(headerLine));

		for (int i = 0; i < cells.size(); i++) {
			String key = cells.get(i).trim().toLowerCase(Locale.ROOT)
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

	/**
	 * A ready-to-share template so whoever fills the file in knows exactly what it should look
	 * like. The second example row leaves the optional department columns empty, which is the
	 * part people most often ask about, and the third carries a division, which only the aided
	 * BSc and BA rows ever should.
	 */
	@NonNull
	public static String templateCsv() {
		return "rollNo,name,pronouns,email,year,stream,majorDept,minorDept,oeDept,semester,"
				+ "division\n"
				+ "2024CS032,Aarav Patil,he/him,aarav@sies.edu.in,SY,BSc,COMPUTER_SCIENCE,"
				+ "MATHEMATICS,ENGLISH,3,\n"
				+ "2024CS033,Sneha Mehta,she/her,sneha@sies.edu.in,SY,BSc,COMPUTER_SCIENCE,,,3,"
				+ "\n"
				+ "2024PH017,Ishaan Rao,he/him,ishaan@sies.edu.in,SY,BSc,PHYSICS,,,3,B\n";
	}

	// ── Cell helpers ─────────────────────────────────────────────────────

	/** Removes the byte order mark a UTF-8 file may open with. */
	@NonNull
	private static String stripByteOrderMark(@NonNull String line) {
		return line.isEmpty() || line.charAt(0) != '﻿' ? line : line.substring(1);
	}

	/**
	 * A cell that must be present and non-blank.
	 *
	 * @param label the human name of the field, used in the error message
	 */
	@NonNull
	private static String required(@NonNull List<String> cells,
	                               @NonNull Map<String, Integer> columns, @NonNull String key,
	                               @NonNull String label) {

		String value = optional(cells, columns, key);

		if (value == null || value.isEmpty()) {
			throw new IllegalArgumentException(label + " is blank");
		}
		return value;
	}

	/**
	 * A cell that may be missing. Returns null both when the column is absent from the header
	 * and when the row is short, so a file whose trailing columns are simply not filled in
	 * imports without complaint.
	 */
	@Nullable
	private static String optional(@NonNull List<String> cells,
	                               @NonNull Map<String, Integer> columns, @NonNull String key) {

		Integer index = columns.get(key);

		if (index == null || index >= cells.size()) {
			return null;
		}
		return cells.get(index).trim();
	}

	/** As {@link #parseOptionalEnum}, but a blank value is an error rather than a null. */
	@NonNull
	private static <E extends Enum<E>> E parseEnum(@NonNull Class<E> type,
	                                               @NonNull String raw, @NonNull String label) {

		E value = parseOptionalEnum(type, raw, label);

		if (value == null) {
			throw new IllegalArgumentException(label + " is blank");
		}
		return value;
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
	private static <E extends Enum<E>> E parseOptionalEnum(@NonNull Class<E> type,
	                                                       @Nullable String raw,
	                                                       @NonNull String label) {

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
	 * Parses an optional whole number. A blank cell is absence, not zero; anything else that is
	 * not a number is an error rather than a silent null, because a typo in a semester column
	 * should be seen.
	 */
	@Nullable
	private static Integer parseOptionalInt(@Nullable String raw, @NonNull String label) {

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
	 * Parses an optional division, accepting a bare letter or one written out as "Div B".
	 * <p>
	 * Not {@link #parseOptionalEnum} because a roster writes the prefix as often as not, and
	 * {@link Division#fromAny} is what knows to strip it. A blank cell is absence, which is the
	 * usual answer: most streams have no divisions at all.
	 */
	@Nullable
	private static Division parseDivision(@Nullable String raw) {

		if (raw == null || raw.trim().isEmpty()) {
			return null;
		}

		Division division = Division.fromAny(raw);

		if (division == null) {
			throw new IllegalArgumentException("Unrecognised division: " + raw);
		}
		return division;
	}
}
