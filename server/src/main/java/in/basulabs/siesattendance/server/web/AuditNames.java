package in.basulabs.siesattendance.server.web;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the IDs in a page of the audit trail are called, for {@link AuditMessages}.
 * <p>
 * An entry records keys: a mark is a lecture ID and a student ID. A person reading the trail wants
 * the student's name and which lecture of which course it was. Two sources, in this order:
 * <ol>
 *     <li><b>The page's own images.</b> A student added or deleted on this page carries their
 *     name in the entry, which is the only place a deleted row's name still exists.</li>
 *     <li><b>The tables, under the reader's own actor.</b> The same policies as everywhere else,
 *     so a message names nothing the reader could not look up for themselves. What they may not
 *     read stays unnamed, and the message says "a student" instead.</li>
 * </ol>
 * Everything is looked up a table at a time for the whole page, never per entry.
 */
final class AuditNames {

	/** One lecture: which course, and when, in the zone it was recorded in. */
	record Lecture(@Nullable Long courseId, @Nullable Instant start, @Nullable String zone,
	               @Nullable Long batchId) {
	}

	/** One course, by its code and its topic. */
	record Course(@NonNull String code, @Nullable String topic) {
	}

	final Map<Long, String> students = new HashMap<>();
	final Map<Long, String> professors = new HashMap<>();
	final Map<Long, Course> courses = new HashMap<>();
	final Map<Long, Lecture> lectures = new HashMap<>();
	final Map<Long, String> batches = new HashMap<>();
	final Map<Long, String> accounts = new HashMap<>();

	/** Learns whatever names a row image carries, before any lookup. */
	void learnFrom(@Nullable String table, @Nullable Map<String, Object> image) {

		if (table == null || image == null) {
			return;
		}

		switch (table) {
			case "students" -> put(students, id(image, "student_id"), text(image, "name"));
			case "professors" -> put(professors, id(image, "prof_id"), text(image, "name"));
			case "batch_desc" -> put(batches, id(image, "batch_id"), text(image, "batch_name"));
			case "app_account" ->
					put(accounts, id(image, "account_id"), text(image, "display_name"));

			case "courses" -> {
				Long course = id(image, "course_id");
				String code = text(image, "course_code");
				if (course != null && code != null) {
					courses.putIfAbsent(course, new Course(code, text(image, "course_topic")));
				}
			}

			case "lectures" -> {
				// A row key carries the lecture's ID and nothing else, and learning that would
				// stand in the way of the image that says which course and when.
				Long lecture = id(image, "lecture_id");
				if (lecture != null && id(image, "course_id") != null) {
					lectures.putIfAbsent(lecture, new Lecture(id(image, "course_id"),
							instant(text(image, "start_at")), text(image, "start_zone"),
							id(image, "batch_id")));
				}
			}

			default -> {
				// Nothing named in it.
			}
		}
	}

	/**
	 * Looks up every ID these images mention that the page did not already name.
	 *
	 * @param images every row image and key on the page, with the table each belongs to
	 */
	void lookUp(@NonNull Connection connection,
	            @NonNull List<Map.Entry<String, Map<String, Object>>> images) throws SQLException {

		Set<Long> wantStudents = new HashSet<>();
		Set<Long> wantProfessors = new HashSet<>();
		Set<Long> wantCourses = new HashSet<>();
		Set<Long> wantLectures = new HashSet<>();
		Set<Long> wantBatches = new HashSet<>();
		Set<Long> wantAccounts = new HashSet<>();

		for (Map.Entry<String, Map<String, Object>> image : images) {

			Map<String, Object> row = image.getValue();

			want(wantStudents, students, id(row, "student_id"));
			want(wantProfessors, professors, id(row, "prof_id"));
			want(wantCourses, courses, id(row, "course_id"));
			want(wantLectures, lectures, id(row, "lecture_id"));
			want(wantBatches, batches, id(row, "batch_id"));
			want(wantAccounts, accounts, id(row, "account_id"));
			want(wantAccounts, accounts, id(row, "granted_by"));
			want(wantAccounts, accounts, id(row, "revoked_by"));
		}

		if (!wantLectures.isEmpty()) {
			query(connection, "SELECT lecture_id, course_id, start_at, start_zone, batch_id "
					+ "FROM lectures WHERE lecture_id = ANY(?)", wantLectures, results -> {
				lectures.put(results.getLong("lecture_id"), new Lecture(
						results.getLong("course_id"), timestamp(results, "start_at"),
						results.getString("start_zone"),
						results.getObject("batch_id", Long.class)));
			});
		}

		// A lecture names a course and a batch, which may not be on the page themselves.
		for (Lecture lecture : lectures.values()) {
			want(wantCourses, courses, lecture.courseId());
			want(wantBatches, batches, lecture.batchId());
		}

		query(connection, "SELECT student_id, name FROM students WHERE student_id = ANY(?)",
				wantStudents, results -> students.put(results.getLong(1), results.getString(2)));

		query(connection, "SELECT prof_id, name FROM professors WHERE prof_id = ANY(?)",
				wantProfessors, results -> professors.put(results.getLong(1), results.getString(2)));

		query(connection, "SELECT course_id, course_code, course_topic FROM courses "
				+ "WHERE course_id = ANY(?)", wantCourses, results -> courses.put(
				results.getLong(1), new Course(results.getString(2), results.getString(3))));

		query(connection, "SELECT batch_id, batch_name FROM batch_desc WHERE batch_id = ANY(?)",
				wantBatches, results -> batches.put(results.getLong(1), results.getString(2)));

		query(connection, "SELECT account_id, display_name FROM app_account "
				+ "WHERE account_id = ANY(?)", wantAccounts,
				results -> accounts.put(results.getLong(1), results.getString(2)));
	}

	// ── Helpers ──────────────────────────────────────────────────────────

	private interface Row {
		void read(@NonNull ResultSet results) throws SQLException;
	}

	private static void query(@NonNull Connection connection, @NonNull String sql,
	                          @NonNull Set<Long> ids, @NonNull Row row) throws SQLException {

		if (ids.isEmpty()) {
			return;
		}

		try (PreparedStatement statement = connection.prepareStatement(sql)) {

			Array array = connection.createArrayOf("bigint", ids.toArray(new Long[0]));
			statement.setArray(1, array);

			try (ResultSet results = statement.executeQuery()) {
				while (results.next()) {
					row.read(results);
				}
			}
		}
	}

	private static <T> void want(@NonNull Set<Long> wanted, @NonNull Map<Long, T> known,
	                             @Nullable Long id) {
		if (id != null && !known.containsKey(id)) {
			wanted.add(id);
		}
	}

	private static void put(@NonNull Map<Long, String> names, @Nullable Long id,
	                        @Nullable String name) {
		if (id != null && name != null && !name.isBlank()) {
			names.putIfAbsent(id, name);
		}
	}

	@Nullable
	static Long id(@Nullable Map<String, Object> row, @NonNull String key) {

		Object value = row == null ? null : row.get(key);

		if (value instanceof Number number) {
			return number.longValue();
		}

		if (value instanceof String digits) {
			try {
				return Long.valueOf(digits.trim());
			} catch (NumberFormatException notAKey) {
				return null;
			}
		}
		return null;
	}

	@Nullable
	static String text(@Nullable Map<String, Object> row, @NonNull String key) {
		Object value = row == null ? null : row.get(key);
		return value == null ? null : String.valueOf(value);
	}

	@Nullable
	static Instant instant(@Nullable String value) {

		if (value == null || value.isBlank()) {
			return null;
		}

		try {
			return java.time.OffsetDateTime.parse(value).toInstant();
		} catch (RuntimeException notAnInstant) {
			return null;
		}
	}

	@Nullable
	private static Instant timestamp(@NonNull ResultSet results, @NonNull String column)
			throws SQLException {
		Timestamp value = results.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}
}
