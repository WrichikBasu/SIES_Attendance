package in.basulabs.siesattendance.server.web;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import javax.sql.DataSource;

import in.basulabs.siesattendance.backend.AuditAction;
import in.basulabs.siesattendance.backend.StructuredId;
import in.basulabs.siesattendance.backend.util.BackupContainer;
import in.basulabs.siesattendance.server.importer.BackupExporter;
import in.basulabs.siesattendance.server.importer.BackupImporter;
import in.basulabs.siesattendance.server.importer.DeviceDatabase;
import in.basulabs.siesattendance.server.importer.ImportReport;
import in.basulabs.siesattendance.server.importer.Progress;
import in.basulabs.siesattendance.server.importer.StagedBackup;
import in.basulabs.siesattendance.server.security.AuditLog;

import jakarta.servlet.http.HttpServletRequest;

/**
 * {@code .siesbak} files, in and out, for an administrator.
 * <pre>
 * POST /api/v1/backup/import?label=&amp;node=&amp;dryRun=   the file as the body
 * POST /api/v1/backup/export                         {"password": "…" or null}
 * </pre>
 * Import is {@link BackupImporter}, the same run the command line makes, as the signed-in
 * administrator: a dry run reports and rolls back, and either way the run is in
 * {@code import_run}. Export is what {@link BackupExporter} writes: every row a phone could hold,
 * and nothing of the server's own (accounts, roles, class representatives, the audit trail).
 * It is not a backup of the server; {@code pg_dump} is.
 * <p>
 * Both take an optional {@value ProgressController#HEADER}, and say how far they have got at
 * {@link ProgressController}'s endpoint under it while they run.
 * <p>
 * The password travels in a header, Base64 of its UTF-8, because the body is the file and a
 * header can carry only Latin-1. It is never in a URL, where it would be logged.
 */
@RestController
@RequestMapping(Api.V1 + "/backup")
public final class BackupController {

	/** The header an import's password arrives in. */
	public static final String PASSWORD_HEADER = "X-Backup-Password";

	/** The encrypted payload's own ceiling, and room for the header in front of it. */
	private static final long MAX_UPLOAD = BackupContainer.MAX_ENCRYPTED_PAYLOAD + (1 << 20);

	private static final ZoneId COLLEGE = ZoneId.of("Asia/Kolkata");

	/**
	 * One import at a time. Two at once insert the same rows, so the second waits on the
	 * first's locks for as long as the first takes, and then does all the same work again.
	 * In-process, because there is one server.
	 */
	private static final ReentrantLock IMPORTING = new ReentrantLock();

	public record TableCounts(int inserted, int updated, int unchanged) {
	}

	/**
	 * @param failure why nothing was written, when {@code succeeded} is false
	 */
	public record ImportResponse(long runId, boolean dryRun, boolean succeeded,
	                             @Nullable String failure, boolean seenBefore,
	                             int schemaVersion, int inserted, int updated, int unchanged,
	                             int remapped, @NonNull Map<String, TableCounts> tables,
	                             @NonNull List<String> skipped) {
	}

	public record ExportRequest(@Nullable String password) {
	}

	@NonNull
	private final ActorDatabase database;

	@NonNull
	private final DataSource dataSource;

	@NonNull
	private final ProgressController.Board board;

	public BackupController(@NonNull ActorDatabase database, @NonNull DataSource dataSource,
	                        @NonNull ProgressController.Board board) {
		this.database = database;
		this.dataSource = dataSource;
		this.board = board;
	}

	@PostMapping(path = "/import", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
	@NonNull
	public ImportResponse importBackup(
			@RequestParam("label") @NonNull String label,
			@RequestParam("node") int node,
			@RequestParam(name = "dryRun", defaultValue = "true") boolean dryRun,
			@RequestHeader(name = PASSWORD_HEADER, required = false) @Nullable String password,
			@RequestHeader(name = ProgressController.HEADER, required = false)
			@Nullable String progressId,
			@NonNull HttpServletRequest http) throws SQLException, IOException {

		RequestActor actor = RequestActor.of(http);
		refuseUnlessAdmin(actor);

		if (label.isBlank()) {
			throw new ApiException(400, "no_label", "Say which phone this backup came from.");
		}

		if (node < StructuredId.FIRST_ASSIGNABLE_NODE_ID || node > StructuredId.MAX_NODE_ID) {
			throw new ApiException(400, "bad_node", "The device number must be between "
					+ StructuredId.FIRST_ASSIGNABLE_NODE_ID + " and "
					+ StructuredId.MAX_NODE_ID + ".");
		}

		if (!IMPORTING.tryLock()) {
			throw new ApiException(409, "import_running",
					"A backup is already being imported. Try again when it has finished.");
		}

		char[] secret = null;
		Path work = null;
		Progress progress = board.start(progressId, actor.accountId());

		try {
			secret = decode(password);
			work = Files.createTempDirectory("sies-backup-");

			Path file = work.resolve("upload.siesbak");
			progress.stage("Receiving the file");
			receive(http.getInputStream(), file);

			BackupImporter.Outcome outcome = new BackupImporter(dataSource).run(
					new BackupImporter.Request(file, secret, label.trim(), node, COLLEGE, work,
							dryRun, actor.accountId()), progress);

			ImportReport report = outcome.report();
			Map<String, TableCounts> tables = new LinkedHashMap<>();

			report.byTable().forEach((table, counts) -> tables.put(table,
					new TableCounts(counts.inserted(), counts.updated(), counts.unchanged())));

			return new ImportResponse(outcome.runId(), dryRun, outcome.succeeded(),
					outcome.failure(), outcome.seenBefore(), outcome.schemaVersionAsFound(),
					report.totalInserted(), report.totalUpdated(), report.totalUnchanged(),
					report.totalRemapped(), tables, report.skipped());

		} catch (StagedBackup.RejectedException rejected) {
			throw new ApiException(400, "rejected", switch (rejected.rejection()) {
				case NOT_A_BACKUP -> "That is not a .siesbak backup.";
				case FORMAT_TOO_NEW, SCHEMA_TOO_NEW ->
						"That backup is from a newer version of the app than this server reads.";
				case BAD_PASSWORD -> secret == null
						? "That backup is encrypted. Enter its password."
						: "That is not the password for this backup.";
				case DAMAGED -> "That backup is damaged or incomplete.";
				case IO -> "The backup could not be read.";
			});

		} finally {
			if (secret != null) {
				Arrays.fill(secret, '\0');
			}
			if (work != null) {
				deleteQuietly(work);
			}
			board.finish(progressId);
			IMPORTING.unlock();
		}
	}

	@PostMapping("/export")
	@NonNull
	public ResponseEntity<byte[]> exportBackup(
			@RequestBody @NonNull ExportRequest body,
			@RequestHeader(name = ProgressController.HEADER, required = false)
			@Nullable String progressId,
			@NonNull HttpServletRequest http)
			throws SQLException, IOException, GeneralSecurityException {

		RequestActor actor = RequestActor.of(http);
		char[] password = body.password() == null || body.password().isEmpty()
				? null : body.password().toCharArray();

		Progress progress = board.start(progressId, actor.accountId());
		Path work = Files.createTempDirectory("sies-backup-");

		try {
			Path sqlite = work.resolve("export.sqlite3");
			Path packed = work.resolve("export.siesbak");

			database.inTransaction(actor, connection -> {

				refuseUnlessAdmin(connection);

				int rows;

				try (Connection device = DeviceDatabase.createAtCurrentVersion(sqlite,
						COLLEGE)) {
					rows = BackupExporter.copy(connection, device, COLLEGE, progress);
				}

				AuditLog.record(connection, AuditAction.EXPORT, "backup", null,
						AuditLog.detail("rows", rows, "encrypted", password != null));
				return rows;
			});

			progress.stage(password == null ? "Packing the file" : "Encrypting the file");
			DeviceDatabase.pack(sqlite, packed, System.currentTimeMillis(), password);

			String name = "sies-attendance-" + LocalDate.now(COLLEGE) + ".siesbak";

			return ResponseEntity.ok()
					.contentType(MediaType.APPLICATION_OCTET_STREAM)
					.header(HttpHeaders.CONTENT_DISPOSITION,
							ContentDisposition.attachment().filename(name).build().toString())
					.body(Files.readAllBytes(packed));

		} finally {
			if (password != null) {
				Arrays.fill(password, '\0');
			}
			deleteQuietly(work);
			board.finish(progressId);
		}
	}

	// ── Helpers ──────────────────────────────────────────────────────────

	private void refuseUnlessAdmin(@NonNull RequestActor actor) throws SQLException {
		database.inTransaction(actor, connection -> {
			refuseUnlessAdmin(connection);
			return null;
		});
	}

	private static void refuseUnlessAdmin(@NonNull Connection connection) throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT actor_has_role('ADMIN')");
		     ResultSet results = statement.executeQuery()) {

			if (!results.next() || !results.getBoolean(1)) {
				throw new ApiException(403, "not_allowed", "Backups are an administrator's.");
			}
		}
	}

	@Nullable
	private static char[] decode(@Nullable String header) {

		if (header == null || header.isBlank()) {
			return null;
		}

		try {
			byte[] bytes = Base64.getDecoder().decode(header.trim());
			CharBuffer decoded = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes));

			char[] password = new char[decoded.remaining()];
			decoded.get(password);

			Arrays.fill(bytes, (byte) 0);
			Arrays.fill(decoded.array(), '\0');

			return password.length == 0 ? null : password;

		} catch (IllegalArgumentException notBase64) {
			throw new ApiException(400, "bad_password_header",
					PASSWORD_HEADER + " must be Base64 of the password's UTF-8.");
		}
	}

	/** Copies the request body to {@code file}, refusing anything past {@link #MAX_UPLOAD}. */
	private static void receive(@NonNull InputStream in, @NonNull Path file) throws IOException {

		try (OutputStream out = Files.newOutputStream(file)) {

			byte[] buffer = new byte[1 << 16];
			long total = 0;
			int read;

			while ((read = in.read(buffer)) != -1) {

				total += read;

				if (total > MAX_UPLOAD) {
					throw new ApiException(413, "too_large", "That file is too large to be a "
							+ "backup.");
				}
				out.write(buffer, 0, read);
			}

			if (total == 0) {
				throw new ApiException(400, "empty_file", "The request held no file.");
			}
		}
	}

	private static void deleteQuietly(@NonNull Path directory) {

		try (Stream<Path> paths = Files.walk(directory)) {

			List<Path> all = new ArrayList<>(paths.toList());
			all.sort(Comparator.reverseOrder());

			for (Path path : all) {
				Files.deleteIfExists(path);
			}

		} catch (IOException ignored) {
			// A temporary directory; the system clears those.
		}
	}
}
