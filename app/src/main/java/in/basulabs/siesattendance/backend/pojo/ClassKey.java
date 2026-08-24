package in.basulabs.siesattendance.backend.pojo;

import androidx.annotation.NonNull;

import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;

/**
 * A distinct (year, stream, department) combination present in the database for a given academic
 * year, for example "SY BSc COMPUTER_SCIENCE". Used to populate the class filter.
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

	public int studentCount;

	public ClassKey(@NonNull Year year, @NonNull Stream stream, @NonNull Department majorDept,
	                int studentCount) {
		this.year = year;
		this.stream = stream;
		this.majorDept = majorDept;
		this.studentCount = studentCount;
	}

	/**
	 * The class as it is written and spoken, for instance "SY BSc CS". The unaided qualifier is
	 * dropped: it distinguishes two streams in a dropdown, but a class label is already long
	 * enough, and the department alongside it makes the division obvious anyway.
	 */
	@NonNull
	public String label() {
		return year + " "
				+ (stream.isUnaided() ? stream.toString().replace(" (unaided)", "") : stream)
				+ " " + majorDept;
	}

	@NonNull
	@Override
	public String toString() {
		return label();
	}
}
