package in.basulabs.siesattendance.server.importer;

import androidx.annotation.NonNull;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicLong;

import in.basulabs.siesattendance.backend.StructuredId;
import in.basulabs.siesattendance.backend.sync.DeviceTables;

/**
 * Mints primary keys under the server's own node.
 * <p>
 * The importer needs this for one case only: a row whose device key cannot be carried across.
 * Keys issued since schema v7 are structured and already unique everywhere, so they come over
 * unchanged; keys from before that were counted out by SQLite from 1 in each file independently
 * and genuinely mean nothing outside the file they came from, so they are replaced.
 * <p>
 * The replacements are minted under {@link StructuredId#SERVER_NODE}, which is zero and is
 * never assigned to a device. That is what makes a key the server invented distinguishable, for
 * ever, from one a phone issued — including on the day somebody has to work out where a row
 * came from.
 *
 * <h3>Priming</h3>
 * The counter lives in memory, so on every start it is raised above every key the server has
 * already issued, read out of the database. Without that, a restart with a clock that had
 * drifted backwards would mint a key that is already taken and the insert would abort — or
 * worse, land on an existing row. Only node 0's keys are considered: a device's key differs in
 * the node field however close the clocks are, and letting a phone with a fast clock drag this
 * counter forward would make server keys claim a creation time that never happened.
 *
 * @see StructuredId for the layout itself
 * @see in.basulabs.siesattendance.backend.IdGenerator the app's equivalent, on a device
 */
final class ServerIds {

	/** Time and sequence of the last key issued, which is what climbs. */
	private final AtomicLong lastCounter = new AtomicLong(0L);

	/**
	 * Raises the floor above every key this server has already issued.
	 *
	 * @throws SQLException if the database cannot be read, which has to stop the import rather
	 *                      than let it mint from a floor of zero
	 */
	void primeFrom(@NonNull Connection connection) throws SQLException {

		StringBuilder sql = new StringBuilder("SELECT MAX(highest) FROM (");
		boolean first = true;

		for (DeviceTables.Table table : DeviceTables.inDependencyOrder()) {

			if (table.surrogate() == null) {
				continue;
			}

			if (!first) {
				sql.append(" UNION ALL ");
			}
			first = false;

			String column = table.column(table.surrogate()).postgres();

			// The node is a literal rather than a parameter because this is assembled from
			// the table description and run once at startup, and a prepared statement over a
			// UNION this wide buys nothing. Nothing interpolated here comes from outside the
			// program: the names are DeviceTables', the numbers are StructuredId's.
			sql.append("SELECT MAX(").append(column).append(") AS highest FROM ")
					.append(table.name())
					.append(" WHERE ").append(column).append(" >= ")
					.append(StructuredId.FIRST_STRUCTURED_ID)
					.append(" AND (").append(column).append(" & ")
					.append(StructuredId.MAX_NODE_ID).append(") = ")
					.append(StructuredId.SERVER_NODE);
		}
		sql.append(") AS issued");

		try (Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(sql.toString())) {

			if (results.next()) {

				long highest = results.getLong(1);

				if (!results.wasNull()) {
					lastCounter.accumulateAndGet(StructuredId.counterOf(highest), Math::max);
				}
			}
		}
	}

	/** A fresh key under the server's node. */
	long next() {
		return StructuredId.compose(nextCounter(), StructuredId.SERVER_NODE);
	}

	/**
	 * The next time-and-sequence value: the clock, or one past the last one issued when the
	 * clock has not moved on, has moved backwards, or is being outrun by an import.
	 * <p>
	 * An import writes thousands of rows well inside one millisecond, so the sequence doing its
	 * job is the common case here rather than the exception it is on a phone.
	 */
	private long nextCounter() {

		while (true) {

			long previous = lastCounter.get();
			long fromClock = (System.currentTimeMillis() - StructuredId.EPOCH_MILLIS)
					<< StructuredId.SEQUENCE_BITS;
			long candidate = Math.max(Math.max(fromClock, StructuredId.MIN_COUNTER),
					previous + 1);

			if (lastCounter.compareAndSet(previous, candidate)) {
				return candidate;
			}
		}
	}
}
