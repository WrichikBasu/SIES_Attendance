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
 * "SY BSc COMPUTER_SCIENCE" possible, with {@code division} narrowing it further on the streams
 * that are divided.
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
	 * The starting calendar year of the academic session. For 2025-26, this is 2025. Refers to
	 * a row in {@link EntityAcademicYear}, which holds the dates that session actually spans.
	 */
	public int academicYear;

	/**
	 * The alphanumeric roll number for this session, unique within it. This is the reason the
	 * table exists: a student is issued a new one on being promoted, so it cannot live on the
	 * student record without rewriting history every year.
	 */
	@NonNull
	public String rollNo;

	@NonNull
	public Year year;

	@NonNull
	public Stream stream;

	/*
	 * A student carries up to three departments at once, and which one matters depends on the
	 * course being looked at. A major course matches majorDept, an open elective matches
	 * oeDept, and the courses taken by a whole year and stream match none of them. See the
	 * roster query in DaoStudent for the full rule.
	 */

	/** The subject they are majoring in. Always present. */
	@NonNull
	public Department majorDept;

	/**
	 * Their secondary subject. Optional, and in practice frequently left blank, which is why
	 * the roll for a minor course also admits students whose major is the offering department.
	 */
	@Nullable
	public Department minorDept;

	/** The department of their open elective, if they are taking one. */
	@Nullable
	public Department oeDept;

	/**
	 * Which semester of {@link #year} the student is in.
	 * <p>
	 * Nullable because the year already narrows it to a pair and the app worked without it for
	 * five schema versions; rows predating the column have no honest value to carry. Where it
	 * is set, it must agree with the year, which {@code AcademicRules.validate} enforces on
	 * every write path.
	 */
	@Nullable
	public Integer semester;

	/**
	 * The division of the class the student sits in, or null when their class is not divided
	 * into any.
	 * <p>
	 * Only the aided BSc and BA split a class into divisions, so on every other stream this is
	 * not a value that has been left blank but one that does not exist; see
	 * {@link Stream#hasDivisions()}. It is also null on rows predating the column, and on a
	 * divided class whose division simply has not been recorded yet, which is why nothing
	 * treats a blank division as an error. {@code AcademicRules.validate} enforces the one rule
	 * that matters: a division may not be set on a stream that has none.
	 */
	@Nullable
	public Division division;

	public EntityStudentYear(long studentID, int academicYear, @NonNull String rollNo,
	                         @NonNull Year year, @NonNull Stream stream,
	                         @NonNull Department majorDept, @Nullable Department minorDept,
	                         @Nullable Department oeDept, @Nullable Integer semester,
	                         @Nullable Division division) {
		this.studentID = studentID;
		this.academicYear = academicYear;
		this.rollNo = rollNo;
		this.year = year;
		this.stream = stream;
		this.majorDept = majorDept;
		this.minorDept = minorDept;
		this.oeDept = oeDept;
		this.semester = semester;
		this.division = division;
	}
}
