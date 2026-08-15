package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Represents a professor. The professor is identified by their name, email, department,
 * and designation. The ID is auto-generated because professors do not have a unique ID other
 * than fingerprint.
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

	@PrimaryKey(autoGenerate = true)
	public long profID;

	@NonNull
	public String name;

	@NonNull
	public String email;

	@NonNull
	public Department department;

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
