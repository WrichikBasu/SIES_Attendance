package in.basulabs.siesattendance.server.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

import in.basulabs.siesattendance.backend.PostgresEnumTypes;
import in.basulabs.siesattendance.backend.StoredTimes;
import in.basulabs.siesattendance.backend.sync.DeviceTables;

/**
 * How a value the device stored becomes a value PostgreSQL will take.
 * <p>
 * One conversion, two callers. The importer reads these values out of a backup's SQLite file; the
 * sync endpoint reads the same values out of a JSON body, because a device sends its rows exactly
 * as it holds them. They have to agree about what {@code "1757000000|0|Asia/Kolkata"} means, and
 * the only way they agree for ever is that there is one place that decides.
 * <p>
 * Moved here from {@code ImportSession}, where it was private and correct and about to be copied.
 * The importer's fourteen tests and the dual-engine differ are what prove the move was inert.
 *
 * <h3>What the device's representation is</h3>
 * {@link DeviceTables.Kind} says, column by column: text as text, a boolean as 0 or 1, a date as
 * days since 1970, a timestamp as {@code "epochSecond|nano|zone"}, an enum as the constant's
 * name. None of that is this class's choice — it is what Room wrote, and it is what a backup
 * holds — so a reader that guessed differently would be reading a different database.
 */
public final class DeviceValue {

	/** One PostgreSQL column and the value bound into it. */
	public record Field(@NonNull String column, @Nullable Object value, @NonNull String cast) {

		/** The placeholder this field contributes, carrying its cast if it needs one. */
		@NonNull
		public String placeholder() {
			return "?" + cast;
		}
	}

	/**
	 * A value that cannot be converted at all.
	 * <p>
	 * Not a failure of the caller's: it means the row holds something no release of this app
	 * wrote, or an enum constant this build does not have. The importer turns it into a skipped
	 * row with a reason; the sync endpoint turns it into a rejected intent with the same reason.
	 */
	public static final class Unconvertible extends RuntimeException {

		private static final long serialVersionUID = 1L;

		public Unconvertible(@NonNull String reason) {
			super(reason);
		}
	}

	private DeviceValue() {
	}

	/** One ordinary column, converted from the way SQLite stored it to the way PostgreSQL will. */
	@NonNull
	public static List<Field> convert(@NonNull DeviceTables.Table table,
	                            @NonNull DeviceTables.Column column, @Nullable Object raw) {

		String name = Objects.requireNonNull(column.postgres());

		switch (column.kind()) {

			case TEXT -> {
				return List.of(new Field(name, raw == null ? null : String.valueOf(raw), ""));
			}

			case INTEGER -> {
				return List.of(new Field(name,
						raw == null ? null : ((Number) raw).longValue(), ""));
			}

			case BOOLEAN -> {
				// SQLite has no boolean; Room wrote 0 or 1 into an INTEGER column.
				return List.of(new Field(name,
						raw == null ? null : ((Number) raw).longValue() != 0L, ""));
			}

			case EPOCH_DAY -> {
				return List.of(new Field(name,
						raw == null ? null
								: StoredTimes.parseDate(((Number) raw).longValue()), ""));
			}

			case ENUM -> {
				String stored = raw == null ? null : String.valueOf(raw);
				String cast = "::" + PostgresEnumTypes.of(column.enumType());

				if (stored != null) {
					requireKnownConstant(table, column, stored);
				}
				return List.of(new Field(name, stored, cast));
			}

			case ZONED_DATE_TIME -> {

				if (raw == null) {
					return List.of(new Field(name, null, ""),
							new Field(column.zoneColumn(), null, ""));
				}

				ZonedDateTime value;

				try {
					value = StoredTimes.parse(String.valueOf(raw));
				} catch (IllegalArgumentException e) {
					throw new Unconvertible(column.room() + " holds " + raw
							+ ", which is not a timestamp any release of this app wrote.");
				}

				return List.of(
						new Field(name, value.toOffsetDateTime(), ""),
						new Field(column.zoneColumn(), value.getZone().getId(), ""));
			}

			default -> throw new IllegalStateException("Unhandled kind " + column.kind());
		}
	}

	/**
	 * The other direction: a server row as the device would hold it.
	 * <p>
	 * What the pull sends. The device applies it with Room's own column names and Room's own
	 * representation, so nothing on the phone has to know that the server keeps a timestamp as
	 * two columns or a boolean as a boolean — and the same {@link DeviceTables.Kind} switch
	 * decides both directions, which is what stops them drifting apart.
	 *
	 * @param row the current server row, keyed by PostgreSQL column name
	 * @return the value to send under the column's Room name, or null
	 */
	@Nullable
	public static Object toDevice(@NonNull DeviceTables.Column column,
	                              @NonNull java.util.Map<String, Object> row) {

		Object value = row.get(column.postgres());

		switch (column.kind()) {

			case TEXT, INTEGER -> {
				return value;
			}

			case BOOLEAN -> {
				// SQLite has no boolean, and Room wrote 0 or 1. Sending true would store the
				// text "true" in an INTEGER column on some drivers and 1 on others, which is
				// the kind of difference that shows up as attendance nobody marked.
				return value == null ? null : (((Boolean) value) ? 1L : 0L);
			}

			case EPOCH_DAY -> {
				return value == null ? null
						: StoredTimes.formatDate(((java.sql.Date) value).toLocalDate());
			}

			case ENUM -> {
				return value == null ? null : String.valueOf(value);
			}

			case ZONED_DATE_TIME -> {

				Object zone = row.get(column.zoneColumn());

				if (value == null || zone == null) {
					return null;
				}

				// The instant from one column and the zone it was recorded in from the other,
				// back into the single string Room stores. Both halves are needed: the instant
				// is when it happened and the zone is what the wall clock said.
				return StoredTimes.format(((java.sql.Timestamp) value).toInstant()
						.atZone(java.time.ZoneId.of(String.valueOf(zone))));
			}

			default -> throw new IllegalStateException("Unhandled kind " + column.kind());
		}
	}

	/**
	 * Refuses an enum value no longer in the Java enum.
	 * <p>
	 * PostgreSQL would refuse it too, on the cast, but the error would name a type and a string
	 * and not the row. A constant can be added freely and must never be renamed or removed once
	 * rows exist that use it, so a value that does not resolve means the data and the code have
	 * diverged and somebody has to decide what the row should say.
	 */
	private static void requireKnownConstant(@NonNull DeviceTables.Table table,
	                                  @NonNull DeviceTables.Column column,
	                                  @NonNull String stored) {

		for (Object constant : column.enumType().getEnumConstants()) {
			if (((Enum<?>) constant).name().equals(stored)) {
				return;
			}
		}

		throw new Unconvertible(column.room() + " holds '" + stored + "', which is not a "
				+ column.enumType().getSimpleName() + " this build knows. The row cannot be "
				+ "imported until somebody decides what it should say.");
	}
}
