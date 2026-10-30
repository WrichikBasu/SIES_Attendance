package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * What an audit entry records happening.
 * <p>
 * Two kinds of constant, and the distinction matters when reading the log. The first three are
 * written by database triggers and carry a before and after image of the row; everything else is
 * written by the service, because no trigger can see it. A login leaves no row behind, and an
 * export leaves nothing behind at all — which is exactly why an export has to be logged, since
 * it is the realistic way a term's attendance leaves the building.
 * <p>
 * Stored by {@code name()} in the {@code audit_action} enum type, so a constant may be added but
 * never renamed once entries exist. {@code PostgresSchemaDriftTest} holds the two in step.
 */
public enum AuditAction {

	// ── Written by triggers, with row images ─────────────────────────────
	//
	// The names match PostgreSQL's TG_OP exactly, which is what lets the
	// trigger cast it straight to this type instead of mapping it.

	/** A row appeared. {@code after_image} only. */
	INSERT("Created"),

	/** A row changed. Both images, and only when they actually differ. */
	UPDATE("Changed"),

	/** A row went away. {@code before_image} only. */
	DELETE("Deleted"),

	// ── Written by the service ───────────────────────────────────────────

	/** Somebody signed in. */
	LOGIN("Signed in"),

	/**
	 * Somebody failed to sign in. Recorded without the address that was tried: a log of failed
	 * logins is a list of valid addresses the moment it is complete enough to be useful to an
	 * attacker who has read it.
	 */
	LOGIN_FAILED("Sign-in refused"),

	/** Enough failures in a row to lock the account. */
	ACCOUNT_LOCKED("Account locked"),

	/** A password was changed, by its owner or by somebody resetting it. */
	PASSWORD_ROTATE("Password changed"),

	/**
	 * Attendance or marks left the system. The filter goes in {@code detail}, because "who
	 * exported what" is the question and the row count alone does not answer it.
	 */
	EXPORT("Exported"),

	/** A roster arrived as a CSV. Counts go in {@code detail}. */
	CSV_IMPORT("Roster imported"),

	/** A device's backup was loaded. The per-table report goes in {@code detail}. */
	BACKUP_IMPORT("Device imported"),

	/** A role was granted to an account. */
	ROLE_GRANT("Role granted"),

	/** A role was revoked. */
	ROLE_REVOKE("Role revoked"),

	/** A student was designated a class representative for a course. */
	DELEGATE_GRANT("CR designated"),

	/** A class representative's grant was pulled. */
	DELEGATE_REVOKE("CR revoked"),

	/** A phone was registered and issued a node number. */
	DEVICE_REGISTER("Device registered"),

	/** An administrator ended every session of an account. The count goes in {@code detail}. */
	SESSIONS_END("Sessions ended");

	@NonNull
	private final String label;

	AuditAction(@NonNull String label) {
		this.label = label;
	}

	@NonNull
	@Override
	public String toString() {
		return label;
	}

	/**
	 * Whether a trigger writes this one, which is the same thing as whether it carries row
	 * images. Derived from the name matching PostgreSQL's {@code TG_OP}, because that identity
	 * is the reason the first three are named as they are.
	 */
	public boolean isRowChange() {
		return this == INSERT || this == UPDATE || this == DELETE;
	}

	/** Resolves from stored data or user input, by constant name or label. */
	@Nullable
	public static AuditAction fromAny(@Nullable String raw) {
		return EnumLookup.find(AuditAction.class, raw);
	}
}
