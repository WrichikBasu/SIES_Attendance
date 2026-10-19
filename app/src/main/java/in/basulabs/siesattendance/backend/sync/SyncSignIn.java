package in.basulabs.siesattendance.backend.sync;

import android.content.Context;
import android.os.Build;

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
import java.util.LinkedHashMap;
import java.util.Map;

import in.basulabs.siesattendance.R;
import in.basulabs.siesattendance.backend.IdGenerator;

/**
 * Signing in, and registering this phone.
 * <p>
 * The two calls that have to happen before anything can sync, and the only two that do not use
 * {@link SyncClient} — because {@code SyncClient} needs a token and these are what get one.
 *
 * <h3>What it does, in order</h3>
 * <ol>
 *     <li>Posts the address, the door and the password, with this installation's identifier, and
 *     stores the token through {@link DeviceIdentity}. A token is never written anywhere else.</li>
 *     <li>If the server did not already know this install, registers it — and applies the node
 *     it is given through {@link IdGenerator#assignNodeID}, which is the moment this phone stops
 *     issuing keys from the half of the space it picked for itself.</li>
 * </ol>
 * The provisional node is sent with the registration, which is the only chance to record it: two
 * installs may have chosen the same one and each already written rows under it, and once those
 * rows arrive there is nothing in them to tell the two apart.
 *
 * <h3>Who calls this</h3>
 * {@code FragmentSync}, and nothing else. It is the only screen that asks for a password, and the
 * password reaches no further than this method: what gets stored is the token, through
 * {@link DeviceIdentity}.
 */
public final class SyncSignIn {

	/** Which login screen the attempt is from. The server keeps the two doors separate. */
	public enum Door {

		/** Professors and office staff. */
		STAFF,

		/** Students, which in practice means class representatives. */
		STUDENT
	}

	/** What signing in produced. */
	public record Session(long accountId, @NonNull String displayName, boolean mustRotate,
	                      int node) {
	}

	/** The server said no. The message is the server's own and is meant to be shown. */
	public static final class Refused extends IOException {

		private static final long serialVersionUID = 1L;

		public Refused(@NonNull String message) {
			super(message);
		}
	}

	private static final int CONNECT_TIMEOUT_MS = 15_000;

	private static final int READ_TIMEOUT_MS = 30_000;

	private SyncSignIn() {
	}

	/**
	 * Signs in, registers this phone if it is new, and stores the token.
	 * <p>
	 * The password array is wiped before this returns, however it returns — the same contract
	 * the server's {@code AccountDirectory} keeps, for the same reason: a {@code String} cannot
	 * be cleared and sits in the heap until it is collected.
	 *
	 * @throws Refused     if the server refused the details or the registration
	 * @throws IOException if it could not be reached
	 */
	@NonNull
	public static Session signIn(@NonNull Context context, @NonNull String email,
	                             @NonNull Door door, @NonNull char[] password)
			throws IOException {

		try {
			String base = base(context);

			Map<String, Object> login = new LinkedHashMap<>();
			login.put("email", email);
			login.put("door", door.name());
			login.put("password", new String(password));
			login.put("installId", DeviceIdentity.installId(context).toString());

			String answer = post(base + "/auth/login", null, writeObject(login));

			String token = string(answer, "token");

			if (token == null) {
				throw new Refused("the server did not return a session");
			}

			DeviceIdentity.storeToken(context, token);

			Integer node = integer(answer, "deviceNode");

			if (node == null) {
				// This install is not registered yet: the token it has just been given is what
				// authorises the registration.
				node = register(context, base, token);
			}

			IdGenerator.assignNodeID(context, node);

			Long accountId = longValue(answer, "accountId");
			String name = string(answer, "displayName");

			return new Session(accountId == null ? 0L : accountId,
					name == null ? email : name,
					"true".equals(flag(answer, "mustRotate")), node);

		} finally {
			Arrays.fill(password, '\0');
		}
	}

	/**
	 * Registers this installation and returns the node it was issued.
	 * <p>
	 * Idempotent on the server, which matters here more than anywhere: a registration whose
	 * response was lost must not take a second node, or half this phone's rows would be
	 * attributed to a device that is really the same one.
	 */
	private static int register(@NonNull Context context, @NonNull String base,
	                            @NonNull String token) throws IOException {

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("installId", DeviceIdentity.installId(context).toString());
		body.put("label", Build.MANUFACTURER + " " + Build.MODEL);

		int provisional = IdGenerator.nodeID();

		if (provisional > IdGenerator.MAX_ASSIGNED_NODE_ID) {
			// The node this install chose for itself. Sent so that a clash with another install
			// that chose the same one is recorded while there is still something to record it
			// against.
			body.put("provisionalNode", provisional);
		}

		String answer = post(base + "/devices", token, writeObject(body));
		Integer node = integer(answer, "node");

		if (node == null) {
			throw new Refused("the server did not issue a node number");
		}

		return node;
	}

	// ── One request ──────────────────────────────────────────────────────

	@NonNull
	private static String post(@NonNull String url, @Nullable String token,
	                           @NonNull String body) throws IOException {

		HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();

		try {
			connection.setRequestMethod("POST");
			connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
			connection.setReadTimeout(READ_TIMEOUT_MS);
			connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
			connection.setRequestProperty("Accept", "application/json");

			if (token != null) {
				connection.setRequestProperty("Authorization", "Bearer " + token);
			}

			connection.setDoOutput(true);

			try (OutputStream out = connection.getOutputStream()) {
				out.write(body.getBytes(StandardCharsets.UTF_8));
			}

			int status = connection.getResponseCode();

			if (status >= 400) {
				String error = read(connection.getErrorStream());
				String message = string(error, "message");

				// The server's own sentence, which is written to be shown to a person: every
				// refusal to sign in reads identically on purpose, and inventing a different
				// one here would undo that.
				throw new Refused(message == null ? "the server refused (" + status + ")"
						: message);
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
			byte[] buffer = new byte[4096];
			int count;

			while ((count = in.read(buffer)) != -1) {
				out.write(buffer, 0, count);
			}
			return out.toString(StandardCharsets.UTF_8);
		}
	}

	// ── Just enough JSON ─────────────────────────────────────────────────
	//
	// Three fields out of two flat answers. SyncJson is for the sync format and is a reader
	// for that shape; teaching it the login response would be teaching it a second format.

	@NonNull
	private static String writeObject(@NonNull Map<String, Object> values) {

		StringBuilder out = new StringBuilder("{");
		boolean first = true;

		for (Map.Entry<String, Object> entry : values.entrySet()) {

			out.append(first ? "" : ",").append('"').append(entry.getKey()).append("\":");
			first = false;

			Object value = entry.getValue();

			if (value instanceof Number) {
				out.append(value);
			} else {
				out.append('"').append(escape(String.valueOf(value))).append('"');
			}
		}

		return out.append("}").toString();
	}

	@NonNull
	private static String escape(@NonNull String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"")
				.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
	}

	@Nullable
	private static String string(@NonNull String json, @NonNull String field) {

		String raw = valueOf(json, field);

		if (raw == null || !raw.startsWith("\"")) {
			return null;
		}

		return raw.substring(1, raw.length() - 1)
				.replace("\\\"", "\"").replace("\\\\", "\\");
	}

	@Nullable
	private static Integer integer(@NonNull String json, @NonNull String field) {

		Long value = longValue(json, field);
		return value == null ? null : value.intValue();
	}

	@Nullable
	private static Long longValue(@NonNull String json, @NonNull String field) {

		String raw = valueOf(json, field);

		if (raw == null) {
			return null;
		}

		try {
			return Long.valueOf(raw);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	@Nullable
	private static String flag(@NonNull String json, @NonNull String field) {
		return valueOf(json, field);
	}

	/**
	 * The raw text of one field, quotes and all, or null.
	 * <p>
	 * The first field of that name anywhere in the document, read to the next delimiter. That is
	 * enough for the two answers this class reads — the account's details are nested one level
	 * inside the login's response and the names do not repeat — and it is not enough for
	 * anything else. {@link SyncJson} is the reader for the sync format; this is three fields out
	 * of two known answers, and it is deliberately not a parser.
	 */
	@Nullable
	private static String valueOf(@NonNull String json, @NonNull String field) {

		int at = json.indexOf("\"" + field + "\"");

		if (at < 0) {
			return null;
		}

		int colon = json.indexOf(':', at);

		if (colon < 0) {
			return null;
		}

		int from = colon + 1;

		while (from < json.length() && Character.isWhitespace(json.charAt(from))) {
			from++;
		}

		if (from >= json.length()) {
			return null;
		}

		if (json.charAt(from) == '"') {

			int end = from + 1;

			while (end < json.length()) {

				if (json.charAt(end) == '\\') {
					end += 2;
					continue;
				}

				if (json.charAt(end) == '"') {
					return json.substring(from, end + 1);
				}
				end++;
			}
			return null;
		}

		int end = from;

		while (end < json.length() && ",}] \n\r\t".indexOf(json.charAt(end)) < 0) {
			end++;
		}

		String raw = json.substring(from, end);
		return "null".equals(raw) ? null : raw;
	}

	@NonNull
	private static String base(@NonNull Context context) throws IOException {

		String url = context.getString(R.string.sync_base_url);

		if (url.isEmpty()) {
			throw new Refused("this build has no server to sign in to");
		}

		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}
}
