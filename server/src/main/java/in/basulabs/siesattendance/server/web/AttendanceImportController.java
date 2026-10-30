package in.basulabs.siesattendance.server.web;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import in.basulabs.siesattendance.backend.AuditAction;
import in.basulabs.siesattendance.server.importer.AttendanceImporter;
import in.basulabs.siesattendance.server.security.AuditLog;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Importing an attendance sheet: the same file the app's import reads, and the same two steps.
 * <pre>
 * POST /api/v1/attendance/import?dryRun=true    the plan: what would be created, nothing written
 * POST /api/v1/attendance/import?dryRun=false   the same plan, written
 * </pre>
 * Stateless on purpose: the commit plans the sheet again rather than trusting a plan the client
 * hands back, so what is written is decided by the server and the caller's policies at the
 * moment it is written. The client shows the preview and sends the same file twice.
 * <p>
 * For a professor or an administrator. Which lectures may be filed is the policies' decision,
 * column by column; see {@link AttendanceImporter}.
 */
@RestController
@RequestMapping(Api.V1 + "/attendance/import")
public final class AttendanceImportController {

	/** A term's register for a large class is a few hundred kilobytes at most. */
	private static final int MAX_CSV_CHARACTERS = 2_000_000;

	public record LectureBody(int column, @NonNull String label, @Nullable String courseCode,
	                          @Nullable String courseTopic, @Nullable String start,
	                          @Nullable String end, int academicYear, @Nullable String batchName,
	                          int present, int absent, int notInClass, int dropped,
	                          boolean replacesExisting, @NonNull List<String> problems,
	                          @NonNull List<String> notes) {
	}

	/**
	 * @param written null for a dry run; what was written otherwise
	 */
	public record ImportResponse(boolean dryRun, boolean canImport, int usable, int skipped,
	                             int marks, @NonNull List<String> errors,
	                             @NonNull List<String> warnings,
	                             @NonNull List<LectureBody> lectures,
	                             @Nullable AttendanceImporter.Result written) {
	}

	@NonNull
	private final ActorDatabase database;

	public AttendanceImportController(@NonNull ActorDatabase database) {
		this.database = database;
	}

	@PostMapping(consumes = {"text/csv", "text/plain", "application/octet-stream"})
	@NonNull
	public ImportResponse load(@RequestParam(name = "dryRun", defaultValue = "true") boolean dryRun,
	                           @RequestBody @NonNull String csv,
	                           @NonNull HttpServletRequest http) throws SQLException {

		RequestActor actor = RequestActor.of(http);

		if (csv.isBlank()) {
			throw new ApiException(400, "empty_file", "The request body held no CSV.");
		}

		if (csv.length() > MAX_CSV_CHARACTERS) {
			throw new ApiException(413, "too_large",
					"That is larger than an attendance sheet: " + csv.length() + " characters.");
		}

		List<String> lines = List.of(csv.split("\\r?\\n", -1));

		return database.inTransaction(actor, connection -> {

			if (!mayImport(connection)) {
				throw new ApiException(403, "not_allowed",
						"Importing attendance is for professors and administrators.");
			}

			AttendanceImporter.Plan plan = AttendanceImporter.plan(connection, lines);
			AttendanceImporter.Result written = null;

			if (!dryRun) {

				if (!plan.canImport()) {
					throw new ApiException(400, "nothing_to_import", plan.errors.isEmpty()
							? "No column of this sheet can be imported."
							: plan.errors.get(0));
				}

				written = AttendanceImporter.commit(connection, plan);

				// Beside the rows it describes, so a commit that fails leaves no entry.
				AuditLog.record(connection, AuditAction.CSV_IMPORT, "attendance", null,
						AuditLog.detail(
								"created", written.created(),
								"replaced", written.replaced(),
								"marks", written.marks(),
								"skipped", written.skipped()));
			}

			List<LectureBody> lectures = new ArrayList<>();

			for (AttendanceImporter.Lecture lecture : plan.lectures) {
				lectures.add(new LectureBody(lecture.column, lecture.label, lecture.courseCode,
						lecture.courseTopic, lecture.start, lecture.end, lecture.academicYear,
						lecture.batchName, lecture.present, lecture.absent, lecture.notInClass,
						lecture.dropped, lecture.replacesExisting, lecture.problems,
						lecture.notes));
			}

			return new ImportResponse(dryRun, plan.canImport(), plan.usable(),
					plan.lectures.size() - plan.usable(), plan.marks(), plan.errors,
					plan.warnings, lectures, written);
		});
	}

	private static boolean mayImport(@NonNull Connection connection) throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT current_prof_id() IS NOT NULL OR actor_has_role('ADMIN')");
		     ResultSet results = statement.executeQuery()) {

			return results.next() && results.getBoolean(1);
		}
	}
}
