package in.basulabs.siesattendance.backend.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import in.basulabs.siesattendance.backend.CourseType;
import in.basulabs.siesattendance.backend.EntityAcademicYear;

/**
 * What a student's per-course breakdown covers.
 * <p>
 * The breakdown answers "how much of each course has this student attended", and unfiltered it
 * answers it over their whole time at the college. That is the right default and the wrong
 * answer to most of the questions actually asked of it: whether a student's attendance fell off
 * after the mid-terms, what their record looked like last session, whether the shortfall is in
 * the practicals or the lectures. Each of those is the same breakdown over less of the record.
 * <p>
 * Everything here is optional and an empty filter means the whole record, so a screen that has
 * not been narrowed does not have to enumerate anything to say so.
 * <p>
 * The window is deliberately a date range rather than an academic session. A lecture carries no
 * session column — the session it belongs to <em>is</em> the range its date falls in, which is
 * what {@link EntityAcademicYear} exists to define — so a session filter would be a date range
 * with extra steps and one more thing that could disagree with the dates. {@link Window#SESSION}
 * resolves to the session's own bounds through {@link #apply}.
 */
public class BreakdownFilter {

	/**
	 * The windows worth one tap. {@link #CUSTOM} keeps whatever {@link #from} and {@link #to}
	 * already hold, which is what the date fields set directly.
	 */
	public enum Window {

		ALL_TIME("All time"),

		/** The session the student is being viewed in, taken from its row's own dates. */
		SESSION("This session"),

		THIS_MONTH("This month"),

		/**
		 * The previous calendar month, whole, which is what "last month" is normally taken to
		 * mean.
		 */
		LAST_MONTH("Last month"),

		LAST_30_DAYS("Last 30 days"),

		CUSTOM("Custom");

		@NonNull
		public final String label;

		Window(@NonNull String label) {
			this.label = label;
		}
	}

	/** Inclusive; null for no lower bound. */
	@Nullable
	public LocalDate from;

	/** Inclusive; null for no upper bound. */
	@Nullable
	public LocalDate to;

	/** Which preset produced the current window, kept so the screen can show it selected. */
	@NonNull
	public Window window = Window.ALL_TIME;

	/**
	 * The course types the breakdown is restricted to, empty for every type.
	 * <p>
	 * A set rather than a single value because the question is usually about a group of types
	 * read together — "how are the practicals doing" spans every laboratory type the student
	 * takes, not one of them — and the overall figure above the list is then their combined
	 * attendance rather than one fraction per type to average by hand. Averaging them by hand
	 * would give the wrong answer anyway, since the types carry different numbers of lectures
	 * behind them.
	 */
	@NonNull
	public final Set<CourseType> courseTypes = new LinkedHashSet<>();

	/**
	 * The courses the breakdown is restricted to, empty for every course.
	 * <p>
	 * A set, because the question is rarely about one course on its own: a student asking
	 * whether they are short is asking about the courses that decide it, which is some of what
	 * they take rather than all or one. The overall figure above the list is summed from the
	 * rows that survive this, so choosing three courses gives their combined attendance rather
	 * than three separate fractions to average by hand — and averaging them by hand would give
	 * the wrong answer anyway, since the courses have different numbers of lectures behind
	 * them.
	 */
	@NonNull
	public final Set<Long> courseIDs = new LinkedHashSet<>();

	private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("d MMM yyyy");

	/**
	 * What a complete side of the syllabus is called on the scope line. Kept here beside the
	 * text it is joined with, as the rest of {@link #describe()} is; the chips that select them
	 * in one tap take their own labels from the string resources, where display text belongs.
	 */
	private static final String PRACTICALS = "Practicals";

	private static final String THEORY = "Theory";

	/**
	 * Recomputes {@link #from} and {@link #to} for a preset.
	 * <p>
	 * {@code session} may be null when the row has not been read yet or does not exist, in which
	 * case {@link Window#SESSION} leaves the window open rather than inventing bounds. That is
	 * the safe direction to fail: a breakdown covering too much is visibly wrong, whereas one
	 * silently bounded to a guessed year is not.
	 */
	public void apply(@NonNull Window preset, @Nullable EntityAcademicYear session) {

		window = preset;
		LocalDate today = LocalDate.now();

		switch (preset) {
			case ALL_TIME:
				from = null;
				to = null;
				break;

			case SESSION:
				from = session == null ? null : session.startDate;
				to = session == null ? null : session.endDate;
				break;

			case THIS_MONTH:
				from = today.withDayOfMonth(1);
				to = today.withDayOfMonth(today.lengthOfMonth());
				break;

			case LAST_MONTH:
				// A whole calendar month, not the thirty days before today: the two differ by
				// up to a fortnight, and it is the calendar one that lines up with how a
				// month's attendance is talked about.
				LocalDate lastMonth = today.minusMonths(1);
				from = lastMonth.withDayOfMonth(1);
				to = lastMonth.withDayOfMonth(lastMonth.lengthOfMonth());
				break;

			case LAST_30_DAYS:
				from = today.minusDays(29);
				to = today;
				break;

			case CUSTOM:
				// Left as set by the caller, which is what the date fields do.
				break;
		}
	}

	/**
	 * Start of the window as an epoch second, or null when unbounded.
	 * <p>
	 * Lectures are stored as an instant, so a date has to become one before it can be compared.
	 * Resolved at the device's zone, the same one the lecture was recorded in.
	 */
	@Nullable
	public Long fromEpoch() {
		return from == null ? null : from.atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
	}

	/** End of the window as an epoch second, inclusive of the whole final day. */
	@Nullable
	public Long toEpoch() {
		return to == null
				? null
				: to.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toEpochSecond() - 1;
	}

	public boolean hasDateWindow() {
		return from != null || to != null;
	}

	public boolean isUnrestricted() {
		return !hasDateWindow() && courseTypes.isEmpty() && courseIDs.isEmpty();
	}

	/**
	 * A one-line description of what the breakdown is covering, for the line above it.
	 * <p>
	 * An unrestricted filter gets a sentence rather than an empty string, because the figures
	 * underneath mean something different depending on what they are over and the screen should
	 * say which it is either way.
	 */
	@NonNull
	public String describe() {

		if (isUnrestricted()) {
			return "Every course, all time";
		}

		StringBuilder out = new StringBuilder();

		if (hasDateWindow()) {
			// The preset's own label where there is one, since "This month" is what the user
			// chose and reads better than the two dates it stands for.
			if (window != Window.CUSTOM && window != Window.ALL_TIME) {
				out.append(window.label);
			} else if (from != null && to != null) {
				out.append(from.format(LABEL)).append(" – ").append(to.format(LABEL));
			} else if (from != null) {
				out.append("From ").append(from.format(LABEL));
			} else {
				out.append("Up to ").append(to.format(LABEL));
			}
		} else {
			out.append("All time");
		}

		if (!courseTypes.isEmpty()) {
			out.append(" · ").append(describeCourseTypes());
		}

		// Counted rather than listed: the codes are already on the chips and on the rows
		// below, and a scope line that grew with the selection would push the figures it
		// describes off the screen.
		if (!courseIDs.isEmpty()) {
			out.append(" · ").append(courseIDs.size())
					.append(courseIDs.size() == 1 ? " course" : " courses");
		}

		return out.toString();
	}

	/**
	 * The course types for the scope line.
	 * <p>
	 * A complete side of the syllabus collapses to the one word that stands for it, since
	 * spelling out every laboratory type to say "practicals" is what selecting them in one tap
	 * exists to avoid. Past two entries the rest are counted rather than named, the same way the
	 * courses are: the chips are already showing which they are, and a scope line that grew with
	 * the selection would push the figures it describes off the screen.
	 */
	@NonNull
	private String describeCourseTypes() {

		Set<CourseType> remaining = new LinkedHashSet<>(courseTypes);
		List<String> named = new ArrayList<>();

		if (remaining.containsAll(CourseType.theory())) {
			named.add(THEORY);
			remaining.removeAll(CourseType.theory());
		}

		if (remaining.containsAll(CourseType.practicals())) {
			named.add(PRACTICALS);
			remaining.removeAll(CourseType.practicals());
		}

		for (CourseType type : remaining) {
			named.add(type.toString());
		}

		if (named.size() <= 2) {
			return String.join(", ", named);
		}
		return courseTypes.size() + " course types";
	}

	/** Puts everything back to the whole record. */
	public void clear() {
		from = null;
		to = null;
		window = Window.ALL_TIME;
		courseTypes.clear();
		courseIDs.clear();
	}
}
