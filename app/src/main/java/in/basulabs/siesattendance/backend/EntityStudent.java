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
 */
@Entity(tableName = EntityStudent.TABLE_NAME,
		indices = {@Index(value = {"email"}, unique = true)})
public class EntityStudent {

	@Ignore
	public static final String TABLE_NAME = "students";

	/**
	 * Auto-generated ID that is used in this database. Has NO relation with the roll number, and
	 * does NOT exist in the college database.
	 */
	@PrimaryKey(autoGenerate = true)
	public long studentID;

	@NonNull
	public String name;

	@NonNull
	public String pronouns;

	@Nullable
	public String email;

	public EntityStudent(@NonNull String name, @NonNull String pronouns, @Nullable String email) {
		this.name = name;
		this.pronouns = pronouns;
		this.email = email;
	}
}
