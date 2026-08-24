package in.basulabs.siesattendance.backend.pojo;

import androidx.annotation.NonNull;

import in.basulabs.siesattendance.backend.util.NameFormat;

/**
 * One row on the mark-attendance screen: a student, and whether they were there.
 * <p>
 * Built from the roll rather than read from the database, so a sheet exists in full before
 * anything is saved. Reopening a lecture rebuilds the rows the same way and then applies the
 * marks already stored over the top.
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
	 * always carries an answer, and a fresh sheet starts with everyone present. That matches how
	 * a roll is actually called: most of the class is there, and the professor marks the few who
	 * are not. It also means a sheet can be saved the moment the absentees are tapped in,
	 * without walking the whole list to confirm the obvious.
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

	/** The name tidied for display; {@link #name} keeps whatever the roster supplied. */
	@NonNull
	public String displayName() {
		return NameFormat.properCase(name);
	}

	/** One or two letters for the avatar shown beside the row. */
	@NonNull
	public String initials() {
		return NameFormat.initials(name);
	}
}
