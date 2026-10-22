package in.basulabs.siesattendance.server.web;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import in.basulabs.siesattendance.backend.AccountKind;
import in.basulabs.siesattendance.backend.AppRole;
import in.basulabs.siesattendance.backend.CourseType;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;

/**
 * One sentence per audit entry, saying what happened in words a person reads.
 * <p>
 * The trail stores what the database saw: an action, a table, a key, and a JSON image of the row
 * before and after. That is the evidence and it stays available behind the sentence. What a head
 * of department reading it wants is "Prof. Maya Nair marked R RAO absent in SIUCSMJ211 on Tue 20
 * Oct 2026, 10:00", not {@code UPDATE attendance {"lecture_id": …, "student_id": …}}.
 * <p>
 * The actor is not in the sentence. The console shows who did it beside it, and a sentence that
 * began with the name would say it twice.
 * <p>
 * Every case has a fallback. An audit trail is read about the past, and the past includes rows
 * that have since been deleted, tables added after this was written, and readers who may not see
 * the rows an entry names. None of those may fail the page: the worst a missing name does is read
 * "a student".
 */
final class AuditMessages {

	/** The college's zone, for a lecture recorded without one. */
	private static final ZoneId COLLEGE = ZoneId.of("Asia/Kolkata");

	private static final DateTimeFormatter WHEN =
			DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.ENGLISH);

	private static final DateTimeFormatter DAY =
			DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

	/** Columns every audited row carries that say nothing about what changed. */
	private static final Set<String> BOOKKEEPING = Set.of("created_at", "created_by",
			"updated_at", "updated_by", "row_version", "password_hash");

	private final AuditNames names;

	AuditMessages(@NonNull AuditNames names) {
		this.names = names;
	}

	/**
	 * The sentence for one entry.
	 *
	 * @param before the row before the change, or null
	 * @param after  the row after it, or null
	 * @param key    the entry's row key, or null
	 * @param detail the entry's detail, for the events no trigger writes, or null
	 */
	@NonNull
	String describe(@NonNull String action, @Nullable String table,
	                @Nullable Map<String, Object> key, @Nullable Map<String, Object> before,
	                @Nullable Map<String, Object> after, @Nullable Map<String, Object> detail) {

		try {
			String sentence = switch (action) {
				case "INSERT", "UPDATE", "DELETE" -> row(action, table, key, before, after);
				default -> event(action, table, detail);
			};
			return sentence != null ? sentence : fallback(action, table);

		} catch (RuntimeException unexpected) {
			// A shape this build has not seen. The raw entry is still there to read.
			return fallback(action, table);
		}
	}

	// ── Row changes ──────────────────────────────────────────────────────

	@Nullable
	private String row(@NonNull String action, @Nullable String table,
	                   @Nullable Map<String, Object> key, @Nullable Map<String, Object> before,
	                   @Nullable Map<String, Object> after) {

		if (table == null) {
			return null;
		}

		// The row as it is best known: what it became, or what it was, or at least its key.
		Map<String, Object> row = after != null ? after : before != null ? before : key;
		boolean insert = action.equals("INSERT");
		boolean delete = action.equals("DELETE");

		return switch (table) {

			case "attendance" -> {
				String who = student(row);
				String where = lecture(AuditNames.id(row, "lecture_id"));

				if (insert) {
					yield "Marked " + who + " " + presence(after) + " in " + where;
				}
				if (delete) {
					yield "Removed " + possessive(who) + " mark from " + where;
				}
				if (changed(before, after, "present")) {
					yield "Changed " + who + " from " + presence(before) + " to "
							+ presence(after) + " in " + where;
				}
				yield "Updated " + possessive(who) + " mark in " + where;
			}

			case "lectures" -> {
				String course = course(AuditNames.id(row, "course_id"));
				String when = when(row);

				if (insert) {
					yield "Added a lecture of " + course + " on " + when + batchSuffix(row);
				}
				if (delete) {
					yield "Deleted the lecture of " + course + " on " + when;
				}

				List<String> changes = new ArrayList<>();

				if (changed(before, after, "start_at")) {
					changes.add("moved it to " + when(after));
				}
				if (changed(before, after, "course_id")) {
					changes.add("moved it to " + course(AuditNames.id(after, "course_id")));
				}
				if (changed(before, after, "batch_id")) {
					Long batch = AuditNames.id(after, "batch_id");
					changes.add(batch == null ? "made it a whole-class lecture"
							: "changed its batch to " + batch(batch));
				}
				if (changed(before, after, "notes")) {
					changes.add("edited its notes");
				}
				if (changed(before, after, "end_at") && !changed(before, after, "start_at")) {
					changes.add("changed its end time");
				}

				yield "Changed the lecture of " + course(AuditNames.id(before, "course_id"))
						+ " on " + when(before)
						+ (changes.isEmpty() ? "" : ": " + String.join(", ", changes));
			}

			case "students" -> {
				String name = orElse(AuditNames.text(row, "name"), "a student");

				if (insert) {
					yield "Added student " + name;
				}
				if (delete) {
					yield "Removed student " + name;
				}
				if (changed(before, after, "name")) {
					yield "Renamed student " + AuditNames.text(before, "name") + " to "
							+ AuditNames.text(after, "name");
				}
				yield "Updated student " + name + changes(before, after, STUDENT_FIELDS);
			}

			case "student_year" -> {
				String who = student(row);
				String session = session(AuditNames.id(row, "academic_year"));

				if (insert) {
					yield "Enrolled " + who + " for " + session + " as "
							+ orElse(AuditNames.text(after, "roll_no"), "a student") + ", "
							+ classOf(after);
				}
				if (delete) {
					String roll = AuditNames.text(before, "roll_no");
					yield "Withdrew " + who + (roll == null ? "" : " (" + roll + ")")
							+ " from " + session;
				}
				yield "Changed " + possessive(who) + " enrolment for " + session
						+ changes(before, after, ENROLMENT_FIELDS);
			}

			case "professors" -> {
				String name = orElse(AuditNames.text(row, "name"), "a professor");

				if (insert) {
					yield "Added professor " + name + ", "
							+ label(Department::fromAny, AuditNames.text(row, "department"));
				}
				if (delete) {
					yield "Removed professor " + name;
				}
				if (changed(before, after, "name")) {
					yield "Renamed professor " + AuditNames.text(before, "name") + " to "
							+ AuditNames.text(after, "name");
				}
				yield "Updated professor " + name + changes(before, after, PROFESSOR_FIELDS);
			}

			case "courses" -> {
				String code = orElse(AuditNames.text(row, "course_code"), "a course");

				if (insert) {
					yield "Added course " + code + topic(row) + ": "
							+ label(CourseType::fromAny, AuditNames.text(row, "course_type"))
							+ ", " + label(Department::fromAny,
							AuditNames.text(row, "offering_department"))
							+ ", " + classOf(row) + semester(row);
				}
				if (delete) {
					yield "Deleted course " + code;
				}
				if (changed(before, after, "date_discontinued")) {
					yield AuditNames.text(after, "date_discontinued") == null
							? "Brought course " + code + " back into use"
							: "Discontinued course " + code;
				}
				if (changed(before, after, "prof_id")) {
					yield "Made " + professor(AuditNames.id(after, "prof_id"))
							+ " the coordinator of " + code;
				}
				yield "Updated course " + code + changes(before, after, COURSE_FIELDS);
			}

			case "course_instructor" -> {
				String who = professor(AuditNames.id(row, "prof_id"));
				String course = course(AuditNames.id(row, "course_id"));
				yield insert ? "Made " + who + " an instructor of " + course
						: delete ? "Removed " + who + " as an instructor of " + course
						: null;
			}

			case "batch_desc" -> {
				String name = orElse(AuditNames.text(row, "batch_name"), "a batch");

				if (insert) {
					yield "Created batch " + name + " for "
							+ session(AuditNames.id(row, "academic_year"));
				}
				if (delete) {
					yield "Deleted batch " + name;
				}
				if (changed(before, after, "batch_name")) {
					yield "Renamed batch " + AuditNames.text(before, "batch_name") + " to "
							+ AuditNames.text(after, "batch_name");
				}
				yield "Updated the description of batch " + name;
			}

			case "batch_student_map" -> {
				String who = student(row);
				String batch = batch(AuditNames.id(row, "batch_id"));
				yield insert ? "Added " + who + " to batch " + batch
						: delete ? "Removed " + who + " from batch " + batch
						: null;
			}

			case "course_batch_map" -> {
				String batch = batch(AuditNames.id(row, "batch_id"));
				String course = course(AuditNames.id(row, "course_id"));
				yield insert ? "Linked batch " + batch + " to " + course
						: delete ? "Unlinked batch " + batch + " from " + course
						: null;
			}

			case "academic_years" -> {
				String session = session(AuditNames.id(row, "academic_year"));

				if (insert) {
					yield "Added the " + session + " session, " + day(row, "start_date")
							+ " to " + day(row, "end_date");
				}
				if (delete) {
					yield "Removed the " + session + " session";
				}
				yield "Changed the dates of the " + session + " session to "
						+ day(after, "start_date") + " – " + day(after, "end_date");
			}

			case "attendance_delegate" -> {
				String who = student(row);
				String course = course(AuditNames.id(row, "course_id"));
				String session = session(AuditNames.id(row, "academic_year"));
				String role = "class representative of " + course + " for " + session;

				if (insert) {
					yield "Appointed " + who + " " + role;
				}
				if (delete) {
					yield "Deleted " + possessive(who) + " appointment as " + role;
				}
				if (changed(before, after, "revoked_at")) {
					yield AuditNames.text(after, "revoked_at") == null
							? "Re-appointed " + who + " " + role
							: "Ended " + possessive(who) + " appointment as " + role;
				}
				if (changed(before, after, "expires_at")) {
					String until = AuditNames.text(after, "expires_at");
					yield until == null
							? "Made " + possessive(who) + " appointment as " + role
									+ " open-ended"
							: "Set " + possessive(who) + " appointment as " + role
									+ " to end on " + day(AuditNames.instant(until));
				}
				yield "Updated " + possessive(who) + " appointment as " + role;
			}

			case "user_role" -> {
				String who = account(AuditNames.id(row, "account_id"));
				String role = role(row);

				if (insert) {
					yield "Gave " + who + " the role of " + role;
				}
				if (delete) {
					yield "Deleted " + possessive(who) + " role of " + role;
				}
				if (changed(before, after, "revoked_at")) {
					yield AuditNames.text(after, "revoked_at") == null
							? "Gave " + who + " the role of " + role + " again"
							: "Revoked " + possessive(who) + " role of " + role;
				}
				yield "Updated " + possessive(who) + " role of " + role;
			}

			case "app_account" -> account(action, before, after);

			default -> null;
		};
	}

	/** An account's row changes, most of which are signing in rather than anybody editing it. */
	@NonNull
	private String account(@NonNull String action, @Nullable Map<String, Object> before,
	                       @Nullable Map<String, Object> after) {

		Map<String, Object> row = after != null ? after : before;
		String who = orElse(AuditNames.text(row, "display_name"),
				account(AuditNames.id(row, "account_id")));

		if (action.equals("INSERT")) {
			String kind = label(AccountKind::fromAny, AuditNames.text(row, "kind"));
			String email = AuditNames.text(row, "login_email");
			return "Created " + article(kind) + " " + kind.toLowerCase(Locale.ENGLISH)
					+ " account for " + who + (email == null ? "" : " (" + email + ")");
		}

		if (action.equals("DELETE")) {
			return "Deleted the account of " + who;
		}

		if (changed(before, after, "disabled_at")) {
			return AuditNames.text(after, "disabled_at") == null
					? "Re-enabled " + possessive(who) + " account"
					: "Disabled " + possessive(who) + " account";
		}

		if (changed(before, after, "locked_until") && AuditNames.text(after, "locked_until") != null) {
			return "Locked " + possessive(who) + " account until "
					+ when(AuditNames.instant(AuditNames.text(after, "locked_until")), COLLEGE);
		}

		if (changed(before, after, "must_rotate")) {
			return "true".equals(AuditNames.text(after, "must_rotate"))
					? "Required " + who + " to choose a new password"
					: who + " chose a new password";
		}

		if (changed(before, after, "display_name") || changed(before, after, "login_email")) {
			return "Updated the account of " + who + changes(before, after, ACCOUNT_FIELDS);
		}

		if (number(after, "failed_logins") > number(before, "failed_logins")) {
			return "Recorded a failed sign-in for " + who;
		}

		if (changed(before, after, "last_login_at")) {
			return "Recorded a sign-in by " + who;
		}

		return "Updated the account of " + who;
	}

	// ── Events no trigger writes ─────────────────────────────────────────

	@Nullable
	private String event(@NonNull String action, @Nullable String table,
	                     @Nullable Map<String, Object> detail) {

		return switch (action) {

			case "LOGIN" -> "STUDENT".equals(AuditNames.text(detail, "door"))
					? "Signed in as a student" : "Signed in";

			case "LOGIN_FAILED" -> {
				String refused = AuditNames.text(detail, "refused");
				yield "A sign-in was refused: "
						+ (refused == null ? "no account has that address" : refused);
			}

			case "ACCOUNT_LOCKED" -> "Locked the account after "
					+ orElse(AuditNames.text(detail, "after"), "too many failed sign-ins");

			case "PASSWORD_ROTATE" -> "true".equals(AuditNames.text(detail, "reset_by_another"))
					? "Reset the password of " + account(AuditNames.id(detail, "account_id"))
					: "Changed their password";

			case "EXPORT" -> {
				if ("backup".equals(table)) {
					long rows = number(detail, "rows");
					yield "Exported a backup" + ("true".equals(AuditNames.text(detail, "encrypted"))
							? " (encrypted)" : "") + ": " + rows + (rows == 1 ? " row" : " rows");
				}
				String filter = AuditNames.text(detail, "filter");
				long rows = number(detail, "rows");
				yield "Exported the " + session(AuditNames.id(detail, "academic_year"))
						+ " register" + (filter == null || filter.isBlank() ? "" : " (" + filter + ")")
						+ ": " + rows + (rows == 1 ? " row" : " rows");
			}

			case "CSV_IMPORT" -> "attendance".equals(table)
					? "Imported an attendance sheet: " + number(detail, "created")
							+ " lectures added, " + number(detail, "replaced") + " replaced, "
							+ number(detail, "marks") + " marks"
					: "Loaded a roster CSV for "
					+ session(AuditNames.id(detail, "academic_year")) + ": "
					+ number(detail, "created") + " added, " + number(detail, "updated")
					+ " updated, " + number(detail, "failed") + " refused";

			case "BACKUP_IMPORT" -> (Boolean.TRUE.equals(detail == null ? null : detail.get("dry_run"))
					? "Tried importing (dry run) a phone backup from "
					: "Imported a phone backup from ")
					+ orElse(AuditNames.text(detail, "source"), "a phone") + ": "
					+ number(detail, "inserted") + " added, " + number(detail, "updated")
					+ " updated, " + number(detail, "unchanged") + " unchanged";

			case "DEVICE_REGISTER" -> {
				String label = AuditNames.text(detail, "label");
				Long node = AuditNames.id(detail, "node");
				long clashes = number(detail, "provisional_node_clashes");
				yield "Registered a phone" + (label == null ? "" : " (" + label + ")")
						+ (node == null ? "" : " as device " + node)
						+ (clashes > 0 ? "; its earlier number was already used by "
						+ clashes + (clashes == 1 ? " other phone" : " other phones") : "");
			}

			case "ROLE_GRANT" -> "Granted a role";
			case "ROLE_REVOKE" -> "Revoked a role";
			case "DELEGATE_GRANT" -> "Appointed a class representative";
			case "DELEGATE_REVOKE" -> "Ended a class representative's appointment";

			case "SESSIONS_END" -> {
				long ended = number(detail, "ended");
				yield "Signed " + account(AuditNames.id(detail, "account_id"))
						+ " out everywhere: " + ended + (ended == 1 ? " session" : " sessions")
						+ " ended";
			}

			default -> null;
		};
	}

	// ── What things are called ───────────────────────────────────────────

	@NonNull
	private String student(@Nullable Map<String, Object> row) {
		Long id = AuditNames.id(row, "student_id");
		return id == null ? "a student" : orElse(names.students.get(id), "a student");
	}

	@NonNull
	private String professor(@Nullable Long id) {
		return id == null ? "a professor" : orElse(names.professors.get(id), "a professor");
	}

	@NonNull
	private String course(@Nullable Long id) {
		AuditNames.Course course = id == null ? null : names.courses.get(id);
		return course == null ? "a course" : course.code();
	}

	@NonNull
	private String batch(@Nullable Long id) {
		return id == null ? "a batch" : orElse(names.batches.get(id), "a batch");
	}

	@NonNull
	private String account(@Nullable Long id) {
		return id == null ? "an account" : orElse(names.accounts.get(id), "account #" + id);
	}

	/** "SIUCSMJ211 on Tue 20 Oct 2026, 10:00", or as much of it as is known. */
	@NonNull
	private String lecture(@Nullable Long id) {

		AuditNames.Lecture lecture = id == null ? null : names.lectures.get(id);

		if (lecture == null) {
			return "a lecture";
		}

		String course = course(lecture.courseId());
		return lecture.start() == null ? "a lecture of " + course
				: course + " on " + when(lecture.start(), zone(lecture.zone()));
	}

	@NonNull
	private String when(@Nullable Map<String, Object> lecture) {
		return when(AuditNames.instant(AuditNames.text(lecture, "start_at")),
				zone(AuditNames.text(lecture, "start_zone")));
	}

	@NonNull
	private static String when(@Nullable Instant instant, @NonNull ZoneId zone) {
		return instant == null ? "an unknown date" : WHEN.format(instant.atZone(zone));
	}

	@NonNull
	private static String day(@Nullable Instant instant) {
		return instant == null ? "an unknown date" : DAY.format(instant.atZone(COLLEGE));
	}

	@NonNull
	private static String day(@Nullable Map<String, Object> row, @NonNull String key) {

		String value = AuditNames.text(row, key);

		try {
			return value == null ? "an unknown date" : DAY.format(LocalDate.parse(value));
		} catch (RuntimeException notADate) {
			return value;
		}
	}

	@NonNull
	private static ZoneId zone(@Nullable String zone) {
		try {
			return zone == null ? COLLEGE : ZoneId.of(zone);
		} catch (DateTimeException unknown) {
			return COLLEGE;
		}
	}

	/** 2026 is the session that starts in June 2026: "2026–27". */
	@NonNull
	static String session(@Nullable Long year) {
		return year == null ? "an unknown session"
				: year + "–" + String.format(Locale.ENGLISH, "%02d", (year + 1) % 100);
	}

	@NonNull
	private String batchSuffix(@Nullable Map<String, Object> lecture) {
		Long batch = AuditNames.id(lecture, "batch_id");
		return batch == null ? ", whole class" : " for batch " + batch(batch);
	}

	@NonNull
	private static String presence(@Nullable Map<String, Object> mark) {
		return "true".equals(AuditNames.text(mark, "present")) ? "present" : "absent";
	}

	@NonNull
	private String role(@Nullable Map<String, Object> grant) {

		String role = AuditNames.text(grant, "role");

		if ("HOD".equals(role)) {
			Long year = AuditNames.id(grant, "academic_year");
			return "head of " + label(Department::fromAny, AuditNames.text(grant, "department"))
					+ (year == null ? "" : " for " + session(year));
		}
		return label(AppRole::fromAny, role).toLowerCase(Locale.ENGLISH);
	}

	/** "SY BSc (unaided), Computer Science" for an enrolment or a course. */
	@NonNull
	private static String classOf(@Nullable Map<String, Object> row) {

		List<String> parts = new ArrayList<>();
		parts.add(label(Year::fromAny, AuditNames.text(row, "year")) + " "
				+ label(Stream::fromAny, AuditNames.text(row, "stream")));

		String major = AuditNames.text(row, "major_dept");
		if (major != null) {
			parts.add(label(Department::fromAny, major));
		}
		return String.join(", ", parts);
	}

	@NonNull
	private static String semester(@Nullable Map<String, Object> row) {
		String semester = AuditNames.text(row, "semester");
		return semester == null ? "" : ", semester " + semester;
	}

	@NonNull
	private static String topic(@Nullable Map<String, Object> row) {
		String topic = AuditNames.text(row, "course_topic");
		return topic == null ? "" : " (" + topic + ")";
	}

	// ── What changed ─────────────────────────────────────────────────────

	private static final Map<String, String> STUDENT_FIELDS = fields(
			"pronouns", "pronouns", "email", "email");

	private static final Map<String, String> ENROLMENT_FIELDS = fields(
			"roll_no", "roll number", "year", "year", "stream", "stream",
			"major_dept", "major department", "minor_dept", "minor department",
			"oe_dept", "open elective department", "semester", "semester",
			"division", "division");

	private static final Map<String, String> PROFESSOR_FIELDS = fields(
			"email", "email", "department", "department", "designation", "designation");

	private static final Map<String, String> COURSE_FIELDS = fields(
			"course_code", "code", "course_topic", "topic", "course_type", "type",
			"offering_department", "offering department", "year", "year", "stream", "stream",
			"semester", "semester", "remarks", "remarks");

	private static final Map<String, String> ACCOUNT_FIELDS = fields(
			"display_name", "name", "login_email", "sign-in address");

	/** ": email from a@x to b@x, semester from 3 to 4", or nothing when no listed field moved. */
	@NonNull
	private static String changes(@Nullable Map<String, Object> before,
	                              @Nullable Map<String, Object> after,
	                              @NonNull Map<String, String> labels) {

		List<String> changes = new ArrayList<>();

		for (Map.Entry<String, String> field : labels.entrySet()) {
			if (changed(before, after, field.getKey())) {
				changes.add(field.getValue() + " from " + shown(field.getKey(), before)
						+ " to " + shown(field.getKey(), after));
			}
		}

		return changes.isEmpty() ? "" : ": " + String.join(", ", changes);
	}

	@NonNull
	private static String shown(@NonNull String key, @Nullable Map<String, Object> row) {

		String value = AuditNames.text(row, key);

		if (value == null || value.isBlank()) {
			return "nothing";
		}

		return switch (key) {
			case "major_dept", "minor_dept", "oe_dept", "department", "offering_department" ->
					label(Department::fromAny, value);
			case "year" -> label(Year::fromAny, value);
			case "stream" -> label(Stream::fromAny, value);
			case "course_type" -> label(CourseType::fromAny, value);
			default -> value;
		};
	}

	private static boolean changed(@Nullable Map<String, Object> before,
	                               @Nullable Map<String, Object> after, @NonNull String key) {
		return before != null && after != null
				&& !BOOKKEEPING.contains(key)
				&& !Objects.equals(AuditNames.text(before, key), AuditNames.text(after, key));
	}

	// ── Small things ─────────────────────────────────────────────────────

	@NonNull
	private static Map<String, String> fields(@NonNull String... pairs) {
		Map<String, String> map = new LinkedHashMap<>();
		for (int at = 0; at + 1 < pairs.length; at += 2) {
			map.put(pairs[at], pairs[at + 1]);
		}
		return map;
	}

	/** The enum's own label for a stored constant, or the stored text when this build has none. */
	@NonNull
	private static String label(@NonNull Function<String, ?> lookup, @Nullable String stored) {

		if (stored == null) {
			return "unknown";
		}

		try {
			Object constant = lookup.apply(stored);
			return constant == null ? stored : constant.toString();
		} catch (RuntimeException unknown) {
			return stored;
		}
	}

	private static long number(@Nullable Map<String, Object> row, @NonNull String key) {
		Long value = AuditNames.id(row, key);
		return value == null ? 0 : value;
	}

	@NonNull
	private static String possessive(@NonNull String name) {
		return name.startsWith("a ") || name.startsWith("an ")
				? "the " + name.substring(name.indexOf(' ') + 1) + "'s"
				: name.endsWith("s") ? name + "'" : name + "'s";
	}

	@NonNull
	private static String article(@NonNull String word) {
		return "AEIOUaeiou".indexOf(word.isEmpty() ? 'x' : word.charAt(0)) >= 0 ? "an" : "a";
	}

	@NonNull
	private static String orElse(@Nullable String value, @NonNull String otherwise) {
		return value == null || value.isBlank() ? otherwise : value;
	}

	@NonNull
	private static String fallback(@NonNull String action, @Nullable String table) {

		String verb = switch (action) {
			case "INSERT" -> "Added a row to";
			case "UPDATE" -> "Changed a row in";
			case "DELETE" -> "Removed a row from";
			default -> action.charAt(0) + action.substring(1).toLowerCase(Locale.ENGLISH)
					.replace('_', ' ');
		};

		return table == null ? verb : verb + " " + table.replace('_', ' ');
	}
}
