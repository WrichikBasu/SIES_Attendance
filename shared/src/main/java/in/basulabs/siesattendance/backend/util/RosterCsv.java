package in.basulabs.siesattendance.backend.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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
 * <pre>rollNo,name,pronouns,email,year,stream,majorDept,minorDept,oeDept,semester,division,
 * apaarId,joinDate,leaveDate</pre>
 * Column order does not matter, because the header is read into a name-to-index map. The
 * {@code pronouns}, {@code email}, {@code minorDept}, {@code oeDept}, {@code semester} and
 * {@code division} columns may be blank or left out of the file altogether, as may
 * {@code apaarId}, {@code joinDate} and {@code leaveDate}; the rest are in
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

	/**
	 * How long a cell stored as it was typed may be.
	 * <p>
	 * The columns are {@code text} in PostgreSQL and {@code TEXT} in SQLite, so until these
	 * existed a cell of any size at all was stored, synced to every phone and shown in every
	 * list: a three-megabyte name in one row of a roster is not a name, and nothing downstream
	 * was ever going to be the thing that noticed. Generous rather than tight — the longest
	 * name in the college's own roster is 62 characters — because the point is to refuse a
	 * paste or a mangled file, not to have an opinion about anybody's name.
	 * <p>
	 * Checked here, in the parser both importers share, so a file is refused the same way on
	 * the phone and at the server. A backup import or a hand-written row in the database is
	 * not covered: these are not constraints, and the schema has none.
	 */
	public static final int NAME_MAX = 200;

	/** As {@link #NAME_MAX}. */
	public static final int PRONOUNS_MAX = 40;

	/** As {@link #NAME_MAX}, at the longest address SMTP will carry (RFC 5321). */
	public static final int EMAIL_MAX = 254;

	/** As {@link #NAME_MAX}. A roll number is canonicalised first, so this is of the stored one. */
	public static final int ROLL_NO_MAX = 32;

	/**
	 * The digits in an APAAR ID, which is a fixed-width number.
	 * <p>
	 * Twelve, as issued. It used to be "one to eighteen digits", which accepted {@code 12} —
	 * and an APAAR ID's whole purpose here is to recognise the same person in a later session
	 * under a new roll number, so one typed short is one that silently matches the wrong
	 * student or nobody at all.
	 */
	public static final int APAAR_DIGITS = 12;

	/** The smallest {@link #APAAR_DIGITS}-digit number. See {@link #parseApaarId}. */
	private static final long APAAR_FLOOR = 100_000_000_000L;

	/**
	 * An address, by shape: something, an {@code @}, then a dotted domain.
	 * <p>
	 * Deliberately not RFC 5322, which would accept {@code a@b} and a quoted local part with a
	 * space in it, and whose real grammar nobody should be writing out here. This refuses the
	 * things a roster actually contains when the column is wrong — a name, a phone number, two
	 * addresses pasted into one cell — and accepts every address the college has on file.
	 * Nothing is ever sent to it; the address is how a promoted student is recognised.
	 */
	private static final Pattern EMAIL =
			Pattern.compile("[^@\\s,;]+@[^@\\s,;.]+(\\.[^@\\s,;.]+)+");

	/**
	 * What a row says, once every cell has been resolved and the combination checked.
	 *
	 * @param columns the file's columns, by normalised name ({@link #parseHeader}). A column the
	 *                file does not have says nothing about the student, so an importer keeps
	 *                what is on record for it rather than writing the empty value the row
	 *                carries in its place (R18); see {@link #has}
	 */
	public record Row(int line, @NonNull String rollNo, @NonNull String name,
	                  @NonNull String pronouns, @Nullable String email, @NonNull Year year,
	                  @NonNull Stream stream, @NonNull Department majorDept,
	                  @Nullable Department minorDept, @Nullable Department oeDept,
	                  @Nullable Integer semester, @Nullable Division division,
	                  @Nullable Long apaarId, @Nullable LocalDate joinDate,
	                  @Nullable LocalDate leaveDate, @NonNull Set<String> columns) {

		/**
		 * Whether the file had that column, by its normalised name: {@code email},
		 * {@code pronouns}, {@code minordept}, {@code oedept}, {@code semester},
		 * {@code division}.
		 * <p>
		 * A file without a column leaves the value on record alone. A blank cell in a column the
		 * file does have is the file saying "none", and clears a minor, an open elective, a
		 * semester or a division -- except the email, the APAAR ID and the dates, which identify a
		 * person or bound their record and are never erased by an import.
		 */
		public boolean has(@NonNull String column) {
			return columns.contains(column);
		}
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

		// A spreadsheet saves an empty row as a run of commas; any above the header are skipped
		// like the empty rows below it.
		String headerLine = reader.readLine();
		int lineNo = 1;

		while (headerLine != null && isBlankRow(stripByteOrderMark(headerLine))) {
			headerLine = reader.readLine();
			lineNo++;
		}

		if (headerLine == null) {
			errors.add("The file is empty.");
			return new Reading(List.of(), errors, true);
		}

		Map<String, Integer> columns;

		try {
			columns = parseHeader(headerLine);
		} catch (IllegalArgumentException duplicated) {
			// A header nobody can read the rows against, so no row is examined: the same
			// treatment as a missing column, for the same reason.
			errors.add(duplicated.getMessage());
			return new Reading(List.of(), errors, true);
		}

		for (String required : REQUIRED_COLUMNS) {
			if (!columns.containsKey(required)) {
				errors.add("Missing required column: " + required);
				return new Reading(List.of(), errors, true);
			}
		}

		String line;

		while ((line = reader.readLine()) != null) {
			lineNo++;

			if (isBlankRow(line)) {
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

		String rawRollNo = required(cells, columns, "rollno", "roll number");
		String rollNo = CanonicalCode.of(rawRollNo);
		if (rollNo.isEmpty()) {
			throw new IllegalArgumentException("the roll number \"" + rawRollNo
					+ "\" has no letters or digits");
		}
		limited(rollNo, ROLL_NO_MAX, "roll number");

		String name = limited(required(cells, columns, "name", "name"), NAME_MAX, "name");

		String pronouns = optional(cells, columns, "pronouns");
		if (pronouns == null || pronouns.isEmpty()) {
			pronouns = "they/them";
		}
		limited(pronouns, PRONOUNS_MAX, "pronouns");

		String email = parseEmail(optional(cells, columns, "email"));

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

		// The open elective has to be a department of its own: student_year_oe_not_major and
		// _oe_not_minor from V2. Checked here rather than left to PostgreSQL so that the app
		// refuses the row for the same reason the server would, and says so with the line
		// number instead of a constraint name. The minor may be the major department (V45).
		if (oeDept != null && (oeDept == majorDept || oeDept == minorDept)) {
			throw new IllegalArgumentException("the open elective department is the same as the "
					+ (oeDept == majorDept ? "major" : "minor") + " department (" + oeDept + ")");
		}

		Long apaarId = parseApaarId(optional(cells, columns, "apaarid"));
		LocalDate joinDate = parseDate(optional(cells, columns, "joindate"), "join date");
		LocalDate leaveDate = parseDate(optional(cells, columns, "leavedate"), "leave date");

		if (joinDate != null && leaveDate != null && leaveDate.isBefore(joinDate)) {
			throw new IllegalArgumentException("the leave date is before the join date");
		}

		return new Row(line, rollNo, name, pronouns, email, year, stream, majorDept, minorDept,
				oeDept, semester, division, apaarId, joinDate, leaveDate,
				java.util.Set.copyOf(columns.keySet()));
	}

	/**
	 * An APAAR ID: {@link #APAAR_DIGITS} digits, as a spreadsheet keeps it. Null for a blank
	 * cell.
	 * <p>
	 * One that begins with a zero is refused rather than stored. The column is a number in both
	 * databases ({@code bigint}, V26), so the leading zero cannot survive being written: the
	 * row would be filed under eleven digits, the export would write eleven, and re-importing
	 * that export would then be refused by this very method. A refusal somebody can read is
	 * better than a number quietly changing shape — and if such IDs are ever issued, it is the
	 * column that has to change, not this.
	 */
	@Nullable
	public static Long parseApaarId(@Nullable String raw) {

		if (raw == null || raw.isBlank()) {
			return null;
		}

		String digits = raw.trim();

		if (!digits.matches("[0-9]{" + APAAR_DIGITS + "}")) {
			throw new IllegalArgumentException("APAAR ID \"" + digits + "\" is not "
					+ APAAR_DIGITS + " digits");
		}

		long id = Long.parseLong(digits);

		if (id < APAAR_FLOOR) {
			throw new IllegalArgumentException("APAAR ID \"" + digits + "\" begins with a zero, "
					+ "which the stored number cannot keep");
		}
		return id;
	}

	/**
	 * An address, by shape, or null for a blank cell.
	 *
	 * @see #EMAIL for what is and is not checked
	 */
	@Nullable
	public static String parseEmail(@Nullable String raw) {

		if (raw == null || raw.isBlank()) {
			return null;
		}

		String email = raw.trim();

		limited(email, EMAIL_MAX, "email address");

		if (!EMAIL.matcher(email).matches()) {
			throw new IllegalArgumentException("the email address \"" + email
					+ "\" is not an address");
		}
		return email;
	}

	/**
	 * A date as a spreadsheet writes it: 2026-06-15, 15/06/2026 or 15-06-2026. Null for a blank
	 * cell.
	 */
	@Nullable
	public static LocalDate parseDate(@Nullable String raw, @NonNull String what) {

		if (raw == null || raw.isBlank()) {
			return null;
		}

		String value = raw.trim();

		for (DateTimeFormatter format : DATE_FORMATS) {

			LocalDate date;

			try {
				date = LocalDate.parse(value, format);
			} catch (DateTimeParseException tryTheNext) {
				continue;
			}

			// A date java.time can hold and nobody joined on: 0025 for 2025 (R21).
			if (!PlausibleDates.plausible(date)) {
				throw new IllegalArgumentException(PlausibleDates.refusal(what, value));
			}
			return date;
		}
		throw new IllegalArgumentException("the " + what + " \"" + value + "\" is not a date; "
				+ "write it as 2026-06-15 or 15/06/2026");
	}

	/**
	 * STRICT, as ISO_LOCAL_DATE already is. The default for a pattern is SMART, which reads
	 * 31/02/2026 as 28 February rather than refusing it, so a typo in a join or leave date
	 * would move a student's attendance window without a word. STRICT needs the year as
	 * {@code uuuu}; {@code yyyy} is year-of-era and would refuse every date.
	 */
	private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
			DateTimeFormatter.ISO_LOCAL_DATE,
			DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT),
			DateTimeFormatter.ofPattern("d-M-uuuu").withResolverStyle(ResolverStyle.STRICT));

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
	 * <p>
	 * Two cells that normalise to the same name are refused, which is a whole-file problem:
	 * the later one used to win, silently, so {@code rollNo,name,Roll No} imported every
	 * student under whatever was in the third column. Which of two columns holds the value is
	 * not something to guess at, and a file with two of anything was edited by somebody who
	 * should see this. Blank cells are not names and are skipped, because a spreadsheet pads a
	 * header row with commas as readily as it pads a data row.
	 *
	 * @throws IllegalArgumentException when one name appears twice; {@link #read} turns that
	 *                                 into a rejected file
	 */
	@NonNull
	public static Map<String, Integer> parseHeader(@NonNull String headerLine) {

		Map<String, Integer> map = new HashMap<>();
		List<String> cells = splitCsvLine(stripByteOrderMark(headerLine));

		for (int i = 0; i < cells.size(); i++) {
			String key = cells.get(i).trim().toLowerCase(Locale.ROOT)
					.replace("_", "").replace(" ", "").replace("-", "");

			if (key.isEmpty()) {
				continue;
			}

			Integer earlier = map.put(key, i);

			if (earlier != null) {
				throw new IllegalArgumentException("Two columns are both \"" + key
						+ "\" (columns " + (earlier + 1) + " and " + (i + 1)
						+ "). Leave one of them out.");
			}
		}
		return map;
	}

	/**
	 * Whether a line holds nothing: empty, spaces only, or (as a spreadsheet saves an empty row)
	 * only commas and empty or blank cells, quoted or not.
	 */
	public static boolean isBlankRow(@NonNull String line) {

		for (String cell : splitCsvLine(line)) {
			if (!cell.trim().isEmpty()) {
				return false;
			}
		}
		return true;
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
				+ "division,apaarId,joinDate,leaveDate\n"
				+ "2024CS032,Aarav Patil,he/him,aarav@sies.edu.in,SY,BSc,COMPUTER_SCIENCE,"
				+ "MATHEMATICS,ENGLISH,3,,123456789012,,\n"
				+ "2024CS033,Sneha Mehta,she/her,sneha@sies.edu.in,SY,BSc,COMPUTER_SCIENCE,,,3,"
				+ ",,15/07/2024,\n"
				+ "2024PH017,Ishaan Rao,he/him,ishaan@sies.edu.in,SY,BSc,PHYSICS,,,3,B,,,\n";
	}

	/**
	 * The file format, written out for a person: what the app's format dialog and the web
	 * console's import panel both show. Built from the same rules the parser enforces -- the
	 * years, the streams, which semesters fit which year -- so the description cannot drift
	 * from what is accepted.
	 */
	@NonNull
	public static String formatHelp() {

		StringBuilder years = new StringBuilder();
		StringBuilder semesters = new StringBuilder();

		for (Year year : Year.values()) {
			years.append(years.length() == 0 ? "" : ", ").append(year);
			List<Integer> fit = AcademicRules.semestersFor(year);
			if (!fit.isEmpty()) {
				semesters.append(semesters.length() == 0 ? "" : "; ").append(year).append(" ")
						.append(fit.get(0)).append(fit.size() > 1 ? "–" + fit.get(fit.size() - 1)
								: "");
			}
		}

		StringBuilder streams = new StringBuilder();
		for (Stream stream : Stream.values()) {
			streams.append(streams.length() == 0 ? "" : ", ").append(stream.name());
		}

		return "A CSV file with one row per student, after a header row naming the columns. "
				+ "Columns can be in any order, and capitals, spaces and underscores in their "
				+ "names don't matter.\n\n"
				+ "REQUIRED COLUMNS\n"
				+ "• rollNo: the roll number in this session, such as 2024CS032. Only letters "
				+ "and digits are kept, in capitals: 2024-cs-032 is read as 2024CS032.\n"
				+ "• name: up to " + NAME_MAX + " characters\n"
				+ "• year: " + years + "\n"
				+ "• stream: " + streams + ". BSc and MSc are read as the unaided course "
				+ "when that is the only one the department runs.\n"
				+ "• majorDept: the department's name or code, such as Computer Science or "
				+ "COMPUTER_SCIENCE\n\n"
				+ "OPTIONAL COLUMNS (leave out, or leave the cell blank)\n"
				+ "• pronouns: they/them when blank\n"
				+ "• email: an address, such as aarav@sies.edu.in\n"
				+ "• minorDept: the minor department, which may be the major one\n"
				+ "• oeDept: the open elective department, different from the major and the "
				+ "minor\n"
				+ "• semester: " + semesters + "\n"
				+ "• division: A to D, only for the aided BSc and BA\n"
				+ "• apaarId: the " + APAAR_DIGITS + "-digit APAAR number. It recognises the "
				+ "same student in a later session, under a new roll number.\n"
				+ "• joinDate: if they joined after the session began. Attendance counts from "
				+ "this day.\n"
				+ "• leaveDate: if they have left. They come off the rolls and the exports.\n"
				+ "Dates are written 2026-06-15, 15/06/2026 or 15-06-2026.\n\n"
				+ "HOW ROWS ARE MATCHED\n"
				+ "A row updates a student already on file when its roll number is in this "
				+ "session, or its APAAR ID or email belongs to a student; otherwise it adds "
				+ "one. A blank APAAR ID or date keeps the one already stored. An import never "
				+ "removes anybody.\n\n"
				+ "WHO MAY IMPORT\n"
				+ "A professor adds students to their own department, a head to the departments "
				+ "they head, and the office or an administrator to any. A row for another "
				+ "department is refused.\n\n"
				+ "A row with a problem is skipped and listed with its line number; the rest "
				+ "still import.";
	}

	// ── Cell helpers ─────────────────────────────────────────────────────

	/**
	 * A cell short enough to store, returned unchanged, or an error naming its length.
	 * <p>
	 * The value itself is not in the message. The whole point of this check is a cell with
	 * three megabytes in it, and quoting that back would put it in a log, a dialog and a
	 * progress report.
	 *
	 * @see #NAME_MAX
	 */
	@NonNull
	private static String limited(@NonNull String value, int max, @NonNull String label) {

		if (value.length() > max) {
			throw new IllegalArgumentException("the " + label + " is " + value.length()
					+ " characters long, and " + max + " is the most that is stored");
		}
		return value;
	}

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
