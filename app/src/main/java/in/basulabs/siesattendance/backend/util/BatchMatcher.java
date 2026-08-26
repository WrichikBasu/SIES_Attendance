package in.basulabs.siesattendance.backend.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Works out which batch a session was held for, from nothing but who was marked on it.
 * <p>
 * An imported sheet says who was present and who was absent, but not which group was in the
 * room, and that is not a cosmetic detail. The export counts a lecture towards a student when it
 * was a whole-class lecture or one for a batch they belong to, so a session filed against the
 * wrong group lands in the denominator of students who were never meant to be there. Their
 * percentage falls for a class they were never in.
 * <p>
 * Only real batches are considered. A whole-class lecture is representable in the database — it
 * is what a null {@code batchID} means — but an import never produces one, so every imported
 * session names a batch. The cost of that is worth being clear about: a class genuinely taught
 * to everybody has to be described by a batch that holds everybody, and if no such batch exists
 * the best available one is chosen and the marks it does not cover are reported and dropped
 * rather than written. See {@link Choice#missing}.
 * <p>
 * The evidence is the pattern of empty cells. A student with no mark was not in the room, so
 * the group that sat the session is the one that contains everybody who was marked and as few
 * as possible of everybody who was not. That is what the ordering below says, in that order of
 * priority:
 * <ol>
 *     <li><b>Covers the marked students.</b> A batch that leaves somebody out cannot be the
 *     group that sat the session, because their mark would have nowhere to go. With no
 *     whole-class fallback among the candidates this can genuinely fail, and when it does the
 *     file is describing a group the app does not have.</li>
 *     <li><b>Then the fewest people it adds.</b> Among the batches that cover, the best is the
 *     one carrying the fewest unmarked members: a batch whose membership is exactly the set of
 *     marked students scores zero here and is unbeatable. This is the rule that stops a
 *     practical being filed against a batch twice its size.</li>
 *     <li><b>Then a batch already linked to the course</b>, then by name, so the answer does
 *     not depend on the order the batches came back in.</li>
 * </ol>
 * Where nothing covers, the ordering falls back to whichever batch accounts for the most marked
 * students. The caller is expected to report the shortfall rather than write marks for students
 * the chosen batch does not contain: those rows would count towards a student's attendance
 * without counting towards the lectures held for them, which can put a percentage above a
 * hundred.
 * <p>
 * Nothing here reads the database. The caller resolves the candidates and passes them in, which
 * keeps the rule itself testable on plain sets.
 */
public final class BatchMatcher {

	/** One batch the session might have been held for. */
	public static final class Candidate {

		public final long batchID;

		/** The batch's name, as it will appear on the review screen. */
		@NonNull
		public final String name;

		/** Everyone on this batch's roll, as student IDs. */
		@NonNull
		public final Set<Long> members;

		/**
		 * Whether the batch is already linked to the course being imported. Only a tie-breaker:
		 * the batch picker elsewhere in the app deliberately does not filter by course, because
		 * a batch that has not been linked yet is still a batch that can sit a lecture.
		 */
		public final boolean linkedToCourse;

		public Candidate(long batchID, @NonNull String name, @NonNull Set<Long> members,
		                 boolean linkedToCourse) {
			this.batchID = batchID;
			this.name = name;
			this.members = members;
			this.linkedToCourse = linkedToCourse;
		}
	}

	/** The chosen candidate, with the figures the choice was made on. */
	public static final class Choice {

		@NonNull
		public final Candidate candidate;

		/** Marked students who are on this roll. */
		public final int matched;

		/**
		 * Marked students who are <em>not</em> in this batch. Zero for a candidate that covers.
		 * A non-zero value is a problem to report, not a detail: those marks cannot be written
		 * safely, and with no whole-class fallback it is the ordinary symptom of a batch that
		 * has not been set up yet.
		 */
		public final int missing;

		/**
		 * Members of this batch with no mark in the file. These are the students the session was
		 * not held for, and the reason a smaller batch is preferred over a larger one.
		 */
		public final int extra;

		Choice(@NonNull Candidate candidate, int matched, int missing, int extra) {
			this.candidate = candidate;
			this.matched = matched;
			this.missing = missing;
			this.extra = extra;
		}

		/** True when every marked student is on this roll, so no mark has to be dropped. */
		public boolean covers() {
			return missing == 0;
		}
	}

	private BatchMatcher() {
	}

	/**
	 * Picks the batch that best explains who was marked.
	 *
	 * @param marked     the students with a P or an AB in this column
	 * @param candidates every batch of the session, in any order
	 * @return the best candidate, or null when the session has no batches at all
	 */
	@Nullable
	public static Choice choose(@NonNull Set<Long> marked, @NonNull List<Candidate> candidates) {

		Choice best = null;

		for (Candidate candidate : candidates) {

			Choice choice = score(marked, candidate);

			if (best == null || isBetter(choice, best)) {
				best = choice;
			}
		}
		return best;
	}

	/** How well one candidate explains the marks, in the three figures the ordering uses. */
	@NonNull
	private static Choice score(@NonNull Set<Long> marked, @NonNull Candidate candidate) {

		int matched = 0;

		for (Long studentID : marked) {
			if (candidate.members.contains(studentID)) {
				matched++;
			}
		}

		return new Choice(candidate, matched, marked.size() - matched,
				candidate.members.size() - matched);
	}

	/**
	 * The ordering described in the class comment, written as a strict comparison so that ties
	 * fall through to the next rule and the first candidate seen wins an exact tie.
	 */
	private static boolean isBetter(@NonNull Choice challenger, @NonNull Choice holder) {

		if (challenger.covers() != holder.covers()) {
			return challenger.covers();
		}

		// Only separates candidates when neither covers; where both do, both matched everybody.
		if (challenger.matched != holder.matched) {
			return challenger.matched > holder.matched;
		}

		if (challenger.extra != holder.extra) {
			return challenger.extra < holder.extra;
		}

		if (challenger.candidate.linkedToCourse != holder.candidate.linkedToCourse) {
			return challenger.candidate.linkedToCourse;
		}

		return challenger.candidate.name.compareToIgnoreCase(holder.candidate.name) < 0;
	}

	/** Convenience for a candidate built from a list of IDs. */
	@NonNull
	public static Set<Long> setOf(@NonNull List<Long> ids) {
		return ids.isEmpty() ? Collections.emptySet() : new HashSet<>(ids);
	}
}
