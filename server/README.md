# server

The PostgreSQL schema and the Spring Boot service the attendance database is moving to.

Nothing here touches the app's data. The migrations describe a database that does not exist
yet; the two query builders are translations of the app's own, checked by running both and
comparing the rows; the importer reads a device's own encrypted backup and folds it in,
resolving every row before it writes one.

The service answers eight endpoints under `/api/v1` — sign in, change a password, sign out,
register a device, export a register, load a roster, push a device's changes, pull what it has not
seen — and a bearer token is the only way to reach any of them. The app is behind one interface
now, keeps Room as its cache, and queues every local change in an outbox its own database fills
with triggers.

```
server/
  build.gradle.kts                      Spring Boot 4.1 on Java 25
  src/main/java/.../server/
    SiesAttendanceServer.java           the application
    web/Api.java                        the versioned path, the headers, the error shape
    web/BearerTokenFilter.java          the one way identity enters the server
    web/RequestActor.java               who is making this request
    web/ActorDatabase.java              one transaction, with the actor announced on it
    web/AuthController.java             sign in, change a password, sign out
    web/DeviceController.java           a node number per install, idempotently
    web/ExportController.java           the register, narrowed to what you may export
    web/RosterController.java           a CSV in, students and student_year out
    web/SyncController.java             push what a phone has, pull what it has not
    web/LoginRateLimiter.java           attempts per address, in memory
    web/ApiAdvice.java                  every error as one shape
    web/WebBeans.java                   what the service is made of
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
    security/Passwords.java             Argon2id, parameters inside the hash
    security/AccountDirectory.java      accounts, lockout, rotation, two doors
    security/ActorSession.java          who is acting, for one transaction
    security/BootstrapCommand.java      the first administrator, once
    security/AuditLog.java              the events no trigger can see
    security/Sessions.java              opaque bearer tokens, revocable
    security/DeviceRegistry.java        which install is which
    importer/RosterImporter.java        the shared CSV parser, into PostgreSQL
    sync/DeviceValue.java               what a device's value means, both ways
    sync/SyncWriter.java                a batch of intents, applied by primary key
    sync/SyncReader.java                a snapshot, or everything since a watermark
  src/main/resources/
    application.yml                     every credential from the environment
    application-import.yml              the importer is a command, not a service
    application-bootstrap.yml           so is creating the first admin
    db/migration/V1__baseline.sql       Room schema v10, translated faithfully
    db/migration/V2__constraints.sql    the rules AcademicRules enforced in Java only
    db/migration/V3__import_bookkeeping.sql   id_remap and import_run
    db/migration/V4__cross_department_courses.sql   V2's course/stream key, corrected
    db/migration/V5__identity.sql       accounts, roles, CR grants, the roster function
    db/migration/V6__row_level_security.sql   which rows each of them reaches
    db/migration/V7__audit_log.sql      append-only by privilege, not by convention
    db/migration/V8__the_wire.sql       sessions, devices, and who may export what
    db/migration/V9__device_schema_v11.sql    a backup may now say 11
    db/migration/V10__sync.sql          the change feed, over the audit log
  src/test/java/.../sql/SqlDumpTest.java         writes every statement for the differ
  src/test/java/.../importer/BackupImporterTest.java   14 tests, against a real database
  src/test/java/.../importer/DeviceBackup.java   builds a genuine .siesbak
  src/test/java/.../importer/SampleBackup.java   one to try the importer on
  src/test/java/.../security/RowLevelSecurityTest.java   20 tests, as a constrained role
  src/test/java/.../security/AccountDirectoryTest.java   15 tests, the credential layer
  src/test/java/.../audit/AuditLogTest.java      15 tests, as a role that may not tidy up
  src/test/java/.../web/WireTest.java            35 tests, the whole service over HTTP
  src/test/java/.../web/LoginRateLimiterTest.java   6 tests, no container needed
  src/test/java/.../web/SyncTest.java            24 tests, the server half of sync
  src/test/java/.../importer/OutboxTriggerTest.java   11 tests, against real SQLite
  src/test/java/.../importer/DeviceSqlTest.java  5 tests, the phone's own statements
  src/test/java/.../testing/TestPostgres.java    the throwaway container, shared
  src/test/sql/constraint_smoke_test.sql    52 assertions, 35 of them rejections
  tools/verify-schema.sh                a throwaway container, checked and removed
  tools/verify-import.sh                the importer, against a real PostgreSQL
  tools/verify-rls.sh                   the policies, as a role they apply to
  tools/verify-audit.sh                 the trail, and that nobody can edit it
  tools/verify-wire.sh                  the endpoints, over real HTTP
  tools/verify-sync.sh                  both halves of sync, as far as a JVM reaches
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

# The Phase 4 and 5 gates. Both connect as a role the policies apply to, because a
# superuser would pass against a database with no policies in it at all.
./server/tools/verify-rls.sh
./server/tools/verify-audit.sh

# The Phase 5.5 gate. Boots the whole service on a random port against a throwaway
# database and talks to it over HTTP, as sies_app.
./server/tools/verify-wire.sh

# Phase 6's gate, in the two halves a machine with no handset can run: the server
# side over HTTP, and the device's queue and statements against real SQLite.
./server/tools/verify-sync.sh
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

Three roles, because the split is what makes the audit log real rather than a
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

## Identity and row-level security

Two migrations, for the same reason V1 and V2 are two: V5 says who exists, V6 says what they
reach.

### Who exists

`app_account` is one row per principal. It points at a professor **or** a student **or**
neither — `STAFF`, for office and registry people, who are not professors and would corrupt
every "count the teaching staff" query if they were given a `professors` row to hang an account
on. A CHECK enforces that the row points at whatever its `kind` says it does.

Accounts exist only where there is something to sign in for, which for a student means a CR
grant. `login_email` is `NOT NULL` on the account although `students.email` is nullable: the
requirement belongs to the account, not to the person, so it lands on the few students who need
one rather than on the roster.

Uniqueness is `(student_login, lower(login_email))` — per door, not global. Professors and
students sign in on separate screens, so the app knows which door before it looks anybody up,
and one address can hold both a staff account and a student one. A research assistant finishing
an MSc is the case, and it is commoner than it sounds. `student_login` is generated from `kind`,
the same trick V4 used, so the two can never disagree.

`user_role` carries the roles granted *on top* of what an account already is: `HOD`, `OFFICE`,
`AUDITOR`, `ADMIN`. There is no `PROFESSOR` role — that is `kind` plus the `course_instructor`
rows that say which courses are yours, and a role saying the same thing would be a second
answer to one question. There is no `CR` role either, for the reason below. It keys on
`account_id` and not on `prof_id`, because an admin or an office clerk need not be a professor
at all.

### A class representative is a set of grants

`attendance_delegate`: one row per student per course per session, naming who designated them
and in what capacity. Several rows for one student is a CR of several courses; several rows for
one course is several CRs of it, which is how a class with a lecture CR and a lab CR works
without a second table. Nothing is ever deleted — revoking sets `revoked_at`, because "who was
allowed to do this in March" is the question a dispute asks.

Two rules the table cannot state, both in `attendance_delegate_authorised()`:

- **The designator held the authority they claimed.** A professor who teaches the course, the
  head of the offering department for that session, or an admin. Three different joins, so not
  a foreign key: a constraint covering only the instructor branch would read as a guarantee
  while being a third of one.
- **The CR is on the course's roll.** A CR of CS-101 who does not take CS-101 is a mistake, not
  a configuration.

`granted_as` is stored rather than recomputed, so an HOD who stops being one in 2027 does not
retroactively change the authority a 2025 grant was made under.

### `roll_for_course`, written once

The second rule needs the roster, which until now existed only as the `@Query` on
`DaoStudent.getRollForCourseSync`. It is a SQL function now, and `PostgresSchemaDriftTest`
holds the two together: one test fails if any `CourseType` is missing from it, another if its
major-department branch stops matching `CourseType.followingOfferingDepartment()`. A second
hand-written roster is how a CR ends up allowed on a course the app would never have put them
on.

### What V6 forces, and what it only enables

| Table | Row-level security | Why |
|---|---|---|
| `attendance`, `lectures` | enabled **and forced** | What the gate is about. Not even the role that ran the migration reads them unfiltered. |
| `attendance_delegate`, `user_role` | enabled, **not** forced | The `actor_*` helper functions are `SECURITY DEFINER` and run as the owner. Forcing these would make a definer function see only its caller's rows, and the answer would be wrong rather than merely slow. |
| `app_account` | none at all | Authentication happens before there is an actor, so a policy keyed on the current account would deny the one lookup that establishes it. Privilege and the service's own code protect it, and saying so beats a policy that looks like protection. |

The policies name no role. `FOR ALL TO sies_app` would make every migration fail on a database
where `tools/roles.sql` has not run, and `verify-schema.sh` deliberately applies them to a bare
container. Unqualified policies apply to every role that is not a superuser and not the owner,
which is exactly the set that must be constrained.

Each `SECURITY DEFINER` function carries `SET search_path = pg_catalog, public`. Without it a
caller could create a schema earlier in their own path, put a `user_role` table in it, and have
these functions answer from that instead.

### Testing it means not being a superuser

A superuser bypasses every policy even under `FORCE`, and an owner bypasses its own unless
forced. So `TestPostgres` creates `sies_app_test` — not a superuser, not an owner, no
`BYPASSRLS` — with deliberately wide grants on every table. The point is to prove the
*policies* stop a read the *grants* allow; a test that passed because of a missing `GRANT`
would prove nothing.

`tools/verify-rls.sh` runs the two classes and then insists they ran: a security test that
skips itself because Docker is missing is a green build that proves nothing.

## The audit trail

Two ways in, and the service holds no privilege on the table for either of them.

**Row changes write themselves.** An `AFTER INSERT OR UPDATE OR DELETE` trigger on fourteen
tables writes a `to_jsonb` image of the row before and after. The trigger takes the key column
names as its own arguments, so an entry can be found again without knowing the table's shape.

**Everything else goes through `audit_event()`**, which takes no actor. It reads one from the
same session settings the policies read, so a caller cannot write an entry naming somebody else
— the difference between a log and a story. That covers the things no trigger can see: a login,
a failed login, a lockout, a password change, and above all an export, which leaves nothing
behind at all and is the realistic way a term's attendance leaves the building.

Both are `SECURITY DEFINER` and owned by the migration role, so `V7` revokes **everything** on
`audit_log` from `sies_app` — not even `INSERT`, and not `SELECT` either. There is no statement
the service can aim at that table. `sies_auditor` gets `SELECT` back. Nobody anywhere gets
`UPDATE` or `DELETE`.

### Three details that are easy to get wrong

**`clock_timestamp()`, not `now()`.** `now()` is the transaction's start time, identical for
every statement in it. Marking a register is one transaction containing sixty inserts, and with
`now()` all sixty would share an instant and their order would be unrecoverable.

**Compare before redacting.** The trigger skips an `UPDATE` whose images are identical, because
the importer and the phone's merge both write rows back unchanged as a matter of course. It
also strips `password_hash`, because the auditor can read this table and an unredacted log
would hand them every hash the college has ever stored. The first draft redacted *first* — so a
change to nothing but the password hash left two identical images, looked like a no-op, and
produced no entry at all. The one column that must never be logged was also the one change that
went unrecorded.

**No foreign keys onto `app_account` or `courses`.** A foreign key would let an audit entry
block the deletion of what it describes, or be cascaded away with it. The log has to outlive its
subject: the last thing it records about an account is usually the account going away.

### Partitions, and why there is an overflow

Monthly ranges, so a retention decision later is a `DETACH` rather than a `DELETE` of millions
of rows while the college is using the system. `audit_log_add_months()` creates them and is
idempotent, so a scheduled job can simply run it; `V7` creates two years' worth up front.

There is also a `DEFAULT` partition, and that is the part worth arguing about. Without one, an
insert past the last pre-created month fails — and because these inserts come from triggers,
the failure is not "the log is behind", it is **"attendance cannot be marked"**. An audit log
that can stop the college taking attendance is worse than one that is untidy. A row in
`audit_log_overflow` is a signal that nobody has run the partition job for two years.

### "pre-migration", and why the actor is not null

The plan said imported rows should carry `actor_label = 'pre-migration'` and a null actor. That
was written before the policies existed, and a null actor now reaches no row at all — the
import could not happen. Both halves of the intent survive, split between the columns:
`actor_account` is the person who ran the import, because somebody is accountable for loading a
device's history; `actor_label` says `pre-migration`, because the authorship of the attendance
*itself* is unrecorded and unrecoverable. Writing the operator's name in as though they had
marked those registers is the invention the plan was guarding against.

A real import of the sample device produces 58 entries — 57 row changes and one
`BACKUP_IMPORT` carrying the per-table report — all labelled
`pre-migration (import #1 from RaoPhone)` against the account that ran it.

## The wire

Four endpoints, all under `/api/v1`, and the version is in the path from the first response
rather than from the first time two versions exist. An old build receiving an enum constant it
has never heard of throws in `TypeConvertors`, by design — the device would rather fail than
store a value it cannot read back — and that design only holds if the server can tell which
client it is talking to.

```
POST /api/v1/auth/login       email, door, password, optionally an install
POST /api/v1/auth/password    the current one and the new one
POST /api/v1/auth/logout      revokes this session
POST /api/v1/devices          an install id in, a node number out
GET  /api/v1/export           the register, as JSON rows
POST /api/v1/roster           a text/csv body in, counts out
```

`/actuator/health` is outside all of it: the token filter is registered for `/api/*` alone, so
a health probe answers before anybody has signed in.

### The token is a row, not a claim

A signed self-contained token is a statement the server cannot withdraw, and the usual
compensation is to make it expire in minutes. That trade is the wrong way round here. A
professor in a basement laboratory marks a register with no signal and cannot refresh anything;
a token that expires while they are out of range fails at exactly the moment the app is
supposed to keep working.

So `app_session` holds a row per token, the token is thirty-two random bytes with no claims in
it at all, and what is stored is its SHA-256 — somebody who reads every row cannot sign in with
any of them. Revoking is an `UPDATE` that takes effect on the next request, which is what lets
the lifetime be a fortnight without that being the window a stolen token buys. There is also no
signing key to hold or rotate, and no algorithm field for anybody to confuse.

Changing a password revokes every *other* session of the account. Somebody who changes their
password because they think somebody else has it has not finished until the other sign-ins are
gone — and the one in their hand is the one to leave alone.

### `must_rotate` is enforced at the filter

A new account's password was typed by whoever created it, so until it is changed the session can
reach `/auth/password` and `/auth/logout` and nothing else. Signing out is on the list because
refusing to let somebody out of a session is not a security property.

### Binding a session to a phone

A session may carry a `device_id`, and when it does, every request has to present that install's
identifier in `X-Device-Install` as well as the token. It is not a second password — it lives on
the same phone — but it does mean a token lifted from a backup is useless without the install id
beside it, and it is the hook Phase 6's write path hangs on, where a row's key has to carry the
node of the device presenting it.

A new install has no device to name when it signs in, so it signs in without one, calls
`POST /devices` on the token it was given, and that call binds the session it arrived on. One
login, not two.

### Registration is the last chance to fix a node clash

`device_node_seq` hands out 1 … 32767, one per install, and never reuses one: the node is in the
bottom sixteen bits of every key that install ever issued, so giving the number to a second
phone would make two devices' rows indistinguishable in precisely the place that distinction is
load-bearing. `PostgresSchemaDriftTest` holds the sequence's bounds against
`StructuredId.MAX_ASSIGNED_NODE_ID`.

Two unregistered installs pick their own node at random from the upper half of the space, and
two of them may pick the same one — each having already written rows under it. Nothing in those
rows says which phone wrote them, which is what the node field was supposed to say.
Registration is the one moment both facts are in the same place: the install's identifier, and
the number it had been using. So a clash is **reported and not refused** — in the response and
in the `DEVICE_REGISTER` entry. Refusing would leave the second phone unregistered and the
collision unrecorded, which is the one outcome with no way back.

Registration is idempotent on the install id, which is the whole reason that column exists: a
retry after a lost response must not burn a second node and leave one phone owning two.

### The export has two filters, and only one is the caller's

`ExportFilter` is what was asked for. `exportable_courses(session_year)` is what the caller is
allowed to have, and it is applied on top: an empty course list means "everything I may export"
rather than "everything", and a course the caller named but may not have is a 403 naming it
rather than a quietly shorter report.

That second filter has to be applied by the endpoint, because `courses` and `student_year` still
have no read policies — V6's header says why at length — and without it a professor's export
would list every student in the college with zeroes against them. It is written in SQL anyway,
in V8, so that it sits beside the policies doing the same job for `attendance` and can be read
by somebody auditing what an export may contain.

The numbers in the cells need no such help: held and attended are counted over `lectures` and
`attendance`, both under `FORCE ROW LEVEL SECURITY`, so the policies decide them whatever the
endpoint asks for. The audit entry carries the filter, the course count and the row count — and
not the rows. An audit log holding a copy of every export would be the most valuable thing in
the database to steal.

### One parser for the roster, two writers

`RosterCsv` moved into `:shared` in this phase, so the file the app reads and the file the server
reads are read by the same code: the same optional cells, the same loose matching of "Computer
Science" against the constant, the same refusals. What differs is the writing. The app commits
row by row; the server runs each row inside a savepoint, so a row PostgreSQL refuses is rolled
back on its own and the surviving rows still land as one transaction. A connection dropped half
way through leaves the roster as it was rather than half rewritten.

Loading a roster is for the office and an administrator, and `actor_may_manage_roster()` is the
rule. A roll number is what every other session's records are matched by — the importer matches
on it, the export sorts by it, a promotion is a new roll number for the same person — so
rewriting a class's roll is a registry operation and not a teaching one, even for the professor
who teaches that class.

### Attempts per address

`app_account` already locks an account after five consecutive failures, which is the defence for
one person's password. It is no defence against somebody working through a list of addresses —
each account's counter is its own — and it is itself a way to cause trouble, since an attacker
who knows an address can spend five guesses to lock its owner out. `LoginRateLimiter` counts per
source address instead: ten attempts every five minutes, counted whether or not the password was
right, in memory, per instance. Two instances would mean two counters and twice the allowance;
if this is ever run behind a load balancer, that is the thing to revisit, and the answer is
probably the proxy rather than a table.

`server.forward-headers-strategy` is how the caller's address is established behind a proxy.
Parsing `X-Forwarded-For` in application code would mean trusting a header any client can send,
which would let a caller choose which bucket they land in and which address their audit entries
name.

### Every refusal to sign in reads identically

Unknown address, wrong password, locked, disabled: one status, one code, one sentence. The log
records which it was, because that is where the difference is useful. A response that
distinguishes them turns the login box into a way to ask whether somebody works here, and then
into a way to find out when their account was locked.

An attempt with an empty password is refused without a database lookup at all — hashing a blank
password against Argon2id on every request is a way to make the server do expensive work for
nothing — so it leaves no entry. Nothing reached an account, so there would be nothing to
attribute it to.

## Sync, and what the device does

Two endpoints, `/sync/push` and `/sync/pull`, and the half of the work that is on the phone. The device code is in `:app` under `backend/sync`; the format and the statements both sides
rely on are in `:shared`, which is what keeps one definition of what a row is.

```
POST /api/v1/sync/push      the rows this phone has that the server does not
GET  /api/v1/sync/pull      everything since this phone's watermark
```

### The outbox is filled by triggers, not by code

Every write to an attendance table queues its own key, in the same statement, through a trigger
generated from `DeviceTables`. The obvious alternative — appending an intent next to each write in
the repository — is wrong twice over. It misses writers: a roster CSV, an attendance-record CSV and
a restored backup all have their own code, and so will whatever is written next by somebody who has
not read this note. And it is not atomic: an append in Java is a second statement, and the window
between the two is exactly the moment an app is most likely to be killed, because the user has just
tapped Save and is putting the phone in their pocket. A mark saved locally and never queued is the
failure this whole phase exists to prevent, and it would be invisible — the screen would show the
attendance and the server would never hear of it.

An intent is a **dirty key**, not a diff: the table, up to three key columns, and `UPSERT` or
`DELETE`. The sender reads the row as it stands when it drains, so three edits before the phone
finds a signal cost one message, and a replay after a lost response sends the current state rather
than an older one. The unique index on the key is how the collapse happens, and the unused key
columns are `0` rather than null because SQLite treats nulls as distinct in a unique index.

Applying what the server sends is also a write, so each trigger is conditioned on
`sync_state.applying`, which the pull sets inside its own transaction — a crash takes the flag back
with the writes rather than leaving a device that silently queues nothing. That condition is
written with `COALESCE(…, 0)` deliberately: without it a missing `sync_state` row makes it
`NULL = 0`, which is not true, which would stop every intent from being recorded. A queued row too
many is recoverable; a mark nobody receives is not.

### The change feed is the audit log

A pull asks "what has changed since I last looked", and nothing on the server could answer that:
the tables carry a `row_version` but no ordering to resume from, and a deleted row carries nothing
at all. V7's audit log already is that answer — every insert, update and delete, in order, with its
table and key — so `changes_since()` reads it and the pull turns those keys into rows. The
alternative was a `sync_seq` column and a bump trigger on each of eleven tables plus a tombstone
table for the deletions: three new mechanisms to keep in step, where this is one function over a
record that is already tested and already relied upon.

The service holds no privilege on `audit_log` at all, so the function is `SECURITY DEFINER` and
hands back keys rather than entries: no actor, no label, no images. A feed, not the record.

The rows themselves are then read **under the caller's own actor**, so the policies decide what
comes back. The feed is a list of keys; the policies are what make it a list of the caller's keys.
One thing does leak: a `DELETE` has no row left to apply a policy to, so a deletion is passed on by
key alone. That is stated rather than papered over — a key is a number that names no person, and
the alternative is a device that never hears about a deleted lecture and shows it for ever.

### Two tables, and why so few

`synced_tables()` says `lectures` and `attendance`, and that is the whole list. Both are under
`FORCE ROW LEVEL SECURITY`, so every push is policed by the policies rather than by the service: a
professor pushing another department's lecture is refused by the database, and the refusal arrives
as something to report rather than a decision to make.

The other nine are refused outright. `courses`, `students` and `student_year` have no read
policies — V6's header says why at length — so accepting them would mean this service deciding who
may rewrite a roster, which is exactly what the design keeps out of Java; and pulling them would
realise a gap that currently has no endpoint to come through. The consequence worth knowing: a
brand-new install cannot populate itself from the server. It needs its roster, and the roster needs
the reachability policy that is still unwritten.

### The node check is the one rule that is not the database's

A row whose key the device issued has to carry that device's node, and nothing in SQL knows which
phone is holding the token. So the push checks it: a key from another node is refused, and so is a
key from before the ID scheme existed — those rows reach the server through the backup importer,
which can renumber them, and a sync push cannot. Attendance has no key of its own to check, and
needs none: its key is a lecture and a student, both issued elsewhere, and a CR marking a register
the professor created is the ordinary case.

### Idempotent in both directions

Every upsert is `INSERT … ON CONFLICT (primary key) DO UPDATE … WHERE the row is distinct from
what is being written`. The `WHERE` is the part worth noticing: without it an upsert always writes,
`updated_at` and `row_version` move, and the audit trigger records a change — so a device that
re-sent a batch because it never saw the response would put a "changed" line in the record against
every row in it, describing a change that did not happen.

A delete of a row that is not there is not an error. A pull is resumable from its watermark, and
the watermark only moves when every row in the page landed.

### What the phone needs and does not have

The transport is complete: a token in the Android Keystore, an install identifier, pinning declared
in `network_security_config.xml` so the platform applies it to every request rather than only the
ones that remembered the right socket factory, and a WorkManager job triggered by Room's
invalidation tracker whenever the outbox changes.

### Pointing a phone at a development server

A debug build and a release build get different resources, and that is the whole mechanism — no
flag to forget and no code that asks which build it is.

```
app/src/main/res/values/strings.xml      sync_base_url is EMPTY: no server
app/src/main/res/xml/…                   https only, pinned, placeholder pins
app/src/debug/res/values/strings.xml     http://10.0.2.2:8080/api/v1
app/src/debug/res/xml/…                  cleartext allowed, no pin-set
```

`10.0.2.2` is how the emulator reaches the machine it runs on. For a real device on the same
network, put that machine's address in the debug overlay instead:

```sh
ip route get 1 | awk '{print $7; exit}'      # e.g. 10.14.0.2
```

Then: start the database and the service, open **Sync with the server** on the dashboard, and sign
in. The screen shows the device's node once it has one and how many changes are waiting.

An empty `sync_base_url` in a release build is deliberate twice over. It means every release is a
local app until somebody configures one — `SyncClient.isConfigured()` is false, nothing is
scheduled, and the sync screen says so rather than offering a form with nowhere to send it. And it
disarms the placeholder pins, which would otherwise refuse every connection: setting the address
without setting real pins makes the first request fail loudly, which is the right order for those
two jobs to be done in.

Cleartext is permitted in the debug configuration only. The development server has no certificate,
pinning one it does not have is not possible, and giving it a self-signed one would mean a trust
anchor in the app for a key on somebody's laptop.

### The path a phone takes, by hand

Worth having, because it is the thing to try when the phone cannot sync and it tells you which
half is wrong:

```sh
INSTALL=$(python3 -c 'import uuid; print(uuid.uuid4())')

# 1. Sign in, with the install id. deviceNode comes back null for an install
#    the server has not seen, which is what makes SyncSignIn register next.
curl -s -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d "{\"email\":\"you@sies.edu.in\",\"door\":\"STAFF\",\"password\":\"…\",\"installId\":\"$INSTALL\"}"

# 2. Register it. The node comes back here, and signing in again returns it
#    rather than issuing another.
curl -s -X POST localhost:8080/api/v1/devices -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"installId\":\"$INSTALL\",\"provisionalNode\":41234,\"label\":\"Pixel 7a\"}"

# 3. Push a row, keyed under that node, and pull it back.
curl -s -X POST localhost:8080/api/v1/sync/push -H "Authorization: Bearer $TOKEN" \
  -H "X-Device-Install: $INSTALL" -H 'Content-Type: application/json' -d @batch.json
curl -s -H "Authorization: Bearer $TOKEN" -H "X-Device-Install: $INSTALL" \
  localhost:8080/api/v1/sync/pull
```

A key has to carry the device's node or the push refuses it, which is the one rule the database
cannot enforce: `(counter << 16) | node`, with the counter at least `1 << 39`.

**The airplane-mode gate has still not been run.** It needs a handset: there is now a sign-in
screen and a debug build that reaches a local server, so the test is runnable, but running it is
somebody holding a phone with the network off. What has been run is each half:
`SyncTest` pushes three lectures' marks over real HTTP and checks the rows, the replay, the forged
node, the policy refusal and the audit entries; `OutboxTriggerTest` and `DeviceSqlTest` exercise
the queue and the device's own statements against real SQLite. `tools/verify-sync.sh` runs both and
says plainly that the two have not been joined on a phone.

## What the device schema loses, and why

| Room | Here | Why |
|---|---|---|
| `startEpoch`, `endEpoch` on `academic_years` | dropped | Derived at whichever device wrote the row. `session_year_of(at, zone)` takes the zone as a parameter instead. |
| `"epochSecond\|nano\|zone"` text | `timestamptz` + a `_zone` column beside it | The instant is the instant; the zone is what it was recorded in, and both are needed. |
| `present INTEGER` 0/1 | `boolean` | |
| enum names in `TEXT` | native enum types | The values are the Java `name()` strings, in declaration order. |
| epoch-day integers | `date` | |

## Findings

Twenty-one things. The first four came out of the translation in Phase 2, the next three out of
building the importer, three more out of Phase 4, two out of Phase 5, four out of Phase 5.5 and
five out of Phase 6. All but one of the first seven are about the app rather than the schema; the rest are
about this schema and this service, which is what happens once they start making decisions of
their own.

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

**`FOR ALL` quietly includes `DELETE`.** The first draft of V6 gave `attendance_delegate` and
`user_role` a `FOR ALL` policy for the HOD and the admin, directly below a comment claiming
that nothing could delete a grant because no `DELETE` policy existed. Both statements were in
the same file and only one was true. `RowLevelSecurityTest.aGrantIsNeverDeleted` caught it on
the first run. Every policy on those two tables now names its verbs, and the comment explaining
why points at the test. The general lesson is narrower than "read your SQL": a permission
written as a shorthand covers whatever the shorthand covers, and a comment is not a constraint.

**A professor reaches their course's whole history, not just this session.** `course_instructor`
carries no academic year, so the instructor policy matches every lecture of a course the actor
teaches, in any session. That is almost certainly right — a professor answering a query about
last year's register needs to see last year's register — but it is worth stating because the
HOD policy next to it *is* session-scoped, and the asymmetry looks like an oversight until you
know it is not. The first version of the test asserted one row and got two; the test was wrong,
not the policy.

**`courses` and `student_year` have no policies, so any authenticated connection reads them.**
This is a real gap and it is left open deliberately rather than filled badly. "Which students
may this professor see" is not a department comparison: a minor or an open elective puts a
student from another department on the roll, which is the whole point of V4. The correct policy
is "students reachable through `roll_for_course` over the courses I instruct", which is a
set-returning function per row and will not survive a roster screen without a materialised
reachability table. Designing that is its own piece of work; guessing at it would either leak
the college's roster or break the roll. The gap is stated in V6's header as well, so it cannot
be discovered by surprise.

**Redacting before comparing hid the one change that mattered.** Written up under the audit
trail above, and worth repeating as a finding because the shape generalises: two correct rules
applied in the wrong order produced a third behaviour neither of them described. The no-op skip
was right, the redaction was right, and together-in-that-order they meant a password change was
never recorded. `aPasswordHashIsNeverWrittenToTheLog` caught it by asserting the entry existed
before asserting what was in it.

**`FOR ALL` was not the only shorthand that over-delivered.** V7's first `CHECK` said
`(action IN ('INSERT','UPDATE','DELETE')) = (table_name IS NOT NULL)` — an equality where an
implication was meant. A row change must name its table; everything else *may*, and an export
of attendance obviously wants to. The constraint forbade exactly the case the column exists
for. Same lesson as the `FOR ALL` finding one phase earlier: a condition written compactly
constrains whatever the compact form happens to constrain.

**A `LEFT JOIN` onto a table with policies is filtered, and silently.** Verifying a bearer token
joins the session to its device, and that query runs *before* there is an actor — working out
who the actor is, is what it is for. `device_registration` has policies, so the join matched
nothing, every column came back NULL, and under a `LEFT JOIN` that reads as a session with no
device at all: a device-bound session could never be verified, and `d.revoked_at IS NULL` was
just as true of a revoked device as of a live one. The same shape had already bitten once in the
same file, where an `UPDATE device_registration SET last_seen_at` would have matched no row and
quietly never written the column for the life of the service. Both now go through
`SECURITY DEFINER` functions — `device_session_binding()`, joined laterally, and `device_seen()`
— which is the same answer V6 gave for the policies that have to ask about `user_role`. The
lesson is narrower than "beware of RLS": a filtered join under an outer join does not look
filtered, it looks like absence, and absence was a valid state here.
`aTokenIsRefusedWhenReplayedFromAnotherDevice` caught it, because it asserted that the right
phone still worked rather than only that the wrong one did not.

**The roster CSV the app accepts is not quite the one the schema accepts.** Moving the parser
into `:shared` for the server to use made the difference visible: V2 has
`student_year_minor_not_major`, `_oe_not_major` and `_oe_not_minor`, and the app's importer never
checked any of them, because the forms cannot offer the same department twice and a CSV is the
only way to reach the case. A file naming Computer Science as both major and minor imported
cleanly on a phone and would have been refused by the server. The three checks now live in
`RosterCsv`, so both sides refuse the row for the same reason — and the app's message is the line
number rather than a constraint name.

**Announcing the actor on the way out attributes everything before it to nobody.**
`AccountDirectory.setPassword` wrote the new hash, cleared the failure counter, and *then*
announced the actor before recording `PASSWORD_ROTATE`. The event named the person and the
`UPDATE` entry beside it — the same change, in the same transaction — said `system`, because the
audit trigger reads the actor from the session at the moment the statement runs. Found by reading
the log of a by-hand run rather than by a test, which is why there is a test now
(`aPasswordChangeIsAttributedOnItsRowEntryToo`). Two fixes: the actor is announced before the
writes rather than after, and the endpoint now changes the password on the *request's* own
transaction through `ActorDatabase`, so both entries carry the session, the device and the
address instead of a bare account id. The self-announcing version is still there for the caller
that has no request — an office clerk resetting somebody's password from a command line.

**`inet` renders as `127.0.0.1/32`.** Small, and the kind of thing to find out in a test rather
than in a report: `client_ip::text` on an audit entry is not the address somebody would compare
it to, and `host(client_ip)` is. Worth knowing before writing the first query over
`audit_log.client_ip`, which is otherwise a query that returns nothing and looks like a logging
bug.

**The tenth migration broke a sort nobody had looked at.** `verify-schema.sh`, `dev-database.sh`
and the test harness all applied migrations in the order the shell or `Files.list` gave them, which
is lexicographic — so `V10__sync.sql` ran first, before `V2`, and failed with *type audit_action
does not exist*. That message reads like a broken migration, and the broken thing was the sort. Nine
migrations had hidden it; Flyway itself was right all along, because it orders by version. All three
now do too.

**A Room column with no default makes a trigger's insert fail, and takes the write with it.**
`outbox.attempts` was `INTEGER NOT NULL` — Room does not give a primitive a default unless it is
asked — and the triggers insert the table, the key and the operation and nothing else. So the
insert violated `NOT NULL`, and because a trigger runs inside the statement that fired it, *every
write in the app* failed. The first draft of the outbox made the app unable to save attendance at
all. `OutboxTriggerTest` found it on its first run, which is the whole argument for testing the
triggers against real SQLite rather than reading them.

**`cond ? Double.valueOf(x) : Long.valueOf(y)` is a `Double`.** Java's conditional operator applies
binary numeric promotion to its branches, so the type of that expression is `double` and the result
is boxed as a `Double` — which meant every integer in a parsed sync message came back as one, and a
55-bit structured key came back rounded. A key one away from the one the phone issued is a different
lecture. The test that found it asserted on a key that is not exactly representable; one that used a
round number would have passed.

**An upsert that always writes turns a replay into a recorded change.** `ON CONFLICT DO UPDATE`
with `updated_at = now()` moves the row every time, and a row that changed is a row the audit
trigger records — so a phone re-sending a batch because it never saw the response would put a
"changed" line in the record against every row in it, describing a change that did not happen. The
fix is a `WHERE ROW(…) IS DISTINCT FROM ROW(EXCLUDED.…)` on the update, which makes a replay touch
nothing at all.

**Two SQLite drivers disagree about `getObject`.** Android's cursor answers a `Long` where
sqlite-jdbc answers an `Integer` for the same column, so a row read by what the driver guessed is a
row whose keys may not compare equal to the ones the server sends back. Columns are read by what
they *are* — `DeviceSql.readsAsText`, over `DeviceTables.Kind` — rather than by asking the cursor.

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

## The first administrator

Nothing else can create one. A migration cannot, because a migration is a committed file and a
committed file with a password in it is a password everybody with the repository has. The
service cannot, because granting `ADMIN` needs `ADMIN` and the first grant has nobody to make
it. So it is a command, run by somebody on the machine, connecting as the migration role:

```bash
SIES_ADMIN_PASSWORD=... ./gradlew :server:bootRun \
    --args='--spring.profiles.active=bootstrap \
            --bootstrap.email=admin@sies.edu.in \
            --bootstrap.name="A Administrator"'
```

It refuses to run a second time. A second administrator is granted by the first, through the
ordinary path, where the grant has an author. The account starts with `must_rotate` set, so
whoever typed the first password is not also the person who ends up knowing it.

The password is never an argument — arguments are readable by every process on the machine
through `/proc` and are kept in shell history — so it comes from the environment or from a
terminal prompt, exactly as the importer's does.

## The importer now needs an account

`attendance` and `lectures` are forced, which not even `sies_owner` bypasses, so an import with
no actor fails on its first lecture:

```
lectures: the row with natural key (courseID=…, startDateTime=…) was refused by the
database: ERROR: new row violates row-level security policy for table "lectures"
```

The resolution step cannot see what is already there, so it inserts, and `WITH CHECK` refuses
the insert. The whole transaction rolls back and `import_run` records the reason — which is the
right failure, and better than the silent one an earlier draft of this file predicted.

Pass `--import.account=N` with the id of an account holding `ADMIN`. The command warns when it
is missing rather than refusing to start, because a connection that is exempt from the policies
— a superuser, which is what the tests use — legitimately needs no actor.

The imported rows still carry no author. That gap is deliberate and does not change: the device
schema records none, and inventing plausible ones would be worse than an honest hole. What the
account establishes is that somebody is accountable for having loaded them, and `import_run` is
where that is recorded.

## Not here yet

**The sync pair.** The write path that drains a device's outbox and the pull that answers
"everything since this watermark" are not here, and that is the same reasoning that kept every
endpoint out until Phase 5.5 — an endpoint written before its caller exists is an endpoint whose
shape is a guess. The difference is that the four that do exist had their inputs settled already
by what was built: `AccountDirectory` decided what a login takes, `StructuredId` decided what a
device registration takes, and `PgExportQueryBuilder`'s columns were already being compared cell
for cell by the differ. The outbox's shape is not settled by anything yet, so the sync pair waits
for Phase 6.

**No Spring Security.** Still `spring-security-crypto` and not the starter. There is one
credential, checked in one place, and no authorization decision is taken in Java at all: V6's
policies and V8's functions decide what an actor may see and do, inside the database, where a
forgotten `WHERE` clause cannot get round them. A filter chain configured with matchers and
roles would be a second, weaker statement of the same rules somewhere that does not enforce
them.

**The three audit actions that now have writers.** `EXPORT`, `CSV_IMPORT` and `DEVICE_REGISTER`
were vocabulary with nothing to say them until this phase; the export, the roster and the device
registration write them now. What is still unwritten is anything to do with sync, for the reason
above.

**`courses` and `student_year` still have no read policies.** The export and the roster both work
around that gap in the service, through `exportable_courses()` and `actor_may_manage_roster()`,
and both are written in SQL so that the day those tables get policies there is one rule to move
rather than two to reconcile. The gap itself is unchanged and is described in V6's header.

The part of Phase 3 that code cannot finish is the data. The gate asks for every device's
backup to be imported, the reconciliation to pass, and the reference exports from Phase 0 to
reproduce from the server byte for byte — and Phase 0, which is collecting those backups and
exports, has not happened. `tools/verify-import.sh` is as far as this goes without them.
