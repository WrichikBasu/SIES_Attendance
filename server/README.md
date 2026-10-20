# server

The PostgreSQL schema and the Spring Boot service the attendance database is moving to.

Nothing here touches the app's data. The migrations describe a database that does not exist
yet; the two query builders are translations of the app's own, checked by running both and
comparing the rows; the importer reads a device's own encrypted backup and folds it in,
resolving every row before it writes one.

The service answers thirteen endpoints under `/api/v1` — sign in, change a password, sign out,
register a device, export a register, load a roster, push a device's changes, pull what it has not
seen, and five for accounts and the roles on them — and a bearer token is the only way to reach any
of them. There is one web page, for account administration, and it is a static file that uses those
same endpoints. The app is behind one interface
now, keeps Room as its cache, and queues every local change in an outbox its own database fills
with triggers. Every table a device holds crosses the wire, and which rows may be read or written
is decided by row-level security rather than by anything in Java.

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
    web/AccountsController.java         accounts, and the roles on them
    web/DataController.java             reading the data, under the caller's policies
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
    security/Roles.java                 granting and revoking, never deleting
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
    db/migration/V11__roster_reachability.sql  policies on the other nine tables
    db/migration/V12__account_administration.sql  who may create an account
    db/migration/V13__provisional_node_push.sql   the node a device used before it had one
  src/main/resources/static/
    accounts.html                       the administration page, no build step
    data.html                           reading the data, read-only
  src/test/java/.../sql/SqlDumpTest.java         writes every statement for the differ
  src/test/java/.../importer/BackupImporterTest.java   14 tests, against a real database
  src/test/java/.../importer/DeviceBackup.java   builds a genuine .siesbak
  src/test/java/.../importer/SampleBackup.java   one to try the importer on
  src/test/java/.../security/RowLevelSecurityTest.java   28 tests, as a constrained role
  src/test/java/.../security/AccountDirectoryTest.java   15 tests, the credential layer
  src/test/java/.../audit/AuditLogTest.java      15 tests, as a role that may not tidy up
  src/test/java/.../web/WireTest.java            35 tests, the whole service over HTTP
  src/test/java/.../web/LoginRateLimiterTest.java   6 tests, no container needed
  src/test/java/.../web/SyncTest.java            29 tests, the server half of sync
  src/test/java/.../importer/OutboxTriggerTest.java   11 tests, against real SQLite
  src/test/java/.../importer/DeviceSqlTest.java  5 tests, the phone's own statements
  src/test/java/.../importer/PullApplyTest.java  4 tests, every table into a device database
  src/test/java/.../testing/TestPostgres.java    the throwaway container, shared
  src/test/sql/constraint_smoke_test.sql    52 assertions, 35 of them rejections
  tools/verify-schema.sh                a throwaway container, checked and removed
  tools/verify-import.sh                the importer, against a real PostgreSQL
  tools/verify-rls.sh                   the policies, as a role they apply to
  tools/verify-audit.sh                 the trail, and that nobody can edit it
  tools/verify-wire.sh                  the endpoints, over real HTTP
  tools/verify-sync.sh                  both halves of sync, as far as a JVM reaches
  tools/dev-database.sh                 a local database that survives a restart
  tools/dev-serve.sh                    the service, and proof it is this build
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
./server/tools/dev-serve.sh               # Flyway applies the migrations on first start
./server/tools/dev-serve.sh --check       # what is answering, and which routes it has
./server/tools/dev-database.sh --reset    # throw it away
```

`dev-serve.sh` rather than `bootRun` directly, for a reason learned twice the hard way. A
`bootRun` whose port is taken fails with `Port 8080 was already in use` and **leaves the old
process serving** — which answers `/actuator/health` exactly as a new one would, serves the new
static pages straight off disk, and 404s every endpoint added since it started. A stale server is
therefore indistinguishable from a fresh one by the check everybody reaches for first. The script
refuses to start when the port is busy, and `--check` asks the running process for its route table
instead of asking whether it is alive. **A 404 from an endpoint you have just written is almost
always this.**

There is a second half to that trap, and it cost another evening. Spring serves
`src/main/resources/static` off the classpath **directory**, read per request, while the classes
beside it were loaded once at startup. So a rebuild without a restart leaves today's page talking
to an older controller — and the route table cannot tell you, because the routes did not change.
What it looked like: a department dropdown of blank options, because the page asked for a label
the running build had never heard of, and `textContent` takes a nullable string, so `undefined`
becomes `null` becomes `""`. `--check` now compares the compiled classes against the moment the
process started and says `STALE` with the pid to kill. The pages are written to degrade rather
than go blank — they accept either shape and fall back to the stored value — but the fix is a
restart. **A page that shows nothing where it should show a list is almost always this.**

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

## Running it in production

Not `dev-serve.sh`. That script compiles the working tree, exposes `/actuator/mappings` so it can
check itself, and applies whatever migrations are on disk at startup — right for a laptop, wrong
for a database holding a term of attendance.

```sh
./gradlew :server:bootJar                 # build/libs/sies-attendance.jar

install -D -m 644 server/deploy/sies-attendance.service \
    /etc/systemd/system/sies-attendance.service
install -D -m 600 -o root -g root server/deploy/sies-attendance.env.example \
    /etc/sies-attendance/env              # then fill in DB_APP_PASSWORD
install -D -m 600 -o root -g root server/deploy/migrate-env.example \
    /etc/sies-attendance/migrate-env      # then fill in DB_OWNER_PASSWORD and PGPASSWORD

# The schema, as a step with a person behind it:
set -a; . /etc/sies-attendance/migrate-env; set +a
java -jar /opt/sies-attendance/sies-attendance.jar --spring.profiles.active=migrate

systemctl daemon-reload
systemctl enable --now sies-attendance
systemctl enable --now sies-audit-partitions.timer
```

**Two environment files, because the point of three database roles is that the long-running
process cannot alter the schema.** The service's file carries `DB_APP_PASSWORD` and the unit sets
`SPRING_FLYWAY_ENABLED=false`, so the service never connects as the owner; the migration step and
the partition job read the other file, which has the owner's password in it. With Flyway left on,
every service process would hold a credential that can run DDL on every attendance record in the
college, and a schema change would happen at whatever moment somebody restarted something.
`SiesAttendanceServer.missingCredentials` knows this split — it does not ask the `migrate` or
`bootstrap` profiles for the app role's password, and does not ask the service for the owner's.

**`--spring.profiles.active=migrate`** has no web server, so it cannot take the port the service
wants, and it reports rather than only exiting zero: *"Applied 2: 14 something, 15 something else.
The schema is at 15."* A failed migration is a failed startup with Flyway's own message and a
non-zero exit, which is what a deployment script needs. See `MigrateCommand`.

**`sies-audit-partitions.timer`** calls `audit_log_add_months(…, 12)` monthly, `Persistent=true` so
a machine that was off on the 1st catches up at boot. V7 created two years ahead and left the
function idempotent for exactly this; nothing called it until now. The failure mode of nobody
running it is silent — rows land in `audit_log_overflow`, which exists so an insert is never lost,
so there is no second symptom.

**`TimeoutStopSec=60`** exceeds Spring's 30-second shutdown phase on purpose. `server.shutdown` is
`graceful`, so SIGTERM makes Tomcat stop accepting connections and finish what is in flight; a
systemd timeout shorter than that phase would SIGKILL the process in the middle of the very
requests the graceful shutdown was waiting for. `DeploymentTest` asserts the inequality, because
nothing else would notice it was wrong.

**`FORWARD_HEADERS=framework`** is set in the unit and is only correct behind a proxy that
overwrites `X-Forwarded-For`. It is what puts the caller's own address in `audit_log.client_ip` and
in the login rate limiter's buckets; left at `none` behind a proxy, every request appears to come
from the proxy — one bucket for the college, and an audit trail naming the load balancer. The
default is `none` because the alternative is trusting a header any client can send.

What this does **not** include, and what still has to be true before a phone can reach it: TLS with
the certificate the app pins in `res/xml/network_security_config.xml`, a `pg_dump` and WAL
schedule with a restore somebody has actually run, and a second instance's worth of thought about
`LoginRateLimiter`, which counts in memory per process.

Checked by hand against the dev database, from the jar: the migrate profile reports and exits 0,
the service starts with no owner password in its environment and no Flyway in its log,
`/actuator/mappings` answers 404 while `/actuator/health` answers UP, `/api/v1/accounts` asks for
a token, and SIGTERM produces *"Commencing graceful shutdown … Graceful shutdown complete"*
followed by Hikari draining and the process exiting.

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
    --import.account=1 \
    --import.dry-run=true"
```

Then drop `--import.dry-run` to keep it. `--import.history=true` lists every run.

All four lines of that matter, and three of them are easy to leave off:

* **The `eval` and the `gradlew` have to be in the same shell.** A variable exported in one
  shell does not reach the next one, and without `DB_OWNER_PASSWORD` the run fails with
  `FATAL: password authentication failed for user "sies_owner"` — which reads as a wrong
  password and is not one. The service refuses to start without the variables now and says
  which; see the finding.
* **`--import.account` is the administrator doing the import.** Without it the run dies on the
  first lecture, because `lectures` and `attendance` are FORCEd and an import with no actor
  cannot satisfy their `WITH CHECK`. `select account_id from app_account` has the number.
* **`--import.node` is this phone, not the example's.** 41231 is the sample device's. A real
  phone's assigned node is in `device_registration.node`, which is also the number the app's
  diagnostics screen shows, and it has to be the same number every time that phone is imported
  — `id_remap` is keyed by it.

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

**Appointing one is an endpoint now**, and until it was, nothing in the service could write that
row at all — which made a student account a login that sees its own enrolment and stops. A phone
signed in to one and asked to sync, and nothing came down; nothing was wrong with the sync. A
student's reach *is* its delegations.

```
GET  /api/v1/accounts/courses                              the courses you may appoint for
POST /api/v1/accounts/{id}/delegations                     courseId, academicYear, reason
POST /api/v1/accounts/{id}/delegations/revoke              courseId, academicYear
```

The one pair of endpoints here that is **not** administrators only, because appointing the
representative of a course is the instructor's business before it is anybody else's.
`Delegates.capacityFor` asks the database which of the three capacities the caller actually holds
— instructor, head of the offering department for that session, administrator, in that order —
and stamps it on the grant; a caller holding none gets a 403 saying who may. The two refusals the
trigger would otherwise return as `check_violation` are checked first so they can be said
properly: *"That student is not on this course's roll for 2025, so they cannot be its class
representative. The roll comes from their enrolment, which is the office's."*

Re-appointing after a revocation revives the row rather than adding one, because the key is the
student, the course and the session. The history is not lost: the audit trigger records the UPDATE
with both images.

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

### Eleven tables, and who may write each

`synced_tables()` is every table a device holds, in dependency order. It was two until V11, and the
reason it was two is worth keeping: a table with no policies would have meant this service deciding
who may rewrite a roster, in Java, which is what the design keeps out of Java. V11 wrote those
policies, so the list could widen without the service gaining a single decision.

| Table | Read | Write |
|---|---|---|
| `academic_years` | anyone signed in | the office |
| `professors` | anyone signed in | the office; a professor may amend their own row |
| `students` | reachable students, or your own row | the office |
| `student_year` | the same reachability | the office |
| `courses` | the coordinator, instructors, the head, a CR | the coordinator, instructors, the head, the office |
| `course_instructor` | whoever may read the course | whoever may write it |
| `batch_desc` | anyone signed in | any professor |
| `batch_student_map` | when the student is readable | a professor, for a student they can reach |
| `course_batch_map` | whoever may read the course | whoever may write it |
| `lectures` | the instructor, the head, a CR, the office | the instructor, the head, a CR (not delete) |
| `attendance` | through its lecture | through its lecture |

"Reachable" is the predicate V6 would not guess at: a student is reachable when the actor heads
their major department in that session, or instructs, represents or heads the offering department
of a course the student's enrolment is on the roll of. That is `roll_for_course()` inverted, and
V11's header says why it has to be written twice.

What this changes in practice: a new install can populate itself — the calendar, the staff list,
its own courses, its own roll — which is what the pull was for. And a professor's roster changes
stay on their phone: the push is refused, the refusal says the roll is loaded by the office, and
attendance keeps syncing. That refusal is final and the device drops the intent, which is the
difference between a queue that empties and one that does not.

### The node check is the one rule that is not the database's

A row whose key the device issued has to carry that device's node, and nothing in SQL knows which
phone is holding the token. So the push checks it — but only when the row is **new**. Before V11 a
device only ever pushed rows it had made itself and the distinction did not arise; now that it
pulls a roster, a professor amending a course that came from the server is pushing a key some other
node issued, and refusing that would make the pull pointless. So a key from another node is
accepted when the row is already there, and whether it may then be changed is the policies'
decision. Two phones belonging to one professor is the ordinary case and it works.

A key from before the ID scheme existed is still refused outright: those rows reach the server
through the backup importer, which can renumber them, and a sync push cannot. Tables with no
surrogate key of their own are not checked and need no check — their keys are other rows' keys.

### Idempotent in both directions

Every upsert is `INSERT … ON CONFLICT (primary key) DO UPDATE … WHERE the row is distinct from
what is being written`. The `WHERE` is the part worth noticing: without it an upsert always writes,
`updated_at` and `row_version` move, and the audit trigger records a change — so a device that
re-sent a batch because it never saw the response would put a "changed" line in the record against
every row in it, describing a change that did not happen.

A delete of a row that is not there is not an error. A pull is resumable from its watermark, and
the watermark only moves when every row in the page landed.

A first pull is a snapshot rather than a replay of the log, and since V11 a snapshot rarely fits in
one page: a college roster is thousands of rows. So it is resumable too. When it fills a page it
hands back a watermark naming the table and key it got to, **carrying the head it started from**,
and the next pull continues from there and finishes on that same head. Keeping the original head is
the whole trick — a head taken at the end of each page would skip every change made while the
pages in between were being sent. The watermark is opaque to the device by contract, which is what
let this be added without teaching the phone anything.

### What the phone needs and does not have

The transport is complete: a token in the Android Keystore, an install identifier, pinning declared
in `network_security_config.xml` so the platform applies it to every request rather than only the
ones that remembered the right socket factory, and a WorkManager job triggered by Room's
invalidation tracker whenever the outbox changes.

### A register needs a session, and nothing else does

A build that has a server asks for a sign-in before the first register can be marked: attendance
is attributed on the server to the account that sent it, and collecting marks that the server will
later refuse is worse than asking once. `SignInGuard` is the only place that decides it, and the
rule is a conjunction — **a server is configured** and **this device has no session** — so a
release build with no `sync_base_url` is not blocked, because there would be nothing to sign in
to and the app would simply stop working.

It is not a connectivity check and must never become one. A session is a token on the device;
having one is the whole question. Marking a register in a basement with no signal works exactly as
it did, which is the requirement the design is built around.

What that costs, stated plainly: if a device's session is revoked or expires and the server
answers 401, the app forgets the token, and that professor cannot mark another register until they
sign in again. A 401 means the server was reachable, so signing in is possible at that moment —
but it does mean revoking a device mid-term stops it taking attendance, which is a real
consequence of making attribution a precondition rather than a hope.

Not gated: importing an attendance CSV, restoring a backup, and editing a register that already
exists. Those are corrections and recoveries rather than new observations, and a device that has
lost its session should still be able to hand its history over.

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
network, put that machine's **wifi** address in the debug overlay instead. Ask for the address this
machine would answer the phone from, rather than for "its address", which it has several of:

```sh
ip route get 192.168.0.42 | grep -oP 'src \K\S+'    # the phone's address; prints e.g. 192.168.0.10
```

`ip route get 1` is the wrong question and this file told you to ask it for a while: it names the
address on the default route, which is the VPN interface whenever a VPN is connected, and nothing
on the wifi can reach that. If a phone that is plainly on the same network cannot connect, a VPN on
this machine is the first thing to suspect — some clients route or drop local traffic, and a
split-tunnel or local-network exception is the setting to look for.

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

Use `-sS --fail-with-body`, not `-s`. With plain `-s` a server that is not running produces no
output at all and no error, and the next command in the pipeline gets an empty string: a
`json.decoder.JSONDecodeError: Expecting value: line 1 column 1` from a command that pipes into
`python3 -c` almost always means the service was down, not that anything was refused. An actual
refusal is JSON and says so: `{"code":"sign_in_refused", …}` with a 401.

```sh
INSTALL=$(python3 -c 'import uuid; print(uuid.uuid4())')

# 0. Is it even up? Do this first, every time.
curl -sS --fail-with-body localhost:8080/actuator/health

# 1. Sign in, with the install id. deviceNode comes back null for an install
#    the server has not seen, which is what makes SyncSignIn register next.
#    -w prints the status, because a body alone cannot tell you a 401 from a 200.
curl -sS --fail-with-body -w '\nHTTP %{http_code}\n' \
  -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
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

Thirty-two things. The first four came out of the translation in Phase 2, the next three out of
building the importer, three more out of Phase 4, two out of Phase 5, four out of Phase 5.5, nine
out of Phase 6 — one of those found on a handset rather than by anything here — two from
building the administration pages, and three from importing a real phone's backup and then using
the server it filled. All but one of the first seven are about the app rather than the schema; the rest are
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

**A variable nobody exported is indistinguishable from a wrong password.** `application.yml`
writes the two database passwords as `${DB_APP_PASSWORD}` and `${DB_OWNER_PASSWORD}` with no
default, which is right: a development default is a service that quietly connects to something.
But Spring's binder passes a placeholder it cannot resolve through *unchanged*, so what Flyway
offers PostgreSQL is the literal twenty characters `${DB_OWNER_PASSWORD}`, and PostgreSQL answers
`FATAL: password authentication failed for user "sies_owner"` with SQLSTATE 28P01. Every instinct
that message provokes is wrong — the roles are intact, the container is the right one, the
password is the one it always was — because the shell running `./gradlew` simply was not the shell
`dev-database.sh --env` was eval'd into. `main` now refuses to start without the credentials and
names the ones it wants, before Spring builds a single bean; `StartupCredentialsTest` holds the
rules, including the two cases where a password is legitimately absent: the `bootstrap` profile,
which connects as the owner, and `spring.flyway.enabled=false`, which is how the tests run against
a schema `psql` has already applied.

**`INSERT OR REPLACE` is a delete, and a delete can be refused.** It is what Room's
`OnConflictStrategy.REPLACE` compiles to, so it looked like the right way to write a pulled row:
whatever is there, the server's version wins. REPLACE means *delete the row that is there, then
insert this one* — and every foreign key on the device is `RESTRICT`, so deleting a row the
device's own rows point at is refused. The phone could not accept the server's copy of a lecture
it had marks against: `FOREIGN KEY constraint failed (code 1811 SQLITE_CONSTRAINT_TRIGGER)`, the
1811 being the internal trigger RESTRICT is implemented with. The page rolled back, the watermark
never moved, every later page queued behind it, and the only thing the phone could say was "sync
failed" — the same shape as the `academic_years` jam, from a different cause. It appeared the
moment a device's backup was imported here, because the importer keeps a structured key, so the
next pull sent that phone its own lectures back. The pull now runs `UPDATE … WHERE` *key* first
and inserts only when that matched nothing, which changes the row in place and leaves the
children pointing at it. SQLite counts a row the `WHERE` matched as changed even when every
value is identical, so "matched nothing" means "is not here" and nothing else; `ON CONFLICT … DO
UPDATE` would say it in one statement and cannot be used, because it arrived in SQLite 3.24 and
the app supports API 26. The three tables that are nothing but their key get `INSERT OR IGNORE`
instead, since there is no column to set. `PullApplyTest` now runs with `PRAGMA foreign_keys =
ON`, which is the actual reason it did not catch this: the fixture had them off, and with them
off REPLACE is harmless.

**A key a double holds exactly still does not survive a browser.** The administration page sent
`studentId: Number(person)`, and creating a student account answered
`{"code":"server_error","message":"Something went wrong at the server."}` — five times, each one
taking an `app_account` id with it. Nothing had gone wrong at the server. `370666965485748224` is a
59-bit `StructuredId`; JavaScript has one numeric type, and the double happens to hold that value
*exactly*, so the usual test for this — does the number come back unchanged — passes. The loss is
in the printing: `String(370666965485748224)` is `"370666965485748200"`, because JavaScript prints
the shortest decimal that identifies a double, and the neighbouring doubles up there are 64 apart.
Jackson then reads that as a different `long`, the foreign key refuses it, and the only thing the
page could say was that the server was broken. Three fixes, because all three were wrong: a
structured key now leaves these endpoints as **text** (`Person.id`, `AccountBody.profId` and
`studentId`) and is accepted as text, the page never puts a key through `Number()`, and
`AccountsController` checks the person is on file before inserting, so naming somebody the office
has not loaded yet is a 404 that says where students come from. `ApiAdvice` is the backstop: a
foreign key, unique, check, not-null or bad-input refusal is now a 409 or 400 saying as much,
instead of a 500 — the constraint is the schema working, and the only question was who got told.
The one place a key is still a JSON number is the login response, whose client parses longs
exactly; the page reads only the token and `mustRotate` from it. This is the same family as the
`Double`/`Long` finding above, and the family's lesson is now stated once: **a key is text
everywhere it meets a language that does not have a 64-bit integer.**

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

## Accounts, and the roles on them

Eight endpoints and one page. `/accounts.html`, served by the same service, is where the second
administrator, the office account, an auditor, a head of department and a class representative
come into being.

```
GET  /api/v1/accounts                                    who exists and what they hold
GET  /api/v1/accounts/options                            departments (stored name and label) and sessions
GET  /api/v1/accounts/people?kind=PROFESSOR|STUDENT      who an account can be attached to
POST /api/v1/accounts                                    an account, and optionally a role with it
POST /api/v1/accounts/{id}/roles                         a role
POST /api/v1/accounts/{id}/roles/{grantId}/revoke        the end of one
GET  /api/v1/accounts/courses                            the courses you may appoint a CR for
POST /api/v1/accounts/{id}/delegations                   a class representative
POST /api/v1/accounts/{id}/delegations/revoke            the end of one
```

An administrator and nobody else, and the rule is in SQL twice. Creating an account asks
`actor_may_manage_accounts()` from V12, because `app_account` has no policies — V6 says why, and
the short version is that a policy keyed on the current account would deny the lookup that
establishes it. Granting and revoking a role is refused by `user_role`'s own policies, which admit
only an admin; the endpoint asks the same function first so that a professor's attempt comes back
as a 403 that says what the matter is rather than as a database error.

**Why an admin and not the office**, when the office owns the roll: an account is not a record
about a person, it is an authority. A `PROFESSOR` account can mark every register of every course
that professor coordinates from the moment it exists, with no role grant at all, because
`lectures_instructor` asks about `course_instructor` and not about `user_role`. Creating one is
therefore closer to granting a role than to editing a roster. The argument for widening it is real
— onboarding is office work in most colleges — which is why it is one line in V12 rather than a
condition at three call sites, and why a test states the current answer rather than assuming it.

Three things the endpoints deliberately cannot do:

- **Set a password on another account.** A new account gets an opening password and `must_rotate`
  is true from the insert, so whoever typed it is not the person who ends up knowing it.
  `/auth/password` needs the current one. An administrator who could skip that could sign in as a
  professor and mark a register in their name.
- **Delete anything.** A role is revoked, never deleted; an account cannot be removed at all. Both
  are pointed at by the audit log, which is the record of what they did.
- **Create the first administrator.** That still needs `BootstrapCommand` and a shell on the
  machine. This is how the second one arrives.

The page holds the token in a variable and nothing else — not `localStorage`, which would outlive
the person walking away from the machine — and writes everything into the DOM through
`textContent`, because a display name is somebody's typed input. It also carries the password
rotation, which is what a new account has to do first and what previously needed two `curl` calls.
No framework and no build step: it calls the same endpoints the phone does.

What is not tested: the HTML and its script. Every endpoint behind it is covered by `WireTest`
over real HTTP, and a test asserts the page is served without a token and stores nothing — but no
browser has opened it in this environment, and no JavaScript engine is installed here to parse it.

## Reading the data in a browser

`/data.html`, and two endpoints behind it:

```
GET /api/v1/data                             the tables, with the rows you can see in each
GET /api/v1/data/{table}?after=&limit=       one page of one table
```

No authority rules of its own, which is the whole design. Every row comes back from a query run
under the caller's own actor, so the policies decide: a professor browsing `students` sees the
students on their own rolls, a head sees their department's, an auditor sees everybody's. A count
of zero means "none you may see", which is not the same as "none", and the page says so.

Readable tables are `synced_tables()` — the same function the sync pair reads, which since V11 is
every table a device holds. That is not a coincidence: those are exactly the tables that carry
policies, so they are exactly the tables it is safe to hand a generic reader. `app_account` is not
among them (no policies, and a password hash is not something to render in a grid) and neither is
`audit_log`, which the service holds no privilege on. A table name reaching SQL comes from that
list and never from the request.

Paging is by primary key, row-wise, the same mechanism the sync snapshot resumes with and for the
same reason: an offset is wrong as soon as a row is inserted behind you. Values are rendered as
text, provenance columns included — this is for reading, not a format for something to parse.

**Enums are shown as what they are called, not as what they are stored as.** A department column
holds `COMPUTER_SCIENCE`; on a timetable it is "CS", and `Department.toString()` is the app's own
answer to what to call it, so the page uses that and the phone and the browser cannot drift apart.
The labels travel *beside* the rows — `labels` on the page response, column by column — rather
than substituted into them: the rows stay exactly what the table holds, and the stored value is on
the cell as its tooltip, dotted so you know to look. A data browser that quietly replaced the data
would be the wrong trade for prettier reading. A value this build has no label for, which is what
a department added in SQL ahead of the app looks like, shows as the stored name.

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

### Rotating that password, which has to happen before anything else works

`must_rotate` is enforced at the filter, not merely suggested: until it is cleared, that session
reaches `/auth/password` and `/auth/logout` and **nothing else**. A phone will sign in, say so and
sync nothing; the data page will sign in and show no tables. So this is the first thing to do with
a new administrator, and with any account somebody else created.

**In a browser.** `/accounts.html` notices and shows the rotation form instead of the rest of the
page. This is the easy way and there is nothing else to know.

**On a phone.** The Server screen has a **Change password** button whenever it holds a session,
and says so in red when the account is under `must_rotate` — because a professor whose first
sign-in came with a password the office typed should not have to discover the rule by syncing and
failing. The phone asks for the current password and the new one twice: the server never sees the
third box, so without it a password typed differently in the two boxes would be set to whichever
was in the first one and discovered at the next sign-in. The refusals come back in the server's own
sentences, on the field they are about. See `FragmentSync.askForNewPassword`,
`SyncClient.changePassword` and `PasswordChange`, which is where the rules the phone can check
without a round trip are written down.

**Over HTTP.** Both calls have to be in *one* shell: `$TOKEN` set in one command and used in the
next is empty if the shell in between has gone, and the answer is then
`{"code":"unauthenticated","message":"This endpoint needs a bearer token."}`, which reads like a
server problem and is not.

```bash
BASE=http://localhost:8080/api/v1
read -rsp 'current password: ' CURRENT; echo
read -rsp 'new password (12 or more): ' FRESH; echo

TOKEN=$(curl -sS --fail-with-body -X POST "$BASE/auth/login" \
    -H 'Content-Type: application/json' \
    -d "$(python3 -c 'import json,sys; print(json.dumps({"email": sys.argv[1], "door": "STAFF", "password": sys.argv[2]}))' \
          admin@sies.edu.in "$CURRENT")" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')

curl -sS --fail-with-body -w '\nHTTP %{http_code}\n' -X POST "$BASE/auth/password" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d "$(python3 -c 'import json,sys; print(json.dumps({"currentPassword": sys.argv[1], "newPassword": sys.argv[2]}))' \
          "$CURRENT" "$FRESH")"
```

`read -rsp` rather than a literal, for the same reason the bootstrap takes none: a password in an
argument is in `/proc` and in shell history. The bodies are built by `python3` so that a password
containing a quote or a backslash is still valid JSON. `--fail-with-body` rather than `-s` alone:
with plain `-s` a server that is not running prints nothing at all, `json.load` is handed an empty
string, and the error you see is `JSONDecodeError: Expecting value: line 1 column 1` rather than
"connection refused".

What comes back is `{"otherSessionsRevoked":N}`. The change revokes every **other** session of that
account and leaves the one in your hand alone: somebody rotating a password because they think
somebody else has it has not finished until the other sign-ins are gone. The current password is
required even though the session is already authenticated — a token lifted from an unlocked phone
should not be enough to take the account over.

Three refusals worth telling apart:

| Answer | Meaning |
|---|---|
| `401 sign_in_refused` | the current password is wrong, or the account is locked |
| `400 weak_password` | twelve characters minimum, and the message says so |
| `400 incomplete` | one of the two fields was missing from the body |

**If the password is lost.** There is no endpoint for setting somebody else's password, deliberately:
an administrator who could skip the current-password check could sign in as a professor and mark a
register in their name. The way back is to revoke the `ADMIN` grant and bootstrap again, which is
possible only for somebody with a shell on the database host — the correct boundary for this
system:

```bash
docker exec sies-pg psql -U postgres -d sies \
    -c "UPDATE user_role SET revoked_at = now(), revoked_by = account_id
        WHERE role = 'ADMIN' AND revoked_at IS NULL"
```

Then run the bootstrap command above with a fresh address. The old account stays, disabled by
having no role, because the audit log names it as the author of things and deleting it would
orphan them.

Both halves leave a trace that says they happened outside the ordinary path, which is the point of
doing it this way rather than by editing a password hash:

```
UPDATE | user_role   | actor_account |             | actor_label | system      <- the revoke, by hand
INSERT | app_account | actor_account |             | actor_label | bootstrap   <- the replacement
INSERT | user_role   | actor_account |             | actor_label | bootstrap
```

No `actor_account` on any of them: nobody signed in to do it. `system` is what a statement run
straight against the database looks like, and `bootstrap` is the label that command announces
itself under — see "pre-migration, and why the actor is not null" above for why there is a label
at all rather than a null. A review that finds those three rows has found somebody with a shell on
the database host taking the college's administrator account, which is exactly what it was, and
exactly what should be visible.

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

**A way to add a professor was missing entirely, and a fresh server could not have one.** A
professor's account has to name a `professors` row, and nothing in this service could write one:
they arrived through a device backup import or by hand in `psql`. So a new server had no
professors, could have no professor accounts, and the administration page could only say "nobody
on file yet" — which is what it did when somebody tried. `POST /accounts` now takes an optional
`person` for a professor and writes both rows in one transaction, keyed by `ServerIds` under node
0 like every other row the server invents. Students are deliberately still not creatable that way:
a student's identity is their roll number, it lives on their enrolment, and the roster CSV is the
way in.

**The conditional operator unboxed a null, again.** `body.profId() == null && … ? createProfessor(…)
: body.profId()` has one `long` arm and one `Long` arm, so it promotes to `long` and throws on the
null it was testing for. Same family as the `Double`/`Long` promotion in `SyncJson` that rounded a
55-bit key, and the same fix: an `if`. Two existing tests caught it as a 500.

**A roster screen.** V11's reachability predicate is evaluated per row and nothing is
materialised, which is right for a dozen professors and a few thousand enrolments — an index scan
and a handful of role lookups. A screen that listed every student in the college would want the
materialised reachability table V6 imagined. That screen does not exist, and the predicate is in
one function when it is wanted.

**Locally created people.** A device that creates a professor or a student of its own and then
signs in cannot push them: the roll is the office's, and the staff list too. Worse, if that person
already exists on the server under a different key, nothing in a push can tell. That is what the
backup importer is for — it matches on natural keys and renumbers — and it is the reason Phase 7
is a shadow run rather than a flag flip.

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

**`courses` and `student_year` had no read policies, and writing them was the whole of V11.**
Until then the export and the roster worked around the gap in the service, through
`exportable_courses()` and `actor_may_manage_roster()` — both written in SQL precisely so that
there would be one rule to move rather than two to reconcile when the day came. It came, and that
is what the two functions bought: `actor_may_read_course()` is the first asked one course at a
time, and the roster policies are written in terms of the second.

**A policy refuses before a foreign key does, which nearly dropped attendance.** `attendance`'s
policies reach the mark's lecture to decide anything at all, so a mark pushed before its lecture
comes back as `insufficient_privilege` — "not yours to change" — and never reaches the foreign
key that would have said "not yet". The first version of the permanent/transient rule called every
policy refusal final, which would have dropped real marks out of a queue whose next batch would
have accepted them. A policy refusal is now final only when every row the intent points at is
already there. A test found it; nothing in the reading of it suggested it.

**A resumed snapshot skipped the table it resumed in.** The resume loop set "reached the table we
stopped in" and then `continue`d, so the remainder of that table was never read: a two-row page
delivered eight of nine rows and the ninth was lost for good. Caught by the test that pulls a whole
fixture two rows at a time and asserts every row arrives exactly once — which is the only shape of
test that could have caught it, since every page looked perfectly well-formed on its own.

**Every row a phone made before it registered was refused, and the device then dropped them
all.** The second defect a handset found, and the one that cost real data the longest. A phone
issues keys from installation, so before it registers it picks a node for itself out of the
self-picked half of the range and every row carries it. Registration assigns a real node — and V8
records the provisional one precisely so this is knowable — but the push compared the key's node
against the assigned node and nothing else. So a phone that had been taking attendance for a term
and was only now pointed at a server had *every* row refused, and because V11 made a policy-style
refusal final, the drain dropped the intents. The sync reported success and moved nothing.

Measured on the handset: a Samsung SM-M315F registered as node 1, provisional node 37498. One row
reached the server and it was `academic_years` — the one synced table whose key no device issues,
so the node check never applied to it. That single row is what made the diagnosis quick.

V13's `device_provisional_node()` is `SECURITY DEFINER`, because `device_registration`'s policies
admit the account that registered the device and that is usually but not always the account
pushing: a filtered-away row would read as "no provisional node", which is the
LEFT-JOIN-onto-an-RLS-table trap that cost this project a day in Phase 5.5. The push now accepts
either of the device's nodes, and another install's provisional node is still refused — both
stated as tests.

The dropped intents needed a way back at the time, since the triggers fire on a write and those
rows were written weeks ago: a statement that re-queued every row of every table, behind a **Send
everything again** button. **Both are gone now**, and deliberately. V13 removed the reason they
existed — the server accepts a pre-registration key, so the drain no longer drops the rows that
button was for — and what remained droppable is the rows that can *never* be pushed: a legacy key,
or a roster row that belongs to the office. Re-queueing those gets them refused again, which is a
button that cannot work. A queue filled by triggers on every write already holds everything that
has not reached the server, so "send everything again" was either a no-op or a lie. The way back
for a device whose own rows cannot sync is the backup importer and then taking the server's copy,
which is a different operation with a different confirmation.

**A device whose data predates the id scheme cannot sync any of it, and said so a hundred times
a minute.** The third thing a handset found. Keys issued before schema v7 were counted out from 1
by SQLite in each file independently, so they mean nothing off the device — the push has always
refused them, pointing at the backup importer, which can renumber. What it did not refuse was the
rows that <em>point at</em> them: `student_year` and `batch_student_map` have no key of their own,
so they got as far as the statement and came back as a foreign key violation. A missing parent is
ordinarily worth waiting for, so the device kept every one and retried on the next sync. For a
phone with a term's roster on it that is a hundred-odd rows refused for ever, with nothing in the
message to say that waiting was hopeless.

A refusal is now final when the parent is **both** missing and unpushable, with a sentence naming
the reference and pointing at the importer. Both halves matter: a parent that is present must not
be judged by the shape of its key, because a server whose rows came from a backup import or a
roster CSV has them keyed under node 0 and a device holding such a key got it from a pull. Stated
as two tests.

What this does not do is get that data onto the server. Nothing in sync can: that is what
"Importing a device" above is for, and doing it for a device that is already running is a cutover
rather than an import — the server renumbers those rows, so a later pull would bring them back
down beside the originals under different keys. Phase 7 is that work, done between sessions with a
frozen backup and a rollback, and it is deliberately not something a button does.

**And the phone had forgotten the number.** The other half of the same defect, found while fixing
it: `IdGenerator.assignNodeID` overwrote the self-picked node, and `SyncSignIn` only reported one
while it was still current. So a device that had registered could never say what its old keys
meant — and a server whose `device_registration` row is gone (a rebuilt development database, a
restore from before the registration) could never be told again. Three changes, because each
covers a case the others do not:

- `IdGenerator` keeps the provisional node in its own preference and never clears it, and reports
  it on every registration rather than only the first.
- For an install made before that preference existed, the number is still in the data: every row
  written back then carries it in the low sixteen bits of its key. `DeviceSql.selfPickedNodes()`
  reads it out, commonest first, over the tables with keys of their own.
- `DeviceRegistry` fills a blank `provisional_node` on a later registration and never overwrites
  a recorded one — a number reported while it was current is the better evidence, and the clash
  count stays as it was counted, against the devices that existed then.

**A pulled session could not be written at all, and it jammed every pull behind it.** Found by
running the app, which is the first defect in this project that a test did not find first.
`academic_years.startEpoch` and `endEpoch` are `NOT NULL` on the device and V1 deliberately
dropped them from the server — they are the session's dates resolved at whichever device's zone
happened to write the row, which is the bug this whole migration exists to remove. So a pulled
row arrived without them, `INSERT OR REPLACE` violated the constraint, the page could not be
applied, and because the watermark only moves when every row in a page lands, every later page was
stuck behind that one for ever. The device reported `sync failed`.

`DeviceDerived` now computes them from the dates that did travel, at the zone of the device doing
the computing and with the zone as a parameter rather than ambient — the same arithmetic
`EntityAcademicYear.setDates` uses, and a test holds the two to the second. `DeviceSql` names the
columns in the statement, because filling a map the statement never reads changes nothing.

Two tests close the class rather than the instance: `PullApplyTest` writes a server-shaped row for
**every** table in the feed into a real device database, and a case in `SyncTest` compares
PostgreSQL's own nullability against `DeviceTables` for every carried column — a column the server
may send as null and the device declares `NOT NULL` is the same defect wearing a different hat.

**The tenth migration broke a fourth lexicographic sort.** `sql_differ.py` applied migrations in
`sorted(os.listdir(...))` order, so V10 ran before V2 and failed on a type V7 creates. The same
trap had already been fixed in `verify-schema.sh`, `dev-database.sh` and `TestPostgres`, and this
was the fourth copy of it. Worth stating as a general lesson: a version number in a filename sorts
as text unless somebody makes it sort as a number, and it stays correct for exactly nine
migrations.

The part of Phase 3 that code cannot finish is the data. The gate asks for every device's
backup to be imported, the reconciliation to pass, and the reference exports from Phase 0 to
reproduce from the server byte for byte — and Phase 0, which is collecting those backups and
exports, has not happened. `tools/verify-import.sh` is as far as this goes without them.
