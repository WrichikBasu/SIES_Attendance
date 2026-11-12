package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.Random;

import javax.crypto.AEADBadTagException;

import in.basulabs.siesattendance.backend.util.BackupContainer;

/**
 * The {@code .siesbak} container, written and read back.
 * <p>
 * Worth testing on its own now that two programs read it. The app wrote these files and the app
 * read them, so a mistake in the format was a mistake in one place and showed up immediately;
 * the server reads them too from this phase on, and a disagreement about the layout would look
 * like a corrupt backup rather than like a bug.
 * <p>
 * The payload here is random bytes, not a database. Nothing in the container cares what it is
 * wrapping, and using real SQLite would test SQLite.
 */
public class BackupContainerTest {

	private static final int SCHEMA_VERSION = 10;

	private static final long CREATED_AT = 1_760_000_000_000L;

	private static final int APP_VERSION = 42;

	/** Deterministic, so a failure is reproducible rather than a story about one run. */
	private static byte[] payload(int length) {
		byte[] bytes = new byte[length];
		new Random(20251018L).nextBytes(bytes);
		return bytes;
	}

	/** Writes a complete backup file and hands back its bytes. */
	private static byte[] write(byte[] contents, char[] password)
			throws IOException, GeneralSecurityException {

		ByteArrayOutputStream file = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(file);

		try (OutputStream body = BackupContainer.openForWriting(out, SCHEMA_VERSION, CREATED_AT,
				APP_VERSION, password)) {
			body.write(contents);
		}
		return file.toByteArray();
	}

	/** Reads one back, returning the unpacked payload. */
	private static byte[] read(byte[] file, char[] password)
			throws IOException, GeneralSecurityException {

		DataInputStream in = new DataInputStream(new ByteArrayInputStream(file));
		BackupContainer.Info info = BackupContainer.readHeader(in);

		assertNotNull("header should have parsed", info);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BackupContainer.unpack(in, info, password, out);
		return out.toByteArray();
	}

	@Test
	public void anUnencryptedBackupRoundTrips() throws Exception {

		byte[] contents = payload(64 * 1024);
		byte[] file = write(contents, null);

		DataInputStream in = new DataInputStream(new ByteArrayInputStream(file));
		BackupContainer.Info info = BackupContainer.readHeader(in);

		assertNotNull(info);
		assertFalse("not encrypted", info.encrypted);
		assertFalse(info.formatTooNew());
		assertEquals(SCHEMA_VERSION, info.schemaVersion);
		assertEquals(CREATED_AT, info.createdAt);
		assertEquals(APP_VERSION, info.appVersionCode);

		assertArrayEquals(contents, read(file, null));
	}

	@Test
	public void anEncryptedBackupRoundTrips() throws Exception {

		byte[] contents = payload(64 * 1024);
		byte[] file = write(contents, "correct horse".toCharArray());

		DataInputStream in = new DataInputStream(new ByteArrayInputStream(file));
		BackupContainer.Info info = BackupContainer.readHeader(in);

		assertNotNull(info);
		assertTrue("encrypted", info.encrypted);
		assertEquals(SCHEMA_VERSION, info.schemaVersion);

		assertArrayEquals(contents, read(file, "correct horse".toCharArray()));
	}

	/**
	 * The header has to be readable without the password. It is what lets the UI ask for one
	 * only when the file needs it, so a change that encrypted it would break the flow rather
	 * than fail a decryption.
	 */
	@Test
	public void theHeaderIsReadableWithoutThePassword() throws Exception {

		byte[] file = write(payload(1024), "a password".toCharArray());

		DataInputStream in = new DataInputStream(new ByteArrayInputStream(file));
		BackupContainer.Info info = BackupContainer.readHeader(in);

		assertNotNull(info);
		assertTrue(info.encrypted);
		assertEquals(SCHEMA_VERSION, info.schemaVersion);
	}

	/**
	 * The one property the whole scheme rests on: a wrong password fails loudly instead of
	 * producing plausible bytes that would then be written over a database.
	 */
	@Test
	public void aWrongPasswordIsRefusedRatherThanDecryptedBadly() throws Exception {

		byte[] file = write(payload(32 * 1024), "the right one".toCharArray());

		assertThrows(AEADBadTagException.class,
				() -> read(file, "the wrong one".toCharArray()));
	}

	/** Truncation is tampering as far as GCM is concerned, and has to be caught the same way. */
	@Test
	public void aTruncatedEncryptedBackupIsRefused() throws Exception {

		byte[] file = write(payload(32 * 1024), "a password".toCharArray());
		byte[] truncated = new byte[file.length - 64];
		System.arraycopy(file, 0, truncated, 0, truncated.length);

		assertThrows(GeneralSecurityException.class,
				() -> read(truncated, "a password".toCharArray()));
	}

	@Test
	public void somethingThatIsNotABackupIsNotMistakenForOne() throws Exception {

		byte[] notABackup = "SQLite format 3\0and then some other file entirely"
				.getBytes("UTF-8");

		assertNull(BackupContainer.readHeader(
				new DataInputStream(new ByteArrayInputStream(notABackup))));
	}

	/** A file shorter than the magic must not be read as a header made of whatever follows. */
	@Test
	public void aFileShorterThanTheMagicIsRefused() throws Exception {
		assertNull(BackupContainer.readHeader(
				new DataInputStream(new ByteArrayInputStream(new byte[]{'S', 'I', 'E'}))));
	}

	/**
	 * A container revision this build does not know has to be refused by the header rather
	 * than guessed at, since every field after the revision byte could have moved.
	 */
	@Test
	public void aNewerContainerRevisionIsRefusedWithoutGuessing() throws Exception {

		byte[] file = write(payload(1024), null);

		// The revision byte sits immediately after the eight-byte magic.
		file[8] = (byte) (BackupContainer.FORMAT_VERSION + 1);

		BackupContainer.Info info = BackupContainer.readHeader(
				new DataInputStream(new ByteArrayInputStream(file)));

		assertNotNull("still recognisably one of ours", info);
		assertTrue("but not readable", info.formatTooNew());
		assertEquals(BackupContainer.SCHEMA_UNREADABLE, info.schemaVersion);
	}

	/**
	 * Encryption parameters that cannot be used have to stop the unpack. A zero iteration
	 * count would otherwise derive a key from nothing.
	 */
	@Test
	public void unusableEncryptionParametersAreRefused() throws Exception {

		byte[] file = write(payload(1024), "a password".toCharArray());

		// iterations is the first four bytes after the twenty-six byte fixed header.
		for (int i = 26; i < 30; i++) {
			file[i] = 0;
		}

		assertThrows(BackupContainer.DamagedBackupException.class,
				() -> read(file, "a password".toCharArray()));
	}

	/** Compression happens, which is the reason it runs before encryption rather than after. */
	@Test
	public void thePayloadIsCompressed() throws Exception {

		// Zeroes rather than the random payload: random bytes do not compress, and what is
		// being checked is that gzip is in the chain at all.
		byte[] compressible = new byte[256 * 1024];
		byte[] file = write(compressible, null);

		assertTrue("gzip should have shrunk a quarter-megabyte of zeroes, got "
				+ file.length, file.length < compressible.length / 10);
	}

	/**
	 * Every backup made before R6 is revision 1, with no authenticated header, and has to keep
	 * opening: the change is in what new files carry, not in which old ones are accepted.
	 */
	@Test
	public void aRevisionOneFileStillOpens() throws Exception {

		char[] password = "correct horse".toCharArray();
		byte[] salt = in.basulabs.siesattendance.backend.util.BackupCrypto.randomBytes(
				in.basulabs.siesattendance.backend.util.BackupCrypto.SALT_BYTES);
		byte[] iv = in.basulabs.siesattendance.backend.util.BackupCrypto.randomBytes(
				in.basulabs.siesattendance.backend.util.BackupCrypto.IV_BYTES);
		int iterations = 1000;

		// Written as revision 1 wrote it: the same header, and no associated data.
		java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
		java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
		out.write(new byte[]{'S', 'I', 'E', 'S', 'B', 'A', 'K', '1'});
		out.writeByte(1);
		out.writeBoolean(true);
		out.writeInt(7);
		out.writeLong(1_700_000_000_000L);
		out.writeInt(20);
		out.writeInt(iterations);
		out.write(salt);
		out.write(iv);

		javax.crypto.Cipher cipher = in.basulabs.siesattendance.backend.util.BackupCrypto
				.encryptCipher(in.basulabs.siesattendance.backend.util.BackupCrypto
						.deriveKey(password, salt, iterations), iv);
		try (java.io.OutputStream gzip = new java.util.zip.GZIPOutputStream(
				new javax.crypto.CipherOutputStream(out, cipher))) {
			gzip.write("an old backup".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}

		java.io.DataInputStream read = new java.io.DataInputStream(
				new java.io.ByteArrayInputStream(bytes.toByteArray()));
		BackupContainer.Info info = BackupContainer.readHeader(read);
		assertEquals(1, info.format);

		java.io.ByteArrayOutputStream unpacked = new java.io.ByteArrayOutputStream();
		BackupContainer.unpack(read, info, password, unpacked);
		assertEquals("an old backup",
				unpacked.toString(java.nio.charset.StandardCharsets.UTF_8.name()));
	}
}
