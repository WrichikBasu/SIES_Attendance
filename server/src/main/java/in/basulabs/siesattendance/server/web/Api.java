package in.basulabs.siesattendance.server.web;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The shape of the wire: where the endpoints live, what a client has to send, and what an error
 * looks like.
 *
 * <h3>Why the version is in the path from the very first response</h3>
 * {@link #V1} is not decoration and is not something to add when a second version is needed. An
 * old build of the app receiving an enum constant it has never heard of throws in
 * {@code TypeConvertors}, by design — the device would rather fail than store a value it cannot
 * read back. That design only holds if the server can tell which client it is talking to, and the
 * cheapest way to be able to is for every URL to have said so since the first release. Adding a
 * version later means the unversioned paths are the ones already installed on every phone, which
 * is the position this exists to avoid.
 */
public final class Api {

	/** Every endpoint lives under here. See the class notes. */
	public static final String V1 = "/api/v1";

	/**
	 * The install the request is coming from, as the {@code uuid} that registered it.
	 * <p>
	 * Required on every request of a device-bound session, and that is the whole point of it: a
	 * token copied off one phone does not work on another, because the copy does not carry the
	 * install. It is not a second password — it is held in the same place the token is — but it
	 * does mean a token alone is not enough, and it is the hook Phase 6's write path hangs on,
	 * where a row's key has to carry the node of the device presenting it.
	 */
	public static final String DEVICE_HEADER = "X-Device-Install";

	/** Which build is calling, for the audit entry. Advisory: nothing is refused for it. */
	public static final String VERSION_HEADER = "X-App-Version";

	private static final ObjectMapper JSON = new ObjectMapper();

	private Api() {
	}

	/**
	 * Writes an error body, the same shape everywhere.
	 * <p>
	 * {@code code} is for the client to branch on and {@code message} is for a person to read.
	 * Nothing else goes in: not the SQL, not the exception, not which of several reasons a login
	 * was refused.
	 */
	public static void writeError(@NonNull HttpServletResponse response, int status,
	                              @NonNull String code, @NonNull String message)
			throws IOException {

		response.setStatus(status);
		response.setContentType("application/json;charset=UTF-8");

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("code", code);
		body.put("message", message);

		response.getWriter().write(JSON.writeValueAsString(body));
	}

	/**
	 * The caller's address, or null when it is not something the {@code inet} column would take.
	 * <p>
	 * {@code getRemoteAddr()} and nothing cleverer. Behind a reverse proxy this is the proxy, and
	 * the fix is {@code server.forward-headers-strategy} in the configuration, so that Tomcat
	 * applies the forwarded headers under rules somebody has set. Parsing
	 * {@code X-Forwarded-For} here by hand would mean trusting a header any client can send,
	 * which turns the rate limiter into something an attacker chooses the buckets of.
	 * <p>
	 * A scope identifier on an IPv6 address ({@code fe80::1%eth0}) is dropped, because
	 * PostgreSQL's {@code inet} will not take one and an audit entry must not fail to be written
	 * over the name of a network interface.
	 */
	@Nullable
	public static String clientIp(@NonNull HttpServletRequest request) {

		String address = request.getRemoteAddr();

		if (address == null || address.isBlank()) {
			return null;
		}

		int scope = address.indexOf('%');

		if (scope >= 0) {
			address = address.substring(0, scope);
		}

		try {
			// Literal only: a hostname here would be a DNS lookup on the request path, and
			// getByName does exactly that for anything that is not an address.
			return InetAddress.ofLiteral(address).getHostAddress();
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** The declared client build, trimmed and capped, or null. */
	@Nullable
	public static String appVersion(@NonNull HttpServletRequest request) {

		String version = request.getHeader(VERSION_HEADER);

		if (version == null || version.isBlank()) {
			return null;
		}

		version = version.trim();
		// A header is whatever the caller sent. The column is text, so the only thing being
		// defended here is the log's readability.
		return version.length() > 64 ? version.substring(0, 64) : version;
	}
}
