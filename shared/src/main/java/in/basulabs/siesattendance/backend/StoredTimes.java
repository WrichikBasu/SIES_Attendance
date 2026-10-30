package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * How a device writes a date or a timestamp into SQLite, and how to read one back.
 * <p>
 * The app reaches these through its Room type converters. The server reaches them directly,
 * because importing a backup means reading the device's own columns: a lecture's start is the
 * text {@code "1719043800|0|Asia/Kolkata"} and has to become a {@code timestamptz} and a zone.
 * A second parser for that format would be a second opinion about when a lecture happened, and
 * the symptom of its being wrong would be attendance filed under the wrong day rather than an
 * error.
 *
 * <h3>Why the format is what it is</h3>
 * {@code epochSecond|nanoAdjustment|zoneId}, rather than ISO-8601, because
 * {@code DateTimeFormatter} and {@code ZonedDateTime.parse} can be unreliable under
 * core-library desugaring on pre-API-26 devices. The leading integer is also what lets a SQLite
 * query slice the instant out with {@code SUBSTR} and compare it without parsing, which several
 * of the app's queries do.
 * <p>
 * A {@link LocalDate} is stored as its epoch day instead: a date has no time and no zone, so
 * storing it as an instant would invent both and risk shifting the date across a zone change.
 */
public final class StoredTimes {

	private static final String SEPARATOR = "|";

	private StoredTimes() {
	}

	/** The stored form of a timestamp, or null for null. */
	@Nullable
	public static String format(@Nullable ZonedDateTime value) {

		if (value == null) {
			return null;
		}
		return value.toEpochSecond() + SEPARATOR + value.getNano() + SEPARATOR
				+ value.getZone().getId();
	}

	/**
	 * Reads a stored timestamp back.
	 *
	 * @throws IllegalArgumentException if the text is not in the stored form, which for an
	 *                                  import means the column holds something no release of
	 *                                  this app wrote
	 */
	@Nullable
	public static ZonedDateTime parse(@Nullable String stored) {

		if (stored == null) {
			return null;
		}

		String[] parts = stored.split("\\|", 3);

		if (parts.length != 3) {
			throw new IllegalArgumentException(
					"Not a stored timestamp (expected epochSecond|nano|zone): " + stored);
		}

		try {
			long epochSecond = Long.parseLong(parts[0]);
			int nano = Integer.parseInt(parts[1]);
			ZoneId zone = ZoneId.of(parts[2]);

			return ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSecond, nano), zone);

		} catch (RuntimeException e) {
			throw new IllegalArgumentException(
					"Not a stored timestamp: " + stored + " (" + e.getMessage() + ")", e);
		}
	}

	/** The stored form of a date: a plain count of days since 1970-01-01. */
	@Nullable
	public static Long formatDate(@Nullable LocalDate date) {
		return date == null ? null : date.toEpochDay();
	}

	/** Reads a stored date back. */
	@Nullable
	public static LocalDate parseDate(@Nullable Long epochDay) {
		return epochDay == null ? null : LocalDate.ofEpochDay(epochDay);
	}
}
