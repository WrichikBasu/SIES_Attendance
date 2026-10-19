package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import in.basulabs.siesattendance.backend.migration.MigrationContext;
import in.basulabs.siesattendance.backend.migration.SchemaMigrations;

/**
 * Schema migrations, as Room wants them.
 * <p>
 * The bodies are not here. They are in {@link SchemaMigrations}, in the shared module, because
 * the server runs the identical steps on a backup before importing it: a backup carries
 * whatever schema version the phone that wrote it was on, and the importer is written against
 * the current one. Two copies of a migration would be two ideas of what an old database means,
 * and the symptom of their disagreeing would be wrong attendance rather than an error.
 * <p>
 * What is left here is the adapter: each {@link Migration} Room needs, wrapping the
 * corresponding shared step, with {@link RoomSqliteDb} bridging the connection and
 * {@link MigrationContext#ofSystemDefault()} supplying the device's own zone and clock — which
 * is what the migrations read directly before they were shared, so the app's behaviour is
 * unchanged.
 */
public final class Migrations {

	private Migrations() {
	}

	/**
	 * Wraps the shared step upgrading from {@code from} as a Room migration.
	 * <p>
	 * Room wraps the call in a transaction of its own and updates {@code user_version} itself,
	 * so neither is done here. The step is looked up by version rather than passed in, so the
	 * numbers below cannot disagree with the numbers Room is told.
	 */
	@NonNull
	private static Migration adapt(int from) {

		SchemaMigrations.Step step = SchemaMigrations.stepFrom(from);

		return new Migration(step.from(), step.to()) {
			@Override
			public void migrate(@NonNull SupportSQLiteDatabase db) {
				step.apply(new RoomSqliteDb(db), MigrationContext.ofSystemDefault());
			}
		};
	}

	/** {@code lectures.batchID} became nullable, for a lecture held for the whole class. */
	public static final Migration MIGRATION_1_2 = adapt(1);

	/** Adds and seeds the {@code academic_years} table. */
	public static final Migration MIGRATION_2_3 = adapt(2);

	/** Indexes {@code attendance.studentID}. */
	public static final Migration MIGRATION_3_4 = adapt(3);

	/** Indexes the three foreign key columns Room warned about. */
	public static final Migration MIGRATION_4_5 = adapt(4);

	/** Adds the semester a student is in. */
	public static final Migration MIGRATION_5_6 = adapt(5);

	/** Takes the five generated primary keys away from SQLite and gives them to IdGenerator. */
	public static final Migration MIGRATION_6_7 = adapt(6);

	/** Adds the division a student's class is split into. */
	public static final Migration MIGRATION_7_8 = adapt(7);

	/** Adds {@code course_instructor}, so a course can have more than one teacher. */
	public static final Migration MIGRATION_8_9 = adapt(8);

	/** Moves six departments' master's rows from {@code MSc} to {@code MSc_unaided}. */
	public static final Migration MIGRATION_9_10 = adapt(9);

	public static final Migration MIGRATION_10_11 = adapt(10);
}
