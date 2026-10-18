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
}
