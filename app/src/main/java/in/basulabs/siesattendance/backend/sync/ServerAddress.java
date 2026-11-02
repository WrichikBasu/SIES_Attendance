package in.basulabs.siesattendance.backend.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.NetworkSecurityPolicy;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

import in.basulabs.siesattendance.R;

/**
 * Where the server is: the address somebody chose on the phone, or else the one the build was
 * given.
 * <p>
 * {@code R.string.sync_base_url} used to be the only answer, which meant a new server was a new
 * build. It is now the default, and an address typed on the Account and sync screen or on the
 * sign-in screen takes its place until it is cleared. {@link SyncClient} and {@link SyncSignIn}
 * read it here and nowhere else.
 *
 * <h3>Changing it is not a setting like any other</h3>
 * Everything a phone holds came from one server: the token, the account it was issued to, and
 * every row the pull wrote. None of it means anything to another server, and some of it would
 * mean something wrong — account numbers are only unique within one. So the screens change the
 * address only once nobody is signed in, by signing out first, which empties the phone. A phone
 * whose session ended without a sign-out still holds the last account's rows; {@link #choose}
 * marks those as another server's, so the next sign-in starts over from a snapshot instead of
 * keeping them. See {@code SyncSignIn.startOverIfSomebodyElse}.
 *
 * <h3>What is accepted</h3>
 * {@code https://host[:port][/path]}, and {@code http} only where this build's network security
 * config allows cleartext to that host — the debug build, for a development server. A release
 * build refuses cleartext anywhere, so accepting an {@code http} address there would only move
 * the failure to the first request, where it reads as a network error. The scheme may be left
 * off, and is then https. A bare host gets {@link #API_PATH}, which is what the server serves
 * and what nobody remembers to type.
 * <p>
 * The pins in {@code network_security_config.xml} are per domain. An address on a domain they do
 * not name is checked against the system's certificate authorities and nothing more.
 */
public final class ServerAddress {

	/** The API's path on the server, added to an address that has none. */
	public static final String API_PATH = "/api/v1";

	/** Why an address was not accepted. */
	public enum Problem {

		/** Not something that parses as an address with a host. */
		NOT_AN_ADDRESS,

		/** A scheme other than http or https. */
		NOT_HTTP,

		/** http, to a host this build will not talk to in cleartext. */
		CLEARTEXT_REFUSED
	}

	private static final String PREFS = "sync_server";

	private static final String CHOSEN = "address";

	private ServerAddress() {
	}

	/**
	 * The address to talk to, without a trailing slash; empty when there is no server, which is
	 * a build that was given none and a phone on which nobody has chosen one.
	 */
	@NonNull
	public static String current(@NonNull Context context) {

		String chosen = prefs(context).getString(CHOSEN, null);
		return chosen != null ? chosen : builtIn(context);
	}

	/** What the build was given, without a trailing slash; possibly empty. */
	@NonNull
	public static String builtIn(@NonNull Context context) {

		String url = context.getString(R.string.sync_base_url).trim();
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	/** Whether somebody chose the address on this phone, rather than it being the build's. */
	public static boolean isChosen(@NonNull Context context) {
		return prefs(context).contains(CHOSEN);
	}

	/**
	 * What is wrong with a typed address, including what only this build can say, or null when
	 * {@link #normalise} will take it.
	 */
	@Nullable
	public static Problem problemWith(@NonNull String typed) {

		Problem problem = check(typed);

		if (problem != null) {
			return problem;
		}

		URI uri = URI.create(normalise(typed));

		if ("http".equals(uri.getScheme()) && !NetworkSecurityPolicy.getInstance()
				.isCleartextTrafficPermitted(uri.getHost())) {
			return Problem.CLEARTEXT_REFUSED;
		}
		return null;
	}

	/**
	 * Uses this address from now on, or the build's own when {@code address} is null.
	 * <p>
	 * Called with nobody signed in; see the class notes. Forgets the last sync error, which was
	 * about the server this replaces.
	 *
	 * @param address an address that {@link #normalise} has already been through
	 */
	public static void choose(@NonNull Context context, @Nullable String address) {

		if (address == null ? !isChosen(context) : address.equals(current(context))) {
			return;
		}

		SharedPreferences.Editor editor = prefs(context).edit();

		if (address == null || address.equals(builtIn(context))) {
			editor.remove(CHOSEN);
		} else {
			editor.putString(CHOSEN, address);
		}
		editor.apply();

		if (DeviceIdentity.lastAccountId(context) != 0L) {
			DeviceIdentity.recordLastAccountId(context, DeviceIdentity.ANOTHER_SERVER);
		}
		DeviceIdentity.recordSyncError(context, null);
	}

	/**
	 * What is wrong with a typed address, as far as the text alone can say, or null when there
	 * is nothing. No Android in it, so that it can be tested.
	 */
	@Nullable
	public static Problem check(@NonNull String typed) {

		URI uri;

		try {
			uri = new URI(withScheme(typed));
		} catch (URISyntaxException e) {
			return Problem.NOT_AN_ADDRESS;
		}

		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);

		if (!scheme.equals("https") && !scheme.equals("http")) {
			return Problem.NOT_HTTP;
		}

		// A query, a fragment or a user name would be dropped or sent somewhere surprising.
		// Nobody means one in a server address, so it is refused rather than guessed at.
		if (uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null
				|| uri.getRawUserInfo() != null) {
			return Problem.NOT_AN_ADDRESS;
		}
		return null;
	}

	/**
	 * The address as it is stored: scheme and host in lower case, no trailing slash, and
	 * {@link #API_PATH} when no path was typed.
	 *
	 * @throws IllegalArgumentException when {@link #check} has a problem with it
	 */
	@NonNull
	public static String normalise(@NonNull String typed) {

		Problem problem = check(typed);

		if (problem != null) {
			throw new IllegalArgumentException(problem + ": " + typed);
		}

		URI uri = URI.create(withScheme(typed));

		String path = uri.getRawPath() == null ? "" : uri.getRawPath();

		while (path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}

		return uri.getScheme().toLowerCase(Locale.ROOT) + "://"
				+ uri.getHost().toLowerCase(Locale.ROOT)
				+ (uri.getPort() == -1 ? "" : ":" + uri.getPort())
				+ (path.isEmpty() ? API_PATH : path);
	}

	@NonNull
	private static String withScheme(@NonNull String typed) {

		String trimmed = typed.trim();
		return trimmed.contains("://") ? trimmed : "https://" + trimmed;
	}

	@NonNull
	private static SharedPreferences prefs(@NonNull Context context) {
		// Device-protected, like DeviceIdentity's: a sync after a reboot runs before the user
		// has unlocked the phone, and it has to know where to send.
		return context.createDeviceProtectedStorageContext()
				.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}
}
