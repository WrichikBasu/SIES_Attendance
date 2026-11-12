package in.basulabs.siesattendance.server.sql;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.sql.SQLException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A database refusal in words a person can act on, for the places that report one per row: a
 * roster's error list and a push's rejections.
 * <p>
 * PostgreSQL's own wording names a constraint, an index or a policy ("duplicate key value
 * violates unique constraint "students_email_uq""), which tells a professor nothing and tells
 * them the schema. Those messages are the ones that name a constraint, and are replaced. The
 * other kind is this schema's own: a trigger's {@code RAISE} ("That would leave no
 * administrator; make another one first") was written for a person already, carries no
 * constraint name, and is kept, less PostgreSQL's "ERROR: " and its DETAIL lines.
 * <p>
 * The constraint is read from the message rather than from the driver's
 * {@code ServerErrorMessage}, because the driver is a runtime dependency only. PostgreSQL
 * quotes the name in every one of these messages, in every language it translates them into.
 * <p>
 * The server's log, not this, is where a refusal is diagnosed: callers log the exception whole.
 */
public final class DatabaseRefusal {

	public static final String UNIQUE_VIOLATION = "23505";
	public static final String FOREIGN_KEY_VIOLATION = "23503";
	public static final String CHECK_VIOLATION = "23514";
	public static final String NOT_NULL_VIOLATION = "23502";
	public static final String INSUFFICIENT_PRIVILEGE = "42501";

	/** The name after "constraint", quoted, as PostgreSQL writes it. */
	private static final Pattern CONSTRAINT = Pattern.compile("constraint \"([^\"]+)\"");

	/**
	 * What each constraint a person can run into means, said without the values (which the
	 * caller may know and add, as the roster does). The ones not here fall back on what kind of
	 * refusal it was.
	 */
	private static final Map<String, String> MEANING = Map.ofEntries(
			Map.entry("student_year_roll_no_academic_year_uq",
					"that roll number is already another student's in that session"),
			Map.entry("students_email_uq",
					"that email address is already another student's"),
			Map.entry("students_apaar_id_key",
					"that APAAR ID is already another student's"),
			Map.entry("professors_email_uq",
					"that email address is already another professor's"),
			Map.entry("courses_course_code_uq",
					"that course code is already another course's"),
			Map.entry("batch_desc_name_academic_year_uq",
					"a batch of that name already exists in that session"),
			Map.entry("lectures_slot_uq",
					"that course already has a lecture for that batch at that time"),
			Map.entry("lectures_whole_class_slot_uq",
					"that course already has a whole-class lecture at that time"),
			// Since V67 a class is identified by when it starts, not by its whole span.
			Map.entry("lectures_start_uq",
					"that course already has a lecture for that batch starting at that time"),
			Map.entry("lectures_whole_class_start_uq",
					"that course already has a whole-class lecture starting at that time"),
			Map.entry("lectures_ends_after_start",
					"the lecture ends before it starts"),
			Map.entry("student_year_academic_year_fkey",
					"that session is not set up on the server"),
			Map.entry("student_year_major_offers_stream_fkey",
					"the major department does not offer that stream"),
			Map.entry("student_year_semester_matches_year",
					"the semester does not belong to that year of study"),
			Map.entry("student_year_year_matches_stream_level",
					"that year of study does not belong to that stream"),
			Map.entry("student_year_division_needs_divided_stream",
					"only the aided BA and BSc have divisions"),
			Map.entry("student_year_oe_not_major",
					"the open elective department is the major department"),
			Map.entry("student_year_oe_not_minor",
					"the open elective department is the minor department"),
			Map.entry("student_year_leaves_after_joining",
					"the leave date is before the join date"),
			Map.entry("student_year_roll_no_canonical",
					"a roll number needs at least one letter or digit"),
			Map.entry("courses_course_code_canonical",
					"a course code needs at least one letter or digit"),
			Map.entry("courses_semester_matches_year",
					"the semester does not belong to that year of study"),
			Map.entry("courses_year_matches_stream_level",
					"that year of study does not belong to that stream"),
			Map.entry("students_apaar_id_positive",
					"an APAAR ID is a positive number"));

	private DatabaseRefusal() {
	}

	/** The constraint a refusal names, or null for one that names none. */
	@Nullable
	public static String constraintOf(@NonNull SQLException e) {

		String message = e.getMessage();

		if (message == null) {
			return null;
		}

		Matcher matcher = CONSTRAINT.matcher(firstLine(message));
		return matcher.find() ? matcher.group(1) : null;
	}

	/** The refusal in words, lower case first so a caller can put it after "Line 4: ". */
	@NonNull
	public static String inWords(@NonNull SQLException e) {

		String state = String.valueOf(e.getSQLState());

		if (INSUFFICIENT_PRIVILEGE.equals(state)) {
			return "it is not yours to change";
		}

		String constraint = constraintOf(e);

		if (constraint != null) {

			String meaning = MEANING.get(constraint);

			if (meaning != null) {
				return meaning;
			}

			return switch (state) {
				case UNIQUE_VIOLATION -> "it is already on the server as another record";
				case FOREIGN_KEY_VIOLATION -> "something it names is not on the server";
				case CHECK_VIOLATION -> "one of its values is not allowed";
				default -> "the server would not accept it";
			};
		}

		if (NOT_NULL_VIOLATION.equals(state)) {
			return "a value it needs is missing";
		}

		String message = e.getMessage();

		if (message == null || message.isBlank()) {
			return "the server could not write it";
		}

		// This schema's own RAISE, in words already.
		String line = firstLine(message);
		return line.startsWith("ERROR: ") ? line.substring("ERROR: ".length()) : line;
	}

	@NonNull
	private static String firstLine(@NonNull String message) {
		int newline = message.indexOf('\n');
		return newline < 0 ? message : message.substring(0, newline);
	}
}
