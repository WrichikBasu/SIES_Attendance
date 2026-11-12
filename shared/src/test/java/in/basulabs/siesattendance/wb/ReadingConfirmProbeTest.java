package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPOutputStream;

import in.basulabs.siesattendance.backend.util.AttendanceCsvParser;
import in.basulabs.siesattendance.backend.util.BackupContainer;
import in.basulabs.siesattendance.backend.util.RosterCsv;

/**
 * Probes for findings that had only been read, not reproduced. Each test states the behaviour
 * that would be right, so a failure here is the finding confirmed. Numbers are those of the
 * review.
 */
public class ReadingConfirmProbeTest {

	// ── R21: dates have no sanity bounds ─────────────────────────────────

	@Test
	public void r21_rosterRefusesAJoinDateInTheYear25() {
		assertThrows("0025-06-15 is a typo for 2025, not a date to store",
				IllegalArgumentException.class,
				() -> RosterCsv.parseDate("0025-06-15", "join date"));
	}

	@Test
	public void r21_rosterRefusesAJoinDateInTheYear12345() {
		assertThrows("+12345-06-15 is not a date anybody joined on",
				IllegalArgumentException.class,
				() -> RosterCsv.parseDate("+12345-06-15", "join date"));
	}

	@Test
	public void r21_attendanceSheetRefusesAClassInTheYear25() {
		AttendanceCsvParser.Sheet sheet = AttendanceCsvParser.parse(List.of(
				"Course code,CS-101", "Date,15/06/0025", "Start time,09:00",
				"End time,10:00", "CS-FY-001,P"));

		boolean refused = !sheet.errors.isEmpty()
				|| sheet.columns.isEmpty()
				|| !sheet.columns.get(0).problems.isEmpty();

		assertTrue("a class dated 15/06/0025 was accepted as "
				+ (sheet.columns.isEmpty() ? "?" : sheet.columns.get(0).date), refused);
	}

	// ── R6: the backup file format has no limits ─────────────────────────

	private static final char[] PASSWORD = "correct horse".toCharArray();

	private static byte[] pack(char[] password, byte[] body) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (OutputStream out = BackupContainer.openForWriting(new DataOutputStream(bytes), 7,
				1_700_000_000L, 20, password)) {
			out.write(body);
		}
		return bytes.toByteArray();
	}

	/** Header: magic 8, format 1, encrypted 1, schema 4, created 8, app version 4 = 26. */
	private static final int ITERATIONS_AT = 26;

	private static final int SCHEMA_AT = 10;

	@Test
	public void r6_anAbsurdRoundCountIsRefusedWithoutBeingRun() throws Exception {

		byte[] file = pack(PASSWORD, "hello".getBytes());
		// 500 million rounds, some 2,400 times the default: minutes of one core.
		ByteBuffer.wrap(file).putInt(ITERATIONS_AT, 500_000_000);

		ExecutorService pool = Executors.newSingleThreadExecutor();
		Future<String> outcome = pool.submit(() -> {
			DataInputStream in = new DataInputStream(new ByteArrayInputStream(file));
			BackupContainer.Info info = BackupContainer.readHeader(in);
			try {
				BackupContainer.unpack(in, info, PASSWORD.clone(), new ByteArrayOutputStream());
				return "unpacked";
			} catch (Exception e) {
				return "refused";
			}
		});

		try {
			assertEquals("refused", outcome.get(5, TimeUnit.SECONDS));
		} catch (TimeoutException e) {
			outcome.cancel(true);
			fail("the round count in the file was simply run: still deriving the key after "
					+ "5 seconds for a 5-byte backup");
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	public void r6_aChangedHeaderIsNoticed() throws Exception {

		byte[] file = pack(PASSWORD, "hello".getBytes());
		ByteBuffer.wrap(file).putInt(SCHEMA_AT, 3);

		DataInputStream in = new DataInputStream(new ByteArrayInputStream(file));
		BackupContainer.Info info = BackupContainer.readHeader(in);
		assertNotNull(info);
		assertEquals("the header as tampered with", 3, info.schemaVersion);

		assertThrows("the schema version was changed after the file was written, and the "
						+ "password still opens it as if nothing had happened",
				Exception.class,
				() -> BackupContainer.unpack(in, info, PASSWORD.clone(),
						new ByteArrayOutputStream()));
	}

	@Test
	public void r6_anUnencryptedFileCannotUnpackToAnySize() throws Exception {

		// 600 MB of zeros, which is well under a megabyte in the file.
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (OutputStream out = BackupContainer.openForWriting(new DataOutputStream(bytes), 7,
				1_700_000_000L, 20, null)) {
			byte[] zeros = new byte[1 << 20];
			for (int i = 0; i < 600; i++) {
				out.write(zeros);
			}
		}
		byte[] file = bytes.toByteArray();
		assertTrue("the file itself is small: " + file.length, file.length < (1 << 20));

		long[] written = {0};
		OutputStream counting = new OutputStream() {
			@Override
			public void write(int b) {
				written[0]++;
			}

			@Override
			public void write(byte[] b, int off, int len) {
				written[0] += len;
			}
		};

		DataInputStream in = new DataInputStream(new ByteArrayInputStream(file));
		BackupContainer.Info info = BackupContainer.readHeader(in);

		try {
			BackupContainer.unpack(in, info, null, counting);
			fail("a " + file.length + "-byte file unpacked to " + written[0]
					+ " bytes with nothing to stop it");
		} catch (IOException expected) {
			// Right: there is a ceiling.
		}
	}
}
