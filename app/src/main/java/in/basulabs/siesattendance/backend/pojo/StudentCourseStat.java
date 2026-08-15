package in.basulabs.siesattendance.backend.pojo;

import androidx.annotation.NonNull;

/**
 * One student's attendance within one course. Drives the per-course breakdown on the student
 * detail screen.
 */
public class StudentCourseStat {

	public long courseID;

	@NonNull
	public String courseCode;

	@NonNull
	public String courseTopic;

	public int totalLectures;

	public int attended;

	public StudentCourseStat(long courseID, @NonNull String courseCode,
	                         @NonNull String courseTopic, int totalLectures, int attended) {
		this.courseID = courseID;
		this.courseCode = courseCode;
		this.courseTopic = courseTopic;
		this.totalLectures = totalLectures;
		this.attended = attended;
	}

	public double percentage() {
		return totalLectures == 0 ? 0d : (attended * 100d) / totalLectures;
	}

	public boolean isShort() {
		return totalLectures > 0 && percentage() < 75d;
	}
}
