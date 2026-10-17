# server

The PostgreSQL schema and the Spring Boot service the attendance database is moving to.

Nothing here touches the app's data. The migrations describe a database that does not exist
yet; the service boots, applies them and answers a health check; the two query builders are
translations of the app's own, checked by running both and comparing the rows.

```
server/
  build.gradle.kts                      Spring Boot 4.1 on Java 25
  src/main/java/.../server/
    SiesAttendanceServer.java           the application; no API yet, on purpose
    sql/BoundStatement.java             a statement and its arguments, kept together
    sql/PgSqlBits.java                  the fragments both builders share
    sql/PostgresStatsDialect.java       the expressions behind each StatsGroupBy
    sql/PgStatsQueryBuilder.java        StatsFilter  -> PostgreSQL
    sql/PgExportQueryBuilder.java       ExportFilter -> PostgreSQL
  src/main/resources/
    application.yml                     every credential from the environment
    db/migration/V1__baseline.sql       Room schema v10, translated faithfully
    db/migration/V2__constraints.sql    the rules AcademicRules enforced in Java only
  src/test/java/.../sql/SqlDumpTest.java    writes every statement for the differ
  src/test/sql/constraint_smoke_test.sql    39 assertions, 25 of them rejections
  tools/verify-schema.sh                a throwaway container, checked and removed
  tools/dev-database.sh                 a local database that survives a restart
  tools/roles.sql                       the three roles and what each may do
```

The domain code the service shares with the app — the enums, `AcademicRules`, `ClassKey`,
`NameFormat`, `BatchMatcher`, and the `StatsFilter` / `ExportFilter` / `LectureFilter`
objects — lives in `:shared` at the repository root, compiled once and used by both.

## Checking it

Everything below needs only Docker and the Gradle wrapper. No database has to be installed
and nothing is left running afterwards.

```sh
# The schema: apply both migrations to a throwaway postgres:18 container, run the
# smoke test, print what landed, remove the container.
./server/tools/verify-schema.sh
./server/tools/verify-schema.sh --dump        # and print the DDL

# The drift guard. No database at all: it compares the committed SQL against the Java
# enums and AcademicRules, so it runs in the ordinary unit test suite.
./gradlew :app:testDebugUnitTest --tests '*PostgresSchemaDriftTest*'

# Everything: the app, the shared domain module and the server.
./gradlew test :app:testDebugUnitTest

# The acceptance gate. Runs every statement on both engines and compares the rows.
./gradlew :app:testDebugUnitTest :server:test
python3 app/src/test/sql/sql_differ.py
```

`PG_IMAGE` overrides the PostgreSQL version for both the verify script and the differ.

## Running the service

```sh
./server/tools/dev-database.sh            # roles and an empty database, ~15 seconds
eval "$(./server/tools/dev-database.sh --env)"
./gradlew :server:bootRun                 # Flyway applies both migrations on first start
curl -s localhost:8080/actuator/health
./server/tools/dev-database.sh --reset    # throw it away
```

The database is left empty on purpose and Flyway fills it. `baseline-on-migrate` is false,
so the service refuses a database that already has the schema but no Flyway history — which
is what you get if you apply the SQL by hand. `MIGRATE=1 ./server/tools/dev-database.sh
--reset` does exactly that, for poking at a schema in psql without running the service, and
says so.

Three roles, because the split is what will make the audit log in Phase 6 real rather than a
convention. `sies_owner` owns every object and is used by Flyway and nothing else;
`sies_app` is what the service connects as and cannot run DDL; `sies_auditor` reads. None is
a superuser and none has `BYPASSRLS` — both of those bypass row-level security outright, and
so does a table's owner unless the table is FORCEd, which is the other reason the service is
not the owner. `tools/roles.sql` has the detail.

## Two migrations, not one

`V1__baseline.sql` is a translation and nothing more. Every table, column, index and foreign
key in it corresponds to something Room's generated `MainDatabase_Impl` creates, so the two
can be read side by side and disagreements found by eye. It adds nothing except the
provenance columns, which are nullable precisely so that an import can leave them unset.

`V2__constraints.sql` is where the rules go that `AcademicRules` enforced in Java and the
SQLite schema could not express: no overlapping sessions, a department that actually offers
the stream it teaches, a semester that belongs to the year, a division only on a stream that
has them.

Keeping them apart means V1 stays diffable against the generated DDL for as long as the app
exists, and V2 can be read as the list of things the database now refuses.

## What the device schema loses, and why

| Room | Here | Why |
|---|---|---|
| `startEpoch`, `endEpoch` on `academic_years` | dropped | Derived at whichever device wrote the row. `session_year_of(at, zone)` takes the zone as a parameter instead. |
| `"epochSecond\|nano\|zone"` text | `timestamptz` + a `_zone` column beside it | The instant is the instant; the zone is what it was recorded in, and both are needed. |
| `present INTEGER` 0/1 | `boolean` | |
| enum names in `TEXT` | native enum types | The values are the Java `name()` strings, in declaration order. |
| epoch-day integers | `date` | |

## Findings

Four things the translation turned up, all of them about the app rather than the schema.

**`batch_desc` has no uniqueness on the name.** Room's
`index_batch_desc_batchID_academicYear` guarantees nothing, because `batchID` is already the
primary key — while `DatabaseMerger` reconciles batches on `batchName + academicYear`. Two
devices that each created a "Batch A" in the same session would merge into one batch,
carrying their membership and their lectures together. V2 adds `UNIQUE (batch_name,
academic_year)`. Collisions in real data have to be resolved by hand; relaxing the
constraint would leave the merge ambiguous for good.

**The statistics report was never deterministically ordered.** Found by the differ, which
is the only reason anyone would find it: two lectures of the same course on the same day
produce the identical label, and the ORDER BY stopped at the label. The row order between
them was whatever the engine happened to produce — the two engines disagreed, and the same
engine could disagree with itself after a plan change. Both builders now fall back to the
row's own id. This is a change to the app's generated SQL, the only one in Phase 2.

**A minor or open elective can only come from a department that runs the student's own
stream.** `FragmentAddCourse` links the offering department to the stream — "a Mass Media
course can only be BAMMC" — so a course's stream must be one its department offers, and V2
enforces that as a foreign key. But the export's roster join handles a minor taught by
another department, matching `c.stream = y.stream`. For a BSc_unaided Computer Science
class, that branch can therefore only ever match a minor from another *unaided* BSc
department. A Mathematics minor, Mathematics being aided, cannot be recorded at all. Either
the course form is too strict for minors and electives or the roster join promises more than
it can deliver; they cannot both be right. Worth deciding before Phase 3 imports real
courses, because the importer will reject whatever the form would have.

**Spring Boot 4 does not auto-configure Flyway from `flyway-core` alone.** Boot 4 split its
auto-configuration into a module per technology. With `flyway-core` on the classpath and
`spring.flyway.*` filled in, the service starts cleanly, reports healthy, and never applies
a migration. `org.springframework.boot:spring-boot-flyway` is what makes it run.

## Expect the import to be rejected

The constraints go on an empty database, so nothing in them can damage existing data. They
will, however, reject rows from the devices — that is their job. When Phase 3's importer
stops on one, the thing to fix is the data, after looking at it. Relaxing a constraint to
let a row in puts the contradiction back in the database where nobody will see it again.

## Not here yet

No API, no authentication, no importer, no audit log. Those are Phases 3 to 6, and an
endpoint written before there is anything to serve or anyone to authenticate would be an
endpoint with no caller and no access control.
