package in.basulabs.siesattendance.backend.pojo;

/**
 * What removing an academic session would cost.
 * <p>
 * Nothing is deleted with the session — no foreign key points at {@code academic_years} — but
 * the row is what gives a lecture a session to belong to, and what the export joins against.
 * Without it, the lectures inside those dates fall out of every report and an export for that
 * year comes back with headings and no students, reporting success. The counts are gathered
 * before the confirmation so the dialog can say which of those two situations this is.
 */
public class SessionImpact {

	/** Lectures whose start falls inside the session's dates. */
	public int lectures;

	/** Students enrolled in the session. */
	public int enrolments;

	/** True when the session has nothing filed under it, so removing it costs nothing. */
	public boolean isEmpty() {
		return lectures == 0 && enrolments == 0;
	}
}
