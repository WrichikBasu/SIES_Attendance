package in.basulabs.siesattendance.server.sql;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A statement and the values bound into it, in order.
 * <p>
 * The server's counterpart to the app's {@code SupportSQLiteQuery}. It exists for one reason:
 * the two query builders must hand back the SQL and the arguments together, because the
 * arguments are positional and a fragment appended without its value — or values added in the
 * wrong order — produces a statement that still runs and still returns rows, just the wrong
 * ones. Keeping them in one object means the pairing cannot be lost on the way to the caller.
 * <p>
 * Nothing here interpolates a value into the SQL. Every value a user supplied is a {@code ?},
 * which is also why the only strings spliced into a statement by the builders are compile-time
 * constants of this application.
 */
public final class BoundStatement {

	@NonNull
	private final String sql;

	@NonNull
	private final List<Object> args;

	BoundStatement(@NonNull String sql, @NonNull List<Object> args) {
		this.sql = sql;
		this.args = Collections.unmodifiableList(new ArrayList<>(args));
	}

	@NonNull
	public String sql() {
		return sql;
	}

	/** The bound values, in the order their placeholders appear. May contain nulls. */
	@NonNull
	public List<Object> args() {
		return args;
	}

	/** For {@code JdbcClient.sql(...).params(...)}, which wants an array. */
	@NonNull
	public Object[] argArray() {
		return args.toArray();
	}

	@Override
	@NonNull
	public String toString() {
		return sql + "  " + args;
	}
}
