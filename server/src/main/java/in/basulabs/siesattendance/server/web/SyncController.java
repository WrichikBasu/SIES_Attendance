package in.basulabs.siesattendance.server.web;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import in.basulabs.siesattendance.backend.sync.OutboxOp;
import in.basulabs.siesattendance.server.sync.SyncReader;
import in.basulabs.siesattendance.server.sync.SyncWriter;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The two endpoints a phone talks to: push what it has, pull what it has not.
 * <p>
 * Both run as the request's actor, so the policies in V6 decide every row either way: a push the
 * actor has no authority for is refused by the database, and a pull returns only what the actor
 * may read. Nothing in this class decides who may see what, which is the property worth keeping.
 *
 * <h3>Why the device sends rows as it holds them</h3>
 * A row on the wire is a JSON object of Room's own column names, with the values exactly as
 * SQLite holds them: a boolean as 0 or 1, a timestamp as {@code "epochSecond|nano|zone"}, an enum
 * as its constant name. The device therefore does no conversion at all — it reads a row and sends
 * it — and the server converts with the same code the backup importer uses. One representation,
 * one conversion, already exercised by the importer's tests and by the dual-engine differ.
 *
 * <h3>Idempotent in both directions</h3>
 * A push applied twice leaves the same rows, so a device that loses the response simply sends the
 * batch again. A pull is resumable from its watermark, and the watermark only moves when the
 * device says it has applied the page — so a dropped response costs a repeated page rather than a
 * missed change.
 */
@RestController
@RequestMapping(Api.V1 + "/sync")
public final class SyncController {

	/** How many intents one push may carry. A day's teaching is a few hundred rows. */
	private static final int MAX_INTENTS = 1000;

	/**
	 * One row the device wants written or removed.
	 *
	 * @param key the primary key values, in the table's own key order
	 * @param row the row as the device holds it; absent for a delete
	 */
	public record IntentBody(@Nullable String table, @Nullable List<Long> key,
	                         @Nullable String op, @Nullable Map<String, Object> row) {
	}

	public record PushRequest(@Nullable List<IntentBody> intents) {
	}

	public record RejectionBody(@NonNull String table, @NonNull List<Long> key,
	                            @NonNull String reason) {
	}

	/**
	 * @param accepted how many intents were applied
	 * @param rejected the ones that were not. The device keeps these and shows them rather than
	 *                 dropping them: a mark that cannot be sent is something a person has to be
	 *                 told about, not something to lose quietly
	 */
	public record PushResponse(int accepted, @NonNull List<RejectionBody> rejected) {
	}

	public record ChangeBody(@NonNull String table, @NonNull List<Long> key, @NonNull String op,
	                         @Nullable Map<String, Object> row) {
	}

	public record PullResponse(@NonNull String watermark, boolean more,
	                           @NonNull List<ChangeBody> changes) {
	}

	@NonNull
	private final ActorDatabase database;

	public SyncController(@NonNull ActorDatabase database) {
		this.database = database;
	}

	@PostMapping("/push")
	@NonNull
	public PushResponse push(@RequestBody @NonNull PushRequest body,
	                         @NonNull HttpServletRequest http) throws SQLException {

		RequestActor actor = RequestActor.of(http);
		List<IntentBody> sent = body.intents();

		if (sent == null || sent.isEmpty()) {
			throw new ApiException(400, "empty_push", "A push has to carry at least one row.");
		}

		if (sent.size() > MAX_INTENTS) {
			throw new ApiException(413, "too_many_intents",
					"A push carries at most " + MAX_INTENTS + " rows; this one had "
							+ sent.size() + ". Drain the outbox in batches.");
		}

		List<SyncWriter.Intent> intents = new ArrayList<>();

		for (IntentBody intent : sent) {

			if (intent.table() == null || intent.key() == null || intent.key().isEmpty()) {
				throw new ApiException(400, "malformed_intent",
						"Every row needs a table and a key.");
			}

			OutboxOp op = OutboxOp.fromStored(intent.op());

			if (op == null) {
				// Named, because the valid values are not a secret and a client sending a third
				// one has a bug. Fatal for the batch rather than a per-row rejection: a client
				// that does not know the operations is not a client to apply rows from.
				throw new ApiException(400, "unknown_op",
						"Unknown operation: " + intent.op() + ". Expected UPSERT or DELETE.");
			}

			intents.add(new SyncWriter.Intent(intent.table(), intent.key(), op, intent.row()));
		}

		return database.inTransaction(actor, connection -> {

			SyncWriter.Result result = SyncWriter.apply(connection, intents,
					actor.session().deviceNode());

			List<RejectionBody> rejected = new ArrayList<>();

			for (SyncWriter.Rejection rejection : result.rejected()) {
				rejected.add(new RejectionBody(rejection.table(), rejection.key(),
						rejection.reason()));
			}

			// No audit entry of its own: the triggers on lectures and attendance wrote one per
			// row, carrying this session, this device and this account, which is more than a
			// "a push happened" entry could say. That is the gate's "correct authorship", and
			// it is the trigger's doing rather than this endpoint's.
			return new PushResponse(result.accepted(), rejected);
		});
	}

	/**
	 * @param since the device's watermark, or absent for a first pull, which answers with a
	 *              snapshot and the head of the feed
	 */
	@GetMapping("/pull")
	@NonNull
	public PullResponse pull(@RequestParam(name = "since", required = false) @Nullable String since,
	                         @RequestParam(name = "limit", defaultValue = "500") int limit,
	                         @NonNull HttpServletRequest http) throws SQLException {

		RequestActor actor = RequestActor.of(http);
		String watermark = since == null ? SyncReader.FROM_THE_BEGINNING : since.trim();

		return database.inTransaction(actor, connection -> {

			SyncReader.Page page;

			try {
				page = SyncReader.read(connection, watermark, limit);

			} catch (IllegalArgumentException e) {
				// A watermark this server did not issue. Not a 500: the honest answer is that
				// the device should start again, and saying so is more useful than a stack
				// trace in a log.
				throw new ApiException(400, "bad_watermark",
						"That is not a watermark this server issued. Pull without one to start "
								+ "again.");
			}

			List<ChangeBody> changes = new ArrayList<>();

			for (SyncReader.Change change : page.changes()) {
				changes.add(new ChangeBody(change.table(), change.key(), change.op().name(),
						change.row()));
			}

			return new PullResponse(page.watermark(), page.more(), changes);
		});
	}
}
