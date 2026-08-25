package in.basulabs.siesattendance.backend.pojo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.ZonedDateTime;

/**
 * One lecture of one course, and whether one particular student was there.
 * <p>
 * This is {@link StudentCourseStat} opened up: that POJO says a student attended 8 of 11
 * lectures, and this one says which 11 and which 3 they missed. A percentage answers whether
 * there is a problem; the dates are what anybody has to look at to do something about it, since
 * three absences in a row is a different conversation from three spread over a term.
 * <p>
 * Only lectures the student has an attendance row for appear, which is the same reckoning
 * {@code DaoStudent.getCourseBreakdown} uses: a lecture they were left off the sheet for
 * entirely is not an absence, and showing it as one would invent a record that was never taken.
 * <p>
 * The lecture's note is not carried. It describes the class rather than this student, so it has
 * nothing to add to a row that exists to say whether they were there.
 */
public class StudentLectureMark {

	public long lectureID;

	@NonNull
	public ZonedDateTime startDateTime;

	@NonNull
	public ZonedDateTime endDateTime;

	/** Null when the lecture was held for the whole class rather than a single batch. */
	@Nullable
	public String batchName;

	public boolean present;

	public StudentLectureMark(long lectureID, @NonNull ZonedDateTime startDateTime,
	                          @NonNull ZonedDateTime endDateTime, @Nullable String batchName,
	                          boolean present) {
		this.lectureID = lectureID;
		this.startDateTime = startDateTime;
		this.endDateTime = endDateTime;
		this.batchName = batchName;
		this.present = present;
	}

	/**
	 * True when the lecture covered the course's whole class rather than a single batch.
	 * Callers substitute their own localised label, since a POJO has no resources.
	 */
	public boolean isWholeClass() {
		return batchName == null || batchName.isEmpty();
	}
}
