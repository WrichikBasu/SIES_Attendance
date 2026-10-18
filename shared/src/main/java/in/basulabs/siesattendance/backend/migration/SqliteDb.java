package in.basulabs.siesattendance.backend.migration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * The little of a SQLite database the schema migrations actually need.
 * <p>
 * The migrations used to be written against {@code SupportSQLiteDatabase}, which is an
 * androidx interface and so kept them inside the app. The server has to run the very same
 * migrations — a backup from an old phone arrives at whatever version that phone was on, and
 * bringing it to the current schema is the first thing the importer does — and it reaches
 * SQLite through JDBC, not through Room.
 * <p>
 * So the migrations are written against this instead: three methods, which is all nine of them
 * between them use. Each side supplies one adapter, and the migration bodies are literally the
 * same code on the phone and on the server rather than two translations of one intent.
 *
 * @see SchemaMigrations
 */
public interface SqliteDb {

	/** Runs a statement with no arguments. */
	void exec(@NonNull String sql);

	/**
	 * Runs a statement with positional arguments, bound rather than interpolated.
	 * <p>
	 * Only the session seed uses this, and its values are numbers, so nothing here is at risk
	 * of injection. They are bound anyway: a migration is the one place where a quoting bug
	 * would be discovered by its effect on real data.
	 */
	void exec(@NonNull String sql, @NonNull Object... args);

	/**
	 * Runs a query and returns every row.
	 * <p>
	 * Materialised rather than streamed, deliberately. The three queries the migrations run
	 * return a handful of integers between them — distinct session years, and one row of
	 * {@code MIN}/{@code MAX} — so there is nothing to gain from a cursor and a good deal to
	 * lose in having to abstract one.
	 */
	@NonNull
	List<Row> query(@NonNull String sql);

	/** One row of a result, read by position. */
	interface Row {

		boolean isNull(int column);

		int getInt(int column);

		long getLong(int column);

		@Nullable
		String getString(int column);
	}
}
