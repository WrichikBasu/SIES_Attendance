package in.basulabs.siesattendance.server.importer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.util.List;

import javax.sql.DataSource;

/**
 * The command line for the importer.
 * <p>
 * Active only under the {@code import} profile, so an ordinary {@code bootRun} is unaffected and
 * the service has no endpoint that can trigger an import. That is deliberate for now: there is
 * no authentication until Phase 5, and importing a device is an operation somebody should have
 * to be on the machine to perform.
 *
 * <pre>
 * ./gradlew :server:bootRun --args='--spring.profiles.active=import \
 *     --import.file=/backups/rao.siesbak \
 *     --import.label="Dr Rao's phone" \
 *     --import.node=41231 \
 *     --import.dry-run=true'
 * </pre>
 *
 * <h3>The password is not an argument</h3>
 * Arguments are visible to every process on the machine through {@code /proc} and are kept in
 * shell history. So the password is read from the environment, or prompted for on the terminal
 * when there is one. The variable is named rather than fixed so that several can be kept side
 * by side for several devices.
 */
@Component
@Profile("import")
public class ImportCommand implements ApplicationRunner {

	/** Where the decrypted copy of a backup is written, unless told otherwise. */
	private static final String DEFAULT_WORK_DIR = "build/import-staging";

	/** The zone the devices record their local dates at. */
	private static final String DEFAULT_ZONE = "Asia/Kolkata";

	private static final String DEFAULT_PASSWORD_ENV = "SIES_BACKUP_PASSWORD";

	@NonNull
	private final DataSource dataSource;

	public ImportCommand(@NonNull DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	public void run(@NonNull ApplicationArguments arguments) throws Exception {

		if (arguments.containsOption("import.history")) {
			printHistory();
			return;
		}

		String file = option(arguments, "import.file");
		String label = option(arguments, "import.label");
		String node = option(arguments, "import.node");

		if (file == null || label == null || node == null) {
			usage();
			throw new IllegalArgumentException(
					"--import.file, --import.label and --import.node are all required");
		}

		Path backup = Paths.get(file);

		if (!Files.isReadable(backup)) {
			throw new IllegalArgumentException("Cannot read " + backup.toAbsolutePath());
		}

		String zoneOption = option(arguments, "import.zone");
		String workDirOption = option(arguments, "import.work-dir");
		boolean dryRun = Boolean.parseBoolean(
				String.valueOf(option(arguments, "import.dry-run")));

		BackupImporter.Request request = new BackupImporter.Request(
				backup,
				readPassword(option(arguments, "import.password-env")),
				label,
				Integer.parseInt(node.trim()),
				ZoneId.of(zoneOption == null ? DEFAULT_ZONE : zoneOption),
				Paths.get(workDirOption == null ? DEFAULT_WORK_DIR : workDirOption),
				dryRun);

		report(new BackupImporter(dataSource).run(request), dryRun);
	}

	/** Prints the outcome, and fails the process when the import did not land. */
	private void report(@NonNull BackupImporter.Outcome outcome, boolean dryRun) {

		System.out.println();
		System.out.println("Run #" + outcome.runId()
				+ "   backup schema v" + outcome.schemaVersionAsFound()
				+ "   sha256 " + outcome.digest().substring(0, 16) + "…");

		if (outcome.seenBefore()) {
			System.out.println("This exact file has been imported successfully before. "
					+ "Re-importing it is harmless; expect no changes below.");
		}

		System.out.println();
		System.out.print(outcome.report().toPlainText());

		if (!outcome.succeeded()) {
			System.out.println();
			System.out.println("NOTHING WAS WRITTEN. The whole import is one transaction and "
					+ "it has been rolled back.");
			System.out.println("  " + outcome.failure());
			System.out.println();
			System.out.println("A row the database refused is a defect in the data. Look at "
					+ "the row before changing the constraint.");
			throw new IllegalStateException("Import run #" + outcome.runId() + " failed");
		}

		System.out.println();

		if (dryRun) {
			System.out.println("DRY RUN: the transaction was rolled back. Nothing above was "
					+ "kept.");
		} else if (outcome.report().isEmpty()) {
			System.out.println("Nothing to do: every row in this backup was already here.");
		} else {
			System.out.println("Committed. " + outcome.report().totalRemapped()
					+ " row(s) needed a server key; see id_remap.");
		}
	}

	private void printHistory() throws Exception {

		List<String> history = new BackupImporter(dataSource).history();

		if (history.isEmpty()) {
			System.out.println("No imports have been run against this database.");
			return;
		}

		System.out.println("run  device                   node        schema  started"
				+ "                        result  inserted/updated/skipped");

		for (String row : history) {
			System.out.println(row);
		}
	}

	/**
	 * The password, from the environment or from the terminal.
	 * <p>
	 * Returns null when neither has one, which is the right answer for an unencrypted backup
	 * and produces a clear rejection for an encrypted one.
	 */
	@Nullable
	private char[] readPassword(@Nullable String variableOption) {

		String variable = variableOption == null ? DEFAULT_PASSWORD_ENV : variableOption;
		String fromEnvironment = System.getenv(variable);

		if (fromEnvironment != null && !fromEnvironment.isEmpty()) {
			return fromEnvironment.toCharArray();
		}

		if (System.console() != null) {
			return System.console().readPassword(
					"Password for the backup (blank if it is not encrypted): ");
		}

		return null;
	}

	@Nullable
	private static String option(@NonNull ApplicationArguments arguments,
	                             @NonNull String name) {

		List<String> values = arguments.getOptionValues(name);
		return values == null || values.isEmpty() ? null : values.get(0);
	}

	private static void usage() {

		System.out.println("""
				Imports one device's backup.

				  --import.file=PATH          the .siesbak to import
				  --import.label=TEXT         what to call this device in the report
				  --import.node=N             the device's node number, 1..65535

				  --import.dry-run=true       roll back at the end, but still report
				  --import.zone=ZONE          the zone the device recorded dates at
				                              (default Asia/Kolkata)
				  --import.work-dir=PATH      where to write the decrypted copy
				  --import.password-env=NAME  which environment variable holds the
				                              password (default SIES_BACKUP_PASSWORD)

				  --import.history            list every run so far and stop

				The password is never an argument: arguments are visible to other
				processes and are kept in shell history.""");
	}
}
