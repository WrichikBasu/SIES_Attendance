package in.basulabs.siesattendance.backend.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

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
	 * Restricts the breakdown to courses of one type, so the practicals can be looked at apart
	 * from the lectures. Null means every type.
	 */
	@Nullable
	public CourseType courseType;

	private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("d MMM yyyy");

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
		return !hasDateWindow() && courseType == null;
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

		if (courseType != null) {
			out.append(" · ").append(courseType);
		}

		return out.toString();
	}

	/** Puts everything back to the whole record. */
	public void clear() {
		from = null;
		to = null;
		window = Window.ALL_TIME;
		courseType = null;
	}
}
