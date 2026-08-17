package in.basulabs.siesattendance.backend;

import androidx.annotation.Nullable;
import androidx.room.TypeConverter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public final class TypeConvertors {

	private static final String SEPARATOR = "|";

	// ── ZonedDateTime ────────────────────────────────────────────────────
	// Stored as "epochSecond|nanoAdjustment|zoneId", e.g. "1719043800|0|Asia/Kolkata".
	// Avoids DateTimeFormatter and ZonedDateTime.parse(), which can be
	// unreliable under core-library desugaring on pre-API-26 devices.

	@TypeConverter
	@Nullable
	public static String fromZonedDateTime(@Nullable ZonedDateTime zdt) {
		if (zdt == null) {
			return null;
		}
		return zdt.toEpochSecond() + SEPARATOR + zdt.getNano() + SEPARATOR
				+ zdt.getZone().getId();
	}

	@TypeConverter
	@Nullable
	public static ZonedDateTime toZonedDateTime(@Nullable String value) {
		if (value == null) {
			return null;
		}
		String[] parts = value.split("\\|", 3);
		long epochSecond = Long.parseLong(parts[0]);
		int nano = Integer.parseInt(parts[1]);
		ZoneId zone = ZoneId.of(parts[2]);
		return ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSecond, nano), zone);
	}

	// ── LocalDate ────────────────────────────────────────────────────────
	// Stored as the epoch day: a plain integer count of days since 1970-01-01. A LocalDate has
	// no time and no zone, so storing it as an instant would invent both and risk shifting the
	// date across a zone change. The epoch day has neither problem and sorts correctly.

	@TypeConverter
	@Nullable
	public static Long fromLocalDate(@Nullable LocalDate date) {
		return date == null ? null : date.toEpochDay();
	}

	@TypeConverter
	@Nullable
	public static LocalDate toLocalDate(@Nullable Long epochDay) {
		return epochDay == null ? null : LocalDate.ofEpochDay(epochDay);
	}

	// ── Department ───────────────────────────────────────────────────────

	@TypeConverter
	@Nullable
	public static String fromDepartment(@Nullable Department department) {
		return department == null ? null : department.name();
	}

	@TypeConverter
	@Nullable
	public static Department toDepartment(@Nullable String value) {
		return value == null ? null : Department.valueOf(value);
	}

	// ── Stream ───────────────────────────────────────────────────────────

	@TypeConverter
	@Nullable
	public static String fromStream(@Nullable Stream stream) {
		return stream == null ? null : stream.name();
	}

	@TypeConverter
	@Nullable
	public static Stream toStream(@Nullable String value) {
		return value == null ? null : Stream.valueOf(value);
	}

	// ── Year ─────────────────────────────────────────────────────────────

	@TypeConverter
	@Nullable
	public static String fromYear(@Nullable Year year) {
		return year == null ? null : year.name();
	}

	@TypeConverter
	@Nullable
	public static Year toYear(@Nullable String value) {
		return value == null ? null : Year.valueOf(value);
	}

	// ── CourseType ────────────────────────────────────────────────────────

	@TypeConverter
	@Nullable
	public static String fromCourseType(@Nullable CourseType courseType) {
		return courseType == null ? null : courseType.name();
	}

	@TypeConverter
	@Nullable
	public static CourseType toCourseType(@Nullable String value) {
		return value == null ? null : CourseType.valueOf(value);
	}

}