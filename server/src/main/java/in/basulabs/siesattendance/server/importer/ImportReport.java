package in.basulabs.siesattendance.server.importer;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an import did, per table, so the operator is told rather than guessing.
 * <p>
 * The same shape as the app's {@code DatabaseMerger.MergeReport}, on purpose: the two
 * operations are the same operation against different databases, and a person importing eight
 * devices one after another should not have to learn two ways of reading the result.
 * <p>
 * Also rendered to JSON and stored in {@code import_run.report}. The gate for this phase is a
 * reconciliation across every device, which cannot be done from terminal scrollback a week
 * after the fact.
 */
public final class ImportReport {

	/** Inserted, updated and skipped counts for one table. */
	public static final class Counts {

		private int inserted;

		private int updated;

		private int unchanged;

		private int carriedKeys;

		private int remappedKeys;

		public int inserted() {
			return inserted;
		}

		/** Rows that were already here and whose values the backup changed. */
		public int updated() {
			return updated;
		}

		/**
		 * Rows that were already here and already identical.
		 * <p>
		 * Counted apart from {@link #updated()} because the two mean very different things to
		 * somebody reading the report. Importing the same backup twice must change nothing,
		 * and a second run that said it had updated every row would look exactly like one
		 * that had rewritten the lot.
		 */
		public int unchanged() {
			return unchanged;
		}

		/** Inserted rows that kept the key the device gave them. */
		public int carriedKeys() {
			return carriedKeys;
		}

		/** Inserted rows that had to be given a server key, and so have an id_remap entry. */
		public int remappedKeys() {
			return remappedKeys;
		}
	}

	/**
	 * Table name to its counts, in the order the tables were imported, which is dependency
	 * order and reads sensibly.
	 */
	private final Map<String, Counts> byTable = new LinkedHashMap<>();

	/** Rows that could not be placed, and anything else worth a human looking at. */
	private final List<String> skipped = new ArrayList<>();

	void inserted(@NonNull String table, boolean keptItsKey) {

		Counts counts = counts(table);
		counts.inserted++;

		if (keptItsKey) {
			counts.carriedKeys++;
		} else {
			counts.remappedKeys++;
		}
	}

	void updated(@NonNull String table) {
		counts(table).updated++;
	}

	void unchanged(@NonNull String table) {
		counts(table).unchanged++;
	}

	void skip(@NonNull String reason) {
		skipped.add(reason);
	}

	/** Makes sure a table appears in the report even when it contributed nothing. */
	void seen(@NonNull String table) {
		counts(table);
	}

	@NonNull
	private Counts counts(@NonNull String table) {
		return byTable.computeIfAbsent(table, name -> new Counts());
	}

	@NonNull
	public Map<String, Counts> byTable() {
		return byTable;
	}

	@NonNull
	public List<String> skipped() {
		return skipped;
	}

	public int totalInserted() {

		int total = 0;

		for (Counts counts : byTable.values()) {
			total += counts.inserted;
		}
		return total;
	}

	public int totalUpdated() {

		int total = 0;

		for (Counts counts : byTable.values()) {
			total += counts.updated;
		}
		return total;
	}

	public int totalUnchanged() {

		int total = 0;

		for (Counts counts : byTable.values()) {
			total += counts.unchanged;
		}
		return total;
	}

	public int totalRemapped() {

		int total = 0;

		for (Counts counts : byTable.values()) {
			total += counts.remappedKeys;
		}
		return total;
	}

	/**
	 * True when the backup contributed nothing at all, which is exactly what importing the
	 * same file a second time must produce.
	 */
	public boolean isEmpty() {
		return totalInserted() == 0 && totalUpdated() == 0;
	}

	/** How many rows the backup was found to describe at all, changed or not. */
	public int totalSeen() {
		return totalInserted() + totalUpdated() + totalUnchanged();
	}

	/** The report as a table, for a terminal. */
	@NonNull
	public String toPlainText() {

		StringBuilder out = new StringBuilder();

		out.append(String.format("%-20s %9s %9s %10s %9s %9s%n",
				"table", "inserted", "updated", "unchanged", "kept id", "remapped"));

		for (Map.Entry<String, Counts> entry : byTable.entrySet()) {

			Counts counts = entry.getValue();

			out.append(String.format("%-20s %9d %9d %10d %9d %9d%n", entry.getKey(),
					counts.inserted, counts.updated, counts.unchanged, counts.carriedKeys,
					counts.remappedKeys));
		}

		out.append(String.format("%-20s %9d %9d %10d%n", "TOTAL",
				totalInserted(), totalUpdated(), totalUnchanged()));

		if (!skipped.isEmpty()) {
			out.append(System.lineSeparator()).append("Needs a look (")
					.append(skipped.size()).append("):").append(System.lineSeparator());
			for (String reason : skipped) {
				out.append("  - ").append(reason).append(System.lineSeparator());
			}
		}
		return out.toString();
	}

	/**
	 * The report as JSON, for {@code import_run.report}.
	 * <p>
	 * Assembled by hand rather than with a library. The shape is three fixed keys and a map of
	 * five integers, the only free text is the skip reasons, and pulling Jackson into the
	 * importer to write this would be more code than escaping a string.
	 */
	@NonNull
	public String toJson() {

		StringBuilder json = new StringBuilder("{\"tables\":{");
		boolean firstTable = true;

		for (Map.Entry<String, Counts> entry : byTable.entrySet()) {

			if (!firstTable) {
				json.append(',');
			}
			firstTable = false;

			Counts counts = entry.getValue();

			json.append(quote(entry.getKey())).append(":{")
					.append("\"inserted\":").append(counts.inserted)
					.append(",\"updated\":").append(counts.updated)
					.append(",\"unchanged\":").append(counts.unchanged)
					.append(",\"carriedKeys\":").append(counts.carriedKeys)
					.append(",\"remappedKeys\":").append(counts.remappedKeys)
					.append('}');
		}

		json.append("},\"totals\":{")
				.append("\"inserted\":").append(totalInserted())
				.append(",\"updated\":").append(totalUpdated())
				.append(",\"unchanged\":").append(totalUnchanged())
				.append(",\"remapped\":").append(totalRemapped())
				.append("},\"skipped\":[");

		for (int i = 0; i < skipped.size(); i++) {

			if (i > 0) {
				json.append(',');
			}
			json.append(quote(skipped.get(i)));
		}

		return json.append("]}").toString();
	}

	/** A JSON string literal, escaped to the letter of RFC 8259. */
	@NonNull
	private static String quote(@NonNull String value) {

		StringBuilder out = new StringBuilder("\"");

		for (int i = 0; i < value.length(); i++) {

			char c = value.charAt(i);

			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				case '\b' -> out.append("\\b");
				case '\f' -> out.append("\\f");
				default -> {
					if (c < 0x20) {
						out.append(String.format("\\u%04x", (int) c));
					} else {
						out.append(c);
					}
				}
			}
		}
		return out.append('"').toString();
	}
}
