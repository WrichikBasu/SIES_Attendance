package in.basulabs.siesattendance.backend.util;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Password-based encryption for backup files.
 * <p>
 * AES-256 in GCM mode, with the key stretched from the password by PBKDF2-HMAC-SHA256. GCM is
 * chosen over CBC because it authenticates as well as encrypts: a file that has been truncated,
 * corrupted in transit, or edited by hand fails to decrypt rather than yielding plausible
 * garbage that would then be written over a real database. The same property gives a free and
 * reliable wrong-password check, since a bad key produces a tag mismatch and nothing else.
 * <p>
 * Every export draws a fresh salt and IV. Reusing an IV under one key would be a real break in
 * GCM, and since a user is likely to export repeatedly with the same password, the salt must
 * not be a constant either.
 * <p>
 * Nothing here is stored: the password is never written to the file, to preferences, or to the
 * database. A forgotten password means the backup is unreadable, which is the intended
 * property of the feature and is stated plainly in the UI.
 */
public final class BackupCrypto {

	/** 256-bit keys; AES-256 is unrestricted on every API level this app supports. */
	private static final int KEY_BITS = 256;

	public static final int SALT_BYTES = 16;

	/** GCM is specified for a 96-bit IV; other lengths force an internal rehash. */
	public static final int IV_BYTES = 12;

	private static final int TAG_BITS = 128;

	/**
	 * Deliberately expensive, to make guessing a weak password slow. Stored in the file header
	 * rather than assumed, so this number can rise in a later release without orphaning
	 * backups already written with the current value.
	 */
	public static final int DEFAULT_ITERATIONS = 210_000;

	private static final String KDF = "PBKDF2WithHmacSHA256";

	private static final String TRANSFORMATION = "AES/GCM/NoPadding";

	private BackupCrypto() {
	}

	@NonNull
	public static byte[] randomBytes(int length) {
		byte[] out = new byte[length];
		new SecureRandom().nextBytes(out);
		return out;
	}

	/**
	 * Stretches {@code password} into an AES key.
	 * <p>
	 * The password is converted to UTF-8 here rather than handed to {@link PBEKeySpec} as
	 * characters. PKCS#5 only defines the conversion for ASCII, and implementations disagree
	 * about the rest — some keep the low byte of each character and discard the rest, which
	 * would silently make two different non-ASCII passwords equivalent. Doing the encoding
	 * explicitly means a password containing accents, Devanagari, or an emoji derives the same
	 * key on every device and in any future version of this app.
	 * <p>
	 * Slow by design; must not be called on the main thread.
	 */
	@NonNull
	public static SecretKey deriveKey(@NonNull char[] password, @NonNull byte[] salt,
	                                  int iterations) throws GeneralSecurityException {

		byte[] utf8 = toUtf8(password);

		// Each byte is widened into one character in the range 0-255, so the implementation's
		// own narrowing back to a byte is lossless whichever convention it follows.
		char[] latin1 = new char[utf8.length];
		for (int i = 0; i < utf8.length; i++) {
			latin1[i] = (char) (utf8[i] & 0xFF);
		}

		PBEKeySpec spec = null;

		try {
			spec = new PBEKeySpec(latin1, salt, iterations, KEY_BITS);
			byte[] keyBytes = SecretKeyFactory.getInstance(KDF).generateSecret(spec).getEncoded();
			return new SecretKeySpec(keyBytes, "AES");
		} finally {
			if (spec != null) {
				spec.clearPassword();
			}
			Arrays.fill(utf8, (byte) 0);
			Arrays.fill(latin1, '\0');
		}
	}

	@NonNull
	public static Cipher encryptCipher(@NonNull SecretKey key, @NonNull byte[] iv)
			throws GeneralSecurityException {

		Cipher cipher = Cipher.getInstance(TRANSFORMATION);
		cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
		return cipher;
	}

	@NonNull
	public static Cipher decryptCipher(@NonNull SecretKey key, @NonNull byte[] iv)
			throws GeneralSecurityException {

		Cipher cipher = Cipher.getInstance(TRANSFORMATION);
		cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
		return cipher;
	}

	/**
	 * Overwrites a password buffer once it is no longer needed. Java strings cannot be wiped,
	 * which is why passwords travel through this file as {@code char[]}.
	 */
	public static void wipe(char[] password) {
		if (password != null) {
			Arrays.fill(password, '\0');
		}
	}

	/**
	 * Encodes without ever materialising a String, which would linger in the heap until
	 * garbage collection and could not be cleared.
	 */
	@NonNull
	private static byte[] toUtf8(@NonNull char[] password) {

		ByteBuffer buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
		byte[] out = new byte[buffer.remaining()];
		buffer.get(out);

		// The encoder's backing array may hold a copy of the plaintext.
		if (buffer.hasArray()) {
			Arrays.fill(buffer.array(), (byte) 0);
		}
		return out;
	}
}
