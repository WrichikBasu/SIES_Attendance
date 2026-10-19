package in.basulabs.siesattendance.server.web;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import in.basulabs.siesattendance.backend.sync.DeviceTables;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Reading the data on the server, as the caller is allowed to see it.
 * <p>
 * One endpoint per question — what is there, and what is in it — and no authority rules of its
 * own, which is the point. Every row comes back from a query run under the caller's own actor, so
 * row-level security decides what they get: a professor browsing {@code students} sees the
 * students on their own rolls, a head sees their department's, an auditor sees everybody's, and
 * nobody sees anything until they have signed in. There is no admin check here because there does
 * not need to be one, and adding one would mean this file forming a second opinion about
 * visibility that could disagree with V6 and V11.
 *
 * <h3>Which tables</h3>
 * {@code synced_tables()}, which since V11 is every table a device holds. That is not a
 * coincidence: those are exactly the tables that carry policies, so they are exactly the tables
 * it is safe to hand a generic reader. {@code app_account} is not among them — it has no
 * policies, and a password hash is not something to render in a table — and neither is
 * {@code audit_log}, which the service holds no privilege on at all.
 * <p>
 * The table name reaching SQL comes from that list and never from the request: the request's name
 * is compared against it and rejected if absent. A name that is in the list is the database's own
 * word for one of its tables.
 *
 * <h3>Paging</h3>
 * By primary key, row-wise, which is the same mechanism the sync snapshot resumes with and for
 * the same reason: an offset is wrong as soon as a row is inserted behind you. The cursor is the
 * last key sent, as text.
 */
@RestController
@RequestMapping(Api.V1 + "/data")
public final class DataController {

	/** One table, and how many rows of it this caller can see. */
	public record TableBody(@NonNull String table, long rows, @NonNull List<String> key) {
	}

	/**
	 * @param columns the column names, in the table's own order
	 * @param rows    each row as text, in that order. Rendered rather than typed, because this
	 *                is for reading: a timestamp with a zone, a date and a numeric all have one
	 *                obvious text form and inventing a JSON shape for each would be a format
	 *                for somebody to parse, which this is not
	 * @param next    the cursor for the next page, or null when this was the last
	 * @param labels  for each column that holds an enum, what each stored value is called:
	 *                {@code {"department": {"COMPUTER_SCIENCE": "CS"}}}. Sent beside the rows
	 *                rather than substituted into them, which is the point — the rows stay
	 *                exactly what the table holds, and the page shows the label with the
	 *                stored value on the cell as its tooltip. A browser that showed
	 *                {@code COMPUTER_SCIENCE} was showing a column's contents to a professor;
	 *                one that quietly replaced it would be showing a data browser that does
	 *                not show the data
	 */
	public record PageBody(@NonNull String table, @NonNull List<String> columns,
	                       @NonNull List<List<String>> rows, @Nullable String next,
	                       @NonNull Map<String, Map<String, String>> labels) {
	}

	/** Enough to read, few enough that a browser renders it without complaint. */
	private static final int DEFAULT_LIMIT = 100;

	private static final int MAX_LIMIT = 500;

	@NonNull
	private final ActorDatabase database;

	public DataController(@NonNull ActorDatabase database) {
		this.database = database;
	}

	/**
	 * What is on the server, with the number of rows this caller can see in each.
	 * <p>
	 * The counts are under the policies like everything else, so two people asking get different
	 * answers and both are right. A zero means "none you may see", which is not the same as
	 * "none", and the page says so rather than pretending otherwise.
	 */
	@GetMapping
	@NonNull
	public List<TableBody> tables(@NonNull HttpServletRequest http) throws SQLException {

		RequestActor actor = RequestActor.of(http);

		return database.inTransaction(actor, connection -> {

			List<TableBody> tables = new ArrayList<>();

			for (String name : readable(connection)) {

				DeviceTables.Table table = DeviceTables.of(name);
				long rows;

				try (Statement statement = connection.createStatement();
				     ResultSet results = statement.executeQuery(
						     "SELECT count(*) FROM " + name)) {

					results.next();
					rows = results.getLong(1);
				}

				List<String> key = new ArrayList<>();

				for (String room : table.primaryKey()) {
					key.add(table.column(room).postgres());
				}

				tables.add(new TableBody(name, rows, key));
			}

			return tables;
		});
	}

	@GetMapping("/{table}")
	@NonNull
	public PageBody rows(@PathVariable @NonNull String table,
	                     @RequestParam(name = "after", required = false) @Nullable String after,
	                     @RequestParam(name = "limit", defaultValue = "0") int limit,
	                     @NonNull HttpServletRequest http) throws SQLException {

		RequestActor actor = RequestActor.of(http);
		int capped = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);

		return database.inTransaction(actor, connection -> {

			if (!readable(connection).contains(table)) {
				throw new ApiException(404, "no_such_table",
						"There is no readable table called " + table + ". The readable ones "
								+ "are the tables a device holds, which are the ones "
								+ "row-level security covers.");
			}

			DeviceTables.Table described = DeviceTables.of(table);
			List<String> key = new ArrayList<>();

			for (String room : described.primaryKey()) {
				key.add(described.column(room).postgres());
			}

			List<Long> cursor = cursorFrom(after, key.size());
			StringBuilder sql = new StringBuilder("SELECT * FROM ").append(table);

			if (!cursor.isEmpty()) {
				sql.append(" WHERE (").append(String.join(", ", key)).append(") > (");

				for (int at = 0; at < cursor.size(); at++) {
					sql.append(at == 0 ? "" : ", ").append('?');
				}
				sql.append(")");
			}

			// One more than asked for, which is how "is there another page" is answered without
			// a second count that could disagree with the first.
			sql.append(" ORDER BY ").append(String.join(", ", key))
					.append(" LIMIT ").append(capped + 1);

			List<String> columns = new ArrayList<>();
			List<List<String>> rows = new ArrayList<>();
			String lastSent = null;
			String next = null;

			try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {

				for (int at = 0; at < cursor.size(); at++) {
					statement.setLong(at + 1, cursor.get(at));
				}

				try (ResultSet results = statement.executeQuery()) {

					ResultSetMetaData shape = results.getMetaData();

					for (int at = 1; at <= shape.getColumnCount(); at++) {
						columns.add(shape.getColumnLabel(at));
					}

					while (results.next()) {

						if (rows.size() == capped) {
							// The extra row proves there is another page. The cursor is the
							// key of the last row actually SENT, not this one: the next page
							// asks for keys greater than the cursor, so using this row's key
							// would skip it -- one row lost per page, silently.
							next = lastSent;
							break;
						}

						List<String> row = new ArrayList<>();

						for (int at = 1; at <= shape.getColumnCount(); at++) {
							row.add(results.getString(at));
						}

						rows.add(row);
						lastSent = cursorOf(results, key);
					}
				}
			}

			return new PageBody(table, columns, rows, next, labels(described, columns));
		});
	}

	// ── Internals ────────────────────────────────────────────────────────

	/**
	 * What each enum value in these columns is called, for the columns that hold one.
	 * <p>
	 * The labels are the app's own — {@code Department.toString()} and the rest — so the
	 * browser and the phone call a department the same thing, and renaming one renames both.
	 * Columns the table description does not know (the provenance ones, which {@code SELECT *}
	 * also returns) simply are not in the map, and nor are the enums a build is too old to
	 * know, which is why the page falls back to the stored value rather than to nothing.
	 */
	@NonNull
	private static Map<String, Map<String, String>> labels(
			@NonNull DeviceTables.Table described, @NonNull List<String> columns) {

		Map<String, Map<String, String>> labels = new LinkedHashMap<>();

		for (String name : columns) {

			for (DeviceTables.Column column : described.carriedColumns()) {

				if (!name.equals(column.postgres())
						|| column.kind() != DeviceTables.Kind.ENUM
						|| column.enumType() == null) {
					continue;
				}

				Map<String, String> named = new LinkedHashMap<>();

				for (Enum<?> constant : column.enumType().getEnumConstants()) {
					named.put(constant.name(), constant.toString());
				}

				labels.put(name, named);
			}
		}

		return labels;
	}

	/**
	 * The tables this endpoint will read, from the database rather than from a list here.
	 * <p>
	 * {@code synced_tables()} is the same function the sync pair reads, so widening what a
	 * device may hold widens what can be browsed, in one migration and with the policies
	 * already written. A table absent from it has no policies, and a generic reader over a
	 * table with no policies is a data leak with a user interface.
	 */
	@NonNull
	private static Set<String> readable(@NonNull Connection connection) throws SQLException {

		Set<String> tables = new LinkedHashSet<>();

		try (Statement statement = connection.createStatement();
		     ResultSet results = statement.executeQuery(
				     "SELECT unnest(synced_tables()) AS table_name")) {

			while (results.next()) {
				tables.add(results.getString("table_name"));
			}
		}
		return tables;
	}

	@NonNull
	private static List<Long> cursorFrom(@Nullable String after, int width) {

		if (after == null || after.isBlank()) {
			return List.of();
		}

		List<Long> cursor = new ArrayList<>();

		try {
			for (String value : after.split(",")) {
				cursor.add(Long.valueOf(value.trim()));
			}

		} catch (NumberFormatException e) {
			throw new ApiException(400, "bad_cursor",
					"That is not a cursor this endpoint issued.");
		}

		if (cursor.size() != width) {
			throw new ApiException(400, "bad_cursor",
					"That cursor has " + cursor.size() + " parts and this table is keyed on "
							+ width + ".");
		}

		return cursor;
	}

	@NonNull
	private static String cursorOf(@NonNull ResultSet results, @NonNull List<String> key)
			throws SQLException {

		List<String> values = new ArrayList<>();

		for (String column : key) {
			values.add(String.valueOf(results.getLong(column)));
		}
		return String.join(",", values);
	}
}
