package in.basulabs.siesattendance.backend.pojo;

import androidx.annotation.NonNull;

import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;

/**
 * A distinct (year, stream, department) combination present in the database for a given
 * academic year — for example "SY BSc COMPUTER_SCIENCE". Used to populate the class filter.
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

	@NonNull
	public String label() {
		return year.name() + " " + stream.name() + " " + majorDept.name();
	}

	@NonNull
	@Override
	public String toString() {
		return label();
	}
}
