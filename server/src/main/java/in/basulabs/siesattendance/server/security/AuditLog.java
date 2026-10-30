package in.basulabs.siesattendance.server.security;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import in.basulabs.siesattendance.backend.AuditAction;

/**
 * Records the things no trigger can see.
 * <p>
 * A row change writes its own entry: {@code V7__audit_log.sql} puts an {@code AFTER} trigger on
 * every table a dispute could turn on, and those entries carry a before and after image. What a
 * trigger cannot see is everything that leaves no row behind — a login, a failed login, a
 * lockout, a password change — and, most importantly, an export, which leaves nothing behind at
 * all and is the realistic way a term's attendance leaves the building.
 *
 * <h3>It cannot name somebody else</h3>
 * {@code audit_event()} takes no actor. It reads one from the same session settings the policies
 * read, so these methods take a {@link Connection} whose transaction has already had
 * {@link ActorSession#announce} called on it, and there is no parameter by which a caller could
 * attribute an entry to a different person. That is the difference between a log and a story.
 *
 * <h3>The service has no privilege on the table</h3>
 * Not even {@code INSERT}. {@code audit_event()} is {@code SECURITY DEFINER} and owned by the
 * migration role, so the only way in is through it — which means there is no statement the
 * service can issue that touches {@code audit_log} directly, and nothing to get wrong about
 * remembering not to.
 */
public final class AuditLog {

	/*
	 * Jackson is already on the classpath for the web layer, so there is no second JSON
	 * writer here -- unlike ImportReport in :shared, which hand-rolls one because the Android
	 * module cannot have this dependency.
	 *
	 * tools.jackson, not com.fasterxml.jackson: Spring Boot 4 ships Jackson 3, which moved
	 * every package. The old coordinates still appear in the dependency tree as
	 * jackson-annotations, which is why a wrong import compiles against nothing and fails
	 * confusingly.
	 */
	private static final ObjectMapper JSON = new ObjectMapper();

	private AuditLog() {
	}

	/**
	 * Records an event with no row and no detail: a login, a lockout.
	 *
	 * @return the {@code audit_id} written
	 */
	public static long record(@NonNull Connection connection, @NonNull AuditAction action)
			throws SQLException {
		return record(connection, action, null, null, null);
	}

	/**
	 * Records an event with detail — an export's filter, an import's counts.
	 * <p>
	 * The detail map is written as a JSON object. Put the things somebody would need in order
	 * to understand the entry a year later in it, and nothing that would make the log itself
	 * worth stealing: no password, no hash, no token, and no bulk copy of the data that was
	 * exported. "Which filter, how many rows" answers the question; the rows themselves do not.
	 */
	public static long record(@NonNull Connection connection, @NonNull AuditAction action,
	                          @Nullable Map<String, ?> detail) throws SQLException {
		return record(connection, action, null, null, detail);
	}

	/**
	 * The full form.
	 *
	 * @param table  the table an entry is about, when it is about one. A row change may not be
	 *               recorded this way at all — triggers do that — and the function refuses it
	 * @param rowKey which row, as a JSON object of its key columns
	 * @param detail anything else worth keeping
	 */
	public static long record(@NonNull Connection connection, @NonNull AuditAction action,
	                          @Nullable String table, @Nullable Map<String, ?> rowKey,
	                          @Nullable Map<String, ?> detail) throws SQLException {

		if (action.isRowChange()) {
			throw new IllegalArgumentException(action + " is written by the audit trigger on "
					+ "the table itself, which is the only writer that has the before and "
					+ "after images. Recording it here would produce an entry describing a "
					+ "change nobody can see.");
		}

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT audit_event(?::audit_action, ?, ?::jsonb, ?::jsonb)")) {

			statement.setString(1, action.name());
			statement.setString(2, table);
			statement.setString(3, json(rowKey));
			statement.setString(4, json(detail));

			try (ResultSet results = statement.executeQuery()) {
				if (!results.next()) {
					throw new SQLException("audit_event() returned no id");
				}
				return results.getLong(1);
			}
		}
	}

	/** A detail map, in declaration order, because an entry reads better in a fixed one. */
	@NonNull
	public static Map<String, Object> detail(@NonNull Object... pairs) {

		if (pairs.length % 2 != 0) {
			throw new IllegalArgumentException("detail() takes key, value, key, value");
		}

		Map<String, Object> map = new LinkedHashMap<>();

		for (int at = 0; at < pairs.length; at += 2) {
			map.put(String.valueOf(pairs[at]), pairs[at + 1]);
		}
		return map;
	}

	@Nullable
	private static String json(@Nullable Map<String, ?> values) throws SQLException {

		if (values == null || values.isEmpty()) {
			return null;
		}

		try {
			return JSON.writeValueAsString(values);
		} catch (tools.jackson.core.JacksonException e) {
			// Unchecked in Jackson 3, and still caught: an audit entry that silently loses its
			// detail is a worse outcome than a failed write, because the first is invisible.
			throw new SQLException("could not write the audit detail as JSON", e);
		}
	}
}
