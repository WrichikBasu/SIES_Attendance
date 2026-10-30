package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A role granted to an account on top of what the account already is.
 * <p>
 * Note what is <em>not</em> here, and why. There is no {@code PROFESSOR} constant: being a
 * professor is {@link AccountKind#PROFESSOR} plus the {@code course_instructor} rows that say
 * which courses are yours, and a role row saying the same thing would be a second answer to one
 * question — the kind that drifts and then has to be reconciled. And there is no {@code CR}
 * constant: a class representative is not a role somebody holds but a set of grants somebody has
 * been given, one per course per session, which is {@code attendance_delegate}. A role is open
 * ended; a grant names what it is for.
 * <p>
 * Stored by {@code name()}, so a constant may be added but never renamed once roles are granted.
 */
public enum AppRole {

	/**
	 * Head of a department, for one department and one session. The only role that is scoped to
	 * a department, and the reason {@code user_role.department} exists at all.
	 */
	HOD("Head of department"),

	/** Registry and office staff: the roster, student details, and exports across departments. */
	OFFICE("Office"),

	/** Read-only across the whole college, including the audit log. Changes nothing. */
	AUDITOR("Auditor"),

	/** Everything, including granting and revoking roles. */
	ADMIN("Administrator");

	@NonNull
	private final String label;

	AppRole(@NonNull String label) {
		this.label = label;
	}

	@NonNull
	@Override
	public String toString() {
		return label;
	}

	/**
	 * Whether this role is held for one department rather than for the college. Only
	 * {@link #HOD} is, which is why {@code user_role.department} is null for every other role
	 * and a CHECK constraint says so.
	 */
	public boolean isDepartmentScoped() {
		return this == HOD;
	}

	/**
	 * Whether this role must name the session it was granted for. A head of department for
	 * 2025-26 should not quietly still be one in 2027-28; an admin is not appointed per session.
	 */
	public boolean isSessionScoped() {
		return this == HOD;
	}

	/** Resolves from stored data or user input, by constant name or label. */
	@Nullable
	public static AppRole fromAny(@Nullable String raw) {
		return EnumLookup.find(AppRole.class, raw);
	}
}
