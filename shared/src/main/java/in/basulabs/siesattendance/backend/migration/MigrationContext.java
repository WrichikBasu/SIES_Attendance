package in.basulabs.siesattendance.backend.migration;

import androidx.annotation.NonNull;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The two pieces of ambient state the migrations used to read straight off the machine they
 * were running on: the time zone and today's date.
 * <p>
 * On a phone those were simply {@link ZoneId#systemDefault()} and {@link LocalDate#now()}, and
 * they were the right answers, because the device running the migration was the device that
 * had written the rows. On the server they are the wrong answers twice over. A service runs in
 * UTC by deliberate configuration, so {@code systemDefault()} there is not the zone the data
 * was recorded in; and a backup being imported was written at some point in the past, so the
 * server's "today" is not the phone's.
 * <p>
 * The difference is not academic. Seeding the session table derives
 * {@code startEpoch}/{@code endEpoch} from a date at a zone, and the same migration run on a
 * phone in Asia/Kolkata and on a server in UTC produces values five and a half hours apart —
 * measured, not supposed. So the zone is a parameter, the importer passes the zone the data
 * came from, and the phone passes the one it is standing in.
 *
 * @param zone  the zone the database's local dates should be interpreted at
 * @param today the date to treat as the present, used only for seeding a session to file new
 *              attendance under
 */
public record MigrationContext(@NonNull ZoneId zone, @NonNull LocalDate today) {

	/**
	 * What the app passes: the device's own zone and clock, which is what the migrations read
	 * directly before they were shared.
	 */
	@NonNull
	public static MigrationContext ofSystemDefault() {
		ZoneId zone = ZoneId.systemDefault();
		return new MigrationContext(zone, LocalDate.now(zone));
	}
}
