package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The capacity in which somebody designated a class representative.
 * <p>
 * Recorded on the grant rather than worked out when the grant is read, for the same reason a
 * revoked grant keeps its row: a head of department who stops being one in 2027 must not
 * retroactively change the authority a 2025 grant was made under. The question an audit asks is
 * "on whose say-so, and in what capacity", and a capacity recomputed from today's roles cannot
 * answer it.
 * <p>
 * Each constant is a different check, which is why this could not be a foreign key.
 * {@code INSTRUCTOR} is a join onto {@code course_instructor}; {@code HOD} is a join onto
 * {@code user_role} by the course's own department <em>and</em> the grant's session;
 * {@code ADMIN} is a join onto {@code user_role} alone. A constraint covering only the first
 * would read as a guarantee while being a third of one, so all three live in
 * {@code attendance_delegate_authorised()}.
 */
public enum GrantAuthority {

	/** A professor who teaches the course. The ordinary case. */
	INSTRUCTOR("Course instructor"),

	/** The head of the department that offers the course, for the grant's own session. */
	HOD("Head of department"),

	/** An administrator, anywhere in the college. */
	ADMIN("Administrator");

	@NonNull
	private final String label;

	GrantAuthority(@NonNull String label) {
		this.label = label;
	}

	@NonNull
	@Override
	public String toString() {
		return label;
	}

	/** The role that backs this authority, or null when the account kind alone supplies it. */
	@Nullable
	public AppRole backingRole() {
		switch (this) {
			case HOD:
				return AppRole.HOD;
			case ADMIN:
				return AppRole.ADMIN;
			default:
				return null;
		}
	}

	/** Resolves from stored data or user input, by constant name or label. */
	@Nullable
	public static GrantAuthority fromAny(@Nullable String raw) {
		return EnumLookup.find(GrantAuthority.class, raw);
	}
}
