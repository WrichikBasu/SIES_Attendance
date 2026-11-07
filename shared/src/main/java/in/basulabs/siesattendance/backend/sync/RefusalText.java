package in.basulabs.siesattendance.backend.sync;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The server's refusals as what a person reads: which rows, then why.
 * <pre>
 * Not accepted: student R RAO, student P SHAH and 58 more.
 * The roll is loaded by the office, so this roster stays on this device. Attendance still syncs.
 * </pre>
 * The reason alone was what the screen used to show, and it says why without saying what: a
 * professor told "the server refused this row: it is not yours to change" after a day's
 * marking had no way to find the row. The names come from the server ({@link
 * SyncJson.Rejection#about()}), because a refused deletion is of a row this device no longer
 * has; a refusal from a server too old to send one is named by its table instead.
 * <p>
 * Grouped by reason, because a refusal is usually one decision applied to many rows — a class
 * list imported on a phone is sixty students refused for the same reason — and sixty
 * paragraphs saying the same thing would bury the one that says something else.
 */
public final class RefusalText {

	/** How many rows are named before the rest are counted. */
	static final int NAMED = 5;

	private RefusalText() {
	}

	/** One entry per distinct reason, in the order the reasons first appeared. */
	@NonNull
	public static List<String> summarise(@NonNull List<SyncJson.Rejection> rejections) {

		Map<String, Set<String>> named = new LinkedHashMap<>();
		Map<String, Map<String, Integer>> unnamed = new LinkedHashMap<>();

		for (SyncJson.Rejection rejection : rejections) {

			String reason = rejection.reason().trim();
			named.computeIfAbsent(reason, r -> new LinkedHashSet<>());
			unnamed.computeIfAbsent(reason, r -> new LinkedHashMap<>());

			if (rejection.about() != null) {
				named.get(reason).add(rejection.about());
			} else {
				unnamed.get(reason).merge(rejection.table(), 1, Integer::sum);
			}
		}

		List<String> entries = new ArrayList<>();

		for (Map.Entry<String, Set<String>> group : named.entrySet()) {

			List<String> rows = new ArrayList<>(group.getValue());

			for (Map.Entry<String, Integer> count : unnamed.get(group.getKey()).entrySet()) {
				rows.add(count.getValue() == 1 ? fallback(count.getKey())
						: count.getValue() + " " + plural(count.getKey()));
			}

			entries.add("Not accepted: " + list(rows) + ".\n" + sentence(group.getKey()));
		}

		return entries;
	}

	/** "A, B and C", or "A, B, C, D, E and 3 more". */
	@NonNull
	static String list(@NonNull List<String> names) {

		if (names.size() == 1) {
			return names.get(0);
		}

		int named = names.size() <= NAMED + 1 ? names.size() : NAMED;
		List<String> shown = names.subList(0, named);
		int rest = names.size() - named;

		if (rest > 0) {
			return String.join(", ", shown) + " and " + rest + " more";
		}

		return String.join(", ", shown.subList(0, named - 1)) + " and " + shown.get(named - 1);
	}

	/** The server writes reasons to follow a colon; on a line of their own they start one. */
	@NonNull
	static String sentence(@NonNull String reason) {

		if (reason.isEmpty()) {
			return "The server gave no reason.";
		}

		String capitalised = reason.substring(0, 1).toUpperCase(Locale.ENGLISH)
				+ reason.substring(1);

		return capitalised.endsWith(".") ? capitalised : capitalised + ".";
	}

	/** Several rows of a table, when the server did not say which. */
	@NonNull
	static String plural(@NonNull String table) {

		return switch (table) {
			case "students" -> "students";
			case "professors" -> "professors";
			case "academic_years" -> "sessions";
			case "student_year" -> "enrolments";
			case "courses" -> "courses";
			case "batch_desc" -> "batches";
			case "batch_student_map" -> "batch members";
			case "course_batch_map" -> "batches on courses";
			case "course_instructor" -> "course instructors";
			case "lectures" -> "lectures";
			case "attendance" -> "marks";
			default -> "rows of " + table;
		};
	}

	/** What a row is when the server did not say which one. */
	@NonNull
	static String fallback(@NonNull String table) {

		return switch (table) {
			case "students" -> "a student";
			case "professors" -> "a professor";
			case "academic_years" -> "a session";
			case "student_year" -> "a student's enrolment";
			case "courses" -> "a course";
			case "batch_desc" -> "a batch";
			case "batch_student_map" -> "a batch's member";
			case "course_batch_map" -> "a batch on a course";
			case "course_instructor" -> "a course's instructor";
			case "lectures" -> "a lecture";
			case "attendance" -> "a mark";
			default -> "a row of " + table;
		};
	}
}
