package in.basulabs.siesattendance.server.web;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import in.basulabs.siesattendance.backend.AuditAction;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Division;
import in.basulabs.siesattendance.backend.EnumLookup;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;
import in.basulabs.siesattendance.backend.util.ExportFilter;
import in.basulabs.siesattendance.server.security.AuditLog;
import in.basulabs.siesattendance.server.sql.BoundStatement;
import in.basulabs.siesattendance.server.sql.PgExportQueryBuilder;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The export: one row per student per course, with lectures held and lectures attended.
 * <p>
 * The realistic way a term's attendance leaves the building, which is why this is the endpoint
 * with an audit entry naming the filter that produced it rather than only the fact that it ran.
 * It is also the endpoint whose shape was least of a guess before there was a client: the columns
 * are the ones {@code PgExportQueryBuilder} already produces, and the dual-engine differ already
 * compares them against the app's own query cell for cell.
 *
 * <h3>Two different filters, and only one of them is the caller's</h3>
 * {@link ExportFilter} is what the caller asked for. {@code exportable_courses()} is what they
 * are allowed to have, and it is applied on top: the requested course list is intersected with
 * it, and an empty request means "everything I may export" rather than "everything".
 * <p>
 * That second filter is in SQL, in V8, and not in a {@code WHERE} clause here. It has to be
 * applied by the service because {@code courses} and {@code student_year} have no read policies —
 * V6's header says why, at length — so without it a professor's export would list every student
 * in the college with zeroes against them. Writing the rule in the database anyway keeps it
 * beside the policies that do the same job for {@code attendance}, where it can be read by
 * somebody auditing what an export may contain.
 * <p>
 * The numbers in the cells need no such help. Held and attended are counted over
 * {@code lectures} and {@code attendance}, both of which are under {@code FORCE ROW LEVEL
 * SECURITY}, so the policies decide them whatever this endpoint asks for.
 */
@RestController
@RequestMapping(Api.V1 + "/export")
public final class ExportController {

	/**
	 * One cell of the register. Field names are the app's, because the exporter looks them up by
	 * those names and the differ compares them.
	 */
	public record Row(long studentID, @NonNull String rollNo, @NonNull String studentName,
	                  long courseID, @NonNull String courseCode, @Nullable String courseTopic,
	                  long held, long attended) {
	}

	/**
	 * @param filter      the filter in words, the same sentence that goes on the sheet and into
	 *                    the audit entry
	 * @param courseCount how many courses the export was narrowed to after the caller's request
	 *                    met what they are allowed to export
	 */
	public record ExportResponse(int academicYear, @NonNull String filter, int courseCount,
	                             int rowCount, @NonNull List<Row> rows) {
	}

	@NonNull
	private final ActorDatabase database;

	public ExportController(@NonNull ActorDatabase database) {
		this.database = database;
	}

	@GetMapping
	@NonNull
	public ExportResponse export(@RequestParam("year") int academicYear,
	                             @RequestParam(name = "courses", required = false)
	                             @Nullable String courses,
	                             @RequestParam(name = "departments", required = false)
	                             @Nullable String departments,
	                             @RequestParam(name = "years", required = false)
	                             @Nullable String years,
	                             @RequestParam(name = "streams", required = false)
	                             @Nullable String streams,
	                             @RequestParam(name = "divisions", required = false)
	                             @Nullable String divisions,
	                             @RequestParam(name = "from", required = false)
	                             @Nullable String from,
	                             @RequestParam(name = "to", required = false) @Nullable String to,
	                             @NonNull HttpServletRequest http) throws SQLException {

		RequestActor actor = RequestActor.of(http);

		ExportFilter filter = new ExportFilter();
		filter.from = date(from, "from");
		filter.to = date(to, "to");
		filter.courseIDs.addAll(ids(courses));
		filter.departments.addAll(constants(Department.class, departments, "departments"));
		filter.years.addAll(constants(Year.class, years, "years"));
		filter.streams.addAll(constants(Stream.class, streams, "streams"));
		filter.divisions.addAll(constants(Division.class, divisions, "divisions"));

		if (filter.from != null && filter.to != null && filter.to.isBefore(filter.from)) {
			throw new ApiException(400, "bad_dates", "The 'to' date is before the 'from' date.");
		}

		// The filter is described before the course list is narrowed, so the sentence says what
		// was asked for. What was actually returned is courseCount and rowCount beside it.
		String described = filter.describe();

		return database.inTransaction(actor, connection -> {

			Set<Long> allowed = exportableCourses(connection, academicYear);

			if (filter.courseIDs.isEmpty()) {
				filter.courseIDs.addAll(allowed);
			} else {
				Set<Long> refused = new TreeSet<>(filter.courseIDs);
				refused.removeAll(allowed);

				if (!refused.isEmpty()) {
					// Named, because the caller named them: this tells them nothing they did
					// not already have. Silently dropping them would be worse -- a report
					// quietly missing a column is how somebody comes to trust a wrong number.
					throw new ApiException(403, "not_yours_to_export",
							"Not yours to export, for " + academicYear + ": " + refused);
				}
			}

			if (filter.courseIDs.isEmpty()) {
				// Nothing to export rather than everything. Without this the statement would
				// carry no course restriction at all, which for an actor who may export
				// nothing is the one thing it must not do.
				AuditLog.record(connection, AuditAction.EXPORT, entry(academicYear, described,
						0, 0));

				return new ExportResponse(academicYear, described, 0, 0, List.of());
			}

			List<Row> rows = run(connection, academicYear, filter);

			// Inside the transaction, so an export that fails on its way out leaves no entry
			// claiming it happened -- and an entry that cannot be written stops the export.
			AuditLog.record(connection, AuditAction.EXPORT,
					entry(academicYear, described, filter.courseIDs.size(), rows.size()));

			return new ExportResponse(academicYear, described, filter.courseIDs.size(),
					rows.size(), rows);
		});
	}

	/**
	 * What goes in the audit entry.
	 * <p>
	 * The filter, the counts, and nothing else. Not the rows: an audit log holding a copy of
	 * every export would be the most valuable thing in the database to steal, which is the
	 * opposite of what logging an export is for.
	 */
	@NonNull
	private static java.util.Map<String, Object> entry(int academicYear, @NonNull String filter,
	                                                   int courses, int rows) {

		return AuditLog.detail(
				"academic_year", academicYear,
				"filter", filter,
				"courses", courses,
				"rows", rows);
	}

	@NonNull
	private static Set<Long> exportableCourses(@NonNull Connection connection, int academicYear)
			throws SQLException {

		Set<Long> allowed = new LinkedHashSet<>();

		// FROM, not a set-returning call in the select list: both work, and this one is the
		// form that still reads as a query if a WHERE clause is ever added to it.
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT * FROM exportable_courses(?)")) {

			statement.setInt(1, academicYear);

			try (ResultSet results = statement.executeQuery()) {
				while (results.next()) {
					allowed.add(results.getLong(1));
				}
			}
		}
		return allowed;
	}

	@NonNull
	private static List<Row> run(@NonNull Connection connection, int academicYear,
	                             @NonNull ExportFilter filter) throws SQLException {

		BoundStatement query = PgExportQueryBuilder.build(academicYear, filter);
		List<Row> rows = new ArrayList<>();

		try (PreparedStatement statement = connection.prepareStatement(query.sql())) {

			List<Object> args = query.args();

			for (int at = 0; at < args.size(); at++) {
				// setObject with a null and no type is fine for the casts this statement
				// carries: every nullable argument in it is written as ?::date.
				statement.setObject(at + 1, args.get(at));
			}

			try (ResultSet results = statement.executeQuery()) {

				while (results.next()) {
					rows.add(new Row(
							results.getLong("studentID"),
							results.getString("rollNo"),
							results.getString("studentName"),
							results.getLong("courseID"),
							results.getString("courseCode"),
							results.getString("courseTopic"),
							results.getLong("held"),
							results.getLong("attended")));
				}
			}
		}
		return rows;
	}

	// ── Parameters ───────────────────────────────────────────────────────

	@Nullable
	private static LocalDate date(@Nullable String raw, @NonNull String name) {

		if (raw == null || raw.isBlank()) {
			return null;
		}

		try {
			return LocalDate.parse(raw.trim());

		} catch (DateTimeParseException e) {
			throw new ApiException(400, "bad_date",
					"'" + name + "' is not an ISO date (yyyy-mm-dd): " + raw);
		}
	}

	@NonNull
	private static Set<Long> ids(@Nullable String raw) {

		Set<Long> out = new LinkedHashSet<>();

		for (String piece : pieces(raw)) {

			try {
				out.add(Long.valueOf(piece));

			} catch (NumberFormatException e) {
				throw new ApiException(400, "bad_course_id", "Not a course id: " + piece);
			}
		}
		return out;
	}

	/**
	 * Enum values from a comma-separated parameter, matched the way a roster CSV's cells are:
	 * constant name or display label, loosely. The client sends constant names, but a person
	 * typing a URL by hand to get a report should not have to know that.
	 */
	@NonNull
	private static <E extends Enum<E>> Set<E> constants(@NonNull Class<E> type,
	                                                    @Nullable String raw,
	                                                    @NonNull String name) {

		Set<E> out = new LinkedHashSet<>();

		for (String piece : pieces(raw)) {

			E value = EnumLookup.find(type, piece);

			if (value == null) {
				throw new ApiException(400, "bad_filter",
						"Unknown value in '" + name + "': " + piece);
			}
			out.add(value);
		}
		return out;
	}

	@NonNull
	private static List<String> pieces(@Nullable String raw) {

		List<String> out = new ArrayList<>();

		if (raw == null || raw.isBlank()) {
			return out;
		}

		for (String piece : raw.split(",")) {

			String trimmed = piece.trim();

			if (!trimmed.isEmpty()) {
				out.add(trimmed);
			}
		}
		return out;
	}
}
