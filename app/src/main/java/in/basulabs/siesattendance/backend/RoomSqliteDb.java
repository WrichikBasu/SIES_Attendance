package in.basulabs.siesattendance.backend;

import android.database.Cursor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.sqlite.db.SupportSQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

import in.basulabs.siesattendance.backend.migration.SqliteDb;

/**
 * Lets the shared schema migrations run against a Room database.
 * <p>
 * The migration bodies live in {@code :shared} because the server runs them too, on backups it
 * is importing, and they are therefore written against {@link SqliteDb} rather than against
 * {@link SupportSQLiteDatabase}. This is the twenty lines that connect the two on this side;
 * the server has its own, over JDBC.
 * <p>
 * Rows are read out of the cursor eagerly, which is what {@link SqliteDb#query} promises. The
 * migrations' queries return a handful of integers between them, so there is nothing to stream.
 */
public final class RoomSqliteDb implements SqliteDb {

	@NonNull
	private final SupportSQLiteDatabase db;

	public RoomSqliteDb(@NonNull SupportSQLiteDatabase db) {
		this.db = db;
	}

	@Override
	public void exec(@NonNull String sql) {
		db.execSQL(sql);
	}

	@Override
	public void exec(@NonNull String sql, @NonNull Object... args) {
		db.execSQL(sql, args);
	}

	@NonNull
	@Override
	public List<Row> query(@NonNull String sql) {

		List<Row> rows = new ArrayList<>();

		try (Cursor cursor = db.query(sql)) {

			int columns = cursor.getColumnCount();

			while (cursor.moveToNext()) {

				Object[] values = new Object[columns];

				for (int i = 0; i < columns; i++) {
					values[i] = switch (cursor.getType(i)) {
						case Cursor.FIELD_TYPE_NULL -> null;
						case Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i);
						case Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i);
						default -> cursor.getString(i);
					};
				}
				rows.add(new ValueRow(values));
			}
		}
		return rows;
	}

	/** A row already read out of the cursor, since the cursor is closed before the caller looks. */
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
}
