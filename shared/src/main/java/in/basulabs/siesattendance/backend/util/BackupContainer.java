package in.basulabs.siesattendance.backend.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;

/**
 * The {@code .siesbak} file format: what is in the header, and how the payload is wrapped.
 * <p>
 * This used to live inside the app's {@code DatabaseBackup}, which was the only thing that
 * read a backup. The server reads them too now — importing a device's data starts with
 * decrypting its backup — and a second implementation of a file format is a format with two
 * definitions, one of which is wrong. So the bytes are described here, once, and the app's
 * export and restore paths call into it exactly as the importer does.
 *
 * <h3>File layout</h3>
 * <pre>
 * magic          8 bytes   "SIESBAK1"
 * format         1 byte    container revision, currently 1
 * encrypted      1 byte    0 or 1
 * schema         4 bytes   Room user_version at export time
 * createdAt      8 bytes   epoch millis
 * appVersion     4 bytes   versionCode that wrote the file
 * iterations     4 bytes   ) present only when encrypted
 * salt          16 bytes   )
 * iv            12 bytes   )
 * payload        rest      gzip(sqlite), then AES-GCM if encrypted
 * </pre>
 * Compression runs before encryption: ciphertext is incompressible, so the other order would
 * save nothing. The header is deliberately readable without the password, which is what lets
 * the UI ask for one only when the file actually needs it, and lets a backup from a newer app
 * version be rejected with a clear message instead of a decryption failure.
 *
 * <h3>Limits, and what a file is trusted with (R6)</h3>
 * A backup is a file somebody hands the server or the phone, so nothing in it is taken on
 * trust:
 * <ul>
 *     <li><b>The header is authenticated</b> from revision 2: every byte of it, up to the
 *     payload, is the encryption's associated data, so a header changed after the file was
 *     written -- a schema version, a creation date -- fails the password check like a changed
 *     payload does. A revision-1 file is still read, with its header taken as written; there is
 *     no key to check an unencrypted file's header against at all.</li>
 *     <li><b>The round count</b> is read from the file and used to derive the key, so it is
 *     refused past {@link #MAX_ITERATIONS} before any deriving starts. Five hundred million
 *     rounds was minutes of a core for a five-byte file.</li>
 *     <li><b>The unpacked size</b> is capped at {@link #MAX_UNPACKED}. A gzip stream can
 *     unpack to hundreds of times its size, and an unencrypted file had no ceiling at all.</li>
 *     <li><b>The encrypted payload</b> is buffered whole and decrypted whole (see
 *     {@link #unpack}), so it is held twice; {@link #MAX_ENCRYPTED_PAYLOAD} keeps that to a
 *     size any phone or server has room for.</li>
 * </ul>
 *
 * @see BackupCrypto for the encryption itself
 */
public final class BackupContainer {

	private static final byte[] MAGIC = {'S', 'I', 'E', 'S', 'B', 'A', 'K', '1'};

	/**
	 * The container revision this code writes, and the highest it reads. Revision 2
	 * authenticates the header (R6); revision 1 is still read.
	 */
	public static final byte FORMAT_VERSION = 2;

	/** The first revision whose header is the encryption's associated data. */
	private static final int FORMAT_AUTHENTICATED_HEADER = 2;

	/**
	 * A ceiling on the encrypted payload, which is buffered whole and decrypted whole, so held
	 * twice at the peak (see {@link #unpack}). A college's roster and a session of attendance
	 * compress to a few megabytes; this is far above that, and only here so an absurd or
	 * hostile file fails with a message instead of taking the heap.
	 */
	public static final long MAX_ENCRYPTED_PAYLOAD = 64L * 1024 * 1024;

	/**
	 * A ceiling on the database a file unpacks to, encrypted or not. A gzip stream can expand to
	 * hundreds of times its size: six hundred megabytes of zeros fit in under one.
	 */
	public static final long MAX_UNPACKED = 256L * 1024 * 1024;

	/**
	 * The most key-derivation rounds a file may ask for: ten times what this code writes. The
	 * count is read from the file, and deriving the key runs it, so a file asking for five
	 * hundred million rounds was minutes of a core before the password was even checked.
	 */
	public static final int MAX_ITERATIONS = BackupCrypto.DEFAULT_ITERATIONS * 10;

	/**
	 * The schema version reported for a file whose container revision is newer than
	 * {@link #FORMAT_VERSION}. Negative because nothing else in the header can be trusted once
	 * the layout is unknown, so the value has to be one no real schema version could be.
	 */
	public static final int SCHEMA_UNREADABLE = -1;

	/**
	 * Tables that must exist for an unpacked file to be one of ours rather than some other
	 * SQLite database. Checked by the caller, which is the side holding an open connection.
	 */
	public static final Set<String> EXPECTED_TABLES = Collections.unmodifiableSet(
			new HashSet<>(Arrays.asList(
					"students", "student_year", "courses", "lectures", "attendance",
					"professors", "academic_years", "batch_desc")));

	private BackupContainer() {
	}

	/**
	 * Raised when a file is structurally a backup but cannot be unpacked: truncated, corrupt,
	 * or carrying encryption parameters that make no sense.
	 * <p>
	 * A subclass rather than a plain {@link IOException} so a caller can tell this apart from a
	 * failure to read the underlying file, which is not the backup's fault and wants a
	 * different sentence in front of the user. The message is allowed to be null, for the cases
	 * where there is nothing to add to that.
	 */
	public static class DamagedBackupException extends IOException {

		private static final long serialVersionUID = 1L;

		public DamagedBackupException(@Nullable String message) {
			super(message);
		}
	}

	/**
	 * What the header says about a file, read without needing the password.
	 */
	public static final class Info {

		/** The container revision the file was written at. */
		public final int format;

		public final boolean encrypted;

		/**
		 * The Room {@code user_version} the file was written at, or
		 * {@link #SCHEMA_UNREADABLE} when the container format itself is too new to read, in
		 * which case nothing else here is meaningful.
		 */
		public final int schemaVersion;

		/** When the backup was written, as epoch milliseconds. */
		public final long createdAt;

		/** The app version that wrote it. Zero when it could not be determined. */
		public final int appVersionCode;

		public Info(boolean encrypted, int schemaVersion, long createdAt, int appVersionCode) {
			this(FORMAT_VERSION, encrypted, schemaVersion, createdAt, appVersionCode);
		}

		public Info(int format, boolean encrypted, int schemaVersion, long createdAt,
		            int appVersionCode) {
			this.format = format;
			this.encrypted = encrypted;
			this.schemaVersion = schemaVersion;
			this.createdAt = createdAt;
			this.appVersionCode = appVersionCode;
		}

		/** Whether the container revision is newer than this build knows how to read. */
		public boolean formatTooNew() {
			return schemaVersion < 0;
		}
	}

	// ── Reading ──────────────────────────────────────────────────────────

	/**
	 * Reads and checks the fixed header, leaving the stream positioned at the encryption
	 * parameters if there are any, and at the payload otherwise.
	 *
	 * @return null when the file is not one of ours at all, or an {@link Info} with
	 *         {@link Info#formatTooNew()} when the container format is newer than this build
	 *         reads
	 */
	@Nullable
	public static Info readHeader(@NonNull DataInputStream in) throws IOException {

		byte[] magic = new byte[MAGIC.length];

		try {
			in.readFully(magic);
		} catch (IOException e) {
			return null;
		}

		if (!Arrays.equals(magic, MAGIC)) {
			return null;
		}

		int format = in.readUnsignedByte();

		if (format > FORMAT_VERSION) {
			return new Info(false, SCHEMA_UNREADABLE, 0L, 0);
		}

		boolean encrypted = in.readBoolean();
		int schemaVersion = in.readInt();
		long createdAt = in.readLong();
		int appVersionCode = in.readInt();

		return new Info(format, encrypted, schemaVersion, createdAt, appVersionCode);
	}

	/**
	 * The header as it was written, up to the payload: what revision 2 authenticates. Rebuilt
	 * from what was read rather than kept as bytes, which is the same thing, since every field
	 * is fixed-width.
	 */
	@NonNull
	private static byte[] headerBytes(int format, boolean encrypted, int schemaVersion,
	                                  long createdAt, int appVersionCode, int iterations,
	                                  @NonNull byte[] salt, @NonNull byte[] iv)
			throws IOException {

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);

		out.write(MAGIC);
		out.writeByte(format);
		out.writeBoolean(encrypted);
		out.writeInt(schemaVersion);
		out.writeLong(createdAt);
		out.writeInt(appVersionCode);
		out.writeInt(iterations);
		out.write(salt);
		out.write(iv);
		out.flush();
		return bytes.toByteArray();
	}

	/**
	 * Decrypts if needed, decompresses, and writes the SQLite file to {@code target}.
	 * <p>
	 * The stream must be positioned where {@link #readHeader} left it. The target is not
	 * closed; the caller owns it.
	 *
	 * @param password the password for an encrypted file, which the caller has already checked
	 *                 is present; ignored, and may be null, for one that is not encrypted. Not
	 *                 wiped here — the caller owns it, and usually has more to do with it
	 * @throws javax.crypto.AEADBadTagException if the password is wrong, which is the whole
	 *                                          reason GCM is used
	 * @throws DamagedBackupException           if the file is truncated, oversized, or its
	 *                                          encryption parameters are not usable
	 */
	public static void unpack(@NonNull DataInputStream in, @NonNull Info info,
	                          @Nullable char[] password, @NonNull OutputStream target)
			throws IOException, GeneralSecurityException {

		InputStream compressed;

		if (info.encrypted) {

			int iterations = in.readInt();
			byte[] salt = new byte[BackupCrypto.SALT_BYTES];
			byte[] iv = new byte[BackupCrypto.IV_BYTES];
			in.readFully(salt);
			in.readFully(iv);

			if (iterations <= 0) {
				throw new DamagedBackupException(null);
			}

			if (iterations > MAX_ITERATIONS) {
				// Refused before anything is derived: deriving is what the count would cost.
				throw new DamagedBackupException("The file asks for " + iterations
						+ " rounds of key derivation, far more than any release of this app "
						+ "writes.");
			}

			if (password == null) {
				throw new DamagedBackupException(
						"The file is encrypted and no password was given.");
			}

			byte[] ciphertext = readPayload(in);

			SecretKey key = BackupCrypto.deriveKey(password, salt, iterations);
			Cipher cipher = BackupCrypto.decryptCipher(key, iv);

			if (info.format >= FORMAT_AUTHENTICATED_HEADER) {
				// A header changed after the file was written fails here like a wrong password.
				cipher.updateAAD(headerBytes(info.format, true, info.schemaVersion,
						info.createdAt, info.appVersionCode, iterations, salt, iv));
			}

			// doFinal on the whole payload rather than a CipherInputStream. GCM only knows
			// the data was untampered once it reaches the tag at the very end, and a
			// streaming reader tends to report that as a plain end-of-stream. That would
			// look like a short but valid file and get written over the live database.
			// Buffering costs memory but turns a wrong password into a thrown exception,
			// which is the whole point of using an authenticated mode.
			byte[] plaintext = cipher.doFinal(ciphertext);
			Arrays.fill(ciphertext, (byte) 0);
			compressed = new ByteArrayInputStream(plaintext);

		} else {
			compressed = in;
		}

		try (GZIPInputStream gzip = new GZIPInputStream(compressed)) {
			copy(gzip, target, MAX_UNPACKED);
		}
	}

	// ── Writing ──────────────────────────────────────────────────────────

	/**
	 * Writes the header, and the encryption parameters when there are any, and returns the
	 * stream the SQLite file's bytes go into.
	 * <p>
	 * Closing the returned stream is what finishes the file: it flushes the gzip trailer and
	 * then closes the cipher stream underneath, which is what writes the GCM authentication
	 * tag. Leaving either unclosed produces a file that will not verify on restore, so the
	 * caller must close it inside a try-with-resources rather than relying on the stream it
	 * wrapped.
	 *
	 * @param password null or empty for an unencrypted file; not wiped here, since the caller
	 *                 owns the array
	 */
	@NonNull
	public static OutputStream openForWriting(@NonNull DataOutputStream out, int schemaVersion,
	                                          long createdAt, int appVersionCode,
	                                          @Nullable char[] password)
			throws IOException, GeneralSecurityException {

		boolean encrypted = password != null && password.length > 0;

		out.write(MAGIC);
		out.writeByte(FORMAT_VERSION);
		out.writeBoolean(encrypted);
		out.writeInt(schemaVersion);
		out.writeLong(createdAt);
		out.writeInt(appVersionCode);

		OutputStream payload = out;

		if (encrypted) {

			byte[] salt = BackupCrypto.randomBytes(BackupCrypto.SALT_BYTES);
			byte[] iv = BackupCrypto.randomBytes(BackupCrypto.IV_BYTES);
			int iterations = BackupCrypto.DEFAULT_ITERATIONS;

			out.writeInt(iterations);
			out.write(salt);
			out.write(iv);
			out.flush();

			SecretKey key = BackupCrypto.deriveKey(password, salt, iterations);
			Cipher cipher = BackupCrypto.encryptCipher(key, iv);

			// The whole header, so a reader can tell when any of it has been changed (R6).
			cipher.updateAAD(headerBytes(FORMAT_VERSION, true, schemaVersion, createdAt,
					appVersionCode, iterations, salt, iv));

			payload = new CipherOutputStream(out, cipher);
		}

		return new GZIPOutputStream(payload);
	}

	// ── Helpers ──────────────────────────────────────────────────────────

	/**
	 * Reads the rest of the stream, refusing anything past
	 * {@link #MAX_ENCRYPTED_PAYLOAD}.
	 */
	@NonNull
	private static byte[] readPayload(@NonNull InputStream in) throws IOException {

		ByteArrayOutputStream buffer = new ByteArrayOutputStream(1 << 16);
		byte[] chunk = new byte[8192];
		long total = 0;
		int read;

		while ((read = in.read(chunk)) != -1) {

			total += read;

			if (total > MAX_ENCRYPTED_PAYLOAD) {
				throw new DamagedBackupException("The file is unexpectedly large.");
			}
			buffer.write(chunk, 0, read);
		}
		return buffer.toByteArray();
	}

	/** Copies, refusing anything past {@code limit} bytes. */
	private static void copy(@NonNull InputStream in, @NonNull OutputStream out, long limit)
			throws IOException {

		byte[] buffer = new byte[8192];
		long total = 0;
		int read;

		while ((read = in.read(buffer)) != -1) {

			total += read;

			if (total > limit) {
				throw new DamagedBackupException("The file unpacks to more than "
						+ (limit >> 20) + " MB, which no backup of this app does.");
			}
			out.write(buffer, 0, read);
		}
		out.flush();
	}
}
