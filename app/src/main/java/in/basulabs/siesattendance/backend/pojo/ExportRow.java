package in.basulabs.siesattendance.backend.pojo;

import androidx.annotation.NonNull;

/**
 * One student's figures for one course, as the export sheet needs them.
 * <p>
 * The query returns a long list of these, one per student per course; the exporter pivots them
 * into a grid with a pair of columns per course.
 */
public class ExportRow {

	public long studentID;

	@NonNull
	public String rollNo = "";

	@NonNull
	public String studentName = "";

	public long courseID;

	@NonNull
	public String courseCode = "";

	@NonNull
	public String courseTopic = "";

	/**
	 * Lectures of this course that this student was supposed to be at: whole-class lectures
	 * plus lectures for batches they belong to.
	 * <p>
	 * Counted from the lectures actually held, not from the student's attendance rows. Those
	 * two differ whenever a student was left off a sheet, and using the rows would quietly
	 * shrink the denominator for exactly the students whose attendance is in question.
	 */
	public int held;

	/** How many of those they were marked present at. */
	public int attended;
}
