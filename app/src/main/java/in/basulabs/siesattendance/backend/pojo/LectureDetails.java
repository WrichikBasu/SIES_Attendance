package in.basulabs.siesattendance.backend.pojo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.ZonedDateTime;

/**
 * A lecture joined with its course and batch names, plus a count of how many attendance rows
 * already exist for it. A count of zero means attendance has not been marked yet.
 */
public class LectureDetails {

	public long lectureID;

	public long courseID;

	@Nullable
	public Long batchID;

	@NonNull
	public String courseCode;

	@NonNull
	public String courseTopic;

	/** Null when the lecture covered the whole class rather than one batch. */
	@Nullable
	public String batchName;

	@NonNull
	public ZonedDateTime startDateTime;

	@NonNull
	public ZonedDateTime endDateTime;

	@Nullable
	public String notes;

	public int markedCount;

	public int presentCount;

	public LectureDetails(long lectureID, long courseID, @Nullable Long batchID,
	                      @NonNull String courseCode, @NonNull String courseTopic,
	                      @Nullable String batchName, @NonNull ZonedDateTime startDateTime,
	                      @NonNull ZonedDateTime endDateTime, @Nullable String notes,
	                      int markedCount, int presentCount) {
		this.lectureID = lectureID;
		this.courseID = courseID;
		this.batchID = batchID;
		this.courseCode = courseCode;
		this.courseTopic = courseTopic;
		this.batchName = batchName;
		this.startDateTime = startDateTime;
		this.endDateTime = endDateTime;
		this.notes = notes;
		this.markedCount = markedCount;
		this.presentCount = presentCount;
	}

	public boolean isMarked() {
		return markedCount > 0;
	}

	/**
	 * True when the lecture covered the course's whole class rather than a single batch.
	 * Callers use this to substitute their own localised label, since a POJO has no resources.
	 */
	public boolean isWholeClass() {
		return batchName == null || batchName.isEmpty();
	}
}
