package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Ignore;
import androidx.room.Index;

/**
 * Represents a student in a particular academic year.
 * <p>
 * Given that a student's alphanumeric roll number changes each year, this table connects a
 * student's personal details to their current details that will change after this academic year.
 * <p>
 * The {@code stream} and {@code year} fields are what make a class-wise lookup such as
 * "SY BSc COMPUTER_SCIENCE" possible.
 */
@Entity(tableName = EntityStudentYear.TABLE_NAME,
		primaryKeys = {"studentID", "academicYear"},
		indices = {
				@Index(value = {"rollNo", "academicYear"}, unique = true),
				@Index(value = {"academicYear", "year", "stream", "majorDept"}),
				@Index(value = {"studentID"})
		},
		foreignKeys = {
				@ForeignKey(entity = EntityStudent.class,
						parentColumns = "studentID",
						childColumns = "studentID",
						onDelete = ForeignKey.RESTRICT,
						onUpdate = ForeignKey.CASCADE)
		})
public class EntityStudentYear {

	@Ignore
	public static final String TABLE_NAME = "student_year";

	public long studentID;

	/**
	 * The starting calendar year of the academic session. For 2025-26, this is 2025.
	 */
	public int academicYear;

	@NonNull
	public String rollNo;

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

	public EntityStudentYear(long studentID, int academicYear, @NonNull String rollNo,
	                         @NonNull Year year, @NonNull Stream stream,
	                         @NonNull Department majorDept, @Nullable Department minorDept,
	                         @Nullable Department oeDept) {
		this.studentID = studentID;
		this.academicYear = academicYear;
		this.rollNo = rollNo;
		this.year = year;
		this.stream = stream;
		this.majorDept = majorDept;
		this.minorDept = minorDept;
		this.oeDept = oeDept;
	}
}
