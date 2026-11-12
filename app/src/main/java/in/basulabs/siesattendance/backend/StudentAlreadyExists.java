package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;

/**
 * Adding a student by hand found somebody already on record with the same roll number this
 * session, the same APAAR ID, or the same email.
 * <p>
 * The roster import treats such a match as the same person and updates them, which is what
 * makes importing a file twice harmless. A form that says "Add student" must not: a mistyped
 * roll number would rewrite another student's name and details and report "Student saved"
 * (R34). So adding refuses, and says who is already there.
 */
public final class StudentAlreadyExists extends RuntimeException {

	/** What the new student shares with the one on record. */
	public enum Match {ROLL_NO, APAAR_ID, EMAIL}

	public final long studentID;

	@NonNull
	public final String name;

	@NonNull
	public final Match match;

	public StudentAlreadyExists(long studentID, @NonNull String name, @NonNull Match match) {
		super(name + " already has this " + match);
		this.studentID = studentID;
		this.name = name;
		this.match = match;
	}
}
