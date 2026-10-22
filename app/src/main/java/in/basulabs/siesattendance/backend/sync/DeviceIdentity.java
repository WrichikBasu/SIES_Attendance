package in.basulabs.siesattendance.backend.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import in.basulabs.siesattendance.backend.AccountKind;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * What this installation is, and the token that proves it.
 * <p>
 * Three things, with three different levels of secrecy, which is why they are not kept the same
 * way.
 *
 * <h3>The install identifier</h3>
 * A {@link UUID}, generated once and kept for ever. Not a secret — it is sent as a header on
 * every request — and not a device identifier either: it says "this installation of this app",
 * survives nothing but a reinstall, and is what makes registering a device idempotent. A phone
 * that lost it would register again and be issued a second node, which is the thing worth
 * avoiding.
 *
 * <h3>The assigned node</h3>
 * Kept by {@code IdGenerator}, not here, because that is what issues keys with it in them. This
 * class only passes it along after a registration.
 *
 * <h3>The token</h3>
 * A secret, and the one thing in this app that lets somebody act as its user. Encrypted with a
 * key in the Android Keystore, which means the ciphertext in preferences is useless on its own:
 * the key cannot be read out of the Keystore at all, only used, so copying the app's data
 * directory off a rooted phone gets an attacker a blob and nothing else.
 * <p>
 * Not {@code EncryptedSharedPreferences}. That would be a dependency on
 * {@code androidx.security:security-crypto}, which is doing the same AES-GCM-through-the-Keystore
 * dance underneath and has been at alpha for years; this is forty lines and no dependency.
 * <p>
 * The key is deliberately <em>not</em> bound to the user being authenticated. Attendance syncs in
 * the background, after a reboot, while the phone is in somebody's pocket — a key that needed a
 * fingerprint first would mean a device that only syncs when somebody is looking at it.
 */
public final class DeviceIdentity {

	/** Device-protected, like the database: the app has to work before the first unlock. */
	private static final String PREFS = "sync_identity";

	private static final String INSTALL_ID = "installId";

	private static final String TOKEN = "token";

	private static final String TOKEN_IV = "tokenIv";

	private static final String WATERMARK_HINT = "lastSyncError";

	private static final String MUST_ROTATE = "mustRotate";

	private static final String ACCOUNT_KIND = "accountKind";

	private static final String DISPLAY_NAME = "displayName";

	private static final String LAST_SENT = "lastSent";

	private static final String LAST_RECEIVED = "lastReceived";

	private static final String MAY_KEEP_A_ROLL = "mayKeepARoll";

	private static final String REFUSALS = "refusals";

	private static final String REPRESENTS = "represents";

	private static final String LAST_ACCOUNT = "lastAccountId";

	private static final String ROLES = "roles";

	private static final String IMPORT_DEPARTMENTS = "importDepartments";

	private static final String REGISTRATION_PENDING = "registrationPending";

	/** How many refusals are kept. Enough to read; not a log. */
	private static final int REFUSALS_KEPT = 20;

	/** Nothing a refusal says contains one, and it keeps the stored form a plain string. */
	private static final String REFUSAL_SEPARATOR = "\u001f";

	/** The Keystore entry the token is encrypted with. One per installation. */
	private static final String KEY_ALIAS = "sies_sync_token";

	private static final String TRANSFORMATION = "AES/GCM/NoPadding";

	/** 128 bits, which is the only tag length Android's GCM implementation accepts. */
	private static final int TAG_BITS = 128;

	private DeviceIdentity() {
	}

	/** This installation's identifier, generated on first use. */
	@NonNull
	public static synchronized UUID installId(@NonNull Context context) {

		SharedPreferences prefs = prefs(context);
		String stored = prefs.getString(INSTALL_ID, null);

		if (stored != null) {

			try {
				return UUID.fromString(stored);
			} catch (IllegalArgumentException e) {
				// Something wrote nonsense there. A new identifier costs a second registration
				// and a second node; carrying on with a value the server will refuse costs
				// every sync from now on.
			}
		}

		UUID fresh = UUID.randomUUID();
		prefs.edit().putString(INSTALL_ID, fresh.toString()).apply();
		return fresh;
	}

	/** The bearer token, or null when this device has never signed in or has been signed out. */
	@Nullable
	public static synchronized String token(@NonNull Context context) {

		SharedPreferences prefs = prefs(context);
		String sealed = prefs.getString(TOKEN, null);
		String iv = prefs.getString(TOKEN_IV, null);

		if (sealed == null || iv == null) {
			return null;
		}

		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, key(),
					new GCMParameterSpec(TAG_BITS, Base64.getDecoder().decode(iv)));

			return new String(cipher.doFinal(Base64.getDecoder().decode(sealed)),
					java.nio.charset.StandardCharsets.US_ASCII);

		} catch (Exception e) {
			// The key is gone — the app's data was restored onto another phone, or the user
			// removed their screen lock on a release that invalidates keys for it. The token
			// cannot be recovered and the honest answer is that there is none: the user signs
			// in again.
			forgetToken(context);
			return null;
		}
	}

	/** Stores a token the login endpoint issued, replacing any previous one. */
	public static synchronized void storeToken(@NonNull Context context,
	                                           @NonNull String token) {

		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, key());

			String sealed = Base64.getEncoder().encodeToString(
					cipher.doFinal(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));

			prefs(context).edit()
					.putString(TOKEN, sealed)
					.putString(TOKEN_IV, Base64.getEncoder().encodeToString(cipher.getIV()))
					.apply();

		} catch (Exception e) {
			// Nothing can be stored, so nothing is: a token kept in the clear because the
			// Keystore was unavailable would be the one failure mode this class exists to
			// prevent. The user is asked to sign in again, every time, until the device is
			// working -- which is visible, unlike a token sitting in plain preferences.
			throw new IllegalStateException("could not seal the session token", e);
		}
	}

	/** Forgets the token. What signing out, and a 401, both come down to. */
	public static synchronized void forgetToken(@NonNull Context context) {
		prefs(context).edit().remove(TOKEN).remove(TOKEN_IV).apply();
	}

	public static synchronized boolean hasToken(@NonNull Context context) {
		return prefs(context).contains(TOKEN);
	}

	/**
	 * The last thing that went wrong while syncing, for a screen to show, or null.
	 * <p>
	 * In preferences rather than in {@code sync_state}, because it is not part of the device's
	 * position in the feed and a failed sync must not be a database write — a phone with no
	 * signal would otherwise be writing a row every quarter of an hour.
	 */
	@Nullable
	public static String lastSyncError(@NonNull Context context) {
		return prefs(context).getString(WATERMARK_HINT, null);
	}

	public static void recordSyncError(@NonNull Context context, @Nullable String error) {
		prefs(context).edit().putString(WATERMARK_HINT, error).apply();
	}

	/**
	 * Who is signed in, as the server's own display name, or null.
	 * <p>
	 * For the screen to say "Signed in as A Rao" rather than a node number, which is what it
	 * used to say and which answered a question nobody was asking.
	 */
	@Nullable
	public static String displayName(@NonNull Context context) {
		return prefs(context).getString(DISPLAY_NAME, null);
	}

	public static void recordDisplayName(@NonNull Context context, @Nullable String name) {
		prefs(context).edit().putString(DISPLAY_NAME, name).apply();
	}

	/**
	 * How many rows the last sync sent, and how many it received.
	 * <p>
	 * In preferences rather than in {@code sync_state}, for the same reason the last error is:
	 * it is a note for a screen, not part of the device's position in the feed, and a phone
	 * with no signal must not be writing a database row every quarter of an hour. The two
	 * timestamps <em>are</em> in {@code sync_state}, because a watermark and the moment it
	 * moved belong together.
	 */
	public static int lastSent(@NonNull Context context) {
		return prefs(context).getInt(LAST_SENT, 0);
	}

	public static int lastReceived(@NonNull Context context) {
		return prefs(context).getInt(LAST_RECEIVED, 0);
	}

	public static void recordLastRun(@NonNull Context context, int sent, int received) {
		prefs(context).edit().putInt(LAST_SENT, sent).putInt(LAST_RECEIVED, received).apply();
	}

	/**
	 * Whether this account may keep a roll: add a student, correct one, remove one.
	 * <p>
	 * The server's answer, from {@code /me}, asked at sign-in. True by default so that an
	 * install which has never asked behaves as it did before there was a server — the server
	 * refuses what is not this account's either way, and now says so.
	 */
	public static boolean mayKeepARoll(@NonNull Context context) {
		return prefs(context).getBoolean(MAY_KEEP_A_ROLL, true);
	}

	public static void recordMayKeepARoll(@NonNull Context context, boolean may) {
		prefs(context).edit().putBoolean(MAY_KEEP_A_ROLL, may).apply();
	}

	/**
	 * The courses this account is a class representative of, as {@code /me} last said, or
	 * null when it has never said.
	 * <p>
	 * What decides which courses a student account is shown; see {@code Permissions}. Empty
	 * is a real answer: a student whose every appointment has ended is shown no course at all.
	 */
	@Nullable
	public static Set<Long> represents(@NonNull Context context) {

		String stored = prefs(context).getString(REPRESENTS, null);

		if (stored == null) {
			return null;
		}

		Set<Long> courses = new HashSet<>();

		for (String id : stored.split(",")) {
			if (!id.isEmpty()) {
				courses.add(Long.parseLong(id));
			}
		}
		return courses;
	}

	/**
	 * Remembers the courses this account represents. Null forgets them, which is what a
	 * sign-in does before asking, so one account's appointments never outlive it on a phone
	 * passed to somebody else.
	 */
	public static void recordRepresents(@NonNull Context context,
	                                    @Nullable Collection<Long> courses) {

		if (courses == null) {
			prefs(context).edit().remove(REPRESENTS).apply();
			return;
		}

		StringBuilder stored = new StringBuilder();

		for (long id : courses) {
			stored.append(stored.length() == 0 ? "" : ",").append(id);
		}
		prefs(context).edit().putString(REPRESENTS, stored.toString()).apply();
	}

	/**
	 * The account that last signed in on this phone, or 0 when none has been recorded.
	 * <p>
	 * Kept past sign-out on purpose: what it answers is whose view of the server this
	 * database was filled from, and signing out does not empty the database.
	 */
	public static long lastAccountId(@NonNull Context context) {
		return prefs(context).getLong(LAST_ACCOUNT, 0L);
	}

	public static void recordLastAccountId(@NonNull Context context, long accountId) {
		prefs(context).edit().putLong(LAST_ACCOUNT, accountId).apply();
	}

	/**
	 * The roles this account holds, as {@code /me} last said, or null when it has never said.
	 * <p>
	 * What decides the few things in the app that belong to a role rather than to an account's
	 * kind: adding an academic session is an administrator's, for instance. See
	 * {@code Permissions}.
	 */
	@Nullable
	public static Set<String> roles(@NonNull Context context) {

		String stored = prefs(context).getString(ROLES, null);

		if (stored == null) {
			return null;
		}

		Set<String> roles = new HashSet<>();

		for (String role : stored.split(",")) {
			if (!role.isEmpty()) {
				roles.add(role);
			}
		}
		return roles;
	}

	/** Remembers the roles this account holds. Null forgets them, as signing out does. */
	public static void recordRoles(@NonNull Context context, @Nullable Collection<String> roles) {

		if (roles == null) {
			prefs(context).edit().remove(ROLES).apply();
			return;
		}

		prefs(context).edit().putString(ROLES, String.join(",", roles)).apply();
	}

	/** Records what {@code /me} said, keeping the earlier answer for anything it did not say. */
	public static void recordMe(@NonNull Context context, @NonNull SyncClient.Me me) {

		recordMayKeepARoll(context, me.mayKeepARoll());

		if (me.represents() != null) {
			recordRepresents(context, me.represents());
		}

		if (me.roles() != null) {
			recordRoles(context, me.roles());
		}

		if (me.importDepartments() != null) {
			recordImportDepartments(context, me.importDepartments());
		}
	}

	/**
	 * The departments this account may import students into, as {@code /me} last said: a
	 * professor's own and any they head. Null when it has never said, and
	 * {@link SyncClient#ANY_DEPARTMENT} alone for the office and an administrator.
	 */
	@Nullable
	public static Set<String> importDepartments(@NonNull Context context) {

		String stored = prefs(context).getString(IMPORT_DEPARTMENTS, null);

		if (stored == null) {
			return null;
		}

		Set<String> departments = new HashSet<>();
		for (String department : stored.split(",")) {
			if (!department.isEmpty()) {
				departments.add(department);
			}
		}
		return departments;
	}

	/** Null forgets them, as signing in again and signing out do. */
	public static void recordImportDepartments(@NonNull Context context,
	                                           @Nullable Collection<String> departments) {

		if (departments == null) {
			prefs(context).edit().remove(IMPORT_DEPARTMENTS).apply();
			return;
		}
		prefs(context).edit().putString(IMPORT_DEPARTMENTS, String.join(",", departments))
				.apply();
	}

	/**
	 * The refusals the server has sent that nobody has dismissed yet, newest first.
	 * <p>
	 * Kept rather than overwritten, which is the fix for a real silence: the one field this
	 * used to share with transport errors was rewritten on every sync, so a refusal — "removing
	 * a student is the office's to do" — was wiped by the next run a few seconds later, and
	 * whether anybody ever saw it depended on which screen they were looking at. A refusal is
	 * news about something the person did; it waits until they say they have read it.
	 * <p>
	 * Bounded and deduplicated: a device that is refused the same thing in forty batches has
	 * one thing to say.
	 */
	@NonNull
	public static List<String> refusals(@NonNull Context context) {

		String stored = prefs(context).getString(REFUSALS, "");

		if (stored == null || stored.isEmpty()) {
			return List.of();
		}

		return List.of(stored.split(REFUSAL_SEPARATOR));
	}

	public static synchronized void addRefusal(@NonNull Context context,
	                                           @Nullable String refusal) {

		if (refusal == null || refusal.isBlank()) {
			return;
		}

		List<String> kept = new ArrayList<>();
		kept.add(refusal.replace(REFUSAL_SEPARATOR, " "));

		for (String older : refusals(context)) {
			if (!older.equals(kept.get(0)) && kept.size() < REFUSALS_KEPT) {
				kept.add(older);
			}
		}

		prefs(context).edit()
				.putString(REFUSALS, String.join(REFUSAL_SEPARATOR, kept))
				.apply();
	}

	public static void clearRefusals(@NonNull Context context) {
		prefs(context).edit().remove(REFUSALS).apply();
	}

	/**
	 * What kind of account is signed in, as the server named it, or null.
	 * <p>
	 * Not a credential and not an authority: the authority is the policy on the table, and this
	 * is what the screens consult so as not to offer a button that leads to a refusal. See
	 * {@code Permissions}. Null on an install that signed in before this was recorded, and on a
	 * build with no server — both of which {@code Permissions} treats as permitted, because the
	 * alternative is an app that can do nothing.
	 */
	@Nullable
	public static AccountKind accountKind(@NonNull Context context) {

		String stored = prefs(context).getString(ACCOUNT_KIND, null);

		return stored == null ? null : AccountKind.fromAny(stored);
	}

	public static void recordAccountKind(@NonNull Context context,
	                                     @Nullable AccountKind kind) {

		prefs(context).edit()
				.putString(ACCOUNT_KIND, kind == null ? null : kind.name())
				.apply();
	}

	/**
	 * Whether the server said this account's password has to be changed before anything else.
	 * <p>
	 * The server is the authority — {@code app_account.must_rotate}, read live on every token
	 * lookup — and this is only a note of what it said at sign-in, so that the screen can offer
	 * the one thing that will work instead of leaving somebody to find out by syncing and
	 * failing. Not a secret and not a credential: it is one bit about what the next tap should
	 * be.
	 */
	public static boolean passwordChangeRequired(@NonNull Context context) {
		return prefs(context).getBoolean(MUST_ROTATE, false);
	}

	public static void recordPasswordChangeRequired(@NonNull Context context,
	                                                boolean required) {
		prefs(context).edit().putBoolean(MUST_ROTATE, required).apply();
	}

	/**
	 * Whether this install still has to be registered: signing in left it for later because the
	 * account had to change its password first. See {@code SyncSignIn.registerIfPending}.
	 */
	public static boolean registrationPending(@NonNull Context context) {
		return prefs(context).getBoolean(REGISTRATION_PENDING, false);
	}

	public static void recordRegistrationPending(@NonNull Context context, boolean pending) {
		prefs(context).edit().putBoolean(REGISTRATION_PENDING, pending).apply();
	}

	// ── Internals ────────────────────────────────────────────────────────

	/** The Keystore key, created on first use. */
	@NonNull
	private static SecretKey key() throws Exception {

		KeyStore keystore = KeyStore.getInstance("AndroidKeyStore");
		keystore.load(null);

		KeyStore.Entry existing = keystore.getEntry(KEY_ALIAS, null);

		if (existing instanceof KeyStore.SecretKeyEntry entry) {
			return entry.getSecretKey();
		}

		KeyGenerator generator = KeyGenerator.getInstance(
				KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");

		generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
				KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.setKeySize(256)
				// No authentication requirement: see the class notes. Syncing happens while
				// nobody is looking at the phone, which is the point of an outbox.
				.setUserAuthenticationRequired(false)
				.build());

		return generator.generateKey();
	}

	@NonNull
	private static SharedPreferences prefs(@NonNull Context context) {
		// Device-protected, like the database, so a sync can run after a reboot before the
		// user has unlocked the phone.
		return context.createDeviceProtectedStorageContext()
				.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}
}
