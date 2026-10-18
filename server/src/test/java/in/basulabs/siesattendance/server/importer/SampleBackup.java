package in.basulabs.siesattendance.server.importer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import in.basulabs.siesattendance.backend.StructuredId;

/**
 * Writes a sample {@code .siesbak}, so the importer can be run by hand before there is a real
 * device backup to run it on.
 * <p>
 * Not a test. It is here rather than in the main sources because building a backup is a thing
 * only the tests need to know how to do — the app writes the real ones — and {@link DeviceBackup}
 * already knows. Invoked through {@code ./gradlew :server:sampleBackup}.
 * <p>
 * The data is one professor, one session, three students, two courses, a batch and a fortnight
 * of lectures: enough that the report has something to say and small enough to read in psql
 * afterwards.
 */
public final class SampleBackup {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	private static final int SESSION = 2025;

	/** The node this pretend device issues keys under, in the self-assigned range. */
	private static final int NODE = 41231;

	private SampleBackup() {
	}

	public static void main(String[] args) throws Exception {

		Path directory = Paths.get(args.length > 0 ? args[0] : "build/sample");
		int version = args.length > 1 ? Integer.parseInt(args[1]) : 10;

		Files.createDirectories(directory);
		Path destination = directory.resolve("sample-device.siesbak");

		try (DeviceBackup device = DeviceBackup.emptyAtVersion6(directory)) {

			boolean legacy = version <= DeviceBackup.BASE_VERSION;

			long professor = key(legacy, 1, 1);
			long course1 = key(legacy, 2, 1);
			long course2 = key(legacy, 3, 2);
			long batch = key(legacy, 4, 1);

			device.professor(professor, "Dr A Rao", "a.rao@sies.edu.in", "COMPUTER_SCIENCE",
					"Assistant Professor");

			device.session(SESSION, LocalDate.of(SESSION, 6, 1),
					LocalDate.of(SESSION + 1, 5, 31), IST);

			device.course(course1, professor, "CS-101", "Programming Fundamentals",
					"BSc_unaided", "COMPUTER_SCIENCE", "FY", 1, "MAJOR");
			device.course(course2, professor, "CS-102", "Discrete Mathematics",
					"BSc_unaided", "COMPUTER_SCIENCE", "FY", 1, "MAJOR");

			device.batch(batch, "Batch A", "first half of the class", SESSION);
			device.courseBatch(course1, batch);

			String[] names = {"Asha Menon", "Bilal Shaikh", "Chitra Iyer"};
			String[] pronouns = {"she/her", "he/him", "she/her"};
			long[] students = new long[names.length];

			for (int i = 0; i < names.length; i++) {

				students[i] = key(legacy, 10 + i, 10 + i);

				device.student(students[i], names[i], pronouns[i],
						"student" + (i + 1) + "@sies.edu.in");
				device.studentYear(students[i], SESSION,
						String.format("CS%02d", i + 1), "FY", "BSc_unaided",
						"COMPUTER_SCIENCE", null, null, 1);
				device.batchMember(batch, students[i], SESSION);
			}

			ZonedDateTime first = ZonedDateTime.of(SESSION, 7, 14, 9, 0, 0, 0, IST);
			int lectures = 0;

			for (int week = 0; week < 2; week++) {
				for (int day = 0; day < 5; day++) {

					ZonedDateTime start = first.plusDays(week * 7L + day);
					long lecture = key(legacy, 100 + lectures, 100 + lectures);
					lectures++;

					// Alternating: the batch's lecture, then one for the whole class, which
					// is the case that leaves batchID null.
					device.lecture(lecture, day % 2 == 0 ? course1 : course2,
							day % 2 == 0 ? batch : null,
							start, start.plusHours(1), null);

					for (int i = 0; i < students.length; i++) {
						device.attendance(lecture, students[i], (day + i) % 4 != 0);
					}
				}
			}

			if (!legacy) {
				device.migrateToCurrent(IST);
			}

			device.pack(destination, null);
		}

		System.out.println("Wrote " + destination.toAbsolutePath());
		System.out.println("Schema version " + (version <= DeviceBackup.BASE_VERSION
				? DeviceBackup.BASE_VERSION + " (the importer will migrate it)"
				: "10"));
		System.out.println("Node " + NODE + ", keys are "
				+ (version <= DeviceBackup.BASE_VERSION ? "legacy" : "structured"));
	}

	/** A legacy counted-out key, or a structured one carrying this device's node. */
	private static long key(boolean legacy, int legacyValue, int sequence) {
		return legacy
				? legacyValue
				: StructuredId.compose(StructuredId.MIN_COUNTER + sequence, NODE);
	}
}
