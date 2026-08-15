package in.basulabs.siesattendance.backend.pojo;

import androidx.annotation.NonNull;

/**
 * One row on the mark-attendance screen. {@code present} is tri-state in the UI: the Boolean is
 * null until the user taps present or absent, which lets the UI distinguish "not yet marked"
 * from "explicitly marked absent".
 */
public class AttendanceRow {

	public final long studentID;

	@NonNull
	public final String name;

	@NonNull
	public final String rollNo;

	public Boolean present;

	public AttendanceRow(long studentID, @NonNull String name, @NonNull String rollNo,
	                     Boolean present) {
		this.studentID = studentID;
		this.name = name;
		this.rollNo = rollNo;
		this.present = present;
	}

	@NonNull
	public String initials() {
		String[] parts = name.trim().split("\\s+");
		if (parts.length == 1) {
			return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase();
		}
		return ("" + parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase();
	}
}
