package in.basulabs.siesattendance.server.importer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import in.basulabs.siesattendance.backend.migration.SqliteDb;

/**
 * Lets the shared schema migrations run against a SQLite file over JDBC.
 * <p>
 * The counterpart of the app's {@code RoomSqliteDb}. The migration bodies are in
 * {@code :shared} and are written against {@link SqliteDb} precisely so that this is all the
 * server needs to run the identical steps on a backup it is about to import.
 * <p>
 * {@link SQLException} is wrapped rather than declared. The migrations cannot throw a checked
 * exception — they are the app's code and the app's connection does not have them — and a
 * failure here is not something a caller can handle anyway: the backup is not what it claimed
 * to be, and the import has to stop.
 */
final class JdbcSqliteDb implements SqliteDb {

	@NonNull
	private final Connection connection;

	JdbcSqliteDb(@NonNull Connection connection) {
		this.connection = connection;
	}

	@Override
	public void exec(@NonNull String sql) {

		try (Statement statement = connection.createStatement()) {
			statement.execute(sql);
		} catch (SQLException e) {
			throw new MigrationFailedException(sql, e);
		}
	}

	@Override
	public void exec(@NonNull String sql, @NonNull Object... args) {

		try (PreparedStatement statement = connection.prepareStatement(sql)) {

			for (int i = 0; i < args.length; i++) {
				statement.setObject(i + 1, args[i]);
			}
			statement.execute();

		} catch (SQLException e) {
			throw new MigrationFailedException(sql, e);
		}
	}

	@NonNull
	@Override
	public List<Row> query(@NonNull String sql) {

		List<Row> rows = new ArrayList<>();

		try (Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(sql)) {

			ResultSetMetaData meta = results.getMetaData();
			int columns = meta.getColumnCount();

			while (results.next()) {

				Object[] values = new Object[columns];

				for (int i = 0; i < columns; i++) {
					values[i] = results.getObject(i + 1);
				}
				rows.add(new ValueRow(values));
			}

		} catch (SQLException e) {
			throw new MigrationFailedException(sql, e);
		}
		return rows;
	}

	/**
	 * A row already read out of the result set, matching {@link SqliteDb#query}'s promise that
	 * the rows outlive the statement.
	 */
	private record ValueRow(@NonNull Object[] values) implements Row {

		@Override
		public boolean isNull(int column) {
			return values[column] == null;
		}

		@Override
		public int getInt(int column) {
			return (int) ((Number) values[column]).longValue();
		}

		@Override
		public long getLong(int column) {
			return ((Number) values[column]).longValue();
		}

		@Nullable
		@Override
		public String getString(int column) {
			Object value = values[column];
			return value == null ? null : String.valueOf(value);
		}
	}

	/** A migration statement that would not run against the backup. */
	static final class MigrationFailedException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		MigrationFailedException(@NonNull String sql, @NonNull SQLException cause) {
			super("Migrating the backup failed on: " + sql, cause);
		}
	}
}
