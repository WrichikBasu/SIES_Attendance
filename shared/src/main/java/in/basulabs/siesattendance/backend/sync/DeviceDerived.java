package in.basulabs.siesattendance.backend.sync;

import androidx.annotation.NonNull;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The columns a device needs and the server does not keep.
 * <p>
 * A pulled row arrives with the columns the server stores, which is deliberately fewer than the
 * device's: {@link DeviceTables} marks the difference with {@code dropped}. Most of those are
 * genuinely absent on both sides. Two are not — {@code academic_years.startEpoch} and
 * {@code endEpoch} are {@code NOT NULL} on the device — so a row that arrives without them cannot
 * be written at all, and a pull that cannot write a row never moves its watermark.
 *
 * <h3>Why the server does not carry them</h3>
 * They are the session's dates resolved at a zone, and the zone they were resolved at was
 * whichever device happened to write the row. That is the bug the whole migration exists to
 * remove: V1 dropped them and the server derives what it needs from the dates with the zone as a
 * parameter, which is why {@code session_year_of()} takes one. Sending them back would be sending
 * one device's idea of midnight to another.
 * <p>
 * So they are computed here instead, from the dates that did travel, at the zone of the device
 * doing the computing — which is exactly what {@code EntityAcademicYear.setDates} does when a
 * person edits a session on the phone. The arithmetic is duplicated and that is the price of the
 * entity living in {@code :app}: the test in the server module checks the two agree to the second.
 *
 * <h3>The zone is a parameter</h3>
 * Never {@code ZoneId.systemDefault()} in here. A caller on a device passes its own zone; a test
 * passes a fixed one and gets an answer it can assert. An ambient zone in a derivation like this
 * is how a row written in one place reads differently in another, which is the class of defect
 * this file is cleaning up after.
 */
public final class DeviceDerived {

	/** The tables with a derivation, and the columns it fills, by their Room names. */
	private static final Map<String, List<String>> DERIVED =
			Map.of("academic_years", List.of("startEpoch", "endEpoch"));

	private DeviceDerived() {
	}

	/**
	 * The columns a write to this table has to name beyond the ones the server carries.
	 * <p>
	 * Asked by {@code DeviceSql} when it builds the statement, so the statement and the values
	 * cannot disagree about which columns exist. The {@link DeviceTables.Column} objects come
	 * out of the table's own description rather than being made here, which is what stops a
	 * name being spelled two ways.
	 */
	@NonNull
	public static List<DeviceTables.Column> columnsOf(@NonNull DeviceTables.Table table) {

		List<DeviceTables.Column> extra = new ArrayList<>();

		for (String room : DERIVED.getOrDefault(table.name(), List.of())) {
			extra.add(table.column(room));
		}
		return extra;
	}

	/**
	 * The row as the device has to write it: what arrived, plus anything the device requires
	 * and the server did not send.
	 * <p>
	 * Returns a new map. The one that came off the wire is left alone, so a caller that logs or
	 * retries sees what the server actually said.
	 *
	 * @param zone the device's own zone, for the derivations that need one
	 */
	@NonNull
	public static Map<String, Object> fill(@NonNull DeviceTables.Table table,
	                                       @NonNull Map<String, Object> row,
	                                       @NonNull ZoneId zone) {

		Map<String, Object> filled = new LinkedHashMap<>(row);

		if (table.name().equals("academic_years")) {

			Long start = epochDay(row.get("startDate"));
			Long end = epochDay(row.get("endDate"));

			if (start != null) {
				filled.put("startEpoch",
						LocalDate.ofEpochDay(start).atStartOfDay(zone).toEpochSecond());
			}

			if (end != null) {
				// The end of the final day, so a lecture at five in the afternoon on the last
				// day of the session is still inside it. EntityAcademicYear.setDates says the
				// same thing in the same way.
				filled.put("endEpoch", LocalDate.ofEpochDay(end).plusDays(1)
						.atStartOfDay(zone).toEpochSecond() - 1);
			}
		}

		return filled;
	}

	/**
	 * A date column as the device holds it: days since 1970-01-01.
	 * <p>
	 * {@link DeviceTables.Kind#EPOCH_DAY} is an integer on both sides, but which integer type a
	 * driver hands back is not something to rely on — the same lesson as
	 * {@code DeviceSql.readsAsText}.
	 */
	private static Long epochDay(Object value) {
		return value instanceof Number day ? day.longValue() : null;
	}
}
