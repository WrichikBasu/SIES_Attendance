package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Represents a student.
 * <p>
 * While a student should have had some kind of fixed unique ID, we do not have so, and the
 * same person has multiple roll numbers when they are promoted to the next year. Therefore,
 * the {@code studentID} field works as a unique identifier for a student across multiple years.
 * <p>
 * This table holds only the details that stay with the person for as long as they are at the
 * college. Everything that is true of them for one session and not the next, the roll number
 * above all, lives in {@link EntityStudentYear} instead. Keeping the two apart is what allows a
 * report for an earlier year to show the roll number the student actually held at the time,
 * rather than the one they hold now.
 */
@Entity(tableName = EntityStudent.TABLE_NAME,
		indices = {@Index(value = {"email"}, unique = true)})
public class EntityStudent {

	@Ignore
	public static final String TABLE_NAME = "students";

	/**
	 * The ID used in this database. Has NO relation with the roll number, and does NOT exist in
	 * the college database.
	 * <p>
	 * Stamped by {@link IdGenerator} when the student is first inserted, not by SQLite, and
	 * unique across every device that syncs into the central database rather than only within
	 * this one. Left at zero on a student who has not been saved yet, which is how
	 * {@code DaoStudent.insertStudent} tells the two apart.
	 */
	@PrimaryKey
	public long studentID;

	/**
	 * Stored exactly as it arrived, which for an imported roster usually means all capitals.
	 * Nothing normalises it on the way in, because the office's spelling is the authoritative
	 * one and rewriting it would make a name harder to match against their records later.
	 * {@code NameFormat.properCase} handles the tidying at display time.
	 */
	@NonNull
	public String name;

	/**
	 * Free text rather than a fixed set, since neither the college roster nor the people on it
	 * restrict themselves to a short list. Used when the app has to refer to a student in a
	 * sentence.
	 */
	@NonNull
	public String pronouns;

	/**
	 * Uniquely indexed, and the fallback identity when a roll number does not match during
	 * import: a student who has changed roll numbers is still recognisably the same person by
	 * their college address. Nullable because a roster does not always carry one, and a student
	 * with no address should not be blocked from being entered.
	 */
	@Nullable
	public String email;

	public EntityStudent(@NonNull String name, @NonNull String pronouns, @Nullable String email) {
		this.name = name;
		this.pronouns = pronouns;
		this.email = email;
	}
}
