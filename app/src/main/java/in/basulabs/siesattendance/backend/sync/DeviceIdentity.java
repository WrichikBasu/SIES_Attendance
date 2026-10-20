package in.basulabs.siesattendance.backend.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.security.KeyStore;
import java.util.Base64;
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
