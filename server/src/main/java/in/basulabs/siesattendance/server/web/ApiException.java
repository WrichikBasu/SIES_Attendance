package in.basulabs.siesattendance.server.web;

import androidx.annotation.NonNull;

/**
 * An error with a status, a code the client can branch on, and a message a person can read.
 * <p>
 * Thrown rather than returned so that a check can sit where the fact is known — in the middle of
 * a transaction, inside a loop — instead of being plumbed back out through every return type.
 * {@link ApiAdvice} turns it into the response.
 * <p>
 * Everything in here is said out loud to the caller, so nothing goes in that the caller should
 * not be told: no SQL, no stack trace, and not which of several reasons a login was refused.
 */
public final class ApiException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final int status;

	@NonNull
	private final String code;

	public ApiException(int status, @NonNull String code, @NonNull String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public int status() {
		return status;
	}

	@NonNull
	public String code() {
		return code;
	}
}
