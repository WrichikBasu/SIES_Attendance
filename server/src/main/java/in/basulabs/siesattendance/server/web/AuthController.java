package in.basulabs.siesattendance.server.web;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;

import in.basulabs.siesattendance.server.security.AccountDirectory;
import in.basulabs.siesattendance.server.security.DeviceRegistry;
import in.basulabs.siesattendance.server.security.Passwords;
import in.basulabs.siesattendance.server.security.Sessions;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Signing in, changing a password, and signing out.
 * <p>
 * The work was all built in Phase 4: {@link AccountDirectory} already does the hashing, the
 * lockout, the forced rotation and the rehash-on-login, and already writes the {@code LOGIN},
 * {@code LOGIN_FAILED}, {@code ACCOUNT_LOCKED} and {@code PASSWORD_ROTATE} entries. What is here
 * is the door it is reached through.
 *
 * <h3>Two doors, named by the caller</h3>
 * Professors and students sign in on separate screens, by decision, and the door is part of the
 * request rather than inferred from the address — {@code app_account} is unique per door, so one
 * address may hold a student account and a staff account, and only the screen the person used
 * says which of them they meant.
 *
 * <h3>One refusal</h3>
 * Unknown address, wrong password, locked, disabled: all of them come back as the same status,
 * code and sentence. The server's log records which it was, because that is where the difference
 * is useful; a response that distinguishes them turns the login box into a way to ask whether
 * somebody works here, and then into a way to find out when their account was locked.
 */
@RestController
@RequestMapping(Api.V1 + "/auth")
public final class AuthController {

	/** What every refused attempt says, whatever the reason. */
	private static final String REFUSED = "Those sign-in details were not accepted.";

	/**
	 * @param door       {@code STAFF} or {@code STUDENT}; see the class notes
	 * @param installId  this device's registration, when it has one. Absent at the very first
	 *                   login of a new install, which then registers on the token it is given
	 */
	public record LoginRequest(@Nullable String email, @Nullable String door,
	                           @Nullable String password, @Nullable String installId) {
	}

	/** Who signed in. Not the whole account row: nothing here is a credential. */
	public record Account(long accountId, @NonNull String kind, @NonNull String displayName,
	                      @Nullable Long profId, @Nullable Long studentId) {
	}

	/**
	 * @param expiresAt ISO-8601, as text. Written out rather than left to the JSON mapper's
	 *                  date handling, so the wire format is decided here and cannot change
	 *                  under a library upgrade — the client parses this
	 * @param mustRotate when true, every endpoint but this one's password and logout refuses
	 *                   until the password is changed
	 */
	public record LoginResponse(@NonNull String token, @NonNull String expiresAt,
	                            boolean mustRotate, @Nullable Integer deviceNode,
	                            @NonNull Account account) {
	}

	public record PasswordRequest(@Nullable String currentPassword,
	                              @Nullable String newPassword) {
	}

	/** @param otherSessionsRevoked how many other sign-ins this change put an end to */
	public record PasswordResponse(int otherSessionsRevoked) {
	}

	public record SignedOut(boolean signedOut) {
	}

	@NonNull
	private final AccountDirectory directory;

	@NonNull
	private final Sessions sessions;

	@NonNull
	private final DeviceRegistry devices;

	@NonNull
	private final LoginRateLimiter limiter;

	@NonNull
	private final ActorDatabase database;

	public AuthController(@NonNull AccountDirectory directory, @NonNull Sessions sessions,
	                      @NonNull DeviceRegistry devices, @NonNull LoginRateLimiter limiter,
	                      @NonNull ActorDatabase database) {

		this.directory = directory;
		this.sessions = sessions;
		this.devices = devices;
		this.limiter = limiter;
		this.database = database;
	}

	@PostMapping("/login")
	@NonNull
	public LoginResponse login(@RequestBody @NonNull LoginRequest body,
	                           @NonNull HttpServletRequest http) throws SQLException {

		String clientIp = Api.clientIp(http);

		if (!limiter.allow(clientIp)) {
			// 429 and not 401: this one is about the address rather than the details, and a
			// person who has genuinely mistyped their password ten times should be told to wait
			// rather than told their password is wrong.
			throw new ApiException(429, "too_many_attempts",
					"Too many sign-in attempts from here. Wait "
							+ limiter.window().toMinutes() + " minutes.");
		}

		AccountDirectory.Door door = door(body.door());
		String appVersion = Api.appVersion(http);

		if (body.email() == null || body.email().isBlank() || body.password() == null
				|| body.password().isEmpty()) {
			// Refused without a database lookup, and refused with the same words as everything
			// else. An empty password is not a special case worth describing.
			throw new ApiException(401, "sign_in_refused", REFUSED);
		}

		AccountDirectory.Attempt attempt = directory.authenticate(body.email(), door,
				body.password().toCharArray(), clientIp, appVersion);

		if (!attempt.succeeded()) {
			throw new ApiException(401, "sign_in_refused", REFUSED);
		}

		AccountDirectory.Principal principal = attempt.principal();

		// The device, when the install has one. An unregistered install gets a session with no
		// device, calls POST /devices on it, and that binds this very session -- so a phone
		// signs in once rather than once before registering and again afterwards.
		DeviceRegistry.Registered device = null;

		if (body.installId() != null && !body.installId().isBlank()) {
			device = devices.forInstall(installId(body.installId()));
		}

		Sessions.Issued issued = sessions.issue(principal.accountId(),
				device == null ? null : device.deviceId(), clientIp, appVersion);

		return new LoginResponse(issued.token(), issued.expiresAt().toString(),
				principal.mustRotate(), device == null ? null : device.node(),
				new Account(principal.accountId(), principal.kind().name(),
						principal.displayName(), principal.profId(), principal.studentId()));
	}

	/**
	 * Changes the signed-in account's own password.
	 * <p>
	 * The current password is required even though the session has already been authenticated.
	 * The session is not the thing being protected here: it is that a token lifted from an
	 * unlocked phone should not be enough to take the account over by changing the password out
	 * from under its owner.
	 * <p>
	 * Every <em>other</em> session of the account is revoked. Somebody changing their password
	 * because they think somebody else has it has not finished until the other sign-ins are
	 * gone, and the one in their hand is the one to leave alone.
	 */
	@PostMapping("/password")
	@NonNull
	public PasswordResponse changePassword(@RequestBody @NonNull PasswordRequest body,
	                                       @NonNull HttpServletRequest http)
			throws SQLException {

		RequestActor actor = RequestActor.of(http);

		if (body.currentPassword() == null || body.newPassword() == null) {
			throw new ApiException(400, "incomplete",
					"Both the current and the new password are needed.");
		}

		/*
		 * The passwords arrive as Strings, because that is what a JSON body is, so the char[]
		 * hygiene AccountDirectory keeps cannot start here: these two are in the heap until
		 * they are collected. Nothing can be done about that short of parsing the body by
		 * hand, and it is worth being clear that the protection those arrays buy is against a
		 * heap dump of the long-lived objects, not of the request.
		 */
		String weak = Passwords.tooWeak(body.newPassword().toCharArray());

		if (weak != null) {
			throw new ApiException(400, "weak_password", weak);
		}

		if (body.newPassword().equals(body.currentPassword())) {
			throw new ApiException(400, "unchanged_password",
					"The new password is the same as the current one.");
		}

		if (!directory.matchesPassword(actor.accountId(),
				body.currentPassword().toCharArray())) {

			// Not 401: the session is perfectly good and signing in again would not help.
			throw new ApiException(403, "current_password_wrong",
					"That is not the current password for this account.");
		}

		// On the request's own transaction, so the PASSWORD_ROTATE entry and the UPDATE the
		// trigger sees both carry the session, the device and the address this was done from.
		// AccountDirectory's own setPassword would announce a bare account instead, which is
		// right for an office clerk resetting somebody's password from a command line and
		// wrong for a request.
		database.inTransaction(actor, connection -> {
			AccountDirectory.setPassword(connection, actor.accountId(),
					body.newPassword().toCharArray(), null);
			return null;
		});

		int revoked = sessions.revokeAllFor(actor.accountId(), actor.session().sessionId(),
				"the password was changed");

		return new PasswordResponse(revoked);
	}

	/**
	 * Revokes this session.
	 * <p>
	 * No audit entry, deliberately: {@code AuditAction} has no constant for it, and adding one
	 * would mean altering the {@code audit_action} type for an event the {@code app_session} row
	 * already records with a reason and a time. {@code LOGIN} is the entry that matters, because
	 * it is the one that says somebody got in.
	 */
	@PostMapping("/logout")
	@NonNull
	public SignedOut logout(@NonNull HttpServletRequest http) throws SQLException {

		RequestActor actor = RequestActor.of(http);
		sessions.revoke(actor.session().sessionId(), "signed out");

		return new SignedOut(true);
	}

	@NonNull
	private static AccountDirectory.Door door(@Nullable String raw) {

		if (raw == null || raw.isBlank()) {
			throw new ApiException(400, "unknown_door",
					"Say which door this is: STAFF or STUDENT.");
		}

		try {
			return AccountDirectory.Door.valueOf(raw.trim().toUpperCase(Locale.ROOT));

		} catch (IllegalArgumentException e) {
			// The valid values are in the message because they are not a secret, and a client
			// sending a third one has a bug rather than a password.
			throw new ApiException(400, "unknown_door",
					"Unknown door: " + raw + ". Expected STAFF or STUDENT.");
		}
	}

	@NonNull
	private static UUID installId(@NonNull String raw) {

		try {
			return UUID.fromString(raw.trim());

		} catch (IllegalArgumentException e) {
			throw new ApiException(400, "bad_install_id",
					"The install identifier is not a UUID.");
		}
	}
}
