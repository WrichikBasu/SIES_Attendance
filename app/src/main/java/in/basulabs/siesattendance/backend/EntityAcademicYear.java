package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

/**
 * One academic session, with the dates that bound it.
 * <p>
 * Before this table existed, {@code academicYear} was a bare integer scattered across three
 * tables and its boundaries were hardcoded as "starts in June" in three separate places in Java.
 * Worse, lectures carry no session column at all, so a report could not tell a lecture from last
 * year apart from one held this morning. This table is what a lecture's date is matched against
 * to decide which session it belongs to.
 * <p>
 * A college running an August-to-May calendar, or a year with an unusual end date, just edits
 * the row.
 */
@Entity(tableName = EntityAcademicYear.TABLE_NAME)
public class EntityAcademicYear {

	@Ignore
	public static final String TABLE_NAME = "academic_years";

	/**
	 * The starting calendar year, which doubles as the identifier: 2025 is the 2025-26 session.
	 * Every other table's {@code academicYear} column refers to this value.
	 */
	@PrimaryKey
	public int academicYear;

	@NonNull
	public LocalDate startDate;

	@NonNull
	public LocalDate endDate;

	/**
	 * Inclusive epoch-second bounds, derived from the dates above at the device's zone.
	 * <p>
	 * These exist purely so SQL can match a lecture's stored timestamp against a session
	 * without doing calendar arithmetic in the query. They are derived state: never set them
	 * directly, use {@link #of} or {@link #setDates}, which keep them in step with the dates.
	 */
	public long startEpoch;

	public long endEpoch;

	public EntityAcademicYear(int academicYear, @NonNull LocalDate startDate,
	                          @NonNull LocalDate endDate, long startEpoch, long endEpoch) {
		this.academicYear = academicYear;
		this.startDate = startDate;
		this.endDate = endDate;
		this.startEpoch = startEpoch;
		this.endEpoch = endEpoch;
	}

	/**
	 * Builds a session, computing the epoch bounds from the dates.
	 */
	@Ignore
	@NonNull
	public static EntityAcademicYear of(int academicYear, @NonNull LocalDate start,
	                                    @NonNull LocalDate end) {
		EntityAcademicYear year = new EntityAcademicYear(academicYear, start, end, 0L, 0L);
		year.setDates(start, end);
		return year;
	}

	/**
	 * The default shape of a session that begins in June, used when seeding a year nobody has
	 * defined yet. It is a starting point, not a rule: the row can be edited afterwards.
	 */
	@Ignore
	@NonNull
	public static EntityAcademicYear defaultFor(int academicYear) {
		return of(academicYear,
				LocalDate.of(academicYear, 6, 1),
				LocalDate.of(academicYear + 1, 5, 31));
	}

	/**
	 * Which session a calendar date falls in under the default June rule. Used only where no
	 * session has been defined yet.
	 */
	public static int defaultYearFor(@NonNull LocalDate date) {
		return date.getMonthValue() >= 6 ? date.getYear() : date.getYear() - 1;
	}

	@Ignore
	public void setDates(@NonNull LocalDate start, @NonNull LocalDate end) {
		this.startDate = start;
		this.endDate = end;

		ZoneId zone = ZoneId.systemDefault();
		this.startEpoch = start.atStartOfDay(zone).toEpochSecond();
		// End of the final day, so a lecture at 5 p.m. on the last day is still inside.
		this.endEpoch = end.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1;
	}

	/** e.g. "2025-26". */
	@NonNull
	public String label() {
		return labelFor(academicYear);
	}

	/**
	 * The same label for a session that has not been read from the database, or that has no row
	 * at all. Kept static because the label is derived entirely from the starting year, and
	 * several callers have the year without the row.
	 * <p>
	 * The trailing year is padded, so 2099-2100 reads "2099-00" rather than "2099-0", and the
	 * format is fixed to {@link Locale#ROOT} because this is an identifier that appears in file
	 * names and is compared by eye against the college's own paperwork, not a localised number.
	 */
	@NonNull
	public static String labelFor(int academicYear) {
		return academicYear + "-"
				+ String.format(Locale.ROOT, "%02d", (academicYear + 1) % 100);
	}

	public boolean contains(@NonNull LocalDate date) {
		return !date.isBefore(startDate) && !date.isAfter(endDate);
	}
}
