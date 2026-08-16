package in.basulabs.siesattendance.backend.pojo;

import androidx.annotation.NonNull;

import in.basulabs.siesattendance.backend.util.NameFormat;

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
	public String displayName() {
		return NameFormat.properCase(name);
	}

	@NonNull
	public String initials() {
		return NameFormat.initials(name);
	}
}
