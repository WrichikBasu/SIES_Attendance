package in.basulabs.siesattendance.backend.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads an attendance sheet in which every class session is a column and every student a row.
 * <p>
 * This is the shape a register already has on paper, and the shape a professor keeping one in a
 * spreadsheet will hand over. The first four lines are the column headings, stacked one under
 * the other because a session needs four facts to identify it and a single heading row cannot
 * hold them:
 * <pre>
 * Course code,PHYS101,PHYS101,CHEM101
 * Date,2025-07-01,2025-07-03,2025-07-01
 * Start time,09:00,09:00,11:00
 * End time,10:00,10:00,12:00
 * 2024PH001,P,AB,P
 * 2024PH002,AB,P,
 * </pre>
 * The first cell of each of those four lines is a label for the reader and is not used, except
 * that a recognisable label lets the four lines be given in any order; a file that labels them
 * differently, or not at all, is read in the order above.
 * <p>
 * Three states are possible in a cell, and the third is the reason this class exists. "P" is
 * present and "AB" is absent, but an empty cell is neither: it says the student was not in that
 * class at all, which is a different fact from having missed it and must not be written as an
 * absence. {@link Mark#NOT_IN_CLASS} keeps them apart, and what the pattern of empty cells
 * implies about which batch sat the session is worked out later, in {@link BatchMatcher}.
 * <p>
 * Nothing here touches the database or looks anything up. Parsing yields a {@link Sheet} of
 * plain values, which {@code AttendanceRecordImporter} then resolves against real courses,
 * students and batches. Keeping the two apart is what makes the grid testable without a device.
 * <p>
 * A malformed column costs that column alone. Its problems are recorded on it and the remaining
 * columns still import, on the same reasoning as the student roster import: one mistyped time
 * should not cost the user a term's register. Only a problem that makes the whole grid
 * untrustworthy, such as the same roll number appearing twice, is recorded in
 * {@link Sheet#errors} and stops everything.
 */
public final class AttendanceCsvParser {

	/** What one cell says about one student at one session. */
	public enum Mark {

		PRESENT,

		ABSENT,

		/**
		 * The cell was empty. The student was not in that class, which is not an absence: no
		 * attendance row is written for them, and their presence in the file is instead
		 * evidence about which batch the session was held for.
		 */
		NOT_IN_CLASS
	}

	/** One class session, as named by the four heading cells stacked in its column. */
	public static final class Column {

		/** Position among the mark columns, counting from one, for error messages. */
		public final int index;

		@Nullable
		public final String courseCode;

		@Nullable
		public final LocalDate date;

		@Nullable
		public final LocalTime start;

		@Nullable
		public final LocalTime end;

		/**
		 * Why this column cannot be imported. Empty means it can. Collected rather than thrown
		 * so that the rest of the file still goes through and the user sees every problem at
		 * once instead of one per attempt.
		 */
		public final List<String> problems = new ArrayList<>();

		Column(int index, @Nullable String courseCode, @Nullable LocalDate date,
		       @Nullable LocalTime start, @Nullable LocalTime end) {
			this.index = index;
			this.courseCode = courseCode;
			this.date = date;
			this.start = start;
			this.end = end;
		}

		public boolean isUsable() {
			return problems.isEmpty();
		}

		/** How this column is referred to in a message, using whatever of it could be read. */
		@NonNull
		public String describe() {

			StringBuilder out = new StringBuilder("Column ").append(index);

			if (courseCode != null && !courseCode.isEmpty()) {
				out.append(" (").append(courseCode);
				if (date != null) {
					out.append(", ").append(date);
				}
				out.append(")");
			}
			return out.toString();
		}
	}

	/** One student's line: the roll number, and one mark per column. */
	public static final class Row {

		/** Line number in the file, so a problem can be pointed at. */
		public final int lineNumber;

		@NonNull
		public final String rollNo;

		/** One entry per column of the sheet, in the same order. */
		@NonNull
		public final Mark[] marks;

		Row(int lineNumber, @NonNull String rollNo, @NonNull Mark[] marks) {
			this.lineNumber = lineNumber;
			this.rollNo = rollNo;
			this.marks = marks;
		}
	}

	/** The whole grid, once read. */
	public static final class Sheet {

		public final List<Column> columns = new ArrayList<>();

		public final List<Row> rows = new ArrayList<>();

		/**
		 * Problems that make the whole file unusable, as opposed to the per-column problems on
		 * {@link Column#problems}. A non-empty list means nothing should be imported.
		 */
		public final List<String> errors = new ArrayList<>();

		public boolean isUsable() {
			return errors.isEmpty() && !columns.isEmpty() && !rows.isEmpty();
		}
	}

	// ── Heading labels ───────────────────────────────────────────────────

	/**
	 * The labels each heading line may carry in its first cell, normalised the same way the
	 * cells are. Recognising them is what lets the four lines be written in any order; a file
	 * whose labels are not in this list is read positionally instead, so an unfamiliar wording
	 * costs nothing as long as the conventional order was kept.
	 */
	private static final String[][] ROLE_LABELS = {
			{"coursecode", "course", "code", "subject", "subjectcode", "paper"},
			{"date", "lecturedate", "classdate", "day"},
			{"starttime", "start", "from", "fromtime", "begins"},
			{"endtime", "end", "to", "totime", "ends"}
	};

	private static final int ROLE_COURSE = 0, ROLE_DATE = 1, ROLE_START = 2, ROLE_END = 3;

	/**
	 * Date spellings accepted in the second heading line.
	 * <p>
	 * Day-first throughout, deliberately. "03/07/2025" is the third of July to everyone who
	 * will use this, and admitting the month-first reading as well would make the two
	 * indistinguishable for the twelve days a month where both parse: a file would import
	 * silently against the wrong dates rather than being rejected.
	 */
	private static final DateTimeFormatter[] DATE_FORMATS = {
			DateTimeFormatter.ISO_LOCAL_DATE,
			ofPattern("d/M/uuuu"),
			ofPattern("d-M-uuuu"),
			ofPattern("d.M.uuuu"),
			ofPattern("d MMM uuuu"),
			ofPattern("d MMMM uuuu"),
			ofPattern("d-MMM-uuuu")
	};

	/** Time spellings accepted in the third and fourth heading lines. */
	private static final DateTimeFormatter[] TIME_FORMATS = {
			ofPattern("H:mm"),
			ofPattern("H:mm:ss"),
			ofPattern("h:mm a"),
			ofPattern("h:mma"),
			ofPattern("h a")
	};

	/**
	 * Case-insensitive so that "9:00 am" reads the same as "9:00 AM", and fixed to
	 * {@link Locale#ENGLISH} because the pattern letters are matched against the file's own
	 * text rather than against anything the device chose.
	 */
	@NonNull
	private static DateTimeFormatter ofPattern(@NonNull String pattern) {
		return new DateTimeFormatterBuilder()
				.parseCaseInsensitive()
				.appendPattern(pattern)
				.toFormatter(Locale.ENGLISH);
	}

	private AttendanceCsvParser() {
	}

	// ── Parsing ──────────────────────────────────────────────────────────

	/**
	 * Reads the grid. Never throws: everything that could go wrong comes back as a problem on
	 * the column it belongs to, or on the sheet when it belongs to the whole file.
	 *
	 * @param lines the file's lines in order, as read
	 */
	@NonNull
	public static Sheet parse(@NonNull List<String> lines) {

		Sheet sheet = new Sheet();

		// Heading lines and their line numbers, kept together so a problem in one can name the
		// line it was on rather than its position among the four.
		List<String> heading = new ArrayList<>();
		List<Integer> headingLines = new ArrayList<>();

		int lineNo = 0;

		for (String raw : lines) {
			lineNo++;

			if (heading.size() == 4) {
				break;
			}

			String line = lineNo == 1 ? stripByteOrderMark(raw) : raw;

			if (!line.trim().isEmpty()) {
				heading.add(line);
				headingLines.add(lineNo);
			}
		}

		if (heading.size() < 4) {
			sheet.errors.add("The file needs four heading lines — course code, date, start "
					+ "time and end time — before the first student.");
			return sheet;
		}

		List<List<String>> cells = new ArrayList<>();
		for (String line : heading) {
			cells.add(RosterCsv.splitCsvLine(line));
		}

		int[] roles = resolveRoles(cells);

		readColumns(sheet, cells, roles, headingLines);

		if (sheet.columns.isEmpty()) {
			sheet.errors.add("No class sessions were found. The first column holds the roll "
					+ "numbers; every column after it is one session.");
			return sheet;
		}

		readRows(sheet, lines, headingLines.get(headingLines.size() - 1));

		if (sheet.rows.isEmpty()) {
			sheet.errors.add("No students were found below the heading lines.");
		}

		return sheet;
	}

	/**
	 * Decides which heading line holds which fact.
	 * <p>
	 * Labels win where all four are recognised and distinct, which is the only case in which
	 * trusting them is safe: a file that labels three lines and leaves the fourth blank tells
	 * us nothing about where the fourth went, so the whole thing falls back to the documented
	 * order rather than half-guessing.
	 *
	 * @return the index into {@code cells} for each of the four roles
	 */
	@NonNull
	private static int[] resolveRoles(@NonNull List<List<String>> cells) {

		int[] byLabel = {-1, -1, -1, -1};

		for (int line = 0; line < cells.size(); line++) {

			String label = normalise(cells.get(line).isEmpty() ? "" : cells.get(line).get(0));

			for (int role = 0; role < ROLE_LABELS.length; role++) {
				for (String candidate : ROLE_LABELS[role]) {
					if (candidate.equals(label) && byLabel[role] == -1) {
						byLabel[role] = line;
					}
				}
			}
		}

		boolean allFound = true;
		for (int line : byLabel) {
			allFound &= line >= 0;
		}

		return allFound && distinct(byLabel) ? byLabel : new int[]{0, 1, 2, 3};
	}

	private static boolean distinct(@NonNull int[] values) {

		for (int i = 0; i < values.length; i++) {
			for (int j = i + 1; j < values.length; j++) {
				if (values[i] == values[j]) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Turns the four heading lines into one {@link Column} per session.
	 * <p>
	 * Trailing columns that are blank in all four lines are dropped rather than reported. A
	 * spreadsheet saving a sheet whose used range is wider than its data writes exactly that,
	 * and complaining about it would reject a file that is in fact correct.
	 */
	private static void readColumns(@NonNull Sheet sheet, @NonNull List<List<String>> cells,
	                                @NonNull int[] roles, @NonNull List<Integer> headingLines) {

		int width = 0;
		for (List<String> line : cells) {
			width = Math.max(width, line.size());
		}

		int lastUsed = 0;
		for (int column = 1; column < width; column++) {
			for (int role = 0; role < 4; role++) {
				if (!at(cells, roles[role], column).isEmpty()) {
					lastUsed = column;
				}
			}
		}

		for (int column = 1; column <= lastUsed; column++) {

			String code = at(cells, roles[ROLE_COURSE], column);
			String dateText = at(cells, roles[ROLE_DATE], column);
			String startText = at(cells, roles[ROLE_START], column);
			String endText = at(cells, roles[ROLE_END], column);

			LocalDate date = parseDate(dateText);
			LocalTime start = parseTime(startText);
			LocalTime end = parseTime(endText);

			Column parsed = new Column(column, code.isEmpty() ? null : code, date, start, end);

			if (code.isEmpty()) {
				problem(parsed, headingLines.get(roles[ROLE_COURSE]), "no course code");
			}
			if (date == null) {
				problem(parsed, headingLines.get(roles[ROLE_DATE]),
						dateText.isEmpty() ? "no date" : "unrecognised date \"" + dateText + "\"");
			}
			if (start == null) {
				problem(parsed, headingLines.get(roles[ROLE_START]), startText.isEmpty()
						? "no start time" : "unrecognised start time \"" + startText + "\"");
			}
			if (end == null) {
				problem(parsed, headingLines.get(roles[ROLE_END]), endText.isEmpty()
						? "no end time" : "unrecognised end time \"" + endText + "\"");
			}
			if (start != null && end != null && !end.isAfter(start)) {
				problem(parsed, headingLines.get(roles[ROLE_END]),
						"the end time is not after the start time");
			}

			sheet.columns.add(parsed);
		}
	}

	/** Reads the student lines, one mark per column, below the heading block. */
	private static void readRows(@NonNull Sheet sheet, @NonNull List<String> lines,
	                             int lastHeadingLine) {

		// Where each roll number was first seen, so a repeat can name both lines.
		Map<String, Integer> seen = new HashMap<>();

		int lineNo = 0;

		for (String raw : lines) {
			lineNo++;

			if (lineNo <= lastHeadingLine || raw.trim().isEmpty()) {
				continue;
			}

			List<String> cells = RosterCsv.splitCsvLine(raw);
			String rollNo = cells.isEmpty() ? "" : cells.get(0).trim();

			if (rollNo.isEmpty()) {
				continue;
			}

			Integer earlier = seen.put(normalise(rollNo), lineNo);

			if (earlier != null) {
				// Two lines for one student make every column ambiguous, so this stops the
				// whole file rather than the column: there is no honest way to choose between
				// them, and importing one of the two silently would be the worst outcome.
				sheet.errors.add("Roll number " + rollNo + " appears on lines " + earlier
						+ " and " + lineNo + ".");
				continue;
			}

			Mark[] marks = new Mark[sheet.columns.size()];

			for (int i = 0; i < marks.length; i++) {

				Column column = sheet.columns.get(i);
				String cell = i + 1 < cells.size() ? cells.get(i + 1).trim() : "";

				Mark mark = parseMark(cell);

				if (mark == null) {
					// Not treated as an empty cell. An empty cell means the student was not in
					// the class, which changes which batch this column is matched to, so
					// guessing at a typo would quietly rewrite the session.
					problem(column, lineNo,
							"unrecognised mark \"" + cell + "\" for " + rollNo);
					mark = Mark.NOT_IN_CLASS;
				}
				marks[i] = mark;
			}

			sheet.rows.add(new Row(lineNo, rollNo, marks));
		}
	}

	// ── Cell readers ─────────────────────────────────────────────────────

	/**
	 * Reads one attendance cell.
	 *
	 * @return the mark, or null when the cell holds something that is neither a mark nor blank
	 */
	@Nullable
	public static Mark parseMark(@Nullable String cell) {

		if (cell == null) {
			return Mark.NOT_IN_CLASS;
		}

		String value = cell.trim().toUpperCase(Locale.ROOT);

		// A dash is how a spreadsheet user writes "nothing here" when a truly empty cell looks
		// like an oversight, so it means the same as blank.
		if (value.isEmpty() || value.equals("-") || value.equals("\u2013")
				|| value.equals("\u2014")) {
			return Mark.NOT_IN_CLASS;
		}

		switch (value) {
			case "P":
			case "PR":
			case "PRESENT":
				return Mark.PRESENT;
			case "A":
			case "AB":
			case "ABS":
			case "ABSENT":
				return Mark.ABSENT;
			default:
				return null;
		}
	}

	@Nullable
	private static LocalDate parseDate(@NonNull String text) {

		if (text.isEmpty()) {
			return null;
		}

		for (DateTimeFormatter format : DATE_FORMATS) {
			try {
				return LocalDate.parse(text, format);
			} catch (DateTimeParseException ignored) {
				// Try the next spelling.
			}
		}
		return null;
	}

	@Nullable
	private static LocalTime parseTime(@NonNull String text) {

		if (text.isEmpty()) {
			return null;
		}

		// "9.30" is the same thing to a person writing it out quickly, and no accepted time
		// format uses a full stop for anything else.
		String value = text.replace('.', ':');

		for (DateTimeFormatter format : TIME_FORMATS) {
			try {
				return LocalTime.parse(value, format);
			} catch (DateTimeParseException ignored) {
				// Try the next spelling.
			}
		}
		return null;
	}

	// ── Helpers ──────────────────────────────────────────────────────────

	private static void problem(@NonNull Column column, int lineNo, @NonNull String message) {
		column.problems.add("Line " + lineNo + ": " + message);
	}

	/** A cell from one heading line, or the empty string where the line is shorter than that. */
	@NonNull
	private static String at(@NonNull List<List<String>> cells, int line, int column) {

		if (line < 0 || line >= cells.size()) {
			return "";
		}

		List<String> row = cells.get(line);
		return column < row.size() ? row.get(column).trim() : "";
	}

	/**
	 * Lower cased with separators stripped, for comparing a label or a roll number against
	 * another spelling of the same thing. Fixed to {@link Locale#ROOT} for the same reason the
	 * roster import fixes it: the default locale would map "I" to a dotless letter on a Turkish
	 * device and the comparison would silently stop matching.
	 */
	@NonNull
	private static String normalise(@NonNull String value) {
		return value.trim().toLowerCase(Locale.ROOT)
				.replace("_", "").replace(" ", "").replace("-", "").replace(".", "");
	}

	/** Removes the byte order mark a UTF-8 file may open with. */
	@NonNull
	private static String stripByteOrderMark(@NonNull String line) {
		return line.isEmpty() || line.charAt(0) != '\uFEFF' ? line : line.substring(1);
	}

	/**
	 * The file format, written out for a person: what the app's format dialog and the web
	 * console's import panel both show.
	 */
	@NonNull
	public static String formatHelp() {
		return "A CSV file laid out like a register: one row per student, one column per "
				+ "class.\n\n"
				+ "THE FIRST FOUR ROWS name each class, one fact per row, with a label in the "
				+ "first cell:\n"
				+ "• Course code: the course's code, such as PHYS101\n"
				+ "• Date: 2025-07-01, 01/07/2025, 1-7-2025, 1.7.2025, 1 Jul 2025 or "
				+ "1-Jul-2025\n"
				+ "• Start time and End time: 09:00, 9:00 AM or 9 AM\n"
				+ "The labels may be in any order when they are recognisable (Course, Date, "
				+ "Start, End); without labels the rows are read in that order.\n\n"
				+ "EVERY ROW AFTER THAT is one student:\n"
				+ "• the first cell is the roll number in the session the date falls in. "
				+ "Capitals, spaces, hyphens and slashes don't matter.\n"
				+ "• each class's cell is P (or Present) for present, AB (or A, Absent) for "
				+ "absent, and blank or a dash when the student was not in that class at "
				+ "all.\n\n"
				+ "WHAT HAPPENS\n"
				+ "• Each class is filed under the session its date falls in, which has to "
				+ "exist.\n"
				+ "• The blank cells decide which batch was in the room: the batch that best "
				+ "matches who was marked. That batch has to exist first; a class taught to "
				+ "everybody needs a batch holding everybody. Marks for students outside that "
				+ "batch are left out.\n"
				+ "• A class already on record for the same course, batch and time has its "
				+ "marks replaced.\n"
				+ "• Roll numbers nobody is enrolled under are listed and ignored.\n"
				+ "• Nothing is saved until you have seen the preview and confirmed it. A "
				+ "column with a problem is skipped; the rest still import.\n\n"
				+ "WHO MAY IMPORT\n"
				+ "A professor imports the classes of courses they teach, a head their "
				+ "department's, and an administrator any.";
	}

	/**
	 * A ready-to-share example, shown when the user asks what the file should look like. The
	 * blank cell on the last line is the part that needs seeing: it is what says that student
	 * was not in that class, as against having missed it.
	 */
	@NonNull
	public static String templateCsv() {
		return "Course code,PHYS101,PHYS101,PHYS102\n"
				+ "Date,01/07/2025,03/07/2025,01/07/2025\n"
				+ "Start time,09:00,09:00,11:00\n"
				+ "End time,10:00,10:00,13:00\n"
				+ "2024PH001,P,AB,P\n"
				+ "2024PH002,AB,P,P\n"
				+ "2024PH003,P,P,\n";
	}
}
