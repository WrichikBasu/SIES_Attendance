package in.basulabs.siesattendance.backend;

/**
 * Values that more than one part of the app has to agree on, and that would be a bug to state
 * twice.
 */
public final class ConstantsAndStatics {

	/**
	 * File name of the live database. The restore path replaces the file underneath Room, so
	 * the name has to be known outside {@link MainDatabase} as well.
	 */
	public static final String DATABASE_NAME = "sies_attendance.sqlite3";

	/**
	 * Must match the {@code version} on {@link MainDatabase}. Kept here as well because the
	 * backup code needs to compare a file's stored schema version against what this build can
	 * open, and the annotation's value is not readable at runtime.
	 * <p>
	 * Raise this in step with the Room annotation whenever a migration is added.
	 */
	public static final int DATABASE_VERSION = 10;

	/**
	 * Extension for exported backups. Distinctive rather than descriptive, so a backup is
	 * recognisable in a file manager without inviting anything else to open it.
	 */
	public static final String BACKUP_EXTENSION = ".siesbak";

	/**
	 * Deliberately generic: the backup is an opaque container, and claiming a specific type
	 * would let file pickers hide it or let other apps try to open it as something it is not.
	 */
	public static final String BACKUP_MIME_TYPE = "application/octet-stream";

}
