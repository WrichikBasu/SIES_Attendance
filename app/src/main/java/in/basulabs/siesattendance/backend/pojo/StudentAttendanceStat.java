package in.basulabs.siesattendance.backend.pojo;

import androidx.annotation.NonNull;

import in.basulabs.siesattendance.backend.util.NameFormat;

/**
 * Per-student attendance aggregate, used by the statistics screen.
 * <p>
 * Counts attendance rows rather than lectures held, so a student who was left off a sheet is
 * not counted absent for it. The export deliberately does the opposite; see {@code ExportRow}.
 */
public class StudentAttendanceStat {

	public long studentID;

	@NonNull
	public String name;

	@NonNull
	public String rollNo;

	public int totalLectures;

	public int attended;

	public StudentAttendanceStat(long studentID, @NonNull String name, @NonNull String rollNo,
	                             int totalLectures, int attended) {
		this.studentID = studentID;
		this.name = name;
		this.rollNo = rollNo;
		this.totalLectures = totalLectures;
		this.attended = attended;
	}

	@NonNull
	public String displayName() {
		return NameFormat.properCase(name);
	}

	public double percentage() {
		if (totalLectures == 0) {
			return 0d;
		}
		return (attended * 100d) / totalLectures;
	}

	/**
	 * Most Indian universities require 75% attendance. This flags students below that line.
	 * <p>
	 * The threshold is fixed here, unlike on the statistics screen, where {@code StatsFilter}
	 * makes it adjustable. This one backs the simpler lists, which have nowhere to set it.
	 */
	public boolean isShort() {
		return totalLectures > 0 && percentage() < 75d;
	}
}
