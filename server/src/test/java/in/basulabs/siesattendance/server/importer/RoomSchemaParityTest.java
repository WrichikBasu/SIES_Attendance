package in.basulabs.siesattendance.server.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The files the server builds for phones have exactly the schema Room expects.
 * <p>
 * {@link DeviceDatabase#createAtCurrentVersion} starts from a hand-written version 6 and runs
 * the shared migrations. Room, opening one of these files on a phone, finds no
 * {@code room_master_table} and validates every table against its entities instead: columns,
 * foreign keys and indices, in both directions. A difference there is an
 * {@code IllegalStateException} at restore. The version 6 DDL once lacked the three
 * {@code student_year} indices, and only a test on a phone noticed.
 * <p>
 * Room's side is not written out here. It is read from {@code MainDatabase_Impl}, which Room's
 * annotation processor generates from the entities when {@code :app} compiles; the server's
 * test task depends on that compile and passes the file's path as {@code sies.roomImpl}. The
 * {@code createAllTables} statements in it are run on an empty SQLite file, and both files are
 * then described through the same pragmas, so nothing about either schema is parsed by hand
 * beyond lifting the statements out of the Java source.
 */
class RoomSchemaParityTest {

	/** Tables that are SQLite's or Room's own bookkeeping, not the app's. */
	private static final Set<String> NOT_ENTITIES =
			Set.of("room_master_table", "sqlite_sequence", "android_metadata");

	private static final Pattern EXEC = Pattern.compile(
			"SQLite\\.execSQL\\(\\s*connection\\s*,\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\)");

	@TempDir
	static Path work;

	private static Schema room;

	private static Schema server;

	@BeforeAll
	static void describeBothFiles() throws Exception {

		Path roomFile = work.resolve("room.sqlite3");

		try (Connection connection = DriverManager.getConnection(
				"jdbc:sqlite:" + roomFile.toAbsolutePath());
		     Statement statement = connection.createStatement()) {

			for (String ddl : roomCreateAllTables()) {
				statement.execute(ddl);
			}
			room = Schema.of(connection);
		}

		try (Connection connection = DeviceDatabase.createAtCurrentVersion(
				work.resolve("server.sqlite3"), ZoneId.of("Asia/Kolkata"))) {
			server = Schema.of(connection);
		}
	}

	@Test
	void theSameTables() {
		assertFalse(room.tables().isEmpty(), "nothing was read out of MainDatabase_Impl");
		assertEquals(room.tables(), server.tables());
	}

	@Test
	void theSameIndices() {
		compare("index", room.indices, server.indices);
	}

	@Test
	void theSameColumns() {
		compare("column", room.columns, server.columns);
	}

	@Test
	void theSameForeignKeys() {
		compare("foreign key", room.foreignKeys, server.foreignKeys);
	}

	/** Lists what is missing and what is extra, table by table, rather than one bare diff. */
	private static void compare(String what, Map<String, Set<String>> expected,
	                            Map<String, Set<String>> actual) {

		List<String> problems = new ArrayList<>();

		for (String table : expected.keySet()) {

			Set<String> want = expected.get(table);
			Set<String> have = actual.getOrDefault(table, Set.of());

			for (String item : want) {
				if (!have.contains(item)) {
					problems.add(table + ": missing " + what + " " + item);
				}
			}
			for (String item : have) {
				if (!want.contains(item)) {
					problems.add(table + ": extra " + what + " " + item);
				}
			}
		}

		if (!problems.isEmpty()) {
			fail("The server builds a file Room would refuse:\n  "
					+ String.join("\n  ", problems));
		}
	}

	/** Room's {@code createAllTables}, statement by statement, from the generated source. */
	private static List<String> roomCreateAllTables() throws IOException {

		String location = System.getProperty("sies.roomImpl");
		assertTrue(location != null && !location.isEmpty(),
				"sies.roomImpl is not set; run this through Gradle, which compiles :app first");

		Path impl = Path.of(location);
		assertTrue(Files.isRegularFile(impl), "no generated MainDatabase_Impl at " + impl);

		String source = Files.readString(impl, StandardCharsets.UTF_8);

		int start = source.indexOf("createAllTables");
		int end = source.indexOf("dropAllTables", start);
		assertTrue(start >= 0 && end > start,
				"MainDatabase_Impl no longer has createAllTables before dropAllTables");

		List<String> statements = new ArrayList<>();
		Matcher matcher = EXEC.matcher(source.substring(start, end));

		while (matcher.find()) {
			statements.add(matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
		}

		assertTrue(statements.stream().anyMatch(s -> s.startsWith("CREATE TABLE")),
				"found no CREATE TABLE in createAllTables; has Room changed its output?");
		return statements;
	}

	/**
	 * One file's schema, described the way Room's {@code TableInfo} sees it, each item reduced
	 * to a string so a difference prints as something a person can read.
	 */
	private record Schema(Map<String, Set<String>> columns,
	                      Map<String, Set<String>> foreignKeys,
	                      Map<String, Set<String>> indices) {

		Set<String> tables() {
			return columns.keySet();
		}

		static Schema of(Connection connection) throws SQLException {

			Map<String, Set<String>> columns = new TreeMap<>();
			Map<String, Set<String>> foreignKeys = new TreeMap<>();
			Map<String, Set<String>> indices = new TreeMap<>();

			for (String table : tables(connection)) {
				columns.put(table, columns(connection, table));
				foreignKeys.put(table, foreignKeys(connection, table));
				indices.put(table, indices(connection, table));
			}
			return new Schema(columns, foreignKeys, indices);
		}

		private static List<String> tables(Connection connection) throws SQLException {

			List<String> tables = new ArrayList<>();

			try (Statement statement = connection.createStatement();
			     ResultSet rows = statement.executeQuery(
					     "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")) {
				while (rows.next()) {
					String name = rows.getString(1);
					if (!NOT_ENTITIES.contains(name) && !name.startsWith("sqlite_")) {
						tables.add(name);
					}
				}
			}
			return tables;
		}

		/** Name, declared type, NOT NULL, position in the primary key, and default. */
		private static Set<String> columns(Connection connection, String table)
				throws SQLException {

			Set<String> out = new TreeSet<>();

			try (Statement statement = connection.createStatement();
			     ResultSet rows = statement.executeQuery(
					     "PRAGMA table_info(`" + table + "`)")) {
				while (rows.next()) {
					out.add(rows.getString("name")
							+ " " + rows.getString("type").toUpperCase(Locale.ROOT)
							+ (rows.getInt("notnull") == 1 ? " NOT NULL" : "")
							+ (rows.getInt("pk") > 0 ? " pk#" + rows.getInt("pk") : "")
							+ (rows.getString("dflt_value") == null
									? "" : " DEFAULT " + rows.getString("dflt_value")));
				}
			}
			return out;
		}

		/** Each key as columns, parent and actions; multi-column keys kept together. */
		private static Set<String> foreignKeys(Connection connection, String table)
				throws SQLException {

			Map<Integer, String[]> byId = new TreeMap<>();
			Map<Integer, List<String>> from = new TreeMap<>();
			Map<Integer, List<String>> to = new TreeMap<>();

			try (Statement statement = connection.createStatement();
			     ResultSet rows = statement.executeQuery(
					     "PRAGMA foreign_key_list(`" + table + "`)")) {
				while (rows.next()) {
					int id = rows.getInt("id");
					byId.put(id, new String[]{rows.getString("table"),
							rows.getString("on_update"), rows.getString("on_delete")});
					from.computeIfAbsent(id, k -> new ArrayList<>()).add(rows.getString("from"));
					to.computeIfAbsent(id, k -> new ArrayList<>()).add(rows.getString("to"));
				}
			}

			Set<String> out = new TreeSet<>();
			for (Map.Entry<Integer, String[]> key : byId.entrySet()) {
				String[] v = key.getValue();
				out.add(from.get(key.getKey()) + " -> " + v[0] + to.get(key.getKey())
						+ " ON UPDATE " + v[1] + " ON DELETE " + v[2]);
			}
			return out;
		}

		/**
		 * Name, uniqueness and columns in order. SQLite's own automatic indices (for
		 * {@code UNIQUE} and composite primary keys) are left out, as Room leaves them out.
		 */
		private static Set<String> indices(Connection connection, String table)
				throws SQLException {

			Map<String, Boolean> unique = new TreeMap<>();

			try (Statement statement = connection.createStatement();
			     ResultSet rows = statement.executeQuery(
					     "PRAGMA index_list(`" + table + "`)")) {
				while (rows.next()) {
					String name = rows.getString("name");
					if (!name.startsWith("sqlite_autoindex_")) {
						unique.put(name, rows.getInt("unique") == 1);
					}
				}
			}

			Set<String> out = new TreeSet<>();

			for (Map.Entry<String, Boolean> index : unique.entrySet()) {

				Map<Integer, String> columns = new TreeMap<>();

				try (Statement statement = connection.createStatement();
				     ResultSet rows = statement.executeQuery(
						     "PRAGMA index_info(`" + index.getKey() + "`)")) {
					while (rows.next()) {
						columns.put(rows.getInt("seqno"), rows.getString("name"));
					}
				}
				out.add((index.getValue() ? "UNIQUE " : "") + index.getKey()
						+ " " + columns.values());
			}
			return out;
		}
	}
}
