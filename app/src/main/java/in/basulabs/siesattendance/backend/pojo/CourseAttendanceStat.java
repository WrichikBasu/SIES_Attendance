package in.basulabs.siesattendance.backend.pojo;

import androidx.annotation.NonNull;

/**
 * Per-course attendance aggregate across all its lectures.
 * <p>
 * {@code totalRecords} counts attendance rows, whereas {@code lectureCount} counts the lectures
 * themselves, so the two answer different questions: how much attendance was recorded against
 * the course, and how many times it actually met. A course with lectures but no records has the
 * second without the first, which is how an unmarked course shows up.
 */
public class CourseAttendanceStat {

	public long courseID;

	@NonNull
	public String courseCode;

	@NonNull
	public String courseTopic;

	public int totalRecords;

	public int presentRecords;

	public int lectureCount;

	public CourseAttendanceStat(long courseID, @NonNull String courseCode,
	                            @NonNull String courseTopic, int totalRecords,
	                            int presentRecords, int lectureCount) {
		this.courseID = courseID;
		this.courseCode = courseCode;
		this.courseTopic = courseTopic;
		this.totalRecords = totalRecords;
		this.presentRecords = presentRecords;
		this.lectureCount = lectureCount;
	}

	/** Attendance across the course as a whole, or zero when nothing has been marked yet. */
	public double percentage() {
		if (totalRecords == 0) {
			return 0d;
		}
		return (presentRecords * 100d) / totalRecords;
	}
}
