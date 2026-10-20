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
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Reading the audit trail.
 * <p>
 * The one endpoint in this service that cannot do its own work in SQL the ordinary way, because
 * {@code sies_app} holds <em>no</em> privilege on {@code audit_log} — not even SELECT. That is
 * what makes the trail worth having: the role that writes the attendance cannot read, rewrite or
 * delete the record of having written it. V14 adds {@code audit_recent()} and
 * {@code audit_size()}, both {@code SECURITY DEFINER}, both refusing anybody who is not an
 * auditor or an administrator — and refusing <em>loudly</em>, with
 * {@code insufficient_privilege}, because an empty answer would be indistinguishable from an
 * empty log.
 *
 * <h3>Everything is text</h3>
 * Every value comes back as a string, including the ids and the two row images. Two reasons, and
 * they are the same two the data browser gives: this is read by a page, where a 59-bit key cannot
 * survive being a number, and a {@code jsonb} image rendered as JSON-inside-JSON would be a
 * format for somebody to parse rather than something to read.
 *
 * <h3>Paging</h3>
 * Newest first, with the cursor being the last row's own {@code (occurred_at, audit_id)} compared
 * row-wise. Both halves are needed: {@code clock_timestamp()} can repeat inside one statement, so
 * a cursor on the timestamp alone would either skip the rows that share one or send them twice.
 */
@RestController
@RequestMapping(Api.V1 + "/audit")
public final class AuditController {

	/**
	 * One entry, rendered.
	 *
	 * @param rowKey the key of the row it happened to, as the trigger recorded it:
	 *               {@code {"lecture_id": 370…}}. Already text, and already the right shape for
	 *               a screen — the keys inside it are JSON numbers written by PostgreSQL, which
	 *               is why this is handed over as a string and never re-parsed here
	 */
	public record Entry(@NonNull String auditId, @NonNull String occurredAt,
	                    @Nullable String actorAccount, @NonNull String actorLabel,
	                    @NonNull String action, @Nullable String tableName,
	                    @Nullable String rowKey, @Nullable String sessionId,
	                    @Nullable String deviceNode, @Nullable String clientIp,
	                    @Nullable String appVersion, @Nullable String before,
	                    @Nullable String after, @Nullable String detail) {
	}

	/**
	 * @param total   how many entries there are in all, which a page of rows cannot say
	 * @param nextAt  the cursor for the next page, or null at the end of the trail
	 */
	public record Page(@NonNull List<Entry> entries, @NonNull String total,
	                   @Nullable String nextAt, @Nullable String nextId) {
	}

	private static final int DEFAULT_LIMIT = 50;

	private static final int MAX_LIMIT = 500;

	@NonNull
	private final ActorDatabase database;

	public AuditController(@NonNull ActorDatabase database) {
		this.database = database;
	}

	@GetMapping
	@NonNull
	public Page recent(@RequestParam(name = "limit", defaultValue = "50") int limit,
	                   @RequestParam(name = "beforeAt", required = false) @Nullable String at,
	                   @RequestParam(name = "beforeId", required = false) @Nullable String id,
	                   @NonNull HttpServletRequest http) throws SQLException {

		RequestActor actor = RequestActor.of(http);

		int capped = Math.max(1, Math.min(limit, MAX_LIMIT));
		Instant before = instant(at);
		Long beforeId = number(id);

		return database.inTransaction(actor, connection -> {

			List<Entry> entries = new ArrayList<>();

			try (PreparedStatement statement = connection.prepareStatement(
					"SELECT * FROM audit_recent(?, ?, ?)")) {

				statement.setInt(1, capped);

				if (before == null) {
					statement.setNull(2, Types.TIMESTAMP_WITH_TIMEZONE);
				} else {
					statement.setTimestamp(2, Timestamp.from(before));
				}

				if (beforeId == null) {
					statement.setNull(3, Types.BIGINT);
				} else {
					statement.setLong(3, beforeId);
				}

				try (ResultSet results = statement.executeQuery()) {
					while (results.next()) {
						entries.add(read(results));
					}
				}
			}

			String total;

			try (PreparedStatement statement =
					     connection.prepareStatement("SELECT audit_size() AS total");
			     ResultSet results = statement.executeQuery()) {

				results.next();
				total = Long.toString(results.getLong("total"));
			}

			// A full page means there may be more; a short one is the end. One row's worth of
			// uncertainty, which costs an empty page at worst and is the price of not counting
			// the remainder on every request.
			Entry last = entries.isEmpty() ? null : entries.get(entries.size() - 1);

			return new Page(entries, total,
					entries.size() < capped || last == null ? null : last.occurredAt(),
					entries.size() < capped || last == null ? null : last.auditId());
		});
	}

	@NonNull
	private static Entry read(@NonNull ResultSet results) throws SQLException {

		Number actor = (Number) results.getObject("actor_account");
		Number node = (Number) results.getObject("device_node");
		Object session = results.getObject("session_id");

		return new Entry(
				Long.toString(results.getLong("audit_id")),
				results.getTimestamp("occurred_at").toInstant().toString(),
				actor == null ? null : Long.toString(actor.longValue()),
				results.getString("actor_label"),
				results.getString("action"),
				results.getString("table_name"),
				results.getString("row_key"),
				session == null ? null : session.toString(),
				node == null ? null : Integer.toString(node.intValue()),
				results.getString("client_ip"),
				results.getString("app_version"),
				results.getString("before_image"),
				results.getString("after_image"),
				results.getString("detail"));
	}

	@Nullable
	private static Instant instant(@Nullable String value) {

		if (value == null || value.isBlank()) {
			return null;
		}

		try {
			return Instant.parse(value.trim());

		} catch (RuntimeException notAnInstant) {
			throw new ApiException(400, "bad_cursor",
					"beforeAt has to be the occurredAt of the last entry you were given.");
		}
	}

	@Nullable
	private static Long number(@Nullable String value) {

		if (value == null || value.isBlank()) {
			return null;
		}

		try {
			return Long.valueOf(value.trim());

		} catch (NumberFormatException notANumber) {
			throw new ApiException(400, "bad_cursor",
					"beforeId has to be the auditId of the last entry you were given.");
		}
	}
}
