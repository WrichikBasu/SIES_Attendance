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
    security/Passwords.java             Argon2id, parameters inside the hash
    security/AccountDirectory.java      accounts, lockout, rotation, two doors
    security/ActorSession.java          who is acting, for one transaction
    security/BootstrapCommand.java      the first administrator, once
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
  src/test/java/.../sql/SqlDumpTest.java         writes every statement for the differ
  src/test/java/.../importer/BackupImporterTest.java   14 tests, against a real database
  src/test/java/.../importer/DeviceBackup.java   builds a genuine .siesbak
  src/test/java/.../importer/SampleBackup.java   one to try the importer on
  src/test/java/.../security/RowLevelSecurityTest.java   20 tests, as a constrained role
  src/test/java/.../security/AccountDirectoryTest.java   15 tests, the credential layer
  src/test/java/.../testing/TestPostgres.java    the throwaway container, shared
  src/test/sql/constraint_smoke_test.sql    39 assertions, 25 of them rejections
  tools/verify-schema.sh                a throwaway container, checked and removed
  tools/verify-import.sh                the importer, against a real PostgreSQL
  tools/verify-rls.sh                   the policies, as a role they apply to
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

## What the device schema loses, and why

| Room | Here | Why |
|---|---|---|
| `startEpoch`, `endEpoch` on `academic_years` | dropped | Derived at whichever device wrote the row. `session_year_of(at, zone)` takes the zone as a parameter instead. |
| `"epochSecond\|nano\|zone"` text | `timestamptz` + a `_zone` column beside it | The instant is the instant; the zone is what it was recorded in, and both are needed. |
| `present INTEGER` 0/1 | `boolean` | |
| enum names in `TEXT` | native enum types | The values are the Java `name()` strings, in declaration order. |
| epoch-day integers | `date` | |

## Findings

Ten things. The first four came out of the translation in Phase 2, the next three out of
building the importer, and the last three out of Phase 4. All but one of the first seven are
about the app rather than the schema; all three of the last are about this schema, which is
what happens once it starts making decisions of its own.

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

No API and no audit log. There is now an identity to authenticate and policies to enforce,
but still no endpoint, and that is on purpose: the thing that would call one is the Android
client in Phase 6, and an endpoint written before its caller exists is an endpoint whose shape
is a guess. So Phase 4 delivered the parts that can be finished and tested without it — the
accounts, the lockout, the rotation, the grants, the policies — and stopped short of a token
format and a filter chain with nothing behind them. `spring-security-crypto` is on the
classpath rather than `spring-boot-starter-security` for the same reason: the starter installs
a servlet filter chain, and there is nothing yet to filter.

The audit log is Phase 5. Note one thing it inherits from here: its actor column has to point
at `app_account` and not at `professors`, because a CR is a student and a log that cannot name
the person who marked the register is not evidence.

The part of Phase 3 that code cannot finish is the data. The gate asks for every device's
backup to be imported, the reconciliation to pass, and the reference exports from Phase 0 to
reproduce from the server byte for byte — and Phase 0, which is collecting those backups and
exports, has not happened. `tools/verify-import.sh` is as far as this goes without them.
