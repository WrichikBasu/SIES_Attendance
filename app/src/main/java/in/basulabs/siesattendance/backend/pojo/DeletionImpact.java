package in.basulabs.siesattendance.backend.pojo;

/**
 * What deleting a student would actually destroy.
 * <p>
 * Gathered before the confirmation dialog rather than after the fact, because the counts are
 * the whole basis for the decision: removing a student with no attendance recorded is a
 * correction, and removing one with two hundred records is throwing away a term's work. The
 * dialog says which of the two this is.
 */
public class DeletionImpact {

	/** Attendance rows naming this student, across every session. */
	public int attendanceRecords;

	/** How many sessions the student is enrolled in. */
	public int enrolments;

	/** Batch memberships, across every session. */
	public int batchMemberships;

	/** Attendance rows for this student in one particular session. */
	public int attendanceThisSession;

	/**
	 * True when the student has any attendance at all. This is the line between a correction
	 * and a loss: a student with no history was almost certainly entered by mistake.
	 */
	public boolean hasHistory() {
		return attendanceRecords > 0;
	}

	/**
	 * True when the student appears in more than one session, which is what makes removing
	 * them from a single session meaningfully different from deleting them outright.
	 */
	public boolean spansMultipleSessions() {
		return enrolments > 1;
	}
}
