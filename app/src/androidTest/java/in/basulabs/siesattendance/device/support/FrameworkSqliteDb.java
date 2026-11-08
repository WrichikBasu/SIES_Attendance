package in.basulabs.siesattendance.device.support;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import in.basulabs.siesattendance.backend.migration.SqliteDb;

/**
 * The shared migrations over a bare framework {@link SQLiteDatabase}, with no Room in between.
 * <p>
 * Used only to walk a version 6 file forward to some version short of the current one, so a
 * test can then hand the last step(s) to Room and watch Room do them. {@code RoomSqliteDb} would
 * need a {@code SupportSQLiteDatabase}, which only exists once Room has opened the file — and
 * opening it is the thing being tested.
 */
public final class FrameworkSqliteDb implements SqliteDb {

	@NonNull
	private final SQLiteDatabase db;

	public FrameworkSqliteDb(@NonNull SQLiteDatabase db) {
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

		try (Cursor cursor = db.rawQuery(sql, null)) {

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
