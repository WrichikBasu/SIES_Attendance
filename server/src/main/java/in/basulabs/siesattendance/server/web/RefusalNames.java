package in.basulabs.siesattendance.server.web;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import in.basulabs.siesattendance.backend.StoredTimes;
import in.basulabs.siesattendance.backend.sync.DeviceTables;
import in.basulabs.siesattendance.server.sync.SyncWriter;

/**
 * Which row a push refusal is about, in words: "R RAO's mark in SIUCSMJ211 on Tue 20 Oct 2026,
 * 10:00" rather than {@code attendance [1234, 5678]}.
 * <p>
 * The reason a refusal carries says why, and for a while that was all the device was told —
 * "the roll is loaded by the office" — with nothing to say which of the sixty students just
 * imported, or which of the week's marks, it meant. The device cannot always say for itself:
 * a refused deletion is of a row the device has already removed, and the server is the only
 * place its name still is. So the server names it, from the same two sources the audit trail
 * uses ({@link AuditNames}): the row the device sent, then the tables, under the pushing actor,
 * so a name is never one the actor could not have read for themselves.
 * <p>
 * Best effort and never in the way. A name that cannot be found reads "a student"; a lookup
 * that fails is rolled back to a savepoint so the push it describes still commits.
 */
final class RefusalNames {

	private RefusalNames() {
	}

	/**
	 * A description per refusal, in the same order, each null where nothing better than the
	 * table could be said.
	 */
	@NonNull
	static List<String> describe(@NonNull Connection connection,
	                             @NonNull List<SyncWriter.Intent> intents,
	                             @NonNull List<SyncWriter.Rejection> rejections) {

		List<String> described = new ArrayList<>();

		if (rejections.isEmpty()) {
			return described;
		}

		Map<String, SyncWriter.Intent> byKey = new HashMap<>();

		for (SyncWriter.Intent intent : intents) {
			byKey.put(intent.table() + intent.key(), intent);
		}

		AuditNames names = new AuditNames();
		List<Map.Entry<String, Map<String, Object>>> images = new ArrayList<>();

		for (SyncWriter.Rejection rejection : rejections) {
			Map<String, Object> image = image(rejection, byKey.get(rejection.table()
					+ rejection.key()));
			images.add(Map.entry(rejection.table(), image));
			names.learnFrom(rejection.table(), image);
		}

		try {
			Savepoint savepoint = connection.setSavepoint();

			try {
				names.lookUp(connection, images);
				connection.releaseSavepoint(savepoint);

			} catch (SQLException e) {
				// The names are a courtesy; the push is the point. Whatever was learnt from
				// the rows themselves is still used.
				connection.rollback(savepoint);
			}

		} catch (SQLException noSavepoint) {
			return nulls(rejections.size());
		}

		AuditMessages say = new AuditMessages(names);

		for (Map.Entry<String, Map<String, Object>> image : images) {
			try {
				described.add(about(say, image.getKey(), image.getValue()));
			} catch (RuntimeException unexpected) {
				described.add(null);
			}
		}

		return described;
	}

	/**
	 * The refused row in the server's column names: the key from the refusal, then whatever the
	 * device sent with it.
	 */
	@NonNull
	private static Map<String, Object> image(@NonNull SyncWriter.Rejection rejection,
	                                         @Nullable SyncWriter.Intent intent) {

		Map<String, Object> image = new LinkedHashMap<>();
		DeviceTables.Table table;

		try {
			table = DeviceTables.of(rejection.table());
		} catch (RuntimeException unknown) {
			return image;
		}

		Map<String, Object> row = intent == null ? null : intent.row();

		if (row != null) {
			for (DeviceTables.Column column : table.columns()) {

				Object value = row.get(column.room());

				if (column.postgres() == null || value == null) {
					continue;
				}

				if (column.kind() == DeviceTables.Kind.ZONED_DATE_TIME) {
					// The device's single string, as the two columns the server keeps it in.
					try {
						var when = StoredTimes.parse(String.valueOf(value));
						image.put(column.postgres(), when.toOffsetDateTime().toString());
						image.put(column.zoneColumn(), when.getZone().getId());
					} catch (RuntimeException notATime) {
						// Left out, and the lecture is looked up instead.
					}
					continue;
				}

				image.put(column.postgres(), value);
			}
		}

		// The key wins over the row, as it does when the row is written.
		for (int at = 0; at < table.primaryKey().size() && at < rejection.key().size(); at++) {
			image.put(table.column(table.primaryKey().get(at)).postgres(),
					rejection.key().get(at));
		}

		return image;
	}

	@Nullable
	private static String about(@NonNull AuditMessages say, @NonNull String table,
	                            @NonNull Map<String, Object> row) {

		Long course = AuditNames.id(row, "course_id");
		Long batch = AuditNames.id(row, "batch_id");
		Long professor = AuditNames.id(row, "prof_id");
		Long year = AuditNames.id(row, "academic_year");

		return switch (table) {
			case "students" -> "student " + say.student(row);
			case "professors" -> "professor " + say.professor(professor);
			case "academic_years" -> "the " + AuditMessages.session(year) + " session";
			case "student_year" -> {
				String roll = AuditNames.text(row, "roll_no");
				yield owned(say.student(row), "enrolment", "an") + " for "
						+ AuditMessages.session(year)
						+ (roll == null || roll.isBlank() ? "" : " (roll " + roll + ")");
			}
			case "courses" -> {
				String code = AuditNames.text(row, "course_code");
				yield "course " + (code == null ? say.course(course) : code);
			}
			case "batch_desc" -> {
				String name = AuditNames.text(row, "batch_name");
				yield "batch " + (name == null ? say.batch(batch) : name);
			}
			case "batch_student_map" -> say.student(row) + " in batch " + say.batch(batch);
			case "course_batch_map" -> "batch " + say.batch(batch) + " on " + say.course(course);
			case "course_instructor" -> say.professor(professor) + " as an instructor of "
					+ say.course(course);
			case "lectures" -> {
				// "SIUCSMJ211 on Tue 20 Oct 2026, 10:00", or "a lecture of SIUCSMJ211".
				String lecture = say.lecture(AuditNames.id(row, "lecture_id"));
				yield lecture.startsWith("a lecture") ? lecture
						: "the " + lecture.replaceFirst(" on ", " lecture on ");
			}
			case "attendance" -> owned(say.student(row), "mark", "a") + " in "
					+ say.lecture(AuditNames.id(row, "lecture_id"));
			default -> null;
		};
	}

	/** "R RAO's mark", or "a student's mark" when the name could not be found. */
	@NonNull
	private static String owned(@NonNull String name, @NonNull String thing,
	                            @NonNull String article) {
		return name.equals("a student") ? article + " " + thing + " of a student"
				: name + "'s " + thing;
	}

	@NonNull
	private static List<String> nulls(int count) {
		List<String> nulls = new ArrayList<>();
		for (int at = 0; at < count; at++) {
			nulls.add(null);
		}
		return nulls;
	}
}
