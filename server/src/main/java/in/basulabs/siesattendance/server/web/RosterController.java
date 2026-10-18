package in.basulabs.siesattendance.server.web;

import androidx.annotation.NonNull;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import in.basulabs.siesattendance.backend.AuditAction;
import in.basulabs.siesattendance.backend.util.RosterCsv;
import in.basulabs.siesattendance.server.importer.RosterImporter;
import in.basulabs.siesattendance.server.security.AuditLog;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Loading a roster CSV.
 * <p>
 * The file format is {@link RosterCsv}'s, shared with the app, so a file that imports on a phone
 * imports here. What differs is who may do it and what happens to a bad row: office and admin
 * only, and the surviving rows land as one transaction rather than one at a time.
 *
 * <h3>Why office and admin, and not a professor</h3>
 * A roll number is what every other session's records are matched by — the importer matches on
 * it, the export sorts by it, a promotion is a new roll number for the same person. Rewriting a
 * class's roll is therefore a registry operation and not a teaching one, even for the professor
 * who teaches that class. {@code actor_may_manage_roster()} in V8 is the rule; this endpoint asks
 * it.
 * <p>
 * It has to be asked here rather than enforced by a policy, because {@code students} and
 * {@code student_year} have no policies — the same gap the export works around, and the same
 * reason: "which students may this professor see" needs roster reachability rather than a
 * department comparison. The function exists so that when those tables do get policies, there is
 * one answer to who may edit the roll rather than two that have to agree.
 */
@RestController
@RequestMapping(Api.V1 + "/roster")
public final class RosterController {

	/**
	 * @param errors one line per row that could not be filed, with its line number. A run can
	 *               succeed and still have these: the rows that parsed and were accepted are
	 *               committed, and correcting the file and sending it again is the intended fix
	 */
	public record RosterResponse(int academicYear, int created, int updated, int failed,
	                             @NonNull List<String> errors) {
	}

	/** Four million characters: a hundred times the largest roster the college could have. */
	private static final int MAX_CSV_CHARACTERS = 4_000_000;

	@NonNull
	private final ActorDatabase database;

	public RosterController(@NonNull ActorDatabase database) {
		this.database = database;
	}

	/**
	 * Takes the CSV as the request body.
	 * <p>
	 * {@code text/csv}, not a multipart upload: the file is what is being sent, there is nothing
	 * else in the request, and a multipart body would add a boundary parser between the client
	 * and the one thing it is trying to say.
	 */
	@PostMapping(consumes = {"text/csv", "text/plain", "application/octet-stream"})
	@NonNull
	public RosterResponse load(@RequestParam("year") int academicYear,
	                           @RequestBody @NonNull String csv,
	                           @NonNull HttpServletRequest http) throws SQLException {

		RequestActor actor = RequestActor.of(http);

		if (csv.isBlank()) {
			throw new ApiException(400, "empty_file", "The request body held no CSV.");
		}

		// A roster of four hundred students is about forty kilobytes. This is not a defence
		// against somebody posting a gigabyte -- by the time this runs the body has already
		// been read into memory, and the only place that can really be stopped is the proxy in
		// front. It is a defence against spending ten minutes parsing something that cannot be
		// a roster.
		if (csv.length() > MAX_CSV_CHARACTERS) {
			throw new ApiException(413, "too_large",
					"That is larger than a roster: " + csv.length() + " characters.");
		}

		return database.inTransaction(actor, connection -> {

			if (!mayManageRoster(connection)) {
				throw new ApiException(403, "not_allowed",
						"Loading a roster is for the office and an administrator.");
			}

			RosterImporter.Result result;

			try {
				result = RosterImporter.load(connection, academicYear, new StringReader(csv));

			} catch (IOException e) {
				// A StringReader does not do this. Caught because load() declares it, and
				// turned into a 400 rather than a 500 because the only thing that could
				// produce it is the body.
				throw new ApiException(400, "unreadable", "The CSV could not be read.");
			}

			// In the same transaction as the rows, so a roster that fails on its way out leaves
			// no entry claiming it landed. The counts are the whole point of the entry: "who
			// changed four hundred students on the 3rd of July" is the question.
			AuditLog.record(connection, AuditAction.CSV_IMPORT, "students", null,
					AuditLog.detail(
							"academic_year", academicYear,
							"created", result.created(),
							"updated", result.updated(),
							"failed", result.failed()));

			return new RosterResponse(academicYear, result.created(), result.updated(),
					result.failed(), result.errors());
		});
	}

	private static boolean mayManageRoster(@NonNull Connection connection) throws SQLException {

		try (Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery("SELECT actor_may_manage_roster()")) {

			return results.next() && results.getBoolean(1);
		}
	}
}
