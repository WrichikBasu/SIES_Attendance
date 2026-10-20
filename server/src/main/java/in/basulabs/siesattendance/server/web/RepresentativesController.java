package in.basulabs.siesattendance.server.web;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

import in.basulabs.siesattendance.backend.GrantAuthority;
import in.basulabs.siesattendance.server.security.Delegates;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Class representatives, as the professor who teaches the course sees them.
 * <p>
 * This replaces the pair that hung off an account — {@code POST
 * /accounts/{id}/delegations} — and the reason is that the account-shaped route could not be
 * used by the people the policies were written for. Appointing a representative belongs to the
 * course's instructor first of all; the form for it needed a list of student <em>accounts</em>,
 * which only an administrator may read, so a professor could reach the endpoint with curl and
 * never from the page. Keyed by the course, the roll is the list, and the roll is the
 * instructor's to see.
 *
 * <h3>Who may</h3>
 * Not asked here. {@code Delegates.capacityFor} asks the database which of the three capacities
 * the caller holds for this course — instructor, head of the offering department, administrator
 * — and the capacity is stamped on the grant. Hold none and the answer is a 403 that says who
 * may. The policies on {@code attendance_delegate} and the BEFORE trigger on it say the same
 * thing twice more.
 *
 * <h3>The roll is the picker</h3>
 * {@code roll_for_course} is the one definition of who takes a course, the same function the
 * register screen and the export use, so the list offered is the list the appointment will be
 * checked against. Each student carries whether they have an account, because a representative
 * without one holds a grant they cannot use: the grant is legal, and it is also somebody waiting
 * for a login nobody made.
 */
@RestController
@RequestMapping(Api.V1 + "/courses")
public final class RepresentativesController {

	/**
	 * One student on a roll.
	 *
	 * @param hasAccount whether there is a student account for them. A grant without one is
	 *                   legal and useless, so the form says which
	 */
	public record OnTheRoll(@NonNull String studentId, @NonNull String name,
	                        @Nullable String rollNo, boolean hasAccount,
	                        boolean represents) {
	}

	public record NewRepresentative(@Nullable String studentId,
	                                @Nullable Integer academicYear,
	                                @Nullable String reason) {
	}

	@NonNull
	private final ActorDatabase database;

	public RepresentativesController(@NonNull ActorDatabase database) {
		this.database = database;
	}

	/**
	 * The roll of one course for one session, with who represents it already.
	 * <p>
	 * Refused unless the caller could appoint somebody, which is the same question as "is this
	 * your course": a roll is a list of names and this endpoint exists to pick from it.
	 */
	@GetMapping("/{courseId}/roll")
	@NonNull
	public List<OnTheRoll> roll(@PathVariable long courseId,
	                            @RequestParam("academicYear") int academicYear,
	                            @NonNull HttpServletRequest http) throws SQLException {

		RequestActor actor = RequestActor.of(http);

		return database.inTransaction(actor, connection -> {

			if (Delegates.capacityFor(connection, courseId, academicYear) == null) {
				throw new ApiException(403, "not_your_course",
						"That course's roll is for the people who could appoint a "
								+ "representative on it: its instructor, the head of the "
								+ "department that offers it, or an administrator.");
			}

			List<OnTheRoll> roll = new ArrayList<>();

			try (PreparedStatement statement = connection.prepareStatement(
					"SELECT r.student_id, r.roll_no, s.name, "
							+ "       EXISTS (SELECT 1 FROM app_account a "
							+ "               WHERE a.student_id = r.student_id "
							+ "                 AND a.disabled_at IS NULL) AS has_account, "
							+ "       EXISTS (SELECT 1 FROM attendance_delegate d "
							+ "               WHERE d.student_id    = r.student_id "
							+ "                 AND d.course_id     = ? "
							+ "                 AND d.academic_year  = ? "
							+ "                 AND d.revoked_at IS NULL) AS represents "
							+ "FROM roll_for_course(?, ?) r "
							+ "JOIN students s ON s.student_id = r.student_id "
							+ "ORDER BY r.roll_no")) {

				statement.setLong(1, courseId);
				statement.setInt(2, academicYear);
				statement.setLong(3, courseId);
				statement.setInt(4, academicYear);

				try (ResultSet results = statement.executeQuery()) {
					while (results.next()) {
						roll.add(new OnTheRoll(
								Long.toString(results.getLong("student_id")),
								results.getString("name"),
								results.getString("roll_no"),
								results.getBoolean("has_account"),
								results.getBoolean("represents")));
					}
				}
			}

			return roll;
		});
	}

	@PostMapping("/{courseId}/representatives")
	@NonNull
	public Delegates.Delegation appoint(@PathVariable long courseId,
	                                    @RequestBody @NonNull NewRepresentative body,
	                                    @NonNull HttpServletRequest http) throws SQLException {

		RequestActor actor = RequestActor.of(http);

		long studentId = key(body.studentId());
		int academicYear = session(body.academicYear());

		return database.inTransaction(actor, connection -> {

			GrantAuthority capacity = Delegates.capacityFor(connection, courseId, academicYear);

			if (capacity == null) {
				throw new ApiException(403, "not_yours_to_grant",
						"A class representative is appointed by the course's instructor, by "
								+ "the head of the department that offers it, or by an "
								+ "administrator.");
			}

			if (!Delegates.onTheRoll(connection, studentId, courseId, academicYear)) {
				throw new ApiException(409, "not_on_the_roll",
						"That student is not on this course's roll for " + academicYear
								+ ", so they cannot be its class representative. The roll "
								+ "comes from their enrolment, which is the office's.");
			}

			Delegates.grant(connection, studentId, courseId, academicYear, capacity,
					actor.session().accountId(), body.reason());

			return written(connection, studentId, courseId, academicYear);
		});
	}

	@PostMapping("/{courseId}/representatives/revoke")
	@NonNull
	public Delegates.Delegation end(@PathVariable long courseId,
	                                @RequestBody @NonNull NewRepresentative body,
	                                @NonNull HttpServletRequest http) throws SQLException {

		RequestActor actor = RequestActor.of(http);

		long studentId = key(body.studentId());
		int academicYear = session(body.academicYear());

		return database.inTransaction(actor, connection -> {

			if (Delegates.capacityFor(connection, courseId, academicYear) == null) {
				throw new ApiException(403, "not_yours_to_grant",
						"That course's representatives are the instructor's to appoint and to "
								+ "end, along with the department's head and an "
								+ "administrator.");
			}

			if (!Delegates.revoke(connection, studentId, courseId, academicYear,
					actor.session().accountId(), body.reason())) {

				throw new ApiException(404, "no_live_grant",
						"That appointment is not there, or was ended already.");
			}

			return written(connection, studentId, courseId, academicYear);
		});
	}

	/** The grant just written, read back so the caller is told what is now on file. */
	@NonNull
	private static Delegates.Delegation written(@NonNull Connection connection, long studentId,
	                                            long courseId, int academicYear)
			throws SQLException {

		for (Delegates.Delegation delegation : Delegates.forStudent(connection, studentId)) {

			if (delegation.courseId().equals(Long.toString(courseId))
					&& delegation.academicYear() == academicYear) {
				return delegation;
			}
		}

		// Unreachable: it was written in this transaction. Said out loud rather than
		// returning something invented.
		throw new IllegalStateException("the appointment just written cannot be read back");
	}

	private static long key(@Nullable String value) {

		if (value == null || value.isBlank()) {
			throw new ApiException(400, "incomplete", "studentId is needed.");
		}

		try {
			return Long.parseLong(value.trim());

		} catch (NumberFormatException notAKey) {
			throw new ApiException(400, "not_a_key",
					"studentId has to be a whole number, as the roll sends it.");
		}
	}

	private static int session(@Nullable Integer academicYear) {

		if (academicYear == null) {
			throw new ApiException(400, "incomplete",
					"academicYear is needed: a representative of a course in one session is "
							+ "not one in the next.");
		}

		return academicYear;
	}
}
