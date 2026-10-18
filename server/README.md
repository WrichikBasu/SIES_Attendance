# server

The PostgreSQL schema and the Spring Boot service the attendance database is moving to.

Nothing here touches the app's data. The migrations describe a database that does not exist
yet; the service boots, applies them and answers a health check; the two query builders are
translations of the app's own, checked by running both and comparing the rows. The importer
reads a device's own encrypted backup and folds it in, resolving every row before it writes
one.

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
    importer/StagedBackup.java          decrypt, check, migrate, open
    importer/JdbcSqliteDb.java          the app's migrations, over JDBC
    importer/ServerIds.java             keys minted under the server's own node
    importer/ImportSession.java         resolve every row, then write it
    importer/BackupImporter.java        one run, one transaction, one report
    importer/ImportReport.java          what it did, per table
    importer/ImportCommand.java         the command line
  src/main/resources/
    application.yml                     every credential from the environment
    application-import.yml              the importer is a command, not a service
    db/migration/V1__baseline.sql       Room schema v10, translated faithfully
    db/migration/V2__constraints.sql    the rules AcademicRules enforced in Java only
    db/migration/V3__import_bookkeeping.sql   id_remap and import_run
    db/migration/V4__cross_department_courses.sql   V2's course/stream key, corrected
  src/test/java/.../sql/SqlDumpTest.java         writes every statement for the differ
  src/test/java/.../importer/BackupImporterTest.java   13 tests, against a real database
  src/test/java/.../importer/DeviceBackup.java   builds a genuine .siesbak
  src/test/java/.../importer/SampleBackup.java   one to try the importer on
  src/test/sql/constraint_smoke_test.sql    39 assertions, 25 of them rejections
  tools/verify-schema.sh                a throwaway container, checked and removed
  tools/verify-import.sh                the importer, against a real PostgreSQL
  tools/dev-database.sh                 a local database that survives a restart
  tools/roles.sql                       the three roles and what each may do
```

The domain code the service shares with the app — the enums, `AcademicRules`, `ClassKey`,
`NameFormat`, `BatchMatcher`, and the `StatsFilter` / `ExportFilter` / `LectureFilter`
objects — lives in `:shared` at the repository root, compiled once and used by both.

The importer added four more things to it, each because two programs now have to agree:

| In `:shared` | Why it cannot live in either program |
|---|---|
| `migration/SchemaMigrations` | A backup arrives at whatever schema the phone was on. The server runs the app's own migration steps over it, through a three-method `SqliteDb` that Room and JDBC each adapt to. |
| `sync/DeviceTables` | What identifies a row, what each column is called on both sides, and the order tables depend on each other in. `DatabaseMerger` reads it too, so the phone and the server cannot disagree about who a student is. |
| `StructuredId` | The key layout. The server reads the device's node out of a key and mints its own under node 0. |
| `StoredTimes`, `BackupContainer`, `BackupCrypto` | The device's storage format for a timestamp, and the `.siesbak` container. A second parser would be a second opinion about when a lecture happened. |

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

# The Phase 2 gate. Runs every statement on both engines and compares the rows.
./gradlew :app:testDebugUnitTest :server:test
python3 app/src/test/sql/sql_differ.py

# The Phase 3 gate, as far as code can carry it. Builds real .siesbak files —
# including one at schema v6, so the migration path and the legacy keys are both
# exercised — imports them into a throwaway database, and looks at the rows.
./server/tools/verify-import.sh
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

## Importing a device

```sh
./server/tools/dev-database.sh                 # somewhere to import into
eval "$(./server/tools/dev-database.sh --env)"

# Something to import, if there is no real backup to hand yet.
./gradlew :server:sampleBackup                 # or -Psample.version=6

export SIES_BACKUP_PASSWORD='the backup password'

./gradlew :server:bootRun --args="--spring.profiles.active=import \
    --import.file=$PWD/server/build/sample/sample-device.siesbak \
    --import.label='Dr Rao phone' \
    --import.node=41231 \
    --import.dry-run=true"
```

Then drop `--import.dry-run` to keep it. `--import.history=true` lists every run.

Paths are resolved against `server/`, because that is where `bootRun` puts the process, so
give an absolute one.

**The password is never an argument.** Arguments are visible to every process on the machine
through `/proc` and stay in shell history. It comes from `SIES_BACKUP_PASSWORD`, or from a
prompt when there is a terminal. `--import.password-env=NAME` names a different variable, for
keeping several devices' passwords side by side.

**`--import.node` is the device's node number, and it matters.** A key issued before schema v7
was counted out by SQLite from one, independently on every phone, so it says nothing about
which phone it came from — and `id_remap` has to be keyed by one. Giving two devices the same
node would fuse their legacy rows together. The number is on the app's diagnostics screen; node
0 is the server's own and is refused.

### What a run does

1. Decrypts the backup, checks it is one of ours and undamaged, and brings it to the current
   schema with the app's own migrations. All before the transaction opens, so a bad file costs
   nothing.
2. Records the attempt in `import_run` and commits that row, so a failure is still on record.
3. Resolves every table in dependency order, in one transaction.
4. Commits, and writes the report back onto the run row.

A row is resolved in three steps, in this order: **by key**, where a structured key or an
`id_remap` entry already says which server row it is; then **by natural key**, where the world
already knows the row under a roll number or a course code; then **inserted**, keeping the
device's key when that key still means something and minting a server one when it does not.

Resolving by key first is the thing the app's merge cannot do, and it is why a second import of
a device whose roll numbers have since been corrected updates the right students instead of
inserting them again.

### Running it twice

Changes nothing, and the report says so rather than leaving it to be inferred: an `UPDATE` that
would write identical values is narrowed so it affects no rows, and those are counted as
`unchanged`. A second run of an unchanged backup reports `0 inserted, 0 updated`, every row
`unchanged`, and mentions that it has seen the file before.

## Two migrations, not one

`V1__baseline.sql` is a translation and nothing more. Every table, column, index and foreign
key in it corresponds to something Room's generated `MainDatabase_Impl` creates, so the two
can be read side by side and disagreements found by eye. It adds nothing except the
provenance columns, which are nullable precisely so that an import can leave them unset.

`V2__constraints.sql` is where the rules go that `AcademicRules` enforced in Java and the
SQLite schema could not express: no overlapping sessions, a department that actually offers
the stream it teaches, a semester that belongs to the year, a division only on a stream that
has them.

`V3__import_bookkeeping.sql` is the importer's own two tables: `id_remap`, which remembers
which legacy device key became which server key, and `import_run`, which keeps every attempt
and its report. Neither holds attendance. The reconciliation this phase ends with has to be
possible from the database rather than from terminal scrollback, which is why the report is
stored and not only printed.

`V4__cross_department_courses.sql` is the first migration that corrects an earlier one
rather than adding to it. V2 required every course's stream to be one its offering department
offers; that is true only of the types whose roll is the offering department's own majors, and
the finding below has the detail. V4 narrows the foreign key to those four types through a
generated column that is null for the other nine, since PostgreSQL has no partial foreign key.

V2 itself is left exactly as written. A migration that has been applied anywhere is a fixed
artefact, so a correction is a new file — which also answers, by example, the open question
about whether the `session_year_of` edit should have been one.

Keeping them apart means V1 stays diffable against the generated DDL for as long as the app
exists, V2 can be read as the list of things the database now refuses, V3 as the list of things
the import has to remember, and V4 as the one thing V2 got wrong.

## What the device schema loses, and why

| Room | Here | Why |
|---|---|---|
| `startEpoch`, `endEpoch` on `academic_years` | dropped | Derived at whichever device wrote the row. `session_year_of(at, zone)` takes the zone as a parameter instead. |
| `"epochSecond\|nano\|zone"` text | `timestamptz` + a `_zone` column beside it | The instant is the instant; the zone is what it was recorded in, and both are needed. |
| `present INTEGER` 0/1 | `boolean` | |
| enum names in `TEXT` | native enum types | The values are the Java `name()` strings, in declaration order. |
| epoch-day integers | `date` | |

## Findings

Seven things. The first four came out of the translation in Phase 2, the last three out of
building the importer, and all but one are about the app rather than the schema — the exception
being the second, where this schema was itself wrong and `V4` corrects it.

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

**A course could only come from a department that runs the students' stream.** *Fixed; see
`V4__cross_department_courses.sql`.* `FragmentAddCourse` linked the offering department to the
stream — "a Mass Media course can only be BAMMC" — so a course's stream had to be one its
department offers, and V2 enforced that as a foreign key. But the roster join matches the
course's stream against the *student's*, `y.stream = c.stream`, while matching the department
differently per type: `majorDept` for a major and a DSE, `minorDept` for a minor, `oeDept` for
an open elective, and nothing at all for VSC, SEC, AEC and IKS, which a whole year takes.

Only the first group can be sure the offering department runs the stream. The rest are taught
by somebody else, and because the aided and unaided courses are stored as separate streams the
restriction bit hard rather than at the margins:

| A student in… | could take an open elective from… |
|---|---|
| `BCom` | Commerce — the only department offering BCom |
| `BAMMC` | Mass Media only |
| `BMS` | Management Studies only |
| `BSc_unaided` | those five departments, and no aided one |
| `MSc` (aided) | Chemistry, Mathematics, Biochemistry, Statistics |

An open elective that cannot leave its own department is not an open elective, and a
Mathematics minor — Mathematics being studied as BSc — could not be recorded for a
BSc_unaided class at all.

The foreign key was the half that was wrong. On a cross-department course
`offering_department` says who teaches it and `stream` says whose roll it draws from, two
different questions that the key conflated. V4 keeps it for the four types that follow the
department and exempts the rest through a generated column; `CourseType.followsOfferingDepartment()`
is the single list, `AcademicRules.validateCourse` is the matching check on the form, and
`DropdownBinder` stops narrowing the stream list when the type does not call for it. No
existing row can have violated the key — courses are only ever created through that form, and
`CsvImporter` handles students only — so this was never an import blocker, as an earlier draft
of this file wrongly said. It was a question about what to allow next.

**Spring Boot 4 does not auto-configure Flyway from `flyway-core` alone.** Boot 4 split its
auto-configuration into a module per technology. With `flyway-core` on the classpath and
`spring.flyway.*` filled in, the service starts cleanly, reports healthy, and never applies
a migration. `org.springframework.boot:spring-boot-flyway` is what makes it run.

**`DatabaseMerger` aborts if a roll number was corrected on the other device.** Not a
translation problem; a live defect in the app, found because the importer was given the same
natural keys and the same test. `student_year` is reconciled on `rollNo + academicYear`, but
its primary key is `(studentID, academicYear)`. So: student exists on both phones, somebody
fixes a typo in their roll number on phone A, A's backup is merged into B. The natural key
matches nothing, the merge inserts — and the insert hits the primary key, which
`CONFLICT_ABORT` turns into a failure of the whole merge. The user sees "the rows could not be
folded into the existing database" and no way forward.

The importer does not have the bug, because it tries the primary key before the natural key:
a student has exactly one enrolment per session, and the roll number on it is an attribute
that can be corrected. `DeviceTables.primaryKey()` now carries what is needed to fix the app
the same way, and `DeviceTables.primaryKeyDiffersFromNaturalKey()` says `student_year` is the
only table where it matters. **Not fixed here** — changing the merge is a change to the app's
behaviour and is not what this phase was for.

**Two batches sharing a name are pooled, and only the server says so.** The uniqueness finding
above, seen from the other end. Because `batch_desc` is reconciled on `batchName +
academicYear`, two batches a device holds under one name do not collide on import — the second
matches the first and updates it, and their memberships and lectures end up in one batch. The
phone's merge does exactly the same thing and says nothing. The importer reports it, which is
the only reason anybody would find out.

**Seeding the session table depended on the machine's zone and clock.** The 2-to-3 migration
derives `startEpoch`/`endEpoch` from a date at `ZoneId.systemDefault()` and seeds a session for
`LocalDate.now()`. On a phone those were the right answers, because the device that ran the
migration was the device that wrote the rows. On a server configured for UTC they are wrong by
five and a half hours — measured, by dumping the migration's own SQL before and after. The zone
and the date are now parameters (`MigrationContext`), the importer passes the zone the data was
recorded at, and the app passes its own, so nothing about the app changed.

## Where the importer deliberately differs from the merge

Three places, each because the server knows something a phone does not. Each is a decision, not
an oversight.

| | The phone | Here | Why |
|---|---|---|---|
| Resolution order | natural key only | key, then natural key | A structured key is unique everywhere, and `id_remap` remembers what a legacy one became. The phone has neither. |
| A lecture's identity | the stored text `epochSecond\|nano\|zone`, compared as a string | the instant alone | The instant is what identifies the lecture; the zone is a note on how it was recorded. The device's comparison would treat the same lecture recorded on a phone set to UTC as a different one. A zone that disagrees is reported. |
| A natural key owned by another parent | repointed silently | reported and skipped | If roll 42 is one student here and another in the backup, repointing moves attendance onto the wrong person and no screen ever says so. |

## Expect the import to be rejected

The constraints go on an empty database, so nothing in them can damage existing data. They
will, however, reject rows from the devices — that is their job. When Phase 3's importer
stops on one, the thing to fix is the data, after looking at it. Relaxing a constraint to
let a row in puts the contradiction back in the database where nobody will see it again.

## Not here yet

No API, no authentication, no audit log. Those are Phases 4 to 6, and an endpoint written
before there is anything to serve or anyone to authenticate would be an endpoint with no caller
and no access control. The importer is deliberately a command and not an endpoint for the same
reason: until Phase 5 there is nobody to authenticate, and importing a device is an operation
somebody should have to be on the machine to perform.

The part of Phase 3 that code cannot finish is the data. The gate asks for every device's
backup to be imported, the reconciliation to pass, and the reference exports from Phase 0 to
reproduce from the server byte for byte — and Phase 0, which is collecting those backups and
exports, has not happened. `tools/verify-import.sh` is as far as this goes without them.
