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

	/**
	 * Present unless marked otherwise.
	 * <p>
	 * A primitive rather than a Boolean, so there is no third "not yet decided" state: a row
	 * always carries an answer, and a fresh sheet starts with everyone present. That matches
	 * how a roll is actually called — most of the class is there, and the professor marks the
	 * few who are not — and it means a sheet can be saved the moment the absentees are tapped
	 * in, without walking the whole list to confirm the obvious.
	 * <p>
	 * The cost is that a row cannot record "I have not looked at this student yet", so a
	 * student skipped by accident is filed as present rather than flagged. Marking everyone
	 * explicitly is what the nullable version bought, and this trades it away deliberately.
	 */
	public boolean present = true;

	public AttendanceRow(long studentID, @NonNull String name, @NonNull String rollNo,
	                     boolean present) {
		this.studentID = studentID;
		this.name = name;
		this.rollNo = rollNo;
		this.present = present;
	}

	/** A student on a fresh sheet: present until someone says otherwise. */
	public AttendanceRow(long studentID, @NonNull String name, @NonNull String rollNo) {
		this(studentID, name, rollNo, true);
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
