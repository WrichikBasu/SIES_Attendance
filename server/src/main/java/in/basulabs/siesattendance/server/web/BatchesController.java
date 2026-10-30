package in.basulabs.siesattendance.server.web;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import in.basulabs.siesattendance.backend.Department;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The batches V31 could not give a department, for an administrator to place.
 * <pre>
 * GET  /api/v1/batches/unplaced              every batch with no department, and the evidence
 * POST /api/v1/batches/{id}/department       {"department": "…"}
 * </pre>
 * A batch from before V31 was placed by what it was used for: its courses' department, or its
 * members' major. The ones left are linked to courses of more than one department, or have no
 * course and members of several majors, and only a person can say whose they are. Until then
 * nobody but an administrator may change them (actor_may_keep_batches_of).
 * <p>
 * Placing a batch unlinks it from every course of another department first, since a batch is
 * linked only to its own department's courses and the database refuses the move otherwise. The
 * answer says which, and the page has already shown them before the administrator confirmed.
 */
@RestController
@RequestMapping(Api.V1 + "/batches")
public final class BatchesController {

	/** A course a batch is linked to, with the department that decides whether it may stay. */
	public record Link(long courseId, @NonNull String courseCode, @NonNull String department,
	                   @NonNull String departmentLabel) {
	}

	/**
	 * @param majors the distinct majors of its members, with how many have each, most first,
	 *               as "Computer Science (12)"
	 */
	public record Unplaced(long batchId, @NonNull String batchName, int academicYear,
	                       @Nullable String batchDesc, int members, @NonNull List<Link> links,
	                       @NonNull List<String> majors) {
	}

	public record Placement(@Nullable String department) {
	}

	/** @param unlinked the course codes the batch was taken off, because they were not its department's */
	public record Placed(long batchId, @NonNull String department,
	                     @NonNull List<String> unlinked) {
	}

	@NonNull
	private final ActorDatabase database;

	public BatchesController(@NonNull ActorDatabase database) {
		this.database = database;
	}

	@GetMapping("/unplaced")
	@NonNull
	public List<Unplaced> unplaced(@NonNull HttpServletRequest http) throws SQLException {

		return database.inTransaction(RequestActor.of(http), connection -> {

			refuseUnlessAdmin(connection);

			List<Unplaced> batches = new ArrayList<>();

			try (Statement statement = connection.createStatement();
			     ResultSet results = statement.executeQuery(
					     "SELECT b.batch_id, b.batch_name, b.academic_year, b.batch_desc, "
							     + "(SELECT count(*) FROM batch_student_map m "
							     + " WHERE m.batch_id = b.batch_id) AS members "
							     + "FROM batch_desc b WHERE b.department IS NULL "
							     + "ORDER BY b.academic_year DESC, b.batch_name")) {

				while (results.next()) {
					long batchId = results.getLong("batch_id");
					batches.add(new Unplaced(batchId, results.getString("batch_name"),
							results.getInt("academic_year"), results.getString("batch_desc"),
							results.getInt("members"), links(connection, batchId),
							majors(connection, batchId)));
				}
			}
			return batches;
		});
	}

	@PostMapping("/{batchId}/department")
	@NonNull
	public Placed place(@PathVariable long batchId, @RequestBody @NonNull Placement body,
	                    @NonNull HttpServletRequest http) throws SQLException {

		Department department = Department.fromAny(body.department());

		if (department == null) {
			throw new ApiException(400, "no_department", "Choose the department this batch "
					+ "belongs to.");
		}

		return database.inTransaction(RequestActor.of(http), connection -> {

			refuseUnlessAdmin(connection);

			List<String> unlinked = new ArrayList<>();

			for (Link link : links(connection, batchId)) {

				if (link.department().equals(department.name())) {
					continue;
				}

				try (PreparedStatement statement = connection.prepareStatement(
						"DELETE FROM course_batch_map WHERE batch_id = ? AND course_id = ?")) {
					statement.setLong(1, batchId);
					statement.setLong(2, link.courseId());
					statement.executeUpdate();
				}
				unlinked.add(link.courseCode());
			}

			try (PreparedStatement statement = connection.prepareStatement(
					"UPDATE batch_desc SET department = ?::department WHERE batch_id = ?")) {

				statement.setString(1, department.name());
				statement.setLong(2, batchId);

				if (statement.executeUpdate() == 0) {
					throw new ApiException(404, "no_batch", "There is no batch " + batchId);
				}
			}

			return new Placed(batchId, department.name(), unlinked);
		});
	}

	@NonNull
	private static List<Link> links(@NonNull Connection connection, long batchId)
			throws SQLException {

		List<Link> links = new ArrayList<>();

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT c.course_id, c.course_code, c.offering_department::text AS dept "
						+ "FROM course_batch_map cb JOIN courses c ON c.course_id = cb.course_id "
						+ "WHERE cb.batch_id = ? ORDER BY c.course_code")) {

			statement.setLong(1, batchId);

			try (ResultSet results = statement.executeQuery()) {
				while (results.next()) {
					String dept = results.getString("dept");
					links.add(new Link(results.getLong("course_id"),
							results.getString("course_code"), dept, label(dept)));
				}
			}
		}
		return links;
	}

	@NonNull
	private static List<String> majors(@NonNull Connection connection, long batchId)
			throws SQLException {

		List<String> majors = new ArrayList<>();

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT y.major_dept::text AS dept, count(*) AS n "
						+ "FROM batch_student_map m "
						+ "JOIN student_year y ON y.student_id = m.student_id "
						+ " AND y.academic_year = m.academic_year "
						+ "WHERE m.batch_id = ? GROUP BY y.major_dept ORDER BY n DESC, dept")) {

			statement.setLong(1, batchId);

			try (ResultSet results = statement.executeQuery()) {
				while (results.next()) {
					majors.add(label(results.getString("dept")) + " (" + results.getLong("n")
							+ ")");
				}
			}
		}
		return majors;
	}

	@NonNull
	private static String label(@NonNull String stored) {
		Department known = Department.fromAny(stored);
		return known == null ? stored : known.toString();
	}

	private static void refuseUnlessAdmin(@NonNull Connection connection) throws SQLException {

		try (Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery("SELECT actor_has_role('ADMIN')")) {

			if (!results.next() || !results.getBoolean(1)) {
				throw new ApiException(403, "not_allowed",
						"Placing a batch in a department is an administrator's.");
			}
		}
	}
}
