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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import in.basulabs.siesattendance.backend.EntityAcademicYear;
import in.basulabs.siesattendance.backend.EntityAttendance;
import in.basulabs.siesattendance.backend.EntityBatchDesc;
import in.basulabs.siesattendance.backend.EntityCourse;
import in.basulabs.siesattendance.backend.EntityLecture;
import in.basulabs.siesattendance.backend.MainDatabase;
import in.basulabs.siesattendance.backend.pojo.StudentWithYear;

/**
 * Turns an imported attendance sheet into lectures and attendance rows.
 * <p>
 * The work is deliberately in two halves. {@link #plan} reads the file and resolves every part
 * of it — course codes to courses, roll numbers to students, and the pattern of marks to the
 * batch that must have sat each session — without writing anything. {@link #commit} then writes
 * the plan it was given. Between the two the user sees exactly what is about to happen and
 * agrees to it, which matters more here than for the roster import: this creates lectures, and a
 * lecture filed against the wrong group quietly changes the attendance percentage of everybody
 * in the year. See {@link BatchMatcher} for why.
 * <p>
 * A column that cannot be resolved is not fatal. Its problems are recorded and it is left out of
 * the write, while every other column still goes in; the review screen shows both. Only a file
 * that cannot be read as a grid at all stops everything, which is {@link Plan#errors}.
 * <p>
 * The write, by contrast, is all or nothing. The user has already seen and approved a specific
 * list of sessions, so a half-finished import that they would then have to reconcile against
 * that list is worse than none: {@link #commit} runs in a single transaction.
 */
public final class AttendanceRecordImporter {

	/**
	 * What one column of the file will become, once resolved.
	 * <p>
	 * Filled in progressively as the column is worked through rather than built complete, so
	 * the fields are not final: a column that fails at the course lookup still has to be
	 * presentable, showing what was read and why it stopped.
	 */
	public static final class LecturePlan {

		/** Position in the file, counting from one, for the review screen and for messages. */
		public final int column;

		@NonNull
		public String label;

		@Nullable
		public String courseCode;

		@Nullable
		public String courseTopic;

		public long courseID;

		@Nullable
		public ZonedDateTime start;

		@Nullable
		public ZonedDateTime end;

		/** The session the date falls in, or 0 when it falls outside every defined session. */
		public int academicYear;

		/**
		 * The batch the marks point at. Null only on a column that could not be resolved: an
		 * import never files a session against the whole class, so every usable plan names a
		 * batch. See {@link BatchMatcher}.
		 */
		@Nullable
		public Long batchID;

		/** The chosen batch's name. Set whenever {@link #batchID} is. */
		@Nullable
		public String batchName;

		public int present;

		public int absent;

		/**
		 * Members of the chosen batch with no mark in this column: the people the session was
		 * not held for. No attendance row is written for them, which is the whole point of the
		 * empty cell.
		 */
		public int notInClass;

		/**
		 * Marks that will not be written because the student is not in the chosen batch.
		 * <p>
		 * Writing them anyway would give the student attendance for a lecture that does not
		 * count among the lectures held for them, and a percentage over a hundred. Non-zero
		 * here means the file describes a group no batch matches — commonly a class taught to
		 * everybody, which now has to be described by a batch that holds everybody — and is
		 * reported rather than silently dropped.
		 */
		public int dropped;

		/** True when a lecture for this course, batch and time span is already on record. */
		public boolean replacesExisting;

		/** Why this column will be skipped. Empty means it will be imported. */
		public final List<String> problems = new ArrayList<>();

		/** Things worth saying about a column that will still be imported. */
		public final List<String> notes = new ArrayList<>();

		/** Student to presence, in the order the file listed them. */
		final LinkedHashMap<Long, Boolean> marks = new LinkedHashMap<>();

		LecturePlan(int column, @NonNull String label) {
			this.column = column;
			this.label = label;
		}

		public boolean isUsable() {
			return problems.isEmpty();
		}

		/** How many attendance rows this column will write. */
		public int markCount() {
			return present + absent;
		}
	}

	/** Everything the file amounts to, ready to be shown and then written. */
	public static final class Plan {

		public final List<LecturePlan> lectures = new ArrayList<>();

		/**
		 * Problems with the file as a whole. A non-empty list means nothing can be imported,
		 * however many lectures happen to be listed above.
		 */
		public final List<String> errors = new ArrayList<>();

		/** Things the user should see but which do not stop the import, such as unknown roll numbers. */
		public final List<String> warnings = new ArrayList<>();

		public boolean canImport() {
			return errors.isEmpty() && usableCount() > 0;
		}

		public int usableCount() {

			int usable = 0;

			for (LecturePlan lecture : lectures) {
				if (lecture.isUsable()) {
					usable++;
				}
			}
			return usable;
		}

		public int skippedCount() {
			return lectures.size() - usableCount();
		}

		/** Attendance rows the import would write, across every usable column. */
		public int markCount() {

			int marks = 0;

			for (LecturePlan lecture : lectures) {
				if (lecture.isUsable()) {
					marks += lecture.markCount();
				}
			}
			return marks;
		}

		/** Columns that will overwrite a sheet already on record rather than add a new lecture. */
		public int replacedCount() {

			int replaced = 0;

			for (LecturePlan lecture : lectures) {
				if (lecture.isUsable() && lecture.replacesExisting) {
					replaced++;
				}
			}
			return replaced;
		}

		/** The sessions the file touches, for the confirmation to name. */
		@NonNull
		public Set<Integer> academicYears() {

			Set<Integer> years = new LinkedHashSet<>();

			for (LecturePlan lecture : lectures) {
				if (lecture.isUsable()) {
					years.add(lecture.academicYear);
				}
			}
			return years;
		}
	}

	/** What the write actually did. */
	public static final class Result {

		public int created;

		public int replaced;

		public int marks;

		/** Columns left out because the plan had already found a problem with them. */
		public int skipped;

		/** Set when the transaction failed, in which case nothing at all was written. */
		@Nullable
		public String error;

		public boolean isFailure() {
			return error != null;
		}
	}

	private AttendanceRecordImporter() {
	}

	// ── Planning ─────────────────────────────────────────────────────────

	/**
	 * Reads the file and works out what it would create, without writing anything.
	 * <p>
	 * Blocks on file and database reads; call off the main thread.
	 */
	@NonNull
	public static Plan plan(@NonNull Context context, @NonNull Uri uri) {

		Plan plan = new Plan();

		List<String> lines;

		try {
			lines = readLines(context, uri);
		} catch (IOException e) {
			plan.errors.add("Could not read the file: " + e.getMessage());
			return plan;
		}

		AttendanceCsvParser.Sheet sheet = AttendanceCsvParser.parse(lines);

		plan.errors.addAll(sheet.errors);

		if (!plan.errors.isEmpty()) {
			return plan;
		}

		Lookups lookups = new Lookups(MainDatabase.getInstance(context));

		// Sessions already claimed by a column of this file, so a sheet that lists the same
		// class twice writes it once rather than having the second silently overwrite the
		// first's marks.
		Map<String, Integer> claimed = new HashMap<>();

		for (int index = 0; index < sheet.columns.size(); index++) {
			plan.lectures.add(resolve(sheet, index, lookups, claimed));
		}

		reportUnknownRollNumbers(plan, lookups);

		return plan;
	}

	/**
	 * Resolves one column into the lecture it describes.
	 * <p>
	 * Each step is guarded and returns early, because every later step depends on the one
	 * before: there is no useful batch to work out for a course that does not exist.
	 */
	@NonNull
	private static LecturePlan resolve(@NonNull AttendanceCsvParser.Sheet sheet, int index,
	                                   @NonNull Lookups lookups,
	                                   @NonNull Map<String, Integer> claimed) {

		AttendanceCsvParser.Column column = sheet.columns.get(index);

		LecturePlan lecture = new LecturePlan(column.index, column.describe());
		lecture.courseCode = column.courseCode;

		if (!column.isUsable()) {
			lecture.problems.addAll(column.problems);
			return lecture;
		}

		EntityCourse course = lookups.course(column.courseCode);

		if (course == null) {
			lecture.problems.add("No course has the code " + column.courseCode + ".");
			return lecture;
		}

		lecture.courseID = course.courseID;
		lecture.courseCode = course.courseCode;
		lecture.courseTopic = course.courseTopic;

		ZoneId zone = ZoneId.systemDefault();
		lecture.start = column.date.atTime(column.start).atZone(zone);
		lecture.end = column.date.atTime(column.end).atZone(zone);
		lecture.label = course.courseCode + " · " + column.date + " "
				+ column.start + "–" + column.end;

		EntityAcademicYear session =
				lookups.sessionAt(lecture.start.toEpochSecond());

		if (session == null) {
			// A lecture outside every session joins to none of them and vanishes from every
			// report, which is the condition DaoAcademicYear.countOrphanLectures exists to
			// surface. Better to refuse the column than to create one deliberately.
			lecture.problems.add("This date is not inside any academic session. Add or extend "
					+ "a session that covers it, then import again.");
			return lecture;
		}

		lecture.academicYear = session.academicYear;

		Set<Long> marked = markedStudents(sheet, index, lookups, session.academicYear);

		if (marked.isEmpty()) {
			lecture.problems.add("Nothing is marked in this column.");
			return lecture;
		}

		String sessionLabel = EntityAcademicYear.labelFor(session.academicYear);

		BatchMatcher.Choice choice = BatchMatcher.choose(marked,
				lookups.candidates(course, session.academicYear));

		if (choice == null) {
			// Every imported session is filed against a batch, so a session with none defined
			// has nowhere to put this class at all.
			lecture.problems.add("There are no batches in " + sessionLabel + ". Create the "
					+ "batch this class was held for, then import again.");
			return lecture;
		}

		if (choice.matched == 0) {
			lecture.problems.add("None of the students marked here belong to any batch in "
					+ sessionLabel + ".");
			return lecture;
		}

		lecture.batchID = choice.candidate.batchID;
		lecture.batchName = choice.candidate.name;
		lecture.notInClass = choice.extra;
		lecture.dropped = choice.missing;

		if (choice.missing > 0) {
			lecture.notes.add(choice.missing + " mark(s) will not be imported: those students "
					+ "are not in " + choice.candidate.name + ". Add them to it, or make a "
					+ "batch that holds everyone who was in this class, and import again.");
		}

		fillMarks(lecture, sheet, index, lookups, session.academicYear,
				choice.candidate.members);

		String key = lecture.courseID + "|" + lecture.batchID + "|"
				+ lecture.start.toEpochSecond() + "|" + lecture.end.toEpochSecond();

		Integer earlier = claimed.putIfAbsent(key, column.index);

		if (earlier != null) {
			lecture.problems.add("The same session is already column " + earlier + ".");
			return lecture;
		}

		lecture.replacesExisting = lookups.db().daoLecture().findExisting(lecture.courseID,
				lecture.batchID, lecture.start, lecture.end) != null;

		return lecture;
	}

	/**
	 * The students with a P or an AB in this column, as IDs.
	 * <p>
	 * A roll number that resolves to nobody contributes nothing here and is collected for the
	 * warning instead, so an unknown student cannot influence which batch is chosen.
	 */
	@NonNull
	private static Set<Long> markedStudents(@NonNull AttendanceCsvParser.Sheet sheet, int index,
	                                        @NonNull Lookups lookups, int academicYear) {

		Set<Long> marked = new LinkedHashSet<>();

		for (AttendanceCsvParser.Row row : sheet.rows) {

			if (row.marks[index] == AttendanceCsvParser.Mark.NOT_IN_CLASS) {
				continue;
			}

			Long studentID = lookups.student(row.rollNo, academicYear);

			if (studentID != null) {
				marked.add(studentID);
			}
		}
		return marked;
	}

	/** Records the marks that will actually be written, and counts what each cell became. */
	private static void fillMarks(@NonNull LecturePlan lecture,
	                              @NonNull AttendanceCsvParser.Sheet sheet, int index,
	                              @NonNull Lookups lookups, int academicYear,
	                              @NonNull Set<Long> roll) {

		for (AttendanceCsvParser.Row row : sheet.rows) {

			AttendanceCsvParser.Mark mark = row.marks[index];

			if (mark == AttendanceCsvParser.Mark.NOT_IN_CLASS) {
				continue;
			}

			Long studentID = lookups.student(row.rollNo, academicYear);

			if (studentID == null || !roll.contains(studentID)) {
				continue;
			}

			boolean present = mark == AttendanceCsvParser.Mark.PRESENT;
			lecture.marks.put(studentID, present);

			if (present) {
				lecture.present++;
			} else {
				lecture.absent++;
			}
		}
	}

	/**
	 * One warning per session naming the roll numbers nothing matched.
	 * <p>
	 * Gathered across the whole file rather than reported per column, since the same unknown
	 * student appears in every one of them and forty copies of the same line would bury the
	 * columns that actually failed.
	 */
	private static void reportUnknownRollNumbers(@NonNull Plan plan, @NonNull Lookups lookups) {

		for (Map.Entry<Integer, Set<String>> entry : lookups.unknownRollNumbers().entrySet()) {

			Set<String> unknown = entry.getValue();

			if (unknown.isEmpty()) {
				continue;
			}

			StringBuilder out = new StringBuilder();
			int shown = 0;

			for (String rollNo : unknown) {
				if (shown == 8) {
					out.append(", and ").append(unknown.size() - shown).append(" more");
					break;
				}
				if (shown > 0) {
					out.append(", ");
				}
				out.append(rollNo);
				shown++;
			}

			plan.warnings.add(unknown.size() + " roll number(s) are not enrolled in "
					+ EntityAcademicYear.labelFor(entry.getKey())
					+ " and were ignored: " + out + ".");
		}
	}

	// ── Writing ──────────────────────────────────────────────────────────

	/**
	 * Writes every usable column of a plan the user has approved.
	 * <p>
	 * Re-recording a session that is already on record replaces its sheet rather than failing on
	 * the unique index, which is what {@code recordLectureWithAttendance} does when attendance is
	 * taken twice for the same class, and what someone correcting a register expects.
	 * <p>
	 * Blocks; call off the main thread.
	 */
	@NonNull
	public static Result commit(@NonNull Context context, @NonNull Plan plan) {

		Result result = new Result();
		result.skipped = plan.skippedCount();

		MainDatabase db = MainDatabase.getInstance(context);

		try {
			db.runInTransaction(() -> {

				for (LecturePlan lecture : plan.lectures) {

					if (!lecture.isUsable()) {
						continue;
					}

					Long existing = db.daoLecture().findExisting(lecture.courseID,
							lecture.batchID, lecture.start, lecture.end);

					long lectureID;

					if (existing != null) {
						lectureID = existing;
						db.daoAttendance().clearForLecture(lectureID);
						result.replaced++;
					} else {
						lectureID = db.daoLecture().insert(new EntityLecture(lecture.courseID,
								lecture.batchID, lecture.start, lecture.end, null));
						result.created++;
					}

					List<EntityAttendance> records = new ArrayList<>();

					for (Map.Entry<Long, Boolean> mark : lecture.marks.entrySet()) {
						records.add(new EntityAttendance(lectureID, mark.getKey(),
								mark.getValue()));
					}

					db.daoAttendance().insertAll(records);
					result.marks += records.size();
				}
			});
		} catch (Exception e) {
			// The transaction rolled back, so the counts gathered above describe work that no
			// longer exists. Reset them rather than report a write that did not happen.
			result.created = 0;
			result.replaced = 0;
			result.marks = 0;
			result.error = e.getMessage() == null ? e.toString() : e.getMessage();
		}

		return result;
	}

	// ── Reading the file ─────────────────────────────────────────────────

	@NonNull
	private static List<String> readLines(@NonNull Context context, @NonNull Uri uri)
			throws IOException {

		List<String> lines = new ArrayList<>();

		try (InputStream in = context.getContentResolver().openInputStream(uri)) {

			if (in == null) {
				throw new IOException("the file could not be opened");
			}

			BufferedReader reader =
					new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));

			String line;
			while ((line = reader.readLine()) != null) {
				lines.add(line);
			}
		}
		return lines;
	}

	// ── Cached database reads ────────────────────────────────────────────

	/**
	 * The database reads the planning needs, each done once.
	 * <p>
	 * A sheet with forty columns asks the same questions forty times over: the same handful of
	 * courses, the same roll for each, the same batch memberships. Without the caching a file of
	 * a term's practicals would run thousands of queries to reach the same answers.
	 */
	private static final class Lookups {

		private final MainDatabase db;

		private final Map<String, EntityCourse> courses = new HashMap<>();

		private final Map<Long, EntityAcademicYear> sessions = new HashMap<>();

		/** Session to roll number to student, the roll numbers normalised for comparison. */
		private final Map<Integer, Map<String, Long>> students = new HashMap<>();

		/** Session to the roll numbers in the file that matched nobody, as the file spelt them. */
		private final Map<Integer, Set<String>> unknown = new LinkedHashMap<>();

		/** Course and session to the candidate groups for it. */
		private final Map<String, List<BatchMatcher.Candidate>> candidates = new HashMap<>();

		Lookups(@NonNull MainDatabase db) {
			this.db = db;
		}

		@NonNull
		MainDatabase db() {
			return db;
		}

		@Nullable
		EntityCourse course(@Nullable String code) {

			if (code == null) {
				return null;
			}

			String key = code.trim().toLowerCase(Locale.ROOT);

			if (courses.containsKey(key)) {
				return courses.get(key);
			}

			EntityCourse course = db.daoCourse().findByCodeSync(code.trim());
			courses.put(key, course);
			return course;
		}

		/**
		 * The session containing an instant. Cached on the second rather than on the date,
		 * which costs a query per distinct time in the file and is still a handful.
		 */
		@Nullable
		EntityAcademicYear sessionAt(long epochSecond) {

			if (sessions.containsKey(epochSecond)) {
				return sessions.get(epochSecond);
			}

			EntityAcademicYear session = db.daoAcademicYear().findContainingSync(epochSecond);
			sessions.put(epochSecond, session);
			return session;
		}

		/**
		 * The student a roll number names in one session.
		 * <p>
		 * Matched with case and separators ignored, because the roll number in an imported file
		 * was typed or pasted by a person and "2024ph001" is the same student as "2024PH001".
		 * The whole session's enrolment is read once and indexed, rather than a query per roll
		 * number.
		 */
		@Nullable
		Long student(@NonNull String rollNo, int academicYear) {

			Map<String, Long> index = students.get(academicYear);

			if (index == null) {
				index = new HashMap<>();

				for (StudentWithYear student : db.daoStudent().filterStudentsSync(
						academicYear, null, null, null, null, null, null, null, "")) {
					index.put(normalise(student.rollNo), student.studentID);
				}
				students.put(academicYear, index);
			}

			Long studentID = index.get(normalise(rollNo));

			if (studentID == null) {
				unknown.computeIfAbsent(academicYear, year -> new LinkedHashSet<>())
						.add(rollNo);
			}
			return studentID;
		}

		@NonNull
		Map<Integer, Set<String>> unknownRollNumbers() {
			return unknown;
		}

		/**
		 * The batches a session of this course could have been held for: every batch of the
		 * academic session, and nothing else. The course's whole class is deliberately not a
		 * candidate; see {@link BatchMatcher}.
		 * <p>
		 * Every batch, not only the linked ones, for the reason set out at the foot of
		 * {@code DaoCourse}: a batch is a group of students for a session and nothing about it
		 * belongs to one course. Being linked is used only to break a tie, which is the only
		 * thing the course is needed for here.
		 */
		@NonNull
		List<BatchMatcher.Candidate> candidates(@NonNull EntityCourse course, int academicYear) {

			String key = course.courseID + "|" + academicYear;
			List<BatchMatcher.Candidate> cached = candidates.get(key);

			if (cached != null) {
				return cached;
			}

			List<BatchMatcher.Candidate> built = new ArrayList<>();

			Set<Long> linked = new HashSet<>(
					db.daoCourse().getLinkedBatchIdsSync(course.courseID));

			for (EntityBatchDesc batch : db.daoBatch().getAllForYearSync(academicYear)) {

				Set<Long> members = BatchMatcher.setOf(
						db.daoBatch().getMemberIdsSync(batch.batchID, academicYear));

				if (members.isEmpty()) {
					// An empty batch explains nothing, and would beat every real candidate on
					// the "fewest extra students" rule if it ever covered a marked set, which
					// it cannot.
					continue;
				}

				built.add(new BatchMatcher.Candidate(batch.batchID, batch.batchName, members,
						linked.contains(batch.batchID)));
			}

			candidates.put(key, built);
			return built;
		}

		@NonNull
		private static String normalise(@NonNull String rollNo) {
			return rollNo.trim().toLowerCase(Locale.ROOT)
					.replace(" ", "").replace("-", "").replace("/", "");
		}
	}
}
