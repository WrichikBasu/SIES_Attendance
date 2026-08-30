package in.basulabs.siesattendance.backend.pojo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Division;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;

/**
 * A distinct (year, stream, department, division) combination present in the database for a
 * given academic year, for example "SY BSc COMPUTER_SCIENCE". Used to populate the class filter.
 * <p>
 * The division is part of the key because a division is a class: FY BA English Div A and Div B
 * are taught apart and are what anyone filtering by class means. It is null for the streams that
 * are not divided, and for students on a divided stream whose division has not been recorded, so
 * those two land in one chip together.
 * <p>
 * These are derived from the students actually enrolled rather than from the possible
 * combinations, so the filter only ever offers classes that exist. {@code studentCount} comes
 * from the same grouping, which is what lets a chip show its size without a second query.
 */
public class ClassKey {

	@NonNull
	public Year year;

	@NonNull
	public Stream stream;

	@NonNull
	public Department majorDept;

	/** Null for an undivided class; see the class comment. */
	@Nullable
	public Division division;

	public int studentCount;

	public ClassKey(@NonNull Year year, @NonNull Stream stream, @NonNull Department majorDept,
	                @Nullable Division division, int studentCount) {
		this.year = year;
		this.stream = stream;
		this.majorDept = majorDept;
		this.division = division;
		this.studentCount = studentCount;
	}

	/**
	 * This class as it is written and spoken; see
	 * {@link #label(Year, Stream, Department, Division)}.
	 */
	@NonNull
	public String label() {
		return label(year, stream, majorDept, division);
	}

	/**
	 * A class as it is written and spoken, for instance "SY BSc CS" or "FY BA English Div B".
	 * <p>
	 * The unaided qualifier is dropped: it distinguishes two streams in a dropdown, but a class
	 * label is already long enough, and the department alongside it says which one this is
	 * anyway. The division is appended only when there is one, so the labels of the streams that
	 * are not divided are unchanged.
	 * <p>
	 * Static because a student carries the same four values and has to render them identically;
	 * {@code StudentWithYear.classLabel} calls this rather than writing the format out a second
	 * time, since two copies of it would eventually disagree.
	 */
	@NonNull
	public static String label(@NonNull Year year, @NonNull Stream stream,
	                           @NonNull Department majorDept, @Nullable Division division) {
		return year + " "
				+ (stream.isUnaided() ? stream.toString().replace(" (unaided)", "") : stream)
				+ " " + majorDept
				+ (division == null ? "" : " " + division.longLabel());
	}

	@NonNull
	@Override
	public String toString() {
		return label();
	}
}
