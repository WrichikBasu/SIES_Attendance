package in.basulabs.siesattendance.backend.util;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import in.basulabs.siesattendance.backend.MainDatabase;
import in.basulabs.siesattendance.backend.pojo.LectureDetails;
import in.basulabs.siesattendance.backend.pojo.LectureMark;

/**
 * Exports the marks themselves — who was there and who was not — for one lecture or for one
 * day, as a spreadsheet or a CSV file.
 * <p>
 * Distinct from {@link AttendanceExporter}, which totals a whole session into held and
 * attended figures. This is the register as it was called: one row per student, one column per
 * lecture, and in each cell {@link #PRESENT} or {@link #ABSENT}. A blank cell means the student
 * was not on that lecture's sheet at all, which is different from absent and is left visibly
 * different.
 * <p>
 * Only saved marks are exported. A lecture nobody has marked yet has no column, since a column
 * of blanks would read as a lecture everyone skipped; a day export names such lectures in its
 * heading instead, so their absence from the table is explained rather than silent.
 * <p>
 * The sheet is built once and then written in whichever format was asked for, so a CSV and a
 * spreadsheet of the same day cannot disagree about what is in them. What differs is only what
 * each format can carry: the spreadsheet has a heading block, totals and, optionally, colour;
 * the CSV is the bare table, so it can be read back by anything that reads a CSV.
 */
public final class MarkSheetExporter {

	/** The mark for a student who was there. */
	public static final String PRESENT = "P";

	/** The mark for a student who was not. */
	public static final String ABSENT = "AB";

	private static final String STYLE_TITLE = "ceTitle";

	private static final String STYLE_HEADER = "ceHeader";

	/**
	 * The highlight pair: green on pale green, red on pale red. The same red as the session
	 * export's defaulter shading, so a report and a register read in one visual language.
	 */
	private static final String STYLE_PRESENT = "cePresent";

	private static final String STYLE_ABSENT = "ceAbsent";

	/**
	 * In English whatever the device is set to, because every other word in the file is: a
	 * month name in the device's language would sit in the middle of English headings. Pinned
	 * to plain English rather than a regional variant too, which is what keeps "Sep" from
	 * becoming "Sept" on some devices and not others.
	 */
	private static final DateTimeFormatter DAY =
			DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

	private static final DateTimeFormatter FILE_DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

	private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("HHmm");

	/**
	 * A text field starting with one of these is read as a formula by Excel and its kin. Roll
	 * numbers and names come from rosters typed by other people, and a CSV opened with a
	 * double-click is exactly where a stray leading "=" would run rather than show.
	 */
	private static final String FORMULA_TRIGGERS = "=+-@\t\r";

	private MarkSheetExporter() {
	}

	/** The two formats offered. */
	public enum Format {

		ODS("application/vnd.oasis.opendocument.spreadsheet", "ods"),

		CSV("text/csv", "csv");

		/** What the save dialog is told the file is. */
		@NonNull
		public final String mimeType;

		@NonNull
		public final String extension;

		Format(@NonNull String mimeType, @NonNull String extension) {
			this.mimeType = mimeType;
			this.extension = extension;
		}
	}

	/**
	 * What to export, and how. Either a day or a lecture, never both; built through
	 * {@link #forDay} and {@link #forLecture} so that cannot be got wrong.
	 */
	public static final class Request {

		/** The day to export, or null when exporting one lecture. */
		@Nullable
		public final LocalDate day;

		/** The lecture to export, or -1 when exporting a day. */
		public final long lectureID;

		@NonNull
		public final Format format;

		/**
		 * Whether to colour the marks. Only a spreadsheet can carry it; a CSV ignores it.
		 */
		public final boolean highlight;

		private Request(@Nullable LocalDate day, long lectureID, @NonNull Format format,
		                boolean highlight) {
			this.day = day;
			this.lectureID = lectureID;
			this.format = format;
			this.highlight = highlight;
		}

		@NonNull
		public static Request forDay(@NonNull LocalDate day, @NonNull Format format,
		                             boolean highlight) {
			return new Request(day, -1L, format, highlight);
		}

		@NonNull
		public static Request forLecture(long lectureID, @NonNull Format format,
		                                 boolean highlight) {
			return new Request(null, lectureID, format, highlight);
		}
	}

	/** What an export produced, for the message shown afterwards. */
	public static final class Summary {

		public final int students;

		/** Lectures that became columns. */
		public final int lectures;

		/** Absent marks across the whole sheet. */
		public final int absences;

		/** Lectures on the day left out because nothing had been marked for them. */
		public final int unmarked;

		Summary(int students, int lectures, int absences, int unmarked) {
			this.students = students;
			this.lectures = lectures;
			this.absences = absences;
			this.unmarked = unmarked;
		}
	}

	/** One student's row: who they are, and a mark per lecture column. */
	static final class Row {

		@NonNull
		String rollNo;

		@NonNull
		final String name;

		/** One per column: true present, false absent, null not on that lecture's sheet. */
		@NonNull
		final Boolean[] marks;

		Row(@NonNull String rollNo, @NonNull String name, int columns) {
			this.rollNo = rollNo;
			this.name = name;
			this.marks = new Boolean[columns];
		}

		int count(boolean present) {
			int n = 0;
			for (Boolean mark : marks) {
				if (mark != null && mark == present) {
					n++;
				}
			}
			return n;
		}
	}

	/** The whole sheet, independent of the format it is about to be written in. */
	static final class Sheet {

		/** Lectures with at least one saved mark, in the order they were held. */
		@NonNull
		final List<LectureDetails> columns;

		/** Lectures that were asked for but have nothing saved. */
		@NonNull
		final List<LectureDetails> unmarked;

		@NonNull
		final List<Row> rows;

		/** The tab name in a spreadsheet. */
		@NonNull
		String name = "Attendance";

		/** Lines above the table in a spreadsheet; the first is the title. */
		@NonNull
		List<String> heading = new ArrayList<>();

		Sheet(@NonNull List<LectureDetails> columns, @NonNull List<LectureDetails> unmarked,
		      @NonNull List<Row> rows) {
			this.columns = columns;
			this.unmarked = unmarked;
			this.rows = rows;
		}

		/**
		 * Whether each row gets its own present and absent totals. Only worth it with more
		 * than one lecture: for one, the totals would repeat the single mark beside them.
		 */
		boolean perStudentTotals() {
			return columns.size() > 1;
		}

		int countInColumn(int column, boolean present) {
			int n = 0;
			for (Row row : rows) {
				Boolean mark = row.marks[column];
				if (mark != null && mark == present) {
					n++;
				}
			}
			return n;
		}

		@NonNull
		Summary summary() {
			int absences = 0;
			for (Row row : rows) {
				absences += row.count(false);
			}
			return new Summary(rows.size(), columns.size(), absences, unmarked.size());
		}
	}

	// ── Entry point ──────────────────────────────────────────────────────

	/**
	 * Reads what the request covers and writes it to {@code destination}.
	 * <p>
	 * Blocks; call off the main thread.
	 */
	@NonNull
	public static Summary export(@NonNull Context context, @NonNull Uri destination,
	                             @NonNull Request request) throws IOException {

		MainDatabase db = MainDatabase.getInstance(context);

		Sheet sheet = request.day != null
				? daySheet(db, request.day)
				: lectureSheet(db, request.lectureID);

		try (OutputStream out = context.getContentResolver()
				.openOutputStream(destination, "wt")) {

			if (out == null) {
				throw new IOException("Could not open the chosen file.");
			}

			if (request.format == Format.CSV) {
				writeCsv(sheet, out);
			} else {
				writeOds(sheet, request.highlight, out);
			}
		}

		return sheet.summary();
	}

	/**
	 * Every lecture held on one day, at the device's zone — the same window the day screen
	 * lists, so the export covers exactly the lectures that were on screen when it was asked for.
	 */
	@NonNull
	private static Sheet daySheet(@NonNull MainDatabase db, @NonNull LocalDate day) {

		ZoneId zone = ZoneId.systemDefault();
		long from = day.atStartOfDay(zone).toEpochSecond();
		long to = day.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1;

		List<LectureDetails> lectures = db.daoLecture().getDetailsBetweenSync(from, to);
		Sheet sheet = build(lectures, marksFor(db, lectures));

		sheet.name = day.format(DAY);
		sheet.heading = dayHeading(day, sheet);
		return sheet;
	}

	@NonNull
	private static Sheet lectureSheet(@NonNull MainDatabase db, long lectureID)
			throws IOException {

		LectureDetails lecture = db.daoLecture().getDetailsByIdSync(lectureID);

		if (lecture == null) {
			throw new IOException("The lecture no longer exists.");
		}

		List<LectureDetails> lectures = Collections.singletonList(lecture);
		Sheet sheet = build(lectures, marksFor(db, lectures));

		sheet.name = lecture.courseCode;
		sheet.heading = lectureHeading(lecture);
		return sheet;
	}

	@NonNull
	private static List<LectureMark> marksFor(@NonNull MainDatabase db,
	                                          @NonNull List<LectureDetails> lectures) {

		if (lectures.isEmpty()) {
			return Collections.emptyList();
		}

		List<Long> ids = new ArrayList<>();
		for (LectureDetails lecture : lectures) {
			ids.add(lecture.lectureID);
		}
		return db.daoAttendance().getMarksSync(ids);
	}

	// ── Building the sheet ───────────────────────────────────────────────

	/**
	 * Lays the marks out as one row per student and one column per lecture.
	 * <p>
	 * A lecture with no saved marks becomes no column and is set aside in
	 * {@link Sheet#unmarked}. A student marked in several of the lectures is one row, with a
	 * blank wherever they were not on the sheet — the blank is what tells a physics student
	 * missing from a chemistry practical apart from one who was on its roll and did not come.
	 * <p>
	 * Rows are in roll-number order, the order a register is read in, with anyone lacking a roll
	 * number for the session at the end rather than the top.
	 *
	 * @param lectures in the order the columns should appear, which is the order they were held
	 */
	@NonNull
	static Sheet build(@NonNull List<LectureDetails> lectures, @NonNull List<LectureMark> marks) {

		Set<Long> marked = new HashSet<>();
		for (LectureMark mark : marks) {
			marked.add(mark.lectureID);
		}

		List<LectureDetails> columns = new ArrayList<>();
		List<LectureDetails> unmarked = new ArrayList<>();

		for (LectureDetails lecture : lectures) {
			(marked.contains(lecture.lectureID) ? columns : unmarked).add(lecture);
		}

		Map<Long, Integer> columnOf = new HashMap<>();
		for (int i = 0; i < columns.size(); i++) {
			columnOf.put(columns.get(i).lectureID, i);
		}

		Map<Long, Row> byStudent = new LinkedHashMap<>();

		for (LectureMark mark : marks) {

			Integer column = columnOf.get(mark.lectureID);
			if (column == null) {
				continue;
			}

			Row row = byStudent.get(mark.studentID);

			if (row == null) {
				row = new Row(mark.rollNo, NameFormat.properCase(mark.name), columns.size());
				byStudent.put(mark.studentID, row);
			} else if (row.rollNo.isEmpty()) {
				// Lectures on one day almost always share a session, but a student can lack a
				// roll number in one lecture's session and have it in another's; the one that
				// exists is the one worth showing.
				row.rollNo = mark.rollNo;
			}

			row.marks[column] = mark.present;
		}

		List<Row> rows = new ArrayList<>(byStudent.values());
		rows.sort(Comparator.<Row, Boolean>comparing(r -> r.rollNo.isEmpty())
				.thenComparing(r -> r.rollNo)
				.thenComparing(r -> r.name));

		return new Sheet(columns, unmarked, rows);
	}

	/**
	 * A lecture's column heading: course, date, time and batch.
	 * <p>
	 * Carries the date as well as the time even though a day export shares one date across
	 * every column, because a CSV has no heading block to carry it: the column has to say which
	 * lecture it is on its own, or the file means nothing once it has been renamed.
	 */
	@NonNull
	static String columnLabel(@NonNull LectureDetails lecture) {
		return lecture.courseCode
				+ " · " + lecture.startDateTime.format(DAY)
				+ " · " + timeRange(lecture)
				+ (lecture.batchName == null ? "" : " · " + lecture.batchName);
	}

	@NonNull
	private static String timeRange(@NonNull LectureDetails lecture) {
		return lecture.startDateTime.format(TIME) + "–" + lecture.endDateTime.format(TIME);
	}

	@NonNull
	private static List<String> dayHeading(@NonNull LocalDate day, @NonNull Sheet sheet) {

		List<String> heading = new ArrayList<>();
		heading.add("Attendance for " + day.format(DAY));
		heading.add(PRESENT + " present · " + ABSENT + " absent · "
				+ "blank: not on that lecture's sheet");

		if (!sheet.unmarked.isEmpty()) {
			List<String> names = new ArrayList<>();
			for (LectureDetails lecture : sheet.unmarked) {
				names.add(lecture.courseCode + " " + timeRange(lecture));
			}
			heading.add("Not marked yet, so left out: " + String.join(", ", names));
		}
		return heading;
	}

	@NonNull
	private static List<String> lectureHeading(@NonNull LectureDetails lecture) {

		List<String> heading = new ArrayList<>();
		heading.add(lecture.courseCode + " — " + lecture.courseTopic);
		heading.add(lecture.startDateTime.format(DAY) + " · " + timeRange(lecture) + " · "
				+ (lecture.batchName == null ? "Whole class" : lecture.batchName));

		if (lecture.notes != null && !lecture.notes.trim().isEmpty()) {
			heading.add("Notes: " + lecture.notes.trim());
		}

		heading.add(PRESENT + " present · " + ABSENT + " absent");
		return heading;
	}

	// ── Spreadsheet ──────────────────────────────────────────────────────

	/**
	 * The heading block, the table, then a present and an absent total under each lecture.
	 * <p>
	 * The totals are real numbers rather than text, so the file can go on being worked with.
	 * The marks stay text either way; highlighting only adds a style to them, so a sheet
	 * exported without it holds exactly the same values.
	 */
	static void writeOds(@NonNull Sheet sheet, boolean highlight, @NonNull OutputStream out)
			throws IOException {

		OdsWriter ods = new OdsWriter(sheet.name);
		ods.defineStyle(STYLE_TITLE, null, null, true);
		ods.defineStyle(STYLE_HEADER, "#D9E1F2", null, true);

		if (highlight) {
			ods.defineStyle(STYLE_PRESENT, "#C6EFCE", "#006100", true);
			ods.defineStyle(STYLE_ABSENT, "#FFC7CE", "#9C0006", true);
		}

		for (int i = 0; i < sheet.heading.size(); i++) {
			ods.addRow(Collections.singletonList(OdsWriter.Cell.text(sheet.heading.get(i),
					i == 0 ? STYLE_TITLE : null)));
		}
		ods.addBlankRow();

		List<OdsWriter.Cell> header = new ArrayList<>();
		for (String label : headerLabels(sheet)) {
			header.add(OdsWriter.Cell.text(label, STYLE_HEADER));
		}
		ods.addRow(header);

		for (Row row : sheet.rows) {

			List<OdsWriter.Cell> cells = new ArrayList<>();
			cells.add(OdsWriter.Cell.text(row.rollNo, null));
			cells.add(OdsWriter.Cell.text(row.name, null));

			for (Boolean mark : row.marks) {
				if (mark == null) {
					cells.add(OdsWriter.Cell.empty());
				} else if (mark) {
					cells.add(OdsWriter.Cell.text(PRESENT, highlight ? STYLE_PRESENT : null));
				} else {
					cells.add(OdsWriter.Cell.text(ABSENT, highlight ? STYLE_ABSENT : null));
				}
			}

			if (sheet.perStudentTotals()) {
				cells.add(OdsWriter.Cell.number(row.count(true), null));
				cells.add(OdsWriter.Cell.number(row.count(false), null));
			}
			ods.addRow(cells);
		}

		ods.addBlankRow();
		ods.addRow(totalsRow(sheet, true));
		ods.addRow(totalsRow(sheet, false));

		ods.writeTo(out);
	}

	@NonNull
	private static List<OdsWriter.Cell> totalsRow(@NonNull Sheet sheet, boolean present) {

		List<OdsWriter.Cell> cells = new ArrayList<>();
		cells.add(OdsWriter.Cell.empty());
		cells.add(OdsWriter.Cell.text(present ? "Present" : "Absent", STYLE_HEADER));

		for (int column = 0; column < sheet.columns.size(); column++) {
			cells.add(OdsWriter.Cell.number(sheet.countInColumn(column, present), null));
		}
		return cells;
	}

	// ── CSV ──────────────────────────────────────────────────────────────

	/**
	 * The bare table: a header row, then one row per student. No heading block and no totals
	 * rows, since either would be read as data by anything that loads the file.
	 * <p>
	 * UTF-8 with a byte-order mark, which is what makes Excel read a name in Devanagari as
	 * Devanagari rather than as mojibake; this app's own CSV readers already skip one. Lines end
	 * in CRLF and fields are quoted where they need to be, as RFC 4180 has it.
	 */
	static void writeCsv(@NonNull Sheet sheet, @NonNull OutputStream out) throws IOException {

		// Not closed: the caller owns the stream. Flushed at the end instead.
		Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
		writer.write('﻿');

		List<String> header = new ArrayList<>();
		for (String label : headerLabels(sheet)) {
			header.add(csvText(label));
		}
		writeCsvLine(writer, header);

		for (Row row : sheet.rows) {

			List<String> fields = new ArrayList<>();
			fields.add(csvText(row.rollNo));
			fields.add(csvText(row.name));

			for (Boolean mark : row.marks) {
				fields.add(mark == null ? "" : mark ? PRESENT : ABSENT);
			}

			if (sheet.perStudentTotals()) {
				fields.add(String.valueOf(row.count(true)));
				fields.add(String.valueOf(row.count(false)));
			}
			writeCsvLine(writer, fields);
		}

		writer.flush();
	}

	private static void writeCsvLine(@NonNull Writer writer, @NonNull List<String> fields)
			throws IOException {
		writer.write(String.join(",", fields));
		writer.write("\r\n");
	}

	/**
	 * A text field made safe for a CSV: defused if a spreadsheet would run it as a formula,
	 * then quoted if it holds a comma, a quote or a line break.
	 * <p>
	 * The defusing prefix is an apostrophe, which is what a spreadsheet user would type to force
	 * the same cell to text. It changes what the cell holds, which is the price of not handing
	 * someone a file that executes when opened.
	 */
	@NonNull
	static String csvText(@NonNull String raw) {

		String value = raw;

		if (!value.isEmpty() && FORMULA_TRIGGERS.indexOf(value.charAt(0)) >= 0) {
			value = "'" + value;
		}

		if (value.contains(",") || value.contains("\"")
				|| value.contains("\n") || value.contains("\r")) {
			value = "\"" + value.replace("\"", "\"\"") + "\"";
		}
		return value;
	}

	// ── Shared ───────────────────────────────────────────────────────────

	@NonNull
	private static List<String> headerLabels(@NonNull Sheet sheet) {

		List<String> labels = new ArrayList<>();
		labels.add("Roll no.");
		labels.add("Name");

		for (LectureDetails lecture : sheet.columns) {
			labels.add(columnLabel(lecture));
		}

		if (sheet.perStudentTotals()) {
			labels.add("Present");
			labels.add("Absent");
		}
		return labels;
	}

	/** For the save dialog: {@code attendance-2026-09-19.ods}. */
	@NonNull
	public static String dayFileName(@NonNull LocalDate day, @NonNull Format format) {
		return "attendance-" + day.format(FILE_DAY) + "." + format.extension;
	}

	/**
	 * For the save dialog: {@code attendance-CS201-2026-09-19-1000.csv}. The time is in the
	 * name because the same course is often taught twice in a day, once per batch.
	 */
	@NonNull
	public static String lectureFileName(@NonNull LectureDetails lecture,
	                                     @NonNull Format format) {
		return "attendance-" + lecture.courseCode.replaceAll("[^A-Za-z0-9_-]", "-")
				+ "-" + lecture.startDateTime.format(FILE_DAY)
				+ "-" + lecture.startDateTime.format(FILE_TIME)
				+ "." + format.extension;
	}
}
