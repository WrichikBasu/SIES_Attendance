package in.basulabs.siesattendance.backend;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which PostgreSQL enum type each of the app's enums is stored as.
 * <p>
 * A name, not a dialect: no SQL is assembled here. It lives in the shared module because three
 * separate things need to agree on it and two of them are in different modules — the server
 * casts a bound parameter to the type ({@code ?::study_year}, since a PostgreSQL enum has no
 * comparison against a bare parameter), and {@code PostgresSchemaDriftTest} checks the
 * migration declares exactly these types with exactly the Java constant names in them. A
 * second copy of this map would be a second thing to forget.
 * <p>
 * The values stored in those types are {@code name()}, never {@code toString()}. The labels are
 * for display and are not unique across constants.
 */
public final class PostgresEnumTypes {

	private static final Map<Class<? extends Enum<?>>, String> TYPES;

	static {
		Map<Class<? extends Enum<?>>, String> types = new LinkedHashMap<>();
		types.put(Department.class, "department");
		// Named degree_stream so a column declaration does not read "stream stream NOT NULL".
		types.put(Stream.class, "degree_stream");
		types.put(Year.class, "study_year");
		types.put(Division.class, "class_division");
		types.put(CourseType.class, "course_type");
		TYPES = Collections.unmodifiableMap(types);
	}

	private PostgresEnumTypes() {
	}

	/** Every mapping, in the order the types are declared in the migration. */
	@NonNull
	public static Map<Class<? extends Enum<?>>, String> all() {
		return TYPES;
	}

	/** The type name for one enum. */
	@NonNull
	public static String of(@NonNull Class<? extends Enum<?>> type) {
		String name = TYPES.get(type);
		if (name == null) {
			throw new IllegalArgumentException(
					type.getName() + " is not stored as a PostgreSQL enum type");
		}
		return name;
	}

	/** The type name for one enum value, which is the commoner thing to have in hand. */
	@NonNull
	public static String of(@NonNull Enum<?> value) {
		// getDeclaringClass rather than getClass: a constant with a body is an anonymous
		// subclass, and the map is keyed by the enum itself.
		return of(value.getDeclaringClass());
	}
}
