package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Ignore;
import androidx.room.Index;

import java.time.LocalDate;

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
	 * A student carries up to three departments at once: what they major in, their minor and
	 * their open elective. None of them puts the student on a course's roll; the batches they
	 * are in do.
	 */

	/** The subject they are majoring in. Always present. */
	@NonNull
	public Department majorDept;

	/**
	 * Their secondary subject. Optional, and in practice frequently left blank.
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
	 * The day the student joined this session's class, or null for the start of the session,
	 * which is what it means for nearly everybody. A late joiner's attendance is counted only
	 * from here: lectures held before it are not held for them.
	 */
	@Nullable
	public LocalDate joinDate;

	/**
	 * The day the student left, or null while they are enrolled. Once set, they come off this
	 * session's rolls, its batch lists, the exports and, unless asked for, the students list.
	 * Their attendance before it is kept.
	 */
	@Nullable
	public LocalDate leaveDate;

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
