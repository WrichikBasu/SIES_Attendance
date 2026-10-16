package in.basulabs.siesattendance.backend.pojo;

import androidx.annotation.NonNull;

/**
 * One stored mark: a student, a lecture, and whether they were there.
 * <p>
 * What the per-lecture and per-day exports are built from. Only saved marks produce one, so a
 * student who was never put on a lecture's sheet has no row here rather than a default: the
 * export says what was recorded, not what the roll would have implied.
 */
public class LectureMark {

	public long lectureID;

	public long studentID;

	/** As the roster supplied it; tidied for display by the exporter. */
	@NonNull
	public String name = "";

	/**
	 * The roll number the student held in the session the lecture falls in, which is the one
	 * the register was called by. Empty when they have no row for that session.
	 */
	@NonNull
	public String rollNo = "";

	public boolean present;
}
