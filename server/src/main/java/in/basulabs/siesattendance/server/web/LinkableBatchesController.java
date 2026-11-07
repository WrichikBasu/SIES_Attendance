package in.basulabs.siesattendance.server.web;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Every batch of a session, for choosing the batches of an OE, AEC, IKS or VEC course.
 * <p>
 * Those courses take any department's batches (V52), and a professor's phone holds only its own
 * department's (V32), so the phone cannot offer what it does not have. This lists them all, by
 * name, department and size and never by member. Linking one is an ordinary push of the
 * {@code course_batch_map} row, which the policies decide. Once linked, the batch and its
 * members reach the phone the way any batch of a course it reads does.
 * <p>
 * The list is {@code linkable_batches()}, which answers for a professor or a staff account and
 * returns nothing to anybody else.
 */
@RestController
@RequestMapping(Api.V1 + "/batches")
public final class LinkableBatchesController {

	/**
	 * @param batchId    as digits in a string, as every key the phone is sent outside sync
	 * @param department the constant name, or null for a batch nobody has placed yet
	 */
	public record Linkable(@NonNull String batchId, @NonNull String batchName,
	                       @Nullable String department, int academicYear, long members) {
	}

	@NonNull
	private final ActorDatabase database;

	public LinkableBatchesController(@NonNull ActorDatabase database) {
		this.database = database;
	}

	@GetMapping("/linkable")
	@NonNull
	public List<Linkable> linkable(@RequestParam("academicYear") int academicYear,
	                               @NonNull HttpServletRequest http) throws SQLException {

		return database.inTransaction(RequestActor.of(http), connection -> {

			List<Linkable> batches = new ArrayList<>();

			try (PreparedStatement statement = connection.prepareStatement(
					"SELECT batch_id, batch_name, department::text AS department, "
							+ "academic_year, members FROM linkable_batches(?)")) {

				statement.setInt(1, academicYear);

				try (ResultSet results = statement.executeQuery()) {
					while (results.next()) {
						batches.add(new Linkable(Long.toString(results.getLong("batch_id")),
								results.getString("batch_name"),
								results.getString("department"),
								results.getInt("academic_year"),
								results.getLong("members")));
					}
				}
			}

			return batches;
		});
	}
}
