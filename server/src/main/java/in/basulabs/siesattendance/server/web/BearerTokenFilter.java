package in.basulabs.siesattendance.server.web;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;

import in.basulabs.siesattendance.server.security.Sessions;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Turns a bearer token into an actor, or refuses the request.
 * <p>
 * The one way identity enters this server. Everything under {@link Api#V1} needs a token except
 * logging in, and a request that gets past here carries a {@link RequestActor} naming an account
 * that exists, is not disabled, and holds a session that is live, unexpired and — when it is
 * bound to a phone — being used from that phone.
 *
 * <h3>Why a filter of our own and not Spring Security</h3>
 * There is one credential, one place it is checked, and no authorization decisions taken in Java
 * at all: the policies in V6 and the functions in V8 decide what the actor may see and do, inside
 * the database, where a forgotten {@code WHERE} clause cannot get round them. A filter chain
 * configured with matchers and roles would be a second, weaker statement of the same rules in a
 * place that does not enforce them, and the one thing it would really add — a
 * {@code SecurityContext} — is the thing to avoid here, because an actor that lives in a thread
 * local is an actor that can be read by code that never asked for it.
 *
 * <h3>Everything is a 401 with the same body</h3>
 * No such token, expired, revoked, account disabled, device mismatch: one status, one code, one
 * message. A 401 that says which it was answers questions for whoever is holding a token they
 * should not have. The log knows the difference; the response does not.
 */
public final class BearerTokenFilter extends OncePerRequestFilter {

	/** The only endpoint under {@link Api#V1} that may be reached without a token. */
	private static final Set<String> OPEN = Set.of(Api.V1 + "/auth/login");

	/**
	 * What an account that has to change its password may still do.
	 * <p>
	 * {@code must_rotate} means somebody else typed the current password — whoever created the
	 * account — so until it is changed the session can do nothing else. Signing out is on the
	 * list because refusing to let somebody out of a session is not a security property.
	 */
	private static final Set<String> ROTATION_ONLY =
			Set.of(Api.V1 + "/auth/password", Api.V1 + "/auth/logout");

	@NonNull
	private final Sessions sessions;

	public BearerTokenFilter(@NonNull Sessions sessions) {
		this.sessions = sessions;
	}

	@Override
	protected void doFilterInternal(@NonNull HttpServletRequest request,
	                                @NonNull HttpServletResponse response,
	                                @NonNull FilterChain chain)
			throws ServletException, IOException {

		String path = request.getRequestURI();

		// Not an API call: the health probe, and whatever the actuator is configured to expose.
		// Those are protected by what is exposed rather than by who is asking.
		if (!path.startsWith(Api.V1) || OPEN.contains(path)) {
			chain.doFilter(request, response);
			return;
		}

		String token = bearerToken(request);

		if (token == null) {
			Api.writeError(response, 401, "unauthenticated",
					"This endpoint needs a bearer token.");
			return;
		}

		Sessions.Live session;

		try {
			session = sessions.verify(token);

		} catch (SQLException e) {
			// The database is unreachable or refusing. Not the caller's fault and not a 401:
			// saying "not authenticated" to somebody whose token is perfectly good would send
			// them to the login screen, where they would fail for the same reason.
			logger.error("could not verify a bearer token", e);
			Api.writeError(response, 503, "unavailable",
					"The server could not check that session. Try again.");
			return;
		}

		if (session == null) {
			Api.writeError(response, 401, "unauthenticated",
					"That session is not valid. Sign in again.");
			return;
		}

		if (session.deviceId() != null && !presentsItsDevice(request, session)) {
			// The same refusal as an invalid token, deliberately: a token being used from the
			// wrong device is the case this exists to stop, and telling the holder that the
			// token itself is fine would be telling them exactly what to fix.
			Api.writeError(response, 401, "unauthenticated",
					"That session is not valid. Sign in again.");
			return;
		}

		if (session.mustRotate() && !ROTATION_ONLY.contains(path)) {
			Api.writeError(response, 403, "password_rotation_required",
					"This account's password has to be changed before anything else.");
			return;
		}

		new RequestActor(session, Api.clientIp(request), Api.appVersion(request))
				.attachTo(request);

		chain.doFilter(request, response);
	}

	/** The token out of {@code Authorization: Bearer ...}, or null. */
	@Nullable
	private static String bearerToken(@NonNull HttpServletRequest request) {

		String header = request.getHeader("Authorization");

		if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
			return null;
		}

		String token = header.substring(7).trim();
		return token.isEmpty() ? null : token;
	}

	/**
	 * Whether the request names the install its session is bound to.
	 * <p>
	 * A malformed header is a mismatch rather than an error: the comparison is against a
	 * {@code uuid} either way, and a 400 here would distinguish "wrong shape" from "wrong
	 * device" for somebody who should learn neither.
	 */
	private static boolean presentsItsDevice(@NonNull HttpServletRequest request,
	                                         @NonNull Sessions.Live session) {

		String claimed = request.getHeader(Api.DEVICE_HEADER);

		if (claimed == null || session.installId() == null) {
			return false;
		}

		try {
			return session.installId().equals(UUID.fromString(claimed.trim()));
		} catch (IllegalArgumentException e) {
			return false;
		}
	}
}
