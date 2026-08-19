package in.basulabs.siesattendance.backend.pojo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;
import in.basulabs.siesattendance.backend.util.NameFormat;

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

	/** Null for records created before the semester was asked for. */
	@Nullable
	public Integer semester;

	public StudentWithYear(long studentID, @NonNull String name, @NonNull String pronouns,
	                       @Nullable String email, @NonNull String rollNo, int academicYear,
	                       @NonNull Year year, @NonNull Stream stream,
	                       @NonNull Department majorDept, @Nullable Department minorDept,
	                       @Nullable Department oeDept, @Nullable Integer semester) {
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
		this.semester = semester;
	}

	/**
	 * @return A label such as "SY BSc COMPUTER_SCIENCE".
	 */
	@NonNull
	public String classLabel() {
		return year + " " +
				(stream.isUnaided() ? stream.toString().replace(" (unaided)", "") : stream)
				+ " " + majorDept;
	}

	/**
	 * The name as it should be shown. The raw {@code name} field keeps whatever was imported.
	 */
	@NonNull
	public String displayName() {
		return NameFormat.properCase(name);
	}

	@NonNull
	public String initials() {
		return NameFormat.initials(name);
	}
}
