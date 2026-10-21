package in.basulabs.siesattendance.backend.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import in.basulabs.siesattendance.backend.CourseType;
import in.basulabs.siesattendance.backend.Department;
import in.basulabs.siesattendance.backend.Division;
import in.basulabs.siesattendance.backend.Stream;
import in.basulabs.siesattendance.backend.Year;

/**
 * Every table a device holds: what its columns are called on each side, what identifies one of
 * its rows, and what it points at.
 * <p>
 * This is the one description the whole sync rests on, and it is here rather than in either
 * program because three things read it and they must not disagree.
 * <ul>
 *     <li>The app's sync, writing what it pulls and queueing what it pushes.</li>
 *     <li>The server's importer, folding a phone's backup into PostgreSQL.</li>
 *     <li>The drift test, checking the committed migrations declare exactly these columns.</li>
 * </ul>
 * The natural keys are the part that would hurt most. A row is recognised across two databases
 * by something that identifies it in the world rather than in the file — a roll number, a course
 * code, a lecture's slot — because the primary keys were issued independently by each device and
 * a key alone can only ever tell you two rows <em>are</em> the same, never that they are
 * different. If the phone matched students on one thing and the server on another, the same
 * backup would merge one way on a device and import another way to the server, and the symptom
 * would be one student's attendance split across two records rather than an error anybody saw.
 *
 * <h3>Two tables are not here</h3>
 * {@code students} and {@code professors} carry a {@link Table#naturalKey()} that is empty, and
 * that is deliberate rather than an omission: neither can be matched by a key inside its own
 * row. A student record holds a name, pronouns and an optional email, none of which identify a
 * person — what identifies them is the roll number, which lives in {@code student_year}. A
 * professor is matched on email first and on name and department only under conditions too
 * particular to express as a column list. Both sides handle those two by hand, and
 * {@link Table#isMatchedByHand()} says so.
 */
public final class DeviceTables {

	private DeviceTables() {
	}

	/**
	 * How a value is stored, which is what says how to carry it from SQLite to PostgreSQL.
	 * <p>
	 * SQLite has four storage classes and Room leans on them heavily: an enum is TEXT holding
	 * {@code name()}, a date is an INTEGER count of days, a timestamp is TEXT in a format of
	 * the app's own, and a boolean is 0 or 1. PostgreSQL has a real type for each of those, so
	 * every one of these is a conversion and not a copy.
	 */
	public enum Kind {

		/** Text on both sides. */
		TEXT,

		/** An integer on both sides. */
		INTEGER,

		/** {@code 0}/{@code 1} in SQLite, {@code boolean} in PostgreSQL. */
		BOOLEAN,

		/** The enum constant's {@code name()} as TEXT, a native enum type in PostgreSQL. */
		ENUM,

		/** Days since 1970-01-01 as an INTEGER, {@code date} in PostgreSQL. */
		EPOCH_DAY,

		/**
		 * {@code "epochSecond|nano|zone"} as TEXT, which becomes two PostgreSQL columns: a
		 * {@code timestamptz} for the instant and a {@code text} beside it for the zone it was
		 * recorded in. The instant is the instant; the zone is what the wall clock said, and a
		 * report that groups lectures by month needs both.
		 */
		ZONED_DATE_TIME
	}

	/**
	 * One column, under both of its names.
	 *
	 * @param room       the column name in the device's SQLite database, which is Room's
	 * @param postgres   the column name on the server, or null for a column the server does
	 *                   not keep
	 * @param kind       how the value is stored, and so how it converts
	 * @param enumType   the enum whose constants this column holds, for {@link Kind#ENUM}
	 * @param zoneColumn the PostgreSQL column the zone goes in, for
	 *                   {@link Kind#ZONED_DATE_TIME}
	 * @param nullable   whether the column may be absent, on both sides
	 */
	public record Column(@NonNull String room, @Nullable String postgres, @NonNull Kind kind,
	                     @Nullable Class<? extends Enum<?>> enumType,
	                     @Nullable String zoneColumn, boolean nullable) {

		/** Whether the server keeps this column at all. */
		public boolean isCarried() {
			return postgres != null;
		}
	}

	/**
	 * One table.
	 *
	 * @param name        the table name, which is the same on both sides
	 * @param columns     every column the device has, carried or not, in Room's order
	 * @param primaryKey  the columns, by their Room names, that the table is actually keyed
	 *                    on. Usually the same as the natural key, or the surrogate alone;
	 *                    {@code student_year} is the one where it differs and matters, because
	 *                    a student has exactly one enrolment per session however often their
	 *                    roll number is corrected
	 * @param naturalKey  the columns, by their Room names, that identify a row across two
	 *                    databases; empty for a table matched by hand
	 * @param surrogate   the app-issued primary key, by its Room name, or null for a table
	 *                    whose key is its natural key
	 * @param foreignKeys Room column name to the table whose identity map rewrites it
	 */
	public record Table(@NonNull String name, @NonNull List<Column> columns,
	                    @NonNull List<String> primaryKey, @NonNull List<String> naturalKey,
	                    @Nullable String surrogate,
	                    @NonNull Map<String, String> foreignKeys) {

		/**
		 * Whether the primary key says something the natural key does not.
		 * <p>
		 * When it does, it is the stronger identity of the two for a row whose parents have
		 * already been resolved, and has to be tried first: a student's enrolment row is
		 * identified by the student and the session, and the roll number on it is an
		 * attribute that can be corrected.
		 */
		public boolean primaryKeyDiffersFromNaturalKey() {
			return !primaryKey.equals(naturalKey) && surrogate == null;
		}

		/**
		 * Whether this table's rows cannot be recognised from their own columns, and so are
		 * resolved by code written for the table rather than by the generic engine.
		 */
		public boolean isMatchedByHand() {
			return naturalKey.isEmpty();
		}

		/** One column by its Room name. */
		@NonNull
		public Column column(@NonNull String roomName) {

			for (Column column : columns) {
				if (column.room().equals(roomName)) {
					return column;
				}
			}
			throw new IllegalArgumentException(name + " has no column " + roomName);
		}

		/** The columns the server keeps, which is what an insert lists. */
		@NonNull
		public List<Column> carriedColumns() {

			List<Column> carried = new ArrayList<>();

			for (Column column : columns) {
				if (column.isCarried()) {
					carried.add(column);
				}
			}
			return carried;
		}
	}

	// ── Column shorthands ────────────────────────────────────────────────

	private static Column text(String room, String postgres, boolean nullable) {
		return new Column(room, postgres, Kind.TEXT, null, null, nullable);
	}

	private static Column integer(String room, String postgres, boolean nullable) {
		return new Column(room, postgres, Kind.INTEGER, null, null, nullable);
	}

	private static Column bool(String room, String postgres) {
		return new Column(room, postgres, Kind.BOOLEAN, null, null, false);
	}

	private static Column enumeration(String room, String postgres,
	                                  Class<? extends Enum<?>> type, boolean nullable) {
		return new Column(room, postgres, Kind.ENUM, type, null, nullable);
	}

	private static Column epochDay(String room, String postgres) {
		return new Column(room, postgres, Kind.EPOCH_DAY, null, null, false);
	}

	private static Column timestamp(String room, String postgres, String zoneColumn,
	                                boolean nullable) {
		return new Column(room, postgres, Kind.ZONED_DATE_TIME, null, zoneColumn, nullable);
	}

	/** A column the device has and the server deliberately does not keep. */
	private static Column dropped(String room) {
		return new Column(room, null, Kind.INTEGER, null, null, false);
	}

	private static Map<String, String> fks(String... pairs) {

		Map<String, String> map = new LinkedHashMap<>();

		for (int i = 0; i + 1 < pairs.length; i += 2) {
			map.put(pairs[i], pairs[i + 1]);
		}
		return Collections.unmodifiableMap(map);
	}

	// ── The tables ───────────────────────────────────────────────────────

	/**
	 * Every table, in dependency order: a table appears only after every table it points at,
	 * so by the time its foreign keys are rewritten the rows they refer to have been resolved.
	 * <p>
	 * {@code students} and {@code professors} come first because nearly everything points at
	 * one of them, and they are also the two that cannot be matched generically — see the class
	 * notes.
	 */
	@NonNull
	public static List<Table> inDependencyOrder() {

		List<Table> tables = new ArrayList<>();

		tables.add(new Table("students",
				List.of(integer("studentID", "student_id", false),
						text("name", "name", false),
						text("pronouns", "pronouns", false),
						text("email", "email", true)),
				List.of("studentID"), List.of(), "studentID", fks()));

		tables.add(new Table("professors",
				List.of(integer("profID", "prof_id", false),
						text("name", "name", false),
						text("email", "email", false),
						enumeration("department", "department", Department.class, false),
						text("designation", "designation", false)),
				List.of("profID"), List.of(), "profID", fks()));

		// The session table. startEpoch and endEpoch are not carried: they are the dates
		// above, resolved at whichever device's zone happened to write the row, and the
		// server derives the same thing from the dates with the zone as a parameter.
		tables.add(new Table("academic_years",
				List.of(integer("academicYear", "academic_year", false),
						epochDay("startDate", "start_date"),
						epochDay("endDate", "end_date"),
						dropped("startEpoch"),
						dropped("endEpoch")),
				List.of("academicYear"), List.of("academicYear"), null, fks()));

		tables.add(new Table("student_year",
				List.of(integer("studentID", "student_id", false),
						integer("academicYear", "academic_year", false),
						text("rollNo", "roll_no", false),
						enumeration("year", "year", Year.class, false),
						enumeration("stream", "stream", Stream.class, false),
						enumeration("majorDept", "major_dept", Department.class, false),
						enumeration("minorDept", "minor_dept", Department.class, true),
						enumeration("oeDept", "oe_dept", Department.class, true),
						integer("semester", "semester", true),
						enumeration("division", "division", Division.class, true)),
				List.of("studentID", "academicYear"), List.of("rollNo", "academicYear"), null,
				fks("studentID", "students")));

		tables.add(new Table("courses",
				List.of(integer("courseID", "course_id", false),
						integer("profID", "prof_id", false),
						text("courseCode", "course_code", false),
						text("courseTopic", "course_topic", false),
						enumeration("stream", "stream", Stream.class, false),
						enumeration("offeringDepartment", "offering_department",
								Department.class, false),
						enumeration("year", "year", Year.class, false),
						integer("semester", "semester", false),
						enumeration("courseType", "course_type", CourseType.class, false),
						text("remarks", "remarks", true),
						timestamp("dateIntroduced", "date_introduced",
								"date_introduced_zone", true),
						timestamp("dateDiscontinued", "date_discontinued",
								"date_discontinued_zone", true)),
				List.of("courseID"), List.of("courseCode"), "courseID",
				fks("profID", "professors")));

		tables.add(new Table("batch_desc",
				List.of(integer("batchID", "batch_id", false),
						text("batchName", "batch_name", false),
						text("batchDesc", "batch_desc", true),
						integer("academicYear", "academic_year", false)),
				List.of("batchID"), List.of("batchName", "academicYear"), "batchID", fks()));

		tables.add(new Table("batch_student_map",
				List.of(integer("batchID", "batch_id", false),
						integer("studentID", "student_id", false),
						integer("academicYear", "academic_year", false)),
				List.of("batchID", "studentID", "academicYear"),
				List.of("batchID", "studentID", "academicYear"), null,
				fks("batchID", "batch_desc", "studentID", "students")));

		tables.add(new Table("course_batch_map",
				List.of(integer("courseID", "course_id", false),
						integer("batchID", "batch_id", false)),
				List.of("courseID", "batchID"), List.of("courseID", "batchID"), null,
				fks("courseID", "courses", "batchID", "batch_desc")));

		tables.add(new Table("course_instructor",
				List.of(integer("courseID", "course_id", false),
						integer("profID", "prof_id", false)),
				List.of("courseID", "profID"), List.of("courseID", "profID"), null,
				fks("courseID", "courses", "profID", "professors")));

		tables.add(new Table("lectures",
				List.of(integer("lectureID", "lecture_id", false),
						integer("courseID", "course_id", false),
						integer("batchID", "batch_id", true),
						timestamp("startDateTime", "start_at", "start_zone", false),
						timestamp("endDateTime", "end_at", "end_zone", false),
						text("notes", "notes", true)),
				List.of("lectureID"),
				List.of("courseID", "batchID", "startDateTime", "endDateTime"), "lectureID",
				fks("courseID", "courses", "batchID", "batch_desc")));

		tables.add(new Table("attendance",
				List.of(integer("lectureID", "lecture_id", false),
						integer("studentID", "student_id", false),
						bool("present", "present")),
				List.of("lectureID", "studentID"), List.of("lectureID", "studentID"), null,
				fks("lectureID", "lectures", "studentID", "students")));

		return tables;
	}

	/** One table by name. */
	@NonNull
	public static Table of(@NonNull String name) {

		for (Table table : inDependencyOrder()) {
			if (table.name().equals(name)) {
				return table;
			}
		}
		throw new IllegalArgumentException("Not a table a device holds: " + name);
	}

	/**
	 * How deep a table sits in dependency order: 0 for one that points at nothing, higher for
	 * one that points at those.
	 * <p>
	 * Both ends of sync sort a batch by this — the server before it writes a push, the device
	 * before it applies a pull — because a foreign key is {@code RESTRICT} on both sides: a
	 * lecture has to land after its course and be deleted before it. Neither end takes the
	 * other's word for the order, and this is the one place the order is written down.
	 *
	 * @param table a table name; an unknown one sorts last, where a row that nothing can point
	 *              at belongs
	 */
	public static int depthOf(@NonNull String table) {

		List<Table> tables = inDependencyOrder();

		for (int at = 0; at < tables.size(); at++) {
			if (tables.get(at).name().equals(table)) {
				return at;
			}
		}
		return tables.size();
	}

	/**
	 * The five tables whose primary keys are issued by {@code IdGenerator} rather than
	 * described by the world, and so the five that need an entry in the identity map when a
	 * legacy key has to be replaced.
	 */
	@NonNull
	public static List<String> tablesWithIssuedKeys() {

		List<String> names = new ArrayList<>();

		for (Table table : inDependencyOrder()) {
			if (table.surrogate() != null) {
				names.add(table.name());
			}
		}
		return names;
	}
}
