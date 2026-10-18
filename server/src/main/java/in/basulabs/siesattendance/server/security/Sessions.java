package in.basulabs.siesattendance.server.security;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import javax.sql.DataSource;

import in.basulabs.siesattendance.backend.AccountKind;

/**
 * Bearer tokens, and the sessions behind them.
 * <p>
 * A token here is thirty-two random bytes and nothing else. It carries no claims, says nothing
 * about who holds it, and cannot be read: everything a request needs comes from the
 * {@code app_session} row it names. The table stores the token's SHA-256, never the token, so
 * somebody who reads every row cannot sign in with any of them.
 *
 * <h3>Why not a signed token</h3>
 * A self-contained signed token is a claim the server cannot withdraw, and the usual
 * compensation is to make it expire in minutes. That trade is the wrong way round for this
 * application. A professor in a basement laboratory marks a register with no signal and cannot
 * refresh anything; a token that expires while they are out of range is a token that fails at the
 * exact moment the app is supposed to keep working. So the session is a row, revoking it is an
 * {@code UPDATE} that takes effect on the very next request, and the lifetime can be
 * {@link #LIFETIME} without that being the window a stolen token buys. There is also no signing
 * key to hold, rotate or get wrong, and no algorithm field for anybody to confuse.
 *
 * <h3>The device</h3>
 * A session may be bound to a {@code device_registration}. When it is, every request has to
 * present that install's identifier as well as the token, which is what makes a token copied off
 * one phone useless on another. The binding is also what Phase 6's write path will check a row
 * key's node field against — a phone must not be able to write rows claiming to be another
 * phone's.
 *
 * <h3>What verification deliberately does not do</h3>
 * It does not set the actor. {@link ActorSession} does that, once per transaction, from what is
 * returned here — because verification happens before there is an actor, in the same way
 * authentication does, and a policy keyed on the current actor would deny the one lookup that
 * establishes one.
 */
public final class Sessions {

	/**
	 * How long a token stays valid without being used or refreshed.
	 * <p>
	 * Long on purpose; see the class notes. The protection against a stolen token is that it can
	 * be revoked, not that it expires soon — and a term's teaching should not be interrupted to
	 * retype a password.
	 */
	public static final Duration LIFETIME = Duration.ofDays(14);

	/**
	 * How stale {@code last_seen_at} is allowed to get before a request updates it.
	 * <p>
	 * Writing it on every request would put a write in front of every read, including the ones
	 * that are supposed to be cheap. Nothing is decided on this column: it answers "when was
	 * this device last heard from", which five minutes of slack does not change the answer to.
	 */
	private static final Duration TOUCH_AFTER = Duration.ofMinutes(5);

	/** 32 bytes, so guessing is not a strategy at any rate the network could carry. */
	private static final int TOKEN_BYTES = 32;

	private static final SecureRandom RANDOM = new SecureRandom();

	/** A newly issued token, which is the only moment the token itself exists in the server. */
	public record Issued(@NonNull String token, @NonNull UUID sessionId,
	                     @NonNull Instant expiresAt) {
	}

	/**
	 * A verified session: who is acting, on which device, and whether they are allowed to do
	 * anything but change their password.
	 *
	 * @param installId the bound device's install identifier, which the request has to match.
	 *                  Null when the session is not bound to a device at all
	 */
	public record Live(@NonNull UUID sessionId, long accountId, @NonNull AccountKind kind,
	                   @Nullable Long profId, @Nullable Long studentId,
	                   @NonNull String displayName, boolean mustRotate, @Nullable Long deviceId,
	                   @Nullable Integer deviceNode, @Nullable UUID installId,
	                   @Nullable Instant lastSeenAt) {
	}

	@NonNull
	private final DataSource dataSource;

	public Sessions(@NonNull DataSource dataSource) {
		this.dataSource = dataSource;
	}

	/**
	 * Issues a token for an account that has just proved who it is.
	 *
	 * @param deviceId the registered device this session is for, or null for a session that is
	 *                 not on one — office staff at a desk, or a phone that has not registered
	 *                 yet and is about to
	 */
	@NonNull
	public Issued issue(long accountId, @Nullable Long deviceId, @Nullable String clientIp,
	                    @Nullable String appVersion) throws SQLException {

		byte[] raw = new byte[TOKEN_BYTES];
		RANDOM.nextBytes(raw);

		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
		Instant expires = Instant.now().plus(LIFETIME);

		try (Connection connection = dataSource.getConnection();
		     PreparedStatement statement = connection.prepareStatement(
				     "INSERT INTO app_session (account_id, token_hash, device_id, expires_at, "
						     + "client_ip, app_version) "
						     + "VALUES (?, ?, ?, ?, ?::inet, ?) RETURNING session_id")) {

			statement.setLong(1, accountId);
			statement.setBytes(2, hash(token));

			if (deviceId == null) {
				statement.setNull(3, java.sql.Types.BIGINT);
			} else {
				statement.setLong(3, deviceId);
			}

			statement.setTimestamp(4, Timestamp.from(expires));
			statement.setString(5, clientIp);
			statement.setString(6, appVersion);

			try (ResultSet results = statement.executeQuery()) {

				if (!results.next()) {
					throw new SQLException("no session id came back from the insert");
				}
				return new Issued(token, results.getObject(1, UUID.class), expires);
			}
		}
	}

	/**
	 * Resolves a token to a live session, or null.
	 * <p>
	 * Null for every reason a caller must not be able to tell apart: no such token, expired,
	 * revoked, or an account since disabled. The distinction belongs in the log, not in a
	 * response — a 401 that explains which one it was is a 401 that answers questions for
	 * whoever is holding a token they should not have.
	 */
	@Nullable
	public Live verify(@NonNull String token) throws SQLException {

		try (Connection connection = dataSource.getConnection()) {

			Live live = read(connection, hash(token));

			if (live != null) {
				touch(connection, live);
			}
			return live;
		}
	}

	/** Revokes one session. Idempotent: revoking a revoked session changes nothing. */
	public void revoke(@NonNull UUID sessionId, @NonNull String reason) throws SQLException {

		try (Connection connection = dataSource.getConnection();
		     PreparedStatement statement = connection.prepareStatement(
				     "UPDATE app_session SET revoked_at = now(), reason = ? "
						     + "WHERE session_id = ? AND revoked_at IS NULL")) {

			statement.setString(1, reason);
			statement.setObject(2, sessionId);
			statement.executeUpdate();
		}
	}

	/**
	 * Revokes every live session of an account except one, and says how many went.
	 * <p>
	 * What a password change calls. Somebody who changes their password because they think
	 * somebody else has it has not finished until the other sessions are gone — and leaving the
	 * current one alone is the difference between that and being signed out of the device in
	 * your hand.
	 *
	 * @param except the session to leave alone, or null to revoke all of them
	 */
	public int revokeAllFor(long accountId, @Nullable UUID except, @NonNull String reason)
			throws SQLException {

		try (Connection connection = dataSource.getConnection();
		     PreparedStatement statement = connection.prepareStatement(
				     "UPDATE app_session SET revoked_at = now(), reason = ? "
						     + "WHERE account_id = ? AND revoked_at IS NULL "
						     + "AND (?::uuid IS NULL OR session_id <> ?::uuid)")) {

			statement.setString(1, reason);
			statement.setLong(2, accountId);
			statement.setString(3, except == null ? null : except.toString());
			statement.setString(4, except == null ? null : except.toString());

			return statement.executeUpdate();
		}
	}

	/**
	 * Binds a session to a device, which is what registration does to the session that performed
	 * it.
	 * <p>
	 * So a phone logs in once: it has no device to name at login, registers on the token it was
	 * given, and the same session becomes device-bound rather than the user having to sign in
	 * again to pick the binding up.
	 */
	public void bindDevice(@NonNull UUID sessionId, long deviceId) throws SQLException {

		try (Connection connection = dataSource.getConnection();
		     PreparedStatement statement = connection.prepareStatement(
				     "UPDATE app_session SET device_id = ? "
						     + "WHERE session_id = ? AND revoked_at IS NULL")) {

			statement.setLong(1, deviceId);
			statement.setObject(2, sessionId);
			statement.executeUpdate();
		}
	}

	// ── Internals ────────────────────────────────────────────────────────

	@Nullable
	private static Live read(@NonNull Connection connection, @NonNull byte[] tokenHash)
			throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT s.session_id, s.account_id, s.device_id, s.last_seen_at, "
						+ "       a.kind, a.prof_id, a.student_id, a.display_name, "
						+ "       a.must_rotate, d.node, d.install_id "
						+ "FROM app_session s "
						+ "JOIN app_account a ON a.account_id = s.account_id "
						// LEFT JOIN LATERAL over a SECURITY DEFINER function, not a join to
						// device_registration itself. This runs before there is an actor, so
						// the policies on that table would filter the join away -- and
						// silently: every column would come back NULL, which reads as "a
						// session with no device" and makes a revoked device indistinguishable
						// from none. The function returns no rows for a null device, so the
						// outer join still yields nulls for the sessions that genuinely have
						// none.
						+ "LEFT JOIN LATERAL device_session_binding(s.device_id) d ON true "
						+ "WHERE s.token_hash = ? "
						+ "  AND s.revoked_at IS NULL "
						+ "  AND s.expires_at > now() "
						// An account disabled after the token was issued stops working at the
						// next request rather than at the next login, which is the point of
						// disabling it.
						+ "  AND a.disabled_at IS NULL "
						// And a device revoked after the session was bound to it. Phrased so a
						// session with no device still matches, and so that a bound session
						// whose device cannot be resolved at all does not.
						+ "  AND (s.device_id IS NULL OR d.revoked = false)")) {

			statement.setBytes(1, tokenHash);

			try (ResultSet results = statement.executeQuery()) {

				if (!results.next()) {
					return null;
				}

				Timestamp lastSeen = results.getTimestamp("last_seen_at");

				return new Live(
						results.getObject("session_id", UUID.class),
						results.getLong("account_id"),
						AccountKind.valueOf(results.getString("kind")),
						nullableLong(results, "prof_id"),
						nullableLong(results, "student_id"),
						results.getString("display_name"),
						results.getBoolean("must_rotate"),
						nullableLong(results, "device_id"),
						nullableInt(results, "node"),
						results.getObject("install_id", UUID.class),
						lastSeen == null ? null : lastSeen.toInstant());
			}
		}
	}

	private static void touch(@NonNull Connection connection, @NonNull Live live)
			throws SQLException {

		if (live.lastSeenAt() != null
				&& live.lastSeenAt().isAfter(Instant.now().minus(TOUCH_AFTER))) {
			return;
		}

		try (PreparedStatement statement = connection.prepareStatement(
				"UPDATE app_session SET last_seen_at = now() WHERE session_id = ?")) {

			statement.setObject(1, live.sessionId());
			statement.executeUpdate();
		}

		if (live.deviceId() != null) {

			// Through device_seen(), not an UPDATE. This runs before the actor is set -- it is
			// part of working out who the actor is -- and device_registration has policies, so
			// a plain UPDATE here would match no row and silently never write the column.
			try (PreparedStatement statement = connection.prepareStatement(
					"SELECT device_seen(?)")) {

				statement.setLong(1, live.deviceId());
				statement.execute();
			}
		}
	}

	/**
	 * SHA-256 of the token's bytes.
	 * <p>
	 * Not a password hash, and deliberately not Argon2id: a token is thirty-two random bytes
	 * rather than something a person chose, so there is no dictionary to slow down and nothing
	 * for a work factor to buy. What the digest is for is that the stored value cannot be
	 * replayed, and one pass of SHA-256 does that while being cheap enough to run on every
	 * request.
	 */
	@NonNull
	private static byte[] hash(@NonNull String token) {

		try {
			return MessageDigest.getInstance("SHA-256")
					.digest(token.getBytes(StandardCharsets.US_ASCII));

		} catch (NoSuchAlgorithmException e) {
			// SHA-256 is required of every JVM, so this is unreachable rather than handled.
			throw new IllegalStateException("SHA-256 is missing from this JVM", e);
		}
	}

	@Nullable
	private static Long nullableLong(@NonNull ResultSet results, @NonNull String column)
			throws SQLException {
		long value = results.getLong(column);
		return results.wasNull() ? null : value;
	}

	@Nullable
	private static Integer nullableInt(@NonNull ResultSet results, @NonNull String column)
			throws SQLException {
		int value = results.getInt(column);
		return results.wasNull() ? null : value;
	}
}
