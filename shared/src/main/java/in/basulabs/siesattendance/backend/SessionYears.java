package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;

import java.time.LocalDate;
import java.util.Locale;

/**
 * The two things about an academic session that are true without reading a row: which session a
 * date falls in when nobody has defined one, and what a session is called.
 * <p>
 * Both lived on {@code EntityAcademicYear}, which is a Room entity and so cannot leave the app.
 * The schema migrations need the first of them and the importer's report needs the second, and
 * both of those now run on the server as well, so the two pure functions moved here and the
 * entity delegates to them. There is still one definition of each.
 */
public final class SessionYears {

	/** The month a session starts in under the default rule. */
	private static final int DEFAULT_START_MONTH = 6;

	private SessionYears() {
	}

	/**
	 * Which session a calendar date falls in under the default June rule.
	 * <p>
	 * Used only where no session has been defined yet — seeding the table during a migration,
	 * and choosing where to file a lecture whose date no row covers. Once a row exists its own
	 * dates decide, and they are editable precisely because this rule is a default and not a
	 * fact about every college.
	 */
	public static int defaultYearFor(@NonNull LocalDate date) {
		return date.getMonthValue() >= DEFAULT_START_MONTH ? date.getYear() : date.getYear() - 1;
	}

	/**
	 * The label for a session, e.g. {@code "2025-26"}.
	 * <p>
	 * The trailing year is padded, so 2099-2100 reads "2099-00" rather than "2099-0", and the
	 * format is fixed to {@link Locale#ROOT} because this is an identifier that appears in file
	 * names and is compared by eye against the college's own paperwork, not a localised number.
	 */
	@NonNull
	public static String labelFor(int academicYear) {
		return academicYear + "-"
				+ String.format(Locale.ROOT, "%02d", (academicYear + 1) % 100);
	}
}
