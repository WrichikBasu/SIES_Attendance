package in.basulabs.siesattendance.backend;

import java.time.ZoneId;

/**
 * Facts about the college that every program has to agree on.
 */
public final class College {

	/**
	 * The zone a sheet's dates and times are read in: the one the register on paper was kept in.
	 * <p>
	 * One constant for the phone and the server. The phone used to read a sheet in whatever zone
	 * the phone was set to, and the server in this one, so the same file was filed at two
	 * different instants by the two (R14). A lecture taken on the phone is still recorded in the
	 * phone's own zone, which for every phone in the college is this one.
	 */
	public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

	private College() {
	}
}
