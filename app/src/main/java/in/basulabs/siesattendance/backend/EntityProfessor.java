package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Represents a professor. The professor is identified by their name, email, department,
 * and designation. The ID is issued by the app on insert because professors do not have a
 * unique ID other than fingerprint.
 * <p>
 * Professors are never deleted. Every course names one, and every lecture and attendance row
 * hangs off a course, so removing a professor would orphan a chain of records that are the
 * point of the app. Details that go out of date are corrected by editing the row; see
 * {@code DaoProfessor}, which deliberately offers no delete.
 */
@Entity(tableName = EntityProfessor.TABLE_NAME,
		indices = {@Index(value = {"name", "department"}),
				@Index(value = {"profID"}, unique = true),
				@Index(value = {"email"}, unique = true),
				@Index(value = {"name", "department", "email"}, unique =true),
				@Index(value = {"department"})})
public class EntityProfessor {

	@Ignore
	public static final String TABLE_NAME = "professors";

	/**
	 * Stamped by {@link IdGenerator} on insert, and unique across every device that syncs into
	 * the central database. Professors have no unique identifier of their own beyond the
	 * fingerprint of name, department and address, which is why the row needs one of these at
	 * all.
	 */
	@PrimaryKey
	public long profID;

	@NonNull
	public String name;

	/**
	 * Uniquely indexed on its own, which makes it the reliable way to tell two professors apart
	 * when they share a name and a department.
	 */
	@NonNull
	public String email;

	/**
	 * The department they belong to, which is not necessarily the department offering every
	 * course they teach: a Physics lecturer may well take a course for the Chemistry students.
	 */
	@NonNull
	public Department department;

	/** Assistant Professor, Associate Professor and so on. Free text, since titles vary. */
	@NonNull
	public String designation;

	public EntityProfessor(@NonNull String name, @NonNull String email,
	                       @NonNull Department department, @NonNull String designation) {
		this.name = name;
		this.email = email;
		this.department = department;
		this.designation = designation;
	}
}
