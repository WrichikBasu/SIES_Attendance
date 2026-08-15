package in.basulabs.siesattendance.backend.pojo;

import androidx.annotation.NonNull;

/**
 * Per-course attendance aggregate across all its lectures.
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

	public double percentage() {
		if (totalRecords == 0) {
			return 0d;
		}
		return (presentRecords * 100d) / totalRecords;
	}
}
