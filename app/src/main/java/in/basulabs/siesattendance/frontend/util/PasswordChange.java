package in.basulabs.siesattendance.frontend.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * What a change-password form can tell on its own, before anything is sent.
 * <p>
 * The server decides, and says so in sentences written for the person who caused the refusal:
 * {@code current_password_wrong}, {@code weak_password}, {@code unchanged_password}. This is not
 * a second copy of that judgement — it is the three questions that do not need a round trip and
 * one that the server cannot answer at all.
 * <p>
 * {@link Problem#MISMATCH} is that last one. The server never sees the confirmation field, so a
 * typed-twice-differently password would otherwise be changed to whichever of the two was in the
 * first box, and the person would find out the next time they signed in.
 * <p>
 * {@link Problem#TOO_SHORT} deliberately repeats the server's own minimum. Repeating a rule is a
 * second place for it to be wrong, which is why the length lives in one constant here and the
 * refusal is still shown when the server disagrees: if the two ever differ, the server wins and
 * says so in its own words.
 * <p>
 * Pure, so it can be tested without a phone — the dialog it serves cannot be.
 */
public final class PasswordChange {

	/** What is wrong with what was typed, as something a screen can turn into a sentence. */
	public enum Problem {

		/** The current password box is empty. */
		NO_CURRENT,

		/** The new password box is empty. */
		NO_NEW,

		/** Shorter than the server will accept. */
		TOO_SHORT,

		/** Nothing but spaces, which the server also refuses. */
		ONLY_SPACES,

		/** The new password is the one already in use. */
		SAME_AS_CURRENT,

		/** The two new-password boxes do not agree. */
		MISMATCH
	}

	/**
	 * The server's {@code Passwords.MINIMUM_LENGTH}. Checked here so the commonest mistake
	 * costs no round trip, and checked there because that is where it is enforced.
	 */
	public static final int MINIMUM_LENGTH = 12;

	private PasswordChange() {
	}

	/**
	 * The first thing wrong with the three boxes, or null when there is nothing this side can
	 * see.
	 *
	 * @param current what is in the current-password box
	 * @param next    what is in the new-password box
	 * @param confirm what is in the box after it
	 */
	@Nullable
	public static Problem check(@NonNull String current, @NonNull String next,
	                            @NonNull String confirm) {

		if (current.isEmpty()) {
			return Problem.NO_CURRENT;
		}

		if (next.isEmpty()) {
			return Problem.NO_NEW;
		}

		if (!next.equals(confirm)) {
			// Before the rules about the password itself: "they do not match" is the useful
			// thing to say about two boxes that disagree, even when both are too short.
			return Problem.MISMATCH;
		}

		if (next.isBlank()) {
			return Problem.ONLY_SPACES;
		}

		if (next.length() < MINIMUM_LENGTH) {
			return Problem.TOO_SHORT;
		}

		if (next.equals(current)) {
			return Problem.SAME_AS_CURRENT;
		}

		return null;
	}
}
