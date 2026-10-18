package in.basulabs.siesattendance.backend;

import androidx.annotation.Nullable;
import androidx.room.TypeConverter;

import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * Converters between the types the entities use and the primitives SQLite can store.
 * <p>
 * Two rules run through all of it. Anything that has to be compared or sorted in SQL is stored
 * as a number, not as formatted text, so a query can work with it without parsing. And anything
 * shown to the user is stored by its stable identity rather than its label, so rewording a
 * label is never a data migration.
 */
public final class TypeConvertors {

	// ── ZonedDateTime ────────────────────────────────────────────────────
	// Stored as "epochSecond|nanoAdjustment|zoneId", e.g. "1719043800|0|Asia/Kolkata".
	// The format and the parsing are StoredTimes', in the shared module, because the
	// server reads these columns too when it imports a backup.

	@TypeConverter
	@Nullable
	public static String fromZonedDateTime(@Nullable ZonedDateTime zdt) {
		return StoredTimes.format(zdt);
	}

	@TypeConverter
	@Nullable
	public static ZonedDateTime toZonedDateTime(@Nullable String value) {
		return StoredTimes.parse(value);
	}

	// ── LocalDate ────────────────────────────────────────────────────────
	// Stored as the epoch day: a plain integer count of days since 1970-01-01. A LocalDate has
	// no time and no zone, so storing it as an instant would invent both and risk shifting the
	// date across a zone change. The epoch day has neither problem and sorts correctly.

	@TypeConverter
	@Nullable
	public static Long fromLocalDate(@Nullable LocalDate date) {
		return StoredTimes.formatDate(date);
	}

	@TypeConverter
	@Nullable
	public static LocalDate toLocalDate(@Nullable Long epochDay) {
		return StoredTimes.parseDate(epochDay);
	}

	// ── Enums ────────────────────────────────────────────────────────────
	// Enums are stored by name(), never by toString(). The display label is free to change, and
	// does not even have to be unique: Stream.BSc and Stream.BSc_unaided both read "BSc". So
	// storing labels would corrupt the database the first time one was reworded, and would have
	// made those two streams indistinguishable from the day they were added.
	//
	// The consequence is that a constant may be added freely but never renamed or removed once
	// rows exist that use it. valueOf() below throws on an unknown name rather than returning
	// null, which is the right failure: a name that no longer resolves means the data and the
	// code have diverged, and carrying on with a silent null would spread the damage.

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

	// ── Division ─────────────────────────────────────────────────────────

	@TypeConverter
	@Nullable
	public static String fromDivision(@Nullable Division division) {
		return division == null ? null : division.name();
	}

	@TypeConverter
	@Nullable
	public static Division toDivision(@Nullable String value) {
		return value == null ? null : Division.valueOf(value);
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