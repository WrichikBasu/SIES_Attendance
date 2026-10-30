package in.basulabs.siesattendance.server.web;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import in.basulabs.siesattendance.server.security.ActorSession;
import in.basulabs.siesattendance.server.security.Sessions;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Who is making this request, as established by the token and nothing else.
 * <p>
 * {@link BearerTokenFilter} puts one of these on the request after verifying the bearer token;
 * controllers read it and hand it to {@link ActorDatabase}, which announces it to the database
 * for the length of one transaction. No other path sets an actor, which is the property this
 * phase exists to establish — before it, the only things that could were a test and a
 * command-line tool.
 */
public record RequestActor(@NonNull Sessions.Live session, @Nullable String clientIp,
                           @Nullable String appVersion) {

	private static final String ATTRIBUTE = RequestActor.class.getName();

	/** Attaches the verified actor to the request, for the controller to find. */
	public void attachTo(@NonNull HttpServletRequest request) {
		request.setAttribute(ATTRIBUTE, this);
	}

	/**
	 * The actor of a request the filter has let through.
	 *
	 * @throws ApiException 401, if there is none. A controller reaching this means an endpoint
	 *                      was added without the filter being told it needs a token, which is a
	 *                      configuration mistake that has to fail rather than run as nobody
	 */
	@NonNull
	public static RequestActor of(@NonNull HttpServletRequest request) {

		Object attached = request.getAttribute(ATTRIBUTE);

		if (!(attached instanceof RequestActor actor)) {
			throw new ApiException(401, "unauthenticated",
					"This request carried no verified session.");
		}
		return actor;
	}

	public long accountId() {
		return session.accountId();
	}

	/** What the database is told, for policies and for the audit entries of this transaction. */
	@NonNull
	public ActorSession.Actor actor() {
		return new ActorSession.Actor(session.accountId(), session.profId(),
				session.studentId(), session.displayName(), session.sessionId(),
				session.deviceNode(), clientIp, appVersion, null);
	}
}
