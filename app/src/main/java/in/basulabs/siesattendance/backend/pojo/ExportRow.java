package in.basulabs.siesattendance.backend.pojo;

import androidx.annotation.NonNull;

/**
 * One student's figures for one course, as the export sheet needs them.
 * <p>
 * The query returns a long list of these, one per student per course; the exporter pivots them
 * into a grid with a pair of columns per course.
 */
public class ExportRow {

	/*
	 * Fields are initialised rather than set through a constructor because Room populates a raw
	 * query result field by field. The empty-string defaults keep a column that the query did
	 * not select from arriving as null into a @NonNull field.
	 */

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
	 * Lectures of this course the student has a mark for, present or absent.
	 * <p>
	 * Counted from the student's own marks and never from batch membership, so changing
	 * batch during the year changes nothing here; see {@code ExportQueryBuilder}.
	 */
	public int held;

	/** How many of those they were marked present at. */
	public int attended;
}
