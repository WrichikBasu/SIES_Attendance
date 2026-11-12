package in.basulabs.siesattendance.backend.pojo;

/**
 * What is still filed under an academic session.
 * <p>
 * A session with anything under it is not removed (R38). Enrolments and batches name it, and
 * the server's foreign keys refuse to delete it while they do, so a phone that removed it
 * anyway would disagree with the server from then on. Lectures carry no session column, but the
 * session's dates are what give them one: without it they fall out of every report.
 */
public class SessionImpact {

	/** Lectures whose start falls inside the session's dates. */
	public int lectures;

	/** Students enrolled in the session. */
	public int enrolments;

	/** Batches made for the session. */
	public int batches;

	/** True when the session has nothing filed under it, so it may be removed. */
	public boolean isEmpty() {
		return lectures == 0 && enrolments == 0 && batches == 0;
	}
}
