package in.basulabs.siesattendance.frontend.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import in.basulabs.siesattendance.backend.AccountKind;

/**
 * What the signed-in account may change on this phone, and the one place that decides it.
 * <p>
 * A class representative signs in to mark registers — that is what an
 * {@code attendance_delegate} grant is for, and it is the whole reason a student has an account
 * at all. It is not a reason to let them add a professor, rename a course or edit somebody's
 * roll number. So the screens that change the college's own records are not offered to a student
 * account.
 *
 * <h3>The server decides; this stops the app offering what will be refused</h3>
 * The authority is in SQL and stays there: {@code students}, {@code professors} and
 * {@code courses} are policed by row-level security, and a push of a row a student had no
 * business writing comes back refused with a sentence saying so. This class exists because a
 * button that leads to a refusal three steps later is a bad button — and because a roster edited
 * on a phone that can never push it is a roster that silently disagrees with the office's.
 * <p>
 * It is therefore <em>not</em> a security boundary and must never be treated as one. Anybody who
 * can modify the app can modify this. What makes the rule real is the policy on the table.
 *
 * <h3>What a student keeps</h3>
 * Everything about attendance: taking a register, looking at one, the statistics, the exports,
 * and reading the lists. Reading is deliberate — a CR marking a roll has to see who is on it.
 *
 * <h3>When the kind is not known</h3>
 * Permitted, for the same reason {@code SignInGuard} lets a build with no server through: an
 * install that signed in before this build stored the kind, and a build with no server to sign
 * in to at all, would otherwise lose the ability to do anything. The next sign-in records the
 * kind and the rule applies from then on.
 */
public final class Permissions {

	/** The things an account may or may not change. */
	public enum Capability {

		/** Adding a student, correcting one, or removing one from a roll. */
		STUDENTS,

		/** Adding a professor or correcting one. */
		PROFESSORS,

		/** Adding a course, correcting one, or marking one discontinued. */
		COURSES
	}

	private Permissions() {
	}

	/**
	 * Whether this kind of account may change that.
	 * <p>
	 * One rule for all three today, written as a table rather than as {@code != STUDENT} so
	 * that the day batches or sessions join the list, or professors stop being allowed to
	 * invent courses, is a line here and not a rewrite.
	 *
	 * @param who  the signed-in account's kind, or null when this install has not recorded one
	 * @param what the thing being changed
	 */
	public static boolean may(@Nullable AccountKind who, @NonNull Capability what) {

		if (who == null) {
			return true;
		}

		return switch (who) {
			case STUDENT -> switch (what) {
				case STUDENTS, PROFESSORS, COURSES -> false;
			};
			// Office staff keep the roll; a professor runs their own courses. Neither is
			// stopped here, and both are still answerable to the policies on the tables.
			case PROFESSOR, STAFF -> true;
		};
	}
}
