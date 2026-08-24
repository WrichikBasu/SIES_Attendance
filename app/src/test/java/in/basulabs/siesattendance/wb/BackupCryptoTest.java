package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.crypto.AEADBadTagException;
import javax.crypto.SecretKey;

import in.basulabs.siesattendance.backend.util.BackupCrypto;

/**
 * The password-based encryption behind {@code .siesbak} files. The properties tested here are
 * the ones that decide whether a wrong password fails cleanly or writes plausible rubbish over
 * a real database.
 */
public class BackupCryptoTest {

	private static final byte[] PLAINTEXT =
			"a whole sqlite database, more or less".getBytes(StandardCharsets.UTF_8);

	/** A cheap iteration count: the production 210 000 would make every test slow. */
	private static final int ITERATIONS = 1_000;

	private static byte[] encrypt(char[] password, byte[] salt, byte[] iv, byte[] data)
			throws GeneralSecurityException {
		SecretKey key = BackupCrypto.deriveKey(password, salt, ITERATIONS);
		return BackupCrypto.encryptCipher(key, iv).doFinal(data);
	}

	private static byte[] decrypt(char[] password, byte[] salt, byte[] iv, byte[] data)
			throws GeneralSecurityException {
		SecretKey key = BackupCrypto.deriveKey(password, salt, ITERATIONS);
		return BackupCrypto.decryptCipher(key, iv).doFinal(data);
	}

	// ── Round trip ───────────────────────────────────────────────────────

	@Test
	public void aBackupRoundTripsWithTheRightPassword() throws Exception {
		byte[] salt = BackupCrypto.randomBytes(BackupCrypto.SALT_BYTES);
		byte[] iv = BackupCrypto.randomBytes(BackupCrypto.IV_BYTES);

		byte[] cipherText = encrypt("correct horse".toCharArray(), salt, iv, PLAINTEXT);
		byte[] back = decrypt("correct horse".toCharArray(), salt, iv, cipherText);

		assertArrayEquals(PLAINTEXT, back);
		assertFalse("ciphertext must not equal plaintext",
				Arrays.equals(PLAINTEXT, cipherText));
	}

	@Test
	public void theTagLengthensTheCiphertextByExactlySixteenBytes() throws Exception {
		byte[] salt = BackupCrypto.randomBytes(BackupCrypto.SALT_BYTES);
		byte[] iv = BackupCrypto.randomBytes(BackupCrypto.IV_BYTES);

		byte[] cipherText = encrypt("pw".toCharArray(), salt, iv, PLAINTEXT);
		assertEquals("GCM with a 128-bit tag appends 16 bytes",
				PLAINTEXT.length + 16, cipherText.length);
	}

	// ── Wrong password and tampering ─────────────────────────────────────

	@Test
	public void aWrongPasswordFailsWithATagMismatchRatherThanReturningRubbish() throws Exception {
		byte[] salt = BackupCrypto.randomBytes(BackupCrypto.SALT_BYTES);
		byte[] iv = BackupCrypto.randomBytes(BackupCrypto.IV_BYTES);
		byte[] cipherText = encrypt("right".toCharArray(), salt, iv, PLAINTEXT);

		try {
			decrypt("wrong".toCharArray(), salt, iv, cipherText);
			fail("a wrong password must not decrypt");
		} catch (AEADBadTagException expected) {
			// The restore path maps exactly this to Failure.BAD_PASSWORD.
		}
	}

	@Test
	public void aTruncatedFileFailsRatherThanYieldingAShortButValidDatabase() throws Exception {
		byte[] salt = BackupCrypto.randomBytes(BackupCrypto.SALT_BYTES);
		byte[] iv = BackupCrypto.randomBytes(BackupCrypto.IV_BYTES);
		byte[] cipherText = encrypt("pw".toCharArray(), salt, iv, PLAINTEXT);

		byte[] truncated = Arrays.copyOf(cipherText, cipherText.length - 5);

		try {
			decrypt("pw".toCharArray(), salt, iv, truncated);
			fail("a truncated payload must not decrypt");
		} catch (GeneralSecurityException expected) {
			// This is the reason the restore buffers and calls doFinal rather than streaming
			// through a CipherInputStream, which would report this as a clean end of stream.
		}
	}

	@Test
	public void aSingleFlippedBitIsDetected() throws Exception {
		byte[] salt = BackupCrypto.randomBytes(BackupCrypto.SALT_BYTES);
		byte[] iv = BackupCrypto.randomBytes(BackupCrypto.IV_BYTES);
		byte[] cipherText = encrypt("pw".toCharArray(), salt, iv, PLAINTEXT);

		cipherText[3] ^= 0x01;

		try {
			decrypt("pw".toCharArray(), salt, iv, cipherText);
			fail("tampering must be detected");
		} catch (AEADBadTagException expected) {
			// Authentication, not just encryption: that is the point of GCM here.
		}
	}

	@Test
	public void theWrongSaltOrIvAlsoFails() throws Exception {
		byte[] salt = BackupCrypto.randomBytes(BackupCrypto.SALT_BYTES);
		byte[] iv = BackupCrypto.randomBytes(BackupCrypto.IV_BYTES);
		byte[] cipherText = encrypt("pw".toCharArray(), salt, iv, PLAINTEXT);

		try {
			decrypt("pw".toCharArray(), BackupCrypto.randomBytes(BackupCrypto.SALT_BYTES),
					iv, cipherText);
			fail("a different salt derives a different key");
		} catch (AEADBadTagException expected) {
			// Expected.
		}

		try {
			decrypt("pw".toCharArray(), salt,
					BackupCrypto.randomBytes(BackupCrypto.IV_BYTES), cipherText);
			fail("a different IV must not decrypt");
		} catch (AEADBadTagException expected) {
			// Expected.
		}
	}

	// ── Key derivation ───────────────────────────────────────────────────

	@Test
	public void nonAsciiPasswordsDeriveADeterministicKey() throws Exception {
		// PKCS#5 leaves the character-to-byte conversion undefined outside ASCII, which is
		// why deriveKey does the UTF-8 encoding itself. Two different non-ASCII passwords
		// must never collapse to the same key.
		byte[] salt = new byte[BackupCrypto.SALT_BYTES];

		byte[] a = BackupCrypto.deriveKey("पासवर्ड".toCharArray(), salt, ITERATIONS)
				.getEncoded();
		byte[] b = BackupCrypto.deriveKey("पासवर्ड".toCharArray(), salt, ITERATIONS)
				.getEncoded();
		byte[] c = BackupCrypto.deriveKey("पासवर्ड2".toCharArray(), salt, ITERATIONS)
				.getEncoded();
		byte[] d = BackupCrypto.deriveKey("café🔒".toCharArray(), salt, ITERATIONS)
				.getEncoded();

		assertArrayEquals("the same password must always derive the same key", a, b);
		assertFalse("two different passwords derived the same key", Arrays.equals(a, c));
		assertFalse(Arrays.equals(a, d));
	}

	@Test
	public void charactersThatShareALowByteDoNotCollide() throws Exception {
		// The classic failure of handing char[] straight to PBEKeySpec: implementations that
		// keep only the low byte make U+0041 and U+0141 the same password.
		byte[] salt = new byte[BackupCrypto.SALT_BYTES];

		byte[] a = BackupCrypto.deriveKey(new char[]{'A'}, salt, ITERATIONS).getEncoded();
		byte[] b = BackupCrypto.deriveKey(new char[]{'Ł'}, salt, ITERATIONS).getEncoded();

		assertFalse("U+0041 and U+0141 derived the same key", Arrays.equals(a, b));
	}

	@Test
	public void theDerivedKeyIsTwoHundredAndFiftySixBits() throws Exception {
		byte[] key = BackupCrypto.deriveKey("pw".toCharArray(),
				new byte[BackupCrypto.SALT_BYTES], ITERATIONS).getEncoded();

		assertEquals(32, key.length);
	}

	@Test
	public void aDifferentIterationCountDerivesADifferentKey() throws Exception {
		// The count is written into the file header precisely so it can be raised later
		// without orphaning existing backups.
		byte[] salt = new byte[BackupCrypto.SALT_BYTES];

		byte[] a = BackupCrypto.deriveKey("pw".toCharArray(), salt, 1_000).getEncoded();
		byte[] b = BackupCrypto.deriveKey("pw".toCharArray(), salt, 2_000).getEncoded();

		assertFalse(Arrays.equals(a, b));
	}

	@Test
	public void deriveKeyLeavesTheCallersPasswordBufferAlone() throws Exception {
		// The caller owns the array; wipe() is how they dispose of it. Clearing it inside
		// deriveKey would break the retry-on-wrong-password path.
		char[] password = "keepme".toCharArray();
		BackupCrypto.deriveKey(password, new byte[BackupCrypto.SALT_BYTES], ITERATIONS);

		assertArrayEquals("keepme".toCharArray(), password);
	}

	// ── Randomness ───────────────────────────────────────────────────────

	@Test
	public void everyExportDrawsAFreshSaltAndIv() {
		// Reusing an IV under one key is a real break in GCM, and a user exporting twice with
		// the same password is the expected case, so neither may be constant.
		Set<String> salts = new HashSet<>();
		Set<String> ivs = new HashSet<>();

		for (int i = 0; i < 200; i++) {
			salts.add(Arrays.toString(BackupCrypto.randomBytes(BackupCrypto.SALT_BYTES)));
			ivs.add(Arrays.toString(BackupCrypto.randomBytes(BackupCrypto.IV_BYTES)));
		}

		assertEquals(200, salts.size());
		assertEquals(200, ivs.size());
	}

	@Test
	public void theIvIsTheNinetySixBitsGcmIsSpecifiedFor() {
		assertEquals(12, BackupCrypto.IV_BYTES);
		assertEquals(16, BackupCrypto.SALT_BYTES);
		assertEquals(BackupCrypto.IV_BYTES,
				BackupCrypto.randomBytes(BackupCrypto.IV_BYTES).length);
	}

	@Test
	public void theProductionIterationCountIsNotTokenistic() {
		assertTrue("key stretching below 100k is not worth the name",
				BackupCrypto.DEFAULT_ITERATIONS >= 100_000);
	}

	@Test
	public void encryptingTheSamePlaintextTwiceProducesDifferentBytes() throws Exception {
		byte[] salt = BackupCrypto.randomBytes(BackupCrypto.SALT_BYTES);

		byte[] a = encrypt("pw".toCharArray(), salt,
				BackupCrypto.randomBytes(BackupCrypto.IV_BYTES), PLAINTEXT);
		byte[] b = encrypt("pw".toCharArray(), salt,
				BackupCrypto.randomBytes(BackupCrypto.IV_BYTES), PLAINTEXT);

		assertNotEquals(Arrays.toString(a), Arrays.toString(b));
	}

	// ── wipe ─────────────────────────────────────────────────────────────

	@Test
	public void wipeClearsTheBufferAndToleratesNull() {
		char[] password = "s3cret!!".toCharArray();
		BackupCrypto.wipe(password);

		assertArrayEquals(new char[]{0, 0, 0, 0, 0, 0, 0, 0}, password);
		BackupCrypto.wipe(null);
	}

}
