package in.basulabs.siesattendance.backend;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import java.time.ZonedDateTime;
import java.util.List;

import in.basulabs.siesattendance.backend.pojo.LectureDetails;

/**
 * Lectures, along with the joined view of them that every list in the app displays.
 * <p>
 * Anything selecting a lecture for display goes through {@link #DETAILS_SELECT} rather than
 * {@code SELECT *}, because a lecture on its own shows nothing worth reading: the course code,
 * the batch name and the marked and present counts all come from elsewhere.
 * <p>
 * Date filtering is awkward here and appears in several queries. Timestamps are stored as
 * "epochSecond|nano|zone", so the leading integer has to be sliced out and cast before it can be
 * compared as a number.
 */
@Dao
public abstract class DaoLecture {

	/** The raw insert. Callers want {@link #insert}, which issues the ID. */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	protected abstract long insertRow(EntityLecture lecture);

	/**
	 * Inserts a lecture, stamping it with an ID first. Nothing in the database generates one:
	 * see {@link IdGenerator}.
	 *
	 * @return the ID the lecture was written under
	 */
	public long insert(EntityLecture lecture) {
		lecture.lectureID = IdGenerator.idFor(lecture.lectureID);
		return insertRow(lecture);
	}

	@Delete
	public abstract void delete(EntityLecture lecture);

	/**
	 * The shared projection behind every {@link LectureDetails} query.
	 * <p>
	 * The batch join is LEFT because a whole-class lecture has no batch, and an INNER join would
	 * silently drop exactly those lectures. The two counts are correlated subqueries rather than
	 * joins onto {@code attendance}, which keeps one row per lecture; joining would multiply the
	 * lecture out by its attendance rows and make every other column wrong.
	 */
	public static final String DETAILS_SELECT =
			"SELECT l.lectureID, l.courseID, l.batchID, c.courseCode, c.courseTopic, " +
					"b.batchName, l.startDateTime, l.endDateTime, l.notes, " +
					"(SELECT COUNT(*) FROM attendance a WHERE a.lectureID = l.lectureID) " +
					"   AS markedCount, " +
					"(SELECT COUNT(*) FROM attendance a WHERE a.lectureID = l.lectureID " +
					"   AND a.present = 1) AS presentCount " +
					"FROM lectures l " +
					"INNER JOIN courses c ON c.courseID = l.courseID " +
					"LEFT JOIN batch_desc b ON b.batchID = l.batchID ";

	/**
	 * Every lecture on record, newest first. Ordering on the stored string works here because
	 * the epoch prefix is fixed-width for any date this app will see.
	 */
	@Query(DETAILS_SELECT + "ORDER BY l.startDateTime DESC")
	public abstract LiveData<List<LectureDetails>> getAllDetails();

	/**
	 * Lectures whose stored start timestamp falls within the given epoch-second bounds. Because
	 * the type converter stores "epochSecond|nano|zone", the leading integer is extracted with
	 * SUBSTR/INSTR and cast, which keeps the comparison numeric rather than lexicographic.
	 */
	@Query(DETAILS_SELECT +
			"WHERE CAST(SUBSTR(l.startDateTime, 1, INSTR(l.startDateTime, '|') - 1) AS INTEGER) " +
			"BETWEEN :fromEpoch AND :toEpoch " +
			"ORDER BY l.startDateTime ASC")
	public abstract LiveData<List<LectureDetails>> getDetailsBetween(long fromEpoch, long toEpoch);

	@Query(DETAILS_SELECT +
			"WHERE CAST(SUBSTR(l.startDateTime, 1, INSTR(l.startDateTime, '|') - 1) AS INTEGER) " +
			"BETWEEN :fromEpoch AND :toEpoch " +
			"ORDER BY l.startDateTime ASC")
	public abstract List<LectureDetails> getDetailsBetweenSync(long fromEpoch, long toEpoch);

	@Query(DETAILS_SELECT + "WHERE l.lectureID = :lectureID")
	public abstract LectureDetails getDetailsByIdSync(long lectureID);

	@Query(DETAILS_SELECT + "WHERE l.courseID = :courseID ORDER BY l.startDateTime DESC")
	public abstract LiveData<List<LectureDetails>> getDetailsForCourse(long courseID);

	/** How many lectures fall in a window. Used for the dashboard counts. */
	@Query("SELECT COUNT(*) FROM lectures WHERE " +
			"CAST(SUBSTR(startDateTime, 1, INSTR(startDateTime, '|') - 1) AS INTEGER) " +
			"BETWEEN :fromEpoch AND :toEpoch")
	public abstract LiveData<Integer> countLecturesBetween(long fromEpoch, long toEpoch);

	@Query("SELECT * FROM lectures WHERE lectureID = :lectureID")
	public abstract EntityLecture getByIdSync(long lectureID);

	/**
	 * Rewrites a lecture in place, keeping its ID and therefore its attendance sheet.
	 * <p>
	 * Moving a lecture to a different course or batch can leave marks against students who are
	 * not on the new roll. {@code DaoAttendance.deleteForLectureExcept} is the other half of
	 * that operation, and the edit path calls both.
	 */
	@Query("UPDATE lectures SET courseID = :courseID, batchID = :batchID, " +
			"startDateTime = :start, endDateTime = :end, notes = :notes " +
			"WHERE lectureID = :lectureID")
	public abstract void updateLecture(long lectureID, long courseID, Long batchID,
	                                   ZonedDateTime start, ZonedDateTime end, String notes);

	/**
	 * Finds a lecture already recorded for the same course, batch and time span.
	 * <p>
	 * SQLite treats NULLs as distinct in a unique index, so a whole-class lecture would not be
	 * caught by the index constraint. Matching the null case explicitly is what stops the same
	 * session being recorded twice.
	 */
	@Query("SELECT lectureID FROM lectures WHERE courseID = :courseID " +
			"AND ((:batchID IS NULL AND batchID IS NULL) OR batchID = :batchID) " +
			"AND startDateTime = :start AND endDateTime = :end LIMIT 1")
	public abstract Long findExisting(long courseID, Long batchID, ZonedDateTime start,
	                                  ZonedDateTime end);

	@Query("DELETE FROM attendance WHERE lectureID = :lectureID")
	public abstract void deleteAttendanceFor(long lectureID);

	/**
	 * Deleting a lecture also discards its attendance sheet. Attendance is meaningless without
	 * the lecture it belongs to, so this cascade is deliberate rather than a RESTRICT.
	 */
	@Transaction
	public void deleteLectureCascade(EntityLecture lecture) {
		deleteAttendanceFor(lecture.lectureID);
		delete(lecture);
	}
}
