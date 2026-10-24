package in.basulabs.siesattendance.backend.sync;

import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import in.basulabs.siesattendance.R;

/**
 * The phone's side of the wire: two calls, over HTTPS, with the token.
 * <p>
 * {@link HttpURLConnection} and nothing else. The alternative is a networking library, and this
 * app would use one feature of it: there are two endpoints, both are JSON over POST and GET, and
 * neither needs interceptors, connection pooling worth tuning, or a serialiser — the body is
 * {@link SyncJson}'s either way. A dependency that brought all that for two methods would be
 * paid for on every build and every security advisory.
 *
 * <h3>Where the pinning is</h3>
 * Not here. Certificate pinning is declared in {@code res/xml/network_security_config.xml} and
 * applied by the platform, which is the only arrangement that cannot be got wrong by a code path
 * that forgot to use the right socket factory. See that file for the pins and for what to do
 * when the certificate is replaced.
 *
 * <h3>Three kinds of failure, and they are not the same</h3>
 * <ul>
 *     <li>{@link Unavailable} — no signal, a timeout, a 5xx. The device keeps its queue and
 *     tries later. This is the ordinary case and must never lose anything.</li>
 *     <li>{@link Unauthenticated} — a 401. The token is no good: the user signs in again, and
 *     the queue waits for them. Retrying would just spend the battery.</li>
 *     <li>{@link Refused} — any other 4xx. The request itself is wrong, so sending it again
 *     cannot help; it is a bug or a client too old for the server, and the only useful thing is
 *     to say so.</li>
 * </ul>
 */
public final class SyncClient {

	/** The server could not be reached, or could not answer. Try again later. */
	public static final class Unavailable extends IOException {

		private static final long serialVersionUID = 1L;

		public Unavailable(@NonNull String message, @Nullable Throwable cause) {
			super(message, cause);
		}
	}

	/** The token is not valid. Nothing will work until somebody signs in. */
	public static final class Unauthenticated extends IOException {

		private static final long serialVersionUID = 1L;

		public Unauthenticated(@NonNull String message) {
			super(message);
		}
	}

	/** The server understood and refused. Sending it again will not help. */
	public static final class Refused extends IOException {

		private static final long serialVersionUID = 1L;

		private final int status;

		@Nullable
		private final String code;

		public Refused(int status, @NonNull String message) {
			this(status, null, message);
		}

		public Refused(int status, @Nullable String code, @NonNull String message) {
			super(message);
			this.status = status;
			this.code = code;
		}

		/**
		 * Whether the server refused because the account's password has to be changed first,
		 * which nothing but changing it will fix.
		 */
		public boolean passwordMustChange() {
			return "password_rotation_required".equals(code);
		}

		public int status() {
			return status;
		}
	}

	/** Long enough for a slow campus connection, short enough not to hold a worker open. */
	private static final int CONNECT_TIMEOUT_MS = 15_000;

	private static final int READ_TIMEOUT_MS = 30_000;

	@NonNull
	private final Context context;

	@NonNull
	private final String baseUrl;

	public SyncClient(@NonNull Context context) {
		this.context = context.getApplicationContext();
		this.baseUrl = trimTrailingSlash(context.getString(R.string.sync_base_url));
	}

	/** Whether this build has somewhere to sync to at all. */
	public boolean isConfigured() {
		return !baseUrl.isEmpty();
	}

	/** Whether anybody is signed in, which is to say whether there is a token to send. */
	public boolean isSignedIn() {
		return DeviceIdentity.hasToken(context);
	}

	/**
	 * Sends a batch and returns what the server made of it.
	 * <p>
	 * The caller deletes the intents the server accepted and keeps the ones it rejected; see
	 * {@link OutboxDrain}.
	 */
	@NonNull
	public SyncJson.PushOutcome push(@NonNull List<SyncIntent> intents) throws IOException {
		return SyncJson.parsePushOutcome(call("POST", "/sync/push", SyncJson.writePush(intents)));
	}

	/**
	 * Fetches one page of changes.
	 *
	 * @param watermark where the last pull got to, or {@code ""} for a first pull — which
	 *                  answers with a snapshot rather than a replay
	 */
	@NonNull
	public SyncJson.PullPage pull(@NonNull String watermark, int limit) throws IOException {

		String query = "/sync/pull?limit=" + limit;

		if (!watermark.isEmpty()) {
			query += "&since=" + java.net.URLEncoder.encode(watermark, StandardCharsets.UTF_8);
		}

		return SyncJson.parsePull(call("GET", query, null));
	}

	/**
	 * Ends this session on the server.
	 * <p>
	 * Worth doing rather than only forgetting the token locally: a session is a row, and a
	 * forgotten token stays live for its full fortnight. Signing out of a phone that is being
	 * handed to somebody else should mean the token on it stops working, not that this app has
	 * stopped using it.
	 * <p>
	 * Best effort. A sign-out with no signal still forgets the token here, because refusing to
	 * let somebody out of a session is not a security property; the session then expires on its
	 * own.
	 */
	public void logout() throws IOException {
		call("POST", "/auth/logout", "{}");
	}

	/**
	 * Changes the password of the account this session belongs to.
	 *
	 * <p>Both arrays are wiped before this returns, however it returns. They have to become
	 * Strings on the way into the body — that is what a JSON document is — so what the wiping
	 * buys is that the app itself keeps no copy afterwards, not that the characters were never
	 * in the heap. The server's own {@code changePassword} says the same thing about its side.
	 *
	 * <p>This is the one call that still works when the account is under {@code must_rotate}:
	 * the token filter admits {@code /auth/password} and {@code /auth/logout} and refuses
	 * everything else, so a phone whose first sign-in came with a password somebody else chose
	 * can do exactly two things, and this is the useful one.
	 *
	 * @return how many other sign-ins the change put an end to. The server keeps this session
	 *         and revokes the rest, so a stolen phone's token stops working when the password
	 *         is changed from another one — worth saying out loud on the screen
	 * @throws Refused         if the current password is wrong, the new one is too weak, or it
	 *                         is the same as the old one. The message is the server's own
	 *                         sentence and is meant to be shown
	 * @throws Unauthenticated if this session is no longer valid at all
	 */
	public int changePassword(@NonNull char[] current, @NonNull char[] next)
			throws IOException {

		try {
			String body = "{\"currentPassword\":" + quote(current)
					+ ",\"newPassword\":" + quote(next) + "}";

			String answer = call("POST", "/auth/password", body);

			return SyncJson.numberIn(answer, "otherSessionsRevoked");

		} finally {
			Arrays.fill(current, '\0');
			Arrays.fill(next, '\0');
		}
	}

	/**
	 * What {@code /me} says this account may do, as the phone uses it.
	 *
	 * @param mayKeepARoll whether to offer the roster screens: add a student, correct one,
	 *                     remove one
	 * @param represents   the courses this account is a class representative of right now,
	 *                     or null when the server did not say
	 * @param roles        the roles this account holds right now ({@code ADMIN},
	 *                     {@code OFFICE}, ...), or null when the server did not say
	 * @param importDepartments the departments this account may import students into, or
	 *                     null when the server did not say. {@link #ANY_DEPARTMENT} alone for
	 *                     the office and an administrator, who may import into any
	 * @param batchDepartments the departments whose batches this account keeps, or null when
	 *                     the server did not say. {@link #ANY_DEPARTMENT} alone for an
	 *                     administrator, who keeps every department's
	 */
	public record Me(boolean mayKeepARoll, @Nullable List<Long> represents,
	                 @Nullable List<String> roles, @Nullable List<String> importDepartments,
	                 @Nullable List<String> batchDepartments) {
	}

	/**
	 * What {@link Me#importDepartments} and {@link Me#batchDepartments} hold for an account
	 * that may act in any department.
	 */
	public static final String ANY_DEPARTMENT = "*";

	/**
	 * Asks the server what this account may do.
	 * <p>
	 * At sign-in and again on every sync. The roll answer changes between terms; the courses a
	 * class representative holds change whenever somebody appoints one or ends an appointment,
	 * and a phone that only asked at sign-in would go on offering a course for weeks after the
	 * appointment ended. The screens consult what was remembered; see {@code Permissions}.
	 *
	 * @param fallback what to answer for the roll when the server does not say. Leaving the
	 *                 app as it was is the right failure: the server refuses what is not this
	 *                 account's anyway, and now says so
	 */
	@NonNull
	public Me me(boolean fallback) throws IOException {

		String body = call("GET", "/me", null);

		List<String> departments = SyncJson.flagAt(body, "imports", "anyDepartment", false)
				? List.of(ANY_DEPARTMENT)
				: SyncJson.textListAt(body, "imports", "departments");

		List<String> batchDepartments = SyncJson.flagAt(body, "batches", "anyDepartment", false)
				? List.of(ANY_DEPARTMENT)
				: SyncJson.textListAt(body, "batches", "departments");

		return new Me(SyncJson.flagIn(body, "mayKeepARoll", fallback),
				SyncJson.courseIdsIn(body, "represents"),
				SyncJson.textsIn(body, "roles", "role"), departments, batchDepartments);
	}

	/** One password as a JSON string, escaped the little that a password can need. */
	@NonNull
	private static String quote(@NonNull char[] password) {

		StringBuilder out = new StringBuilder("\"");

		for (char c : password) {

			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20) {
						out.append(String.format("\\u%04x", (int) c));
					} else {
						out.append(c);
					}
				}
			}
		}

		return out.append('"').toString();
	}

	// ── The one request method ───────────────────────────────────────────

	@NonNull
	private String call(@NonNull String method, @NonNull String path, @Nullable String body)
			throws IOException {

		String token = DeviceIdentity.token(context);

		if (token == null) {
			throw new Unauthenticated("this device has no session");
		}

		HttpURLConnection connection;

		try {
			connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();

		} catch (IOException e) {
			throw new Unavailable("could not open a connection", e);
		}

		try {
			connection.setRequestMethod(method);
			connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
			connection.setReadTimeout(READ_TIMEOUT_MS);
			connection.setRequestProperty("Authorization", "Bearer " + token);
			connection.setRequestProperty("Accept", "application/json");
			connection.setRequestProperty("X-Device-Install",
					DeviceIdentity.installId(context).toString());
			connection.setRequestProperty("X-App-Version", appVersion());

			if (body != null) {
				connection.setDoOutput(true);
				connection.setFixedLengthStreamingMode(
						body.getBytes(StandardCharsets.UTF_8).length);
				connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

				try (OutputStream out = connection.getOutputStream()) {
					out.write(body.getBytes(StandardCharsets.UTF_8));
				}
			}

			int status;

			try {
				status = connection.getResponseCode();
			} catch (IOException e) {
				throw new Unavailable("no answer from the server", e);
			}

			if (status == HttpURLConnection.HTTP_UNAUTHORIZED) {
				throw new Unauthenticated("the server did not accept this session");
			}

			if (status >= 500) {
				throw new Unavailable("the server answered " + status, null);
			}

			if (status >= 400) {
				// The body carries a code and a message meant for a person; the message is the
				// most useful thing there is to report, and it is the server's own words. The
				// raw body is the fallback rather than the first choice, because this string
				// ends up on a screen -- see DeviceIdentity.recordSyncError -- and a
				// professor should not be shown JSON.
				String error = read(connection.getErrorStream());
				String sentence = SyncJson.messageIn(error);

				throw new Refused(status, SyncJson.codeIn(error),
						sentence == null ? error : sentence);
			}

			return read(connection.getInputStream());

		} finally {
			connection.disconnect();
		}
	}

	@NonNull
	private static String read(@Nullable InputStream stream) throws IOException {

		if (stream == null) {
			return "";
		}

		try (InputStream in = stream) {

			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			int count;

			while ((count = in.read(buffer)) != -1) {
				out.write(buffer, 0, count);
			}

			return out.toString(StandardCharsets.UTF_8);
		}
	}

	/**
	 * This build, for the audit entries the server writes.
	 * <p>
	 * From the package rather than from a constant, so it cannot be a version this build is not.
	 */
	@NonNull
	private String appVersion() {

		try {
			return "sies-attendance/" + context.getPackageManager()
					.getPackageInfo(context.getPackageName(), 0).versionName;

		} catch (PackageManager.NameNotFoundException e) {
			return "sies-attendance/unknown";
		}
	}

	@NonNull
	private static String trimTrailingSlash(@NonNull String url) {
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}
}
