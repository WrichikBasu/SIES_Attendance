package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.database.Cursor;

import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import in.basulabs.siesattendance.backend.ConstantsAndStatics;
import in.basulabs.siesattendance.backend.Migrations;

/**
 * Records the exact SQL every schema migration emits.
 * <p>
 * The migrations are about to be shared with the server, which cannot use Room and so cannot
 * use {@link Migration} either. Before that move, this writes down what each one does against
 * an empty database, so the same dump taken afterwards proves the move changed nothing. It is
 * the trick that caught nothing in Phase 2 precisely because it was there to catch something.
 * <p>
 * Nothing executes: the database is a recorder. That is enough, because every migration here
 * is a sequence of statements and the only decisions any of them make are the ones
 * {@link Migrations} makes in Java from query results, which an empty database makes
 * deterministic.
 */
public class MigrationSqlTest {

	/** One statement as the migration asked for it. */
	private record Statement(String sql, List<Object> args) {
	}

	/**
	 * A {@link SupportSQLiteDatabase} that writes nothing down but what it was asked to do.
	 * <p>
	 * A dynamic proxy rather than a hand-written stub because the interface has some forty
	 * methods and the migrations call three of them. Queries come back empty, which is the
	 * state a migration running against a fresh database sees.
	 */
	private static List<Statement> record(Migration migration) {

		List<Statement> recorded = new ArrayList<>();

		InvocationHandler cursor = (proxy, method, args) -> switch (method.getName()) {
			case "moveToNext", "moveToFirst" -> false;
			case "isNull" -> true;
			case "getCount" -> 0;
			case "close" -> null;
			default -> defaultValue(method);
		};

		InvocationHandler db = (proxy, method, args) -> {

			switch (method.getName()) {
				case "execSQL" -> {
					List<Object> bound = args.length > 1 && args[1] != null
							? Arrays.asList((Object[]) args[1])
							: List.of();
					recorded.add(new Statement((String) args[0], bound));
					return null;
				}
				case "query" -> {
					return Proxy.newProxyInstance(Cursor.class.getClassLoader(),
							new Class<?>[]{Cursor.class}, cursor);
				}
				default -> {
					return defaultValue(method);
				}
			}
		};

		migration.migrate((SupportSQLiteDatabase) Proxy.newProxyInstance(
				SupportSQLiteDatabase.class.getClassLoader(),
				new Class<?>[]{SupportSQLiteDatabase.class}, db));

		return recorded;
	}

	/** What an unstubbed method returns: enough to keep a proxy from throwing on a primitive. */
	private static Object defaultValue(Method method) {

		Class<?> type = method.getReturnType();

		if (type == boolean.class) {
			return false;
		}
		if (type == int.class) {
			return 0;
		}
		if (type == long.class) {
			return 0L;
		}
		if (type == double.class) {
			return 0d;
		}
		if (type == float.class) {
			return 0f;
		}
		if (type == short.class) {
			return (short) 0;
		}
		if (type == byte.class) {
			return (byte) 0;
		}
		if (type == char.class) {
			return '\0';
		}
		return null;
	}

	/** Every migration, in the order Room would apply them, with the version it reaches. */
	private static List<Migration> all() {
		return List.of(Migrations.MIGRATION_1_2, Migrations.MIGRATION_2_3,
				Migrations.MIGRATION_3_4, Migrations.MIGRATION_4_5, Migrations.MIGRATION_5_6,
				Migrations.MIGRATION_6_7, Migrations.MIGRATION_7_8, Migrations.MIGRATION_8_9,
				Migrations.MIGRATION_9_10);
	}

	/**
	 * Writes one file per migration into {@code build/wb-sql-migrations/}, in the same
	 * statement-then-arguments format the query dumps use.
	 */
	@Test
	public void dumpEveryMigration() throws IOException {

		File dir = new File("build/wb-sql-migrations");

		if (dir.exists()) {
			File[] existing = dir.listFiles();
			if (existing != null) {
				for (File file : existing) {
					assertTrue("could not clear " + file, file.delete());
				}
			}
		}
		assertTrue("could not create " + dir, dir.isDirectory() || dir.mkdirs());

		int version = 1;

		for (Migration migration : all()) {

			StringBuilder out = new StringBuilder();

			for (Statement statement : record(migration)) {
				out.append(statement.sql()).append('\n');
				out.append("----ARGS----").append('\n');
				for (Object arg : statement.args()) {
					out.append(arg == null ? " NULL" : String.valueOf(arg)).append('\n');
				}
				out.append("----END----").append('\n');
			}

			Files.write(new File(dir, version + "_to_" + (version + 1) + ".sql").toPath(),
					out.toString().getBytes(StandardCharsets.UTF_8));
			version++;
		}

		assertEquals("one dump per migration", all().size(),
				dir.listFiles() == null ? 0 : dir.listFiles().length);
	}

	/** The chain has to reach the version the app actually declares, with no gaps. */
	@Test
	public void theMigrationsReachTheCurrentSchemaVersion() {
		assertEquals(ConstantsAndStatics.DATABASE_VERSION, all().size() + 1);
	}
}
