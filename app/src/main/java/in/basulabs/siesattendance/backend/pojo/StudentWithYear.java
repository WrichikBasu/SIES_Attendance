package in.basulabs.siesattendance.backend.pojo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;

/**
 * A flattened join of {@code students} and {@code student_year}, which is what every student
 * list in the UI actually needs to display.
 */
public class StudentWithYear {

	public long studentID;

	@NonNull
	public String name;

	@NonNull
	public String pronouns;

	@Nullable
	public String email;

	@NonNull
	public String rollNo;

	public int academicYear;

	@NonNull
	public Year year;

	@NonNull
	public Stream stream;

	@NonNull
	public Department majorDept;

	@Nullable
	public Department minorDept;

	@Nullable
	public Department oeDept;

	public StudentWithYear(long studentID, @NonNull String name, @NonNull String pronouns,
	                       @Nullable String email, @NonNull String rollNo, int academicYear,
	                       @NonNull Year year, @NonNull Stream stream,
	                       @NonNull Department majorDept, @Nullable Department minorDept,
	                       @Nullable Department oeDept) {
		this.studentID = studentID;
		this.name = name;
		this.pronouns = pronouns;
		this.email = email;
		this.rollNo = rollNo;
		this.academicYear = academicYear;
		this.year = year;
		this.stream = stream;
		this.majorDept = majorDept;
		this.minorDept = minorDept;
		this.oeDept = oeDept;
	}

	/**
	 * @return A label such as "SY BSc COMPUTER_SCIENCE".
	 */
	@NonNull
	public String classLabel() {
		return year.name() + " " + stream.name() + " " + majorDept.name();
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
