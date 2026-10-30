package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * What kind of person an account belongs to, which decides which login screen it answers on and
 * which of the two person tables it points at.
 * <p>
 * Stored by {@code name()} like every other enum here, so a constant may be added but never
 * renamed once accounts exist.
 * <p>
 * There are three kinds and only two doors. Students sign in on their own screen; professors and
 * office staff share the other one, because what the screen needs to know is which table to look
 * the address up in, and for both of those the answer is the same. {@code app_account} derives a
 * {@code student_login} column from this for exactly that reason, so the same address can hold a
 * student account and a staff account without colliding — a research assistant finishing an MSc
 * is the case, and it is commoner than it sounds.
 */
public enum AccountKind {

	/** Points at a {@code professors} row. Takes attendance for the courses it instructs. */
	PROFESSOR("Professor"),

	/**
	 * Points at a {@code students} row. Exists only where a CR grant does: a student with no
	 * {@code attendance_delegate} row has nothing to sign in for, so no account is created.
	 */
	STUDENT("Student"),

	/**
	 * Points at neither. Office and registry staff are not professors and not students, and
	 * giving them a {@code professors} row to hang an account on would corrupt every query that
	 * counts teaching staff or offers a coordinator to pick.
	 */
	STAFF("Staff");

	@NonNull
	private final String label;

	AccountKind(@NonNull String label) {
		this.label = label;
	}

	@NonNull
	@Override
	public String toString() {
		return label;
	}

	/**
	 * Whether an account of this kind signs in on the student screen. The complement is the staff
	 * screen, which professors and office staff share.
	 */
	public boolean isStudentLogin() {
		return this == STUDENT;
	}

	/** Resolves from stored data or user input, by constant name or label. */
	@Nullable
	public static AccountKind fromAny(@Nullable String raw) {
		return EnumLookup.find(AccountKind.class, raw);
	}
}
