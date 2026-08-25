package in.basulabs.siesattendance.backend.util;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import in.basulabs.siesattendance.backend.EntityAcademicYear;
import in.basulabs.siesattendance.backend.MainDatabase;
import in.basulabs.siesattendance.backend.pojo.ExportRow;

/**
 * Exports the attendance register as a spreadsheet.
 * <p>
 * One row per student, one pair of columns per course (lectures held, lectures attended), then
 * an overall total and percentage. Students below the threshold are shaded.
 * <p>
 * The sheet deliberately carries held and attended side by side rather than percentages alone.
 * A percentage on its own hides how much it rests on: 2 out of 3 and 40 out of 60 are both 67%,
 * and only one of them is worth acting on early in the term.
 * <p>
 * Everything is assembled in memory before a byte is written, because the query returns one row
 * per student per course and the sheet needs one row per student. A session of a few hundred
 * students across a few dozen courses is small enough for that to be unremarkable.
 */
public final class AttendanceExporter {

	/** Shading for a student below the threshold. */
	private static final String STYLE_DEFAULTER = "ceDefaulter";

	/** The course code row and the Held/Attended row beneath it. */
	private static final String STYLE_HEADER = "ceHeader";

	private static final String STYLE_SUBHEADER = "ceSubHeader";

	/** The heading block above the table: title, scope and threshold note. */
	private static final String STYLE_TITLE = "ceTitle";

	/**
	 * Passed as the threshold to turn shading off entirely.
	 * <p>
	 * A real threshold of 0 would not do: 0% is a value a student can genuinely have, and
	 * shading nobody is a different intent from shading everyone who has attended nothing.
	 */
	public static final int NO_THRESHOLD = -1;

	private AttendanceExporter() {
	}

	/** Whether a threshold was asked for at all, as opposed to one that happens to be low. */
	private static boolean shadingOn(int threshold) {
		return threshold != NO_THRESHOLD;
	}

	/** What the export produced, for the message shown afterwards. */
	public static class Summary {

		/** Students who appeared on the main sheet. */
		public final int students;

		/** Courses that became column pairs. */
		public final int courses;

		/**
		 * Students shaded on the main sheet. Counts the overall figures only: a student may be
		 * short in one group and not overall, and would not be counted here.
		 */
		public final int defaulters;

		Summary(int students, int courses, int defaulters) {
			this.students = students;
			this.courses = courses;
			this.defaulters = defaulters;
		}
	}

	/**
	 * Writes the workbook for one academic year to {@code destination}.
	 * <p>
	 * The first sheet carries every course as its own pair of columns. Each group adds a
	 * further sheet totalling only its courses, with its own defaulter list, because a group's
	 * percentage is not derivable from the overall one.
	 * <p>
	 * Blocks; call off the main thread.
	 *
	 * @param thresholdPercent a student below this is a defaulter, or {@link #NO_THRESHOLD} to
	 *                         shade nobody
	 * @param groups           may be empty, in which case only the overall sheet is written
	 * @param filter           narrows the dates, courses and students covered; an unrestricted
	 *                         filter exports the whole session
	 */
	@NonNull
	public static Summary export(@NonNull Context context, @NonNull Uri destination,
	                             int academicYear, int thresholdPercent,
	                             @NonNull List<CourseGroup> groups,
	                             @NonNull ExportFilter filter) throws IOException {

		List<ExportRow> rows = MainDatabase.getInstance(context).daoAttendance()
				.getExportRowsSync(ExportQueryBuilder.build(academicYear, filter));

		// Courses become column pairs and students become rows. The query orders by roll
		// number first and course code second, so a map filled as the rows arrive records
		// each course at the point the first student who takes it appears, not in code
		// order: a course only a later student takes would land after courses whose codes
		// sort above it. The columns are put back into code order afterwards.
		Map<Long, String> encountered = new LinkedHashMap<>();
		Map<Long, Student> students = new LinkedHashMap<>();

		for (ExportRow row : rows) {
			encountered.put(row.courseID, row.courseCode);

			Student student = students.get(row.studentID);

			if (student == null) {
				student = new Student(row.rollNo, row.studentName);
				students.put(row.studentID, student);
			}
			student.figures.put(row.courseID, new int[]{row.held, row.attended});
		}

		Map<Long, String> courseColumns = byCourseCode(encountered);

		OdsWriter ods = new OdsWriter("Attendance " + academicYear);
		ods.defineStyle(STYLE_TITLE, null, null, true);
		ods.defineStyle(STYLE_HEADER, "#D9E1F2", null, true);
		ods.defineStyle(STYLE_SUBHEADER, "#EDF2FA", null, true);
		ods.defineStyle(STYLE_DEFAULTER, "#FFC7CE", "#9C0006", false);

		writeTitle(ods, academicYear, thresholdPercent, null, filter);
		writeHeaders(ods, courseColumns);

		int defaulters = 0;

		for (Student student : students.values()) {
			if (writeStudent(ods, student, courseColumns, thresholdPercent)) {
				defaulters++;
			}
		}

		for (CourseGroup group : groups) {
			if (group.isUsable()) {
				writeGroupSheet(ods, group, academicYear, thresholdPercent,
						courseColumns, students.values(), filter);
			}
		}

		try (OutputStream out = context.getContentResolver()
				.openOutputStream(destination, "wt")) {

			if (out == null) {
				throw new IOException("Could not open the chosen file.");
			}
			ods.writeTo(out);
		}

		return new Summary(students.size(), courseColumns.size(), defaulters);
	}

	/**
	 * The heading block that sits above every table: what the sheet covers, and what the shading
	 * means.
	 *
	 * @param group null for the main sheet, otherwise the group whose sheet is being written
	 */
	private static void writeTitle(@NonNull OdsWriter ods, int academicYear, int threshold,
	                               @Nullable CourseGroup group, @NonNull ExportFilter filter) {

		String heading = "Attendance register "
				+ EntityAcademicYear.labelFor(academicYear);

		if (group != null) {
			heading = group.name + " \u2013 " + heading;
		}

		List<OdsWriter.Cell> title = new ArrayList<>();
		title.add(OdsWriter.Cell.text(heading, STYLE_TITLE));
		ods.addRow(title);

		if (group != null) {
			List<OdsWriter.Cell> courses = new ArrayList<>();
			courses.add(OdsWriter.Cell.text("Courses: " + group.describeCourses(), null));
			ods.addRow(courses);
		}

		// A filtered sheet says so on its face. Someone reading it later has no other way to
		// tell a partial export from a complete one, and would take the figures for the whole
		// session.
		List<OdsWriter.Cell> scope = new ArrayList<>();
		scope.add(OdsWriter.Cell.text(filter.describe(), null));
		ods.addRow(scope);

		List<OdsWriter.Cell> note = new ArrayList<>();
		note.add(OdsWriter.Cell.text(shadingOn(threshold)
				? "Shaded rows are below " + threshold + "%"
				: "No defaulter threshold applied", null));
		ods.addRow(note);

		ods.addBlankRow();
	}

	/**
	 * A sheet for one group: the group's own courses, its own total, and its own defaulters.
	 * <p>
	 * Students who take none of the group's courses are left out entirely rather than listed
	 * with blanks, since a defaulter list is only meaningful for people the rule applies to.
	 */
	private static void writeGroupSheet(@NonNull OdsWriter ods, @NonNull CourseGroup group,
	                                    int academicYear, int threshold,
	                                    @NonNull Map<Long, String> allCourses,
	                                    @NonNull Collection<Student> students,
	                                    @NonNull ExportFilter filter) {

		ods.startSheet(group.name);
		writeTitle(ods, academicYear, threshold, group, filter);

		// Only the group's courses become columns, in the order they appear overall so the
		// group sheet reads in the same order as the main one.
		Map<Long, String> columns = new LinkedHashMap<>();

		for (Map.Entry<Long, String> entry : allCourses.entrySet()) {
			if (group.courseCodes.contains(entry.getValue())) {
				columns.put(entry.getKey(), entry.getValue());
			}
		}

		writeHeaders(ods, columns);

		for (Student student : students) {

			boolean takesAny = false;
			for (Long courseID : columns.keySet()) {
				if (student.figures.containsKey(courseID)) {
					takesAny = true;
					break;
				}
			}

			if (takesAny) {
				writeStudent(ods, student, columns, threshold);
			}
		}
	}

	/**
	 * Two header rows: the course code spanning its pair of columns, then Held and Attended
	 * beneath. Without the second row a reader has no way to tell the two numbers apart.
	 */
	private static void writeHeaders(@NonNull OdsWriter ods,
	                                 @NonNull Map<Long, String> courseColumns) {

		List<OdsWriter.Cell> top = new ArrayList<>();
		top.add(OdsWriter.Cell.text("Roll no.", STYLE_HEADER));
		top.add(OdsWriter.Cell.text("Name", STYLE_HEADER));

		for (String code : courseColumns.values()) {
			top.add(OdsWriter.Cell.text(code, STYLE_HEADER));
			top.add(OdsWriter.Cell.empty());
		}

		top.add(OdsWriter.Cell.text("Overall", STYLE_HEADER));
		top.add(OdsWriter.Cell.empty());
		top.add(OdsWriter.Cell.empty());
		ods.addRow(top);

		List<OdsWriter.Cell> sub = new ArrayList<>();
		sub.add(OdsWriter.Cell.empty());
		sub.add(OdsWriter.Cell.empty());

		for (int i = 0; i < courseColumns.size(); i++) {
			sub.add(OdsWriter.Cell.text("Held", STYLE_SUBHEADER));
			sub.add(OdsWriter.Cell.text("Attended", STYLE_SUBHEADER));
		}

		sub.add(OdsWriter.Cell.text("Held", STYLE_SUBHEADER));
		sub.add(OdsWriter.Cell.text("Attended", STYLE_SUBHEADER));
		sub.add(OdsWriter.Cell.text("%", STYLE_SUBHEADER));
		ods.addRow(sub);
	}

	/**
	 * Writes one student's row and works out whether they fall short.
	 * <p>
	 * The totals are taken over the columns on this sheet alone, which is what makes a group
	 * sheet report the group's percentage rather than the overall one.
	 *
	 * @return true if this student is a defaulter on this sheet
	 */
	private static boolean writeStudent(@NonNull OdsWriter ods, @NonNull Student student,
	                                    @NonNull Map<Long, String> courseColumns,
	                                    int thresholdPercent) {

		// Totalled over the columns on this sheet, not over everything the student takes, so a
		// group sheet reports the group's percentage rather than the overall one.
		int totalHeld = 0;
		int totalAttended = 0;

		for (Long courseID : courseColumns.keySet()) {
			int[] figures = student.figures.get(courseID);
			if (figures != null) {
				totalHeld += figures[0];
				totalAttended += figures[1];
			}
		}

		// A student with no lectures held yet has no percentage at all, and inventing one
		// either way misreports them: 0% would shade the whole sheet red at the start of term,
		// and 100% would print a perfect attender who has never been counted. The cell is left
		// blank instead, which is what the app's own screens show for this case, and the
		// defaulter test is guarded on its own rather than on a stand-in value.
		boolean anyHeld = totalHeld > 0;
		double percent = anyHeld ? (100.0 * totalAttended) / totalHeld : 0d;
		boolean defaulter = shadingOn(thresholdPercent)
				&& anyHeld && percent < thresholdPercent;

		String style = defaulter ? STYLE_DEFAULTER : null;

		List<OdsWriter.Cell> row = new ArrayList<>();
		row.add(OdsWriter.Cell.text(student.rollNo, style));
		row.add(OdsWriter.Cell.text(student.name, style));

		for (Long courseID : courseColumns.keySet()) {
			int[] figures = student.figures.get(courseID);

			if (figures == null) {
				// The student does not take this course. Left blank rather than zeroed, so a
				// course they never sat cannot be mistaken for one they skipped entirely.
				row.add(OdsWriter.Cell.empty());
				row.add(OdsWriter.Cell.empty());
			} else {
				row.add(OdsWriter.Cell.number(figures[0], style));
				row.add(OdsWriter.Cell.number(figures[1], style));
			}
		}

		row.add(OdsWriter.Cell.number(totalHeld, style));
		row.add(OdsWriter.Cell.number(totalAttended, style));
		row.add(anyHeld
				? OdsWriter.Cell.number(Math.round(percent * 10) / 10.0, style)
				: OdsWriter.Cell.text("", style));

		ods.addRow(row);
		return defaulter;
	}

	/**
	 * The same course map ordered by course code, which is the order a reader scans the sheet
	 * in and the order the column headings are expected to run in.
	 */
	@NonNull
	private static Map<Long, String> byCourseCode(@NonNull Map<Long, String> courses) {

		List<Map.Entry<Long, String>> entries = new ArrayList<>(courses.entrySet());
		entries.sort((a, b) -> a.getValue().compareToIgnoreCase(b.getValue()));

		Map<Long, String> ordered = new LinkedHashMap<>();
		for (Map.Entry<Long, String> entry : entries) {
			ordered.put(entry.getKey(), entry.getValue());
		}
		return ordered;
	}

	/** A student's row while it is being assembled, before the courses become columns. */
	private static class Student {

		final String rollNo;

		final String name;

		/**
		 * Course ID to a two-element array of held and attended. An absent key means the student
		 * does not take the course, which is written as a blank pair rather than as zeroes.
		 */
		final Map<Long, int[]> figures = new LinkedHashMap<>();

		Student(String rollNo, String name) {
			this.rollNo = rollNo;
			this.name = name;
		}
	}

	/** The default name offered in the save dialog, for instance "attendance-2025-26.ods". */
	@NonNull
	public static String suggestedFileName(int academicYear) {
		return "attendance-" + EntityAcademicYear.labelFor(academicYear) + ".ods";
	}
}
