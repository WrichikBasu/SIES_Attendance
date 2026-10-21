package in.basulabs.siesattendance.server.importer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
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

import in.basulabs.siesattendance.backend.util.AttendanceCsvParser;
import in.basulabs.siesattendance.backend.util.BatchMatcher;

/**
 * An attendance sheet, turned into lectures and marks on the server.
 * <p>
 * The device's {@code AttendanceRecordImporter} on PostgreSQL. The same file format, read by
 * the same {@link AttendanceCsvParser}; the same two steps, a {@link #plan} that writes nothing
 * and a {@link #commit} that writes what the plan says; and the same decisions: a column's
 * course by its code, its session by its date, and the batch that sat it by
 * {@link BatchMatcher} from the pattern of empty cells. A sheet the phone would import one way
 * imports the same way here.
 * <p>
 * Every lookup and every write runs under the caller's actor, so the policies decide what may
 * be imported: a professor can file lectures of their own courses, a head their department's,
 * an administrator any. A column for a course the caller may not write is reported in the plan
 * rather than left to fail the whole commit.
 * <p>
 * Lectures are recorded in the college's zone, which is the zone a register on paper was kept
 * in. A phone records in its own; the two agree for every phone in the college.
 */
public final class AttendanceImporter {

	/** The zone a sheet's dates and times are read in. */
	static final ZoneId COLLEGE = ZoneId.of("Asia/Kolkata");

	/** One column of the sheet, as it will be filed. */
	public static final class Lecture {

		public final int column;
		@NonNull
		public String label;
		@Nullable
		public String courseCode;
		@Nullable
		public String courseTopic;
		long courseId;
		@Nullable
		public String start;
		@Nullable
		public String end;
		@Nullable
		ZonedDateTime startAt;
		@Nullable
		ZonedDateTime endAt;
		public int academicYear;
		@Nullable
		Long batchId;
		@Nullable
		public String batchName;
		public int present;
		public int absent;
		/** Students on the batch with an empty cell: not in this class. */
		public int notInClass;
		/** Marks for students not in the chosen batch, which are left out. */
		public int dropped;
		/** Whether a lecture with this course, batch and time is already on record. */
		public boolean replacesExisting;
		@NonNull
		public final List<String> problems = new ArrayList<>();
		@NonNull
		public final List<String> notes = new ArrayList<>();

		final LinkedHashMap<Long, Boolean> marks = new LinkedHashMap<>();

		Lecture(int column, @NonNull String label) {
			this.column = column;
			this.label = label;
		}

		public boolean isUsable() {
			return problems.isEmpty();
		}
	}

	/** Everything a sheet would do, before any of it is done. */
	public static final class Plan {

		@NonNull
		public final List<Lecture> lectures = new ArrayList<>();

		/** Problems with the whole sheet, any of which stops it. */
		@NonNull
		public final List<String> errors = new ArrayList<>();

		@NonNull
		public final List<String> warnings = new ArrayList<>();

		public boolean canImport() {
			return errors.isEmpty() && usable() > 0;
		}

		public int usable() {
			int usable = 0;
			for (Lecture lecture : lectures) {
				usable += lecture.isUsable() ? 1 : 0;
			}
			return usable;
		}

		public int marks() {
			int marks = 0;
			for (Lecture lecture : lectures) {
				marks += lecture.isUsable() ? lecture.present + lecture.absent : 0;
			}
			return marks;
		}
	}

	/** What a commit wrote. */
	public record Result(int created, int replaced, int marks, int skipped) {
	}

	private AttendanceImporter() {
	}

	// ── Planning ─────────────────────────────────────────────────────────

	@NonNull
	public static Plan plan(@NonNull Connection connection, @NonNull List<String> lines)
			throws SQLException {

		Plan plan = new Plan();
		AttendanceCsvParser.Sheet sheet = AttendanceCsvParser.parse(lines);
		plan.errors.addAll(sheet.errors);

		if (!plan.errors.isEmpty()) {
			return plan;
		}

		Lookups lookups = new Lookups(connection);
		Map<String, Integer> claimed = new HashMap<>();

		for (int index = 0; index < sheet.columns.size(); index++) {
			plan.lectures.add(resolve(sheet, index, lookups, claimed));
		}

		for (Map.Entry<Integer, Set<String>> entry : lookups.unknown.entrySet()) {
			if (!entry.getValue().isEmpty()) {
				plan.warnings.add(entry.getValue().size() + " roll number(s) are not enrolled in "
						+ session(entry.getKey()) + ", or not ones you can see, and were "
						+ "ignored: " + shortList(entry.getValue()) + ".");
			}
		}
		return plan;
	}

	@NonNull
	private static Lecture resolve(@NonNull AttendanceCsvParser.Sheet sheet, int index,
	                               @NonNull Lookups lookups,
	                               @NonNull Map<String, Integer> claimed) throws SQLException {

		AttendanceCsvParser.Column column = sheet.columns.get(index);
		Lecture lecture = new Lecture(column.index, column.describe());
		lecture.courseCode = column.courseCode;

		if (!column.isUsable()) {
			lecture.problems.addAll(column.problems);
			return lecture;
		}

		Course course = lookups.course(column.courseCode);

		if (course == null) {
			lecture.problems.add("No course you can see has the code " + column.courseCode + ".");
			return lecture;
		}

		lecture.courseId = course.id();
		lecture.courseCode = course.code();
		lecture.courseTopic = course.topic();
		lecture.startAt = column.date.atTime(column.start).atZone(COLLEGE);
		lecture.endAt = column.date.atTime(column.end).atZone(COLLEGE);
		lecture.start = lecture.startAt.toOffsetDateTime().toString();
		lecture.end = lecture.endAt.toOffsetDateTime().toString();
		lecture.label = course.code() + " · " + column.date + " " + column.start + "–"
				+ column.end;

		Integer session = lookups.sessionAt(lecture.startAt);

		if (session == null) {
			lecture.problems.add("This date is not inside any academic session.");
			return lecture;
		}
		lecture.academicYear = session;

		if (!lookups.mayRecord(course, session)) {
			lecture.problems.add("You do not teach " + course.code() + ", and it is not your "
					+ "department's, so its lectures are not yours to record.");
			return lecture;
		}

		Set<Long> marked = new LinkedHashSet<>();

		for (AttendanceCsvParser.Row row : sheet.rows) {
			if (row.marks[index] != AttendanceCsvParser.Mark.NOT_IN_CLASS) {
				Long student = lookups.student(row.rollNo, session);
				if (student != null) {
					marked.add(student);
				}
			}
		}

		if (marked.isEmpty()) {
			lecture.problems.add("Nothing is marked in this column.");
			return lecture;
		}

		BatchMatcher.Choice choice = BatchMatcher.choose(marked, lookups.candidates(course,
				session));

		if (choice == null) {
			lecture.problems.add("There are no batches in " + session(session) + ". Create the "
					+ "batch this class was held for, then import again.");
			return lecture;
		}

		if (choice.matched == 0) {
			lecture.problems.add("None of the students marked here belong to any batch in "
					+ session(session) + ".");
			return lecture;
		}

		lecture.batchId = choice.candidate.batchID;
		lecture.batchName = choice.candidate.name;
		lecture.notInClass = choice.extra;
		lecture.dropped = choice.missing;

		if (choice.missing > 0) {
			lecture.notes.add(choice.missing + " mark(s) will not be imported: those students "
					+ "are not in " + choice.candidate.name + ".");
		}

		for (AttendanceCsvParser.Row row : sheet.rows) {

			AttendanceCsvParser.Mark mark = row.marks[index];

			if (mark == AttendanceCsvParser.Mark.NOT_IN_CLASS) {
				continue;
			}

			Long student = lookups.student(row.rollNo, session);

			if (student == null || !choice.candidate.members.contains(student)) {
				continue;
			}

			boolean present = mark == AttendanceCsvParser.Mark.PRESENT;
			lecture.marks.put(student, present);

			if (present) {
				lecture.present++;
			} else {
				lecture.absent++;
			}
		}

		String key = lecture.courseId + "|" + lecture.batchId + "|"
				+ lecture.startAt.toEpochSecond() + "|" + lecture.endAt.toEpochSecond();
		Integer earlier = claimed.putIfAbsent(key, column.index);

		if (earlier != null) {
			lecture.problems.add("The same session is already column " + earlier + ".");
			return lecture;
		}

		lecture.replacesExisting = lookups.existing(lecture) != null;
		return lecture;
	}

	// ── Writing ──────────────────────────────────────────────────────────

	/**
	 * Writes every usable lecture of the plan, on the caller's transaction. A lecture already on
	 * record has its marks replaced; a new one is keyed under the server's node.
	 */
	@NonNull
	public static Result commit(@NonNull Connection connection, @NonNull Plan plan)
			throws SQLException {

		ServerIds ids = new ServerIds();
		ids.primeFrom(connection);

		Lookups lookups = new Lookups(connection);
		int created = 0;
		int replaced = 0;
		int marks = 0;

		for (Lecture lecture : plan.lectures) {

			if (!lecture.isUsable()) {
				continue;
			}

			Long lectureId = lookups.existing(lecture);

			if (lectureId != null) {
				try (PreparedStatement clear = connection.prepareStatement(
						"DELETE FROM attendance WHERE lecture_id = ?")) {
					clear.setLong(1, lectureId);
					clear.executeUpdate();
				}
				replaced++;

			} else {
				lectureId = ids.next();

				try (PreparedStatement insert = connection.prepareStatement(
						"INSERT INTO lectures (lecture_id, course_id, batch_id, start_at, "
								+ "start_zone, end_at, end_zone) VALUES (?, ?, ?, ?, ?, ?, ?)")) {

					insert.setLong(1, lectureId);
					insert.setLong(2, lecture.courseId);
					setNullable(insert, 3, lecture.batchId);
					insert.setObject(4, lecture.startAt.toOffsetDateTime());
					insert.setString(5, COLLEGE.getId());
					insert.setObject(6, lecture.endAt.toOffsetDateTime());
					insert.setString(7, COLLEGE.getId());
					insert.executeUpdate();
				}
				created++;
			}

			try (PreparedStatement mark = connection.prepareStatement(
					"INSERT INTO attendance (lecture_id, student_id, present) VALUES (?, ?, ?)")) {

				for (Map.Entry<Long, Boolean> entry : lecture.marks.entrySet()) {
					mark.setLong(1, lectureId);
					mark.setLong(2, entry.getKey());
					mark.setBoolean(3, entry.getValue());
					mark.addBatch();
				}
				mark.executeBatch();
			}
			marks += lecture.marks.size();
		}

		return new Result(created, replaced, marks, plan.lectures.size() - plan.usable());
	}

	// ── Lookups, cached for one sheet ────────────────────────────────────

	private record Course(long id, @NonNull String code, @Nullable String topic,
	                      @NonNull String department) {
	}

	private static final class Lookups {

		private final Connection connection;
		private final Map<String, Course> courses = new HashMap<>();
		private final Map<Integer, Map<String, Long>> students = new HashMap<>();
		private final Map<Integer, Set<String>> unknown = new LinkedHashMap<>();
		private final Map<String, List<BatchMatcher.Candidate>> candidates = new HashMap<>();
		private final Map<String, Boolean> mayRecord = new HashMap<>();

		Lookups(@NonNull Connection connection) {
			this.connection = connection;
		}

		@Nullable
		Course course(@Nullable String code) throws SQLException {

			if (code == null) {
				return null;
			}

			String key = code.trim().toLowerCase(Locale.ROOT);

			if (courses.containsKey(key)) {
				return courses.get(key);
			}

			Course found = null;

			// Case-insensitive, as the device's COLLATE NOCASE is.
			try (PreparedStatement statement = connection.prepareStatement(
					"SELECT course_id, course_code, course_topic, offering_department::text "
							+ "FROM courses WHERE lower(course_code) = lower(?) LIMIT 1")) {

				statement.setString(1, code.trim());

				try (ResultSet results = statement.executeQuery()) {
					if (results.next()) {
						found = new Course(results.getLong(1), results.getString(2),
								results.getString(3), results.getString(4));
					}
				}
			}

			courses.put(key, found);
			return found;
		}

		@Nullable
		Integer sessionAt(@NonNull ZonedDateTime start) throws SQLException {

			try (PreparedStatement statement = connection.prepareStatement(
					"SELECT session_year_of(?, ?)")) {

				statement.setObject(1, start.toOffsetDateTime());
				statement.setString(2, COLLEGE.getId());

				try (ResultSet results = statement.executeQuery()) {
					return results.next() ? results.getObject(1, Integer.class) : null;
				}
			}
		}

		/** Whether the caller may file lectures of this course in this session. */
		boolean mayRecord(@NonNull Course course, int session) throws SQLException {

			String key = course.id() + "|" + session;
			Boolean cached = mayRecord.get(key);

			if (cached != null) {
				return cached;
			}

			boolean may;

			try (PreparedStatement statement = connection.prepareStatement(
					"SELECT actor_instructs(?) OR actor_has_role('ADMIN') "
							+ "OR actor_is_hod_of(?::department, ?)")) {

				statement.setLong(1, course.id());
				statement.setString(2, course.department());
				statement.setInt(3, session);

				try (ResultSet results = statement.executeQuery()) {
					may = results.next() && results.getBoolean(1);
				}
			}

			mayRecord.put(key, may);
			return may;
		}

		@Nullable
		Long student(@NonNull String rollNo, int session) throws SQLException {

			Map<String, Long> index = students.get(session);

			if (index == null) {
				index = new HashMap<>();

				// Everybody enrolled, including those who have since left: an old sheet
				// carries their marks from before they went.
				try (PreparedStatement statement = connection.prepareStatement(
						"SELECT student_id, roll_no FROM student_year WHERE academic_year = ?")) {

					statement.setInt(1, session);

					try (ResultSet results = statement.executeQuery()) {
						while (results.next()) {
							index.put(normalise(results.getString(2)), results.getLong(1));
						}
					}
				}
				students.put(session, index);
			}

			Long student = index.get(normalise(rollNo));

			if (student == null) {
				unknown.computeIfAbsent(session, year -> new LinkedHashSet<>()).add(rollNo);
			}
			return student;
		}

		@NonNull
		List<BatchMatcher.Candidate> candidates(@NonNull Course course, int session)
				throws SQLException {

			String key = course.id() + "|" + session;
			List<BatchMatcher.Candidate> cached = candidates.get(key);

			if (cached != null) {
				return cached;
			}

			Set<Long> linked = new HashSet<>();

			try (PreparedStatement statement = connection.prepareStatement(
					"SELECT batch_id FROM course_batch_map WHERE course_id = ?")) {

				statement.setLong(1, course.id());

				try (ResultSet results = statement.executeQuery()) {
					while (results.next()) {
						linked.add(results.getLong(1));
					}
				}
			}

			Map<Long, String> names = new LinkedHashMap<>();
			Map<Long, Set<Long>> members = new HashMap<>();

			try (PreparedStatement statement = connection.prepareStatement(
					"SELECT b.batch_id, b.batch_name, m.student_id FROM batch_desc b "
							+ "JOIN batch_student_map m ON m.batch_id = b.batch_id "
							+ "  AND m.academic_year = b.academic_year "
							+ "WHERE b.academic_year = ? ORDER BY b.batch_name")) {

				statement.setInt(1, session);

				try (ResultSet results = statement.executeQuery()) {
					while (results.next()) {
						long batch = results.getLong(1);
						names.put(batch, results.getString(2));
						members.computeIfAbsent(batch, id -> new HashSet<>())
								.add(results.getLong(3));
					}
				}
			}

			List<BatchMatcher.Candidate> built = new ArrayList<>();

			for (Map.Entry<Long, String> batch : names.entrySet()) {
				built.add(new BatchMatcher.Candidate(batch.getKey(), batch.getValue(),
						members.get(batch.getKey()), linked.contains(batch.getKey())));
			}

			candidates.put(key, built);
			return built;
		}

		@Nullable
		Long existing(@NonNull Lecture lecture) throws SQLException {

			try (PreparedStatement statement = connection.prepareStatement(
					"SELECT lecture_id FROM lectures WHERE course_id = ? "
							+ "AND batch_id IS NOT DISTINCT FROM ? AND start_at = ? "
							+ "AND end_at = ? LIMIT 1")) {

				statement.setLong(1, lecture.courseId);
				setNullable(statement, 2, lecture.batchId);
				statement.setObject(3, lecture.startAt.toOffsetDateTime());
				statement.setObject(4, lecture.endAt.toOffsetDateTime());

				try (ResultSet results = statement.executeQuery()) {
					return results.next() ? results.getLong(1) : null;
				}
			}
		}

		@NonNull
		private static String normalise(@NonNull String rollNo) {
			return rollNo.trim().toLowerCase(Locale.ROOT)
					.replace(" ", "").replace("-", "").replace("/", "");
		}
	}

	// ── Small things ─────────────────────────────────────────────────────

	private static void setNullable(@NonNull PreparedStatement statement, int at,
	                                @Nullable Long value) throws SQLException {
		if (value == null) {
			statement.setNull(at, Types.BIGINT);
		} else {
			statement.setLong(at, value);
		}
	}

	@NonNull
	private static String session(int year) {
		return year + "–" + String.format(Locale.ENGLISH, "%02d", (year + 1) % 100);
	}

	@NonNull
	private static String shortList(@NonNull Set<String> items) {

		List<String> shown = new ArrayList<>();

		for (String item : items) {
			if (shown.size() == 8) {
				return String.join(", ", shown) + ", and " + (items.size() - 8) + " more";
			}
			shown.add(item);
		}
		return String.join(", ", shown);
	}
}
