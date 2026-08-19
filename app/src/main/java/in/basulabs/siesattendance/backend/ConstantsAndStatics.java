package in.basulabs.siesattendance.backend;

public final class ConstantsAndStatics {

	public static final String DATABASE_NAME = "sies_attendance.sqlite3";

	/**
	 * Must match the {@code version} on {@link MainDatabase}. Kept here as well because the
	 * backup code needs to compare a file's stored schema version against what this build can
	 * open, and the annotation's value is not readable at runtime.
	 * <p>
	 * Raise this in step with the Room annotation whenever a migration is added.
	 */
	public static final int DATABASE_VERSION = 6;

	/** Extension for exported backups. */
	public static final String BACKUP_EXTENSION = ".siesbak";

	/**
	 * Deliberately generic: the backup is an opaque container, and claiming a specific type
	 * would let file pickers hide it or let other apps try to open it as something it is not.
	 */
	public static final String BACKUP_MIME_TYPE = "application/octet-stream";

}
