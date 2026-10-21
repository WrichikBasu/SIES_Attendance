# server

The PostgreSQL schema and the Spring Boot service behind SIES Attendance. Phones sync with it,
staff use its web console, and every rule about who may read or write which row lives in the
database as row-level security, not in Java.

```
server/
  src/main/java/.../server/
    web/          HTTP endpoints, the bearer-token filter, per-request database actor
    security/     passwords, accounts, sessions, roles, CR appointments, audit events
    sync/         push and pull for phones
    importer/     importing a phone's encrypted .siesbak backup
    sql/          the export and statistics query builders
  src/main/resources/
    application*.yml                   configuration; every credential comes from the environment
    db/migration/V*.sql                the schema, applied in order by Flyway
    static/console.html                the web console
  deploy/         systemd units and example environment files
  tools/          scripts for a dev database, the dev server and the verification suites
```

Code shared with the app (enums, `AcademicRules`, the table descriptions in `DeviceTables`, the
app's schema migrations, the backup format) lives in the `:shared` module at the repository root.

Requirements: Java 25, Docker, and the Gradle wrapper.

## Development

```sh
./server/tools/dev-database.sh             # roles and an empty database in Docker (port 55432)
eval "$(./server/tools/dev-database.sh --env)"
./server/tools/dev-serve.sh                # starts the service; Flyway applies the migrations
./server/tools/dev-serve.sh --check        # what is running, its routes, and whether it is stale
./server/tools/dev-database.sh --reset     # throw the database away
```

- **Run `eval` and the service in the same shell.** Without the variables the service refuses to
  start and names the one that is missing.
- **Use `dev-serve.sh`, not `bootRun`.** If port 8080 is taken, `bootRun` fails but leaves the old
  process serving, and that process looks healthy. A 404 from an endpoint you have just written
  almost always means an old server is still answering.
- **Restart after rebuilding.** Static pages are served straight from disk, but the classes are
  loaded once at startup, so a rebuilt page can end up talking to an old controller.
  `--check` reports `STALE` when that has happened.
- **Leave the database empty and let Flyway fill it.** `baseline-on-migrate` is off, so a schema
  applied by hand is refused.

### Database roles

| Role | Used by | May |
|---|---|---|
| `sies_owner` | Flyway, the importer, the bootstrap command | own every object and run DDL |
| `sies_app` | the service | read and write rows, subject to every policy; no DDL; nothing on `audit_log` |
| `sies_auditor` | reporting | read, including the audit log |

None is a superuser or has `BYPASSRLS`. Details are in `tools/roles.sql`.

### Configuration

| Variable | Default | |
|---|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME` | `localhost`, `55432`, `sies` | |
| `DB_APP_USER`, `DB_APP_PASSWORD` | `sies_app`, none | the service's connection |
| `DB_OWNER_USER`, `DB_OWNER_PASSWORD` | `sies_owner`, none | migrations, import, bootstrap |
| `SERVER_PORT` | `8080` | |
| `FORWARD_HEADERS` | `none` | set to `framework` only behind a proxy that overwrites `X-Forwarded-For` |
| `LOGIN_ATTEMPTS` | `10` | sign-in attempts per address per five minutes |
| `SQL_LOG_LEVEL` | `INFO` | |

### Migrations

Never edit a migration that has been applied anywhere; Flyway's checksum check will refuse to
start. A correction is always a new `V<n>__….sql`.

## Tests

```sh
./gradlew test :app:testDebugUnitTest      # everything: app, shared and server
python3 app/src/test/sql/sql_differ.py     # app and server SQL return identical rows

./server/tools/verify-schema.sh            # migrations plus constraint smoke test, throwaway container
./server/tools/verify-import.sh            # the importer against real backups
./server/tools/verify-rls.sh               # the policies, as a role they apply to
./server/tools/verify-audit.sh             # the audit trail, and that nobody can edit it
./server/tools/verify-wire.sh              # the whole service over HTTP
./server/tools/verify-sync.sh              # both halves of sync
```

The database tests need Docker. Tests that touch row-level security connect as a non-superuser,
because a superuser bypasses every policy. `PG_IMAGE` overrides the PostgreSQL image.

The Gradle build may reuse earlier test results. Add `--rerun` to the test task when you need a
fresh run.

## Production

`dev-serve.sh` is for a laptop only: it compiles the working tree, exposes `/actuator/mappings`,
and migrates on start.

```sh
./gradlew :server:bootJar                  # build/libs/sies-attendance.jar → /opt/sies-attendance/

install -D -m 644 server/deploy/sies-attendance.service /etc/systemd/system/sies-attendance.service
install -D -m 600 server/deploy/sies-attendance.env.example /etc/sies-attendance/env
install -D -m 600 server/deploy/migrate-env.example      /etc/sies-attendance/migrate-env
# fill in DB_APP_PASSWORD in env, and DB_OWNER_PASSWORD and PGPASSWORD in migrate-env

# Migrate as a deliberate step, never on service start:
set -a; . /etc/sies-attendance/migrate-env; set +a
java -jar /opt/sies-attendance/sies-attendance.jar --spring.profiles.active=migrate

systemctl daemon-reload
systemctl enable --now sies-attendance
systemctl enable --now sies-audit-partitions.timer
```

- **Two environment files.** The service holds only the `sies_app` password, and its unit sets
  `SPRING_FLYWAY_ENABLED=false`, so the long-running process can never change the schema. The
  migrate profile prints what it applied and exits non-zero on failure.
- **`sies-audit-partitions.timer`** creates audit-log partitions monthly. If it doesn't run, rows
  land in `audit_log_overflow` and nothing else complains.
- **`TimeoutStopSec=60`** has to exceed Spring's 30-second graceful shutdown; `DeploymentTest`
  checks it.

Still needed before phones can use production:
- TLS with the certificate pinned in the app's `res/xml/network_security_config.xml`
- a tested backup and restore (`pg_dump` and WAL)
- a different login rate limiter if more than one instance runs: `LoginRateLimiter` counts in memory

## The first administrator

Only a command on the machine can create the first one, and it refuses to run a second time.
Later administrators are granted from the console.

```sh
SIES_ADMIN_PASSWORD=... ./gradlew :server:bootRun \
    --args='--spring.profiles.active=bootstrap \
            --bootstrap.email=admin@sies.edu.in --bootstrap.name="A Administrator"'
```

**Change the password first.** Every new account must change its password before it can do
anything. Until then, a session can only change the password or sign out: a phone signs in and
syncs nothing, and the console shows only the password form. Change it in the console, on the
phone's Account and sync screen, or over HTTP:

```sh
BASE=http://localhost:8080/api/v1
read -rsp 'current password: ' CURRENT; echo
read -rsp 'new password (12 or more): ' FRESH; echo

TOKEN=$(curl -sS --fail-with-body -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
    -d "$(python3 -c 'import json,sys; print(json.dumps({"email": sys.argv[1], "door": "STAFF", "password": sys.argv[2]}))' \
          admin@sies.edu.in "$CURRENT")" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')

curl -sS --fail-with-body -X POST "$BASE/auth/password" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d "$(python3 -c 'import json,sys; print(json.dumps({"currentPassword": sys.argv[1], "newPassword": sys.argv[2]}))' \
          "$CURRENT" "$FRESH")"
```

Changing a password signs out every *other* session of the account.

**A lost administrator password** can't be reset from the console; nobody may set another
account's password. Revoke the grant on the database host, then run the bootstrap command again
with a new address:

```sh
docker exec sies-pg psql -U postgres -d sies -c \
  "UPDATE user_role SET revoked_at = now(), revoked_by = account_id WHERE role = 'ADMIN' AND revoked_at IS NULL"
```

## Who may do what

Everything below is enforced in SQL. `GET /api/v1/me` tells the phone and the console what to
offer, but whatever they offer is still checked row by row. An account's permissions are the
union of its kind, its roles (`HOD`, `OFFICE`, `AUDITOR`, `ADMIN`) and its CR appointments.

| | student (CR) | professor | head of department | office | auditor | admin |
|---|---|---|---|---|---|---|
| sign in on the phone | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| sign in on the web console | — | ✓ | ✓ | ✓ | ✓ | ✓ |
| take and edit a register | courses they represent | their courses | the department's | — | — | ✓ |
| read students | their courses' rolls | their courses' rolls | the department's | all | all | all |
| add or remove students and enrolments | — | — | the department's | ✓ | — | ✓ |
| add or correct a professor | — | — | — | ✓ | — | ✓ |
| add or withdraw a course | — | — | the department's | ✓ | — | ✓ |
| open a new academic session | — | — | — | — | — | ✓ |
| correct or remove a session | — | — | — | ✓ | — | ✓ |
| create, edit, delete batches; link them to courses | — | any course | any course | — | — | — |
| read batches | those linked to their courses | all | all | all | all | all |
| load a roster CSV | — | — | — | ✓ | — | ✓ |
| appoint a CR | — | their courses | the department's | — | — | ✓ |
| create accounts, grant or revoke roles | — | — | — | — | — | ✓ |
| read the audit trail | their own actions | their department's teaching | their department's | their own actions | all | all |
| export a register | courses they represent | their courses | the department's | all | all | all |

- **A student account can do nothing until someone appoints it CR** of a course
  (`attendance_delegate`). Appointments are per course and per session.
- **A HOD grant names a department and, optionally, a session.** With no session it lasts until
  revoked.
- **Nothing is deleted to make room.** Every foreign key is `RESTRICT`. Roles and appointments
  are revoked, never deleted, and accounts cannot be removed.
- **Nobody can alter the audit log**, the service included.

### A course's roll

`course_admits()` (V23) is the single definition, and both `roll_for_course()` and visibility use
it:

- **A course with batches linked for the session:** its roll is the members of those batches, from
  any department, year or stream.
- **A course with no batch linked for that session:** the course-type rules. Major, DSE and
  their practicals take the offering department's majors in that year and stream. Minors take
  its minors or majors. An OE takes students whose OE department it is. AEC, IKS, VSC and SEC take
  the whole year and stream.

The app uses the same rule in `DaoStudent.getRollForCourseSync` and both export builders. Change
all of them together; `PostgresSchemaDriftTest` and the SQL differ check that they agree.

A CR can read the rolls of the courses they represent, anyone marked on those courses' lectures,
and themselves.

## API

Everything is under `/api/v1` and needs `Authorization: Bearer <token>`, except signing in and
`/actuator/health`. A session tied to a phone also needs `X-Device-Install: <install id>`.
Errors are JSON: `{"code": …, "message": …}`.

```
POST /auth/login                          email, door (STAFF | STUDENT), password, optional installId
POST /auth/password                       currentPassword, newPassword
POST /auth/logout
GET  /me                                  who you are and what to offer you
POST /devices                             register an install, get its node number

POST /sync/push                           the phone's queued changes
GET  /sync/pull?since=&limit=             a snapshot, or changes since the watermark

GET  /export                              a register, as JSON rows
POST /roster                              a roster CSV (office, admin)
GET  /data, /data/{table}?after=&limit=   browse tables, under your own policies
GET  /audit?limit=&beforeAt=&beforeId=    the audit trail, as far as you may read it

GET  /accounts                            accounts with their live roles and appointments
GET  /accounts/options, /accounts/people?kind=, /accounts/sessions, /accounts/courses
POST /accounts                            create an account (optionally with a role, or a new professor)
POST /accounts/{id}/roles                 grant a role
POST /accounts/{id}/roles/{grantId}/revoke

GET  /courses/{id}/roll?academicYear=     a course's roll, for appointing a CR
POST /courses/{id}/representatives        appoint a CR
POST /courses/{id}/representatives/revoke
```

Every refused sign-in gets the same response, whatever the reason. After five failures in a row
an account is locked, and each address gets `LOGIN_ATTEMPTS` tries per five minutes.

## The web console

`/console.html` holds the Accounts, Data, Representatives and Audit trail panels. Only the staff
sign-in is accepted, so students cannot use it. The page shows only the panels `/me` says the
account can use, and refreshes the visible panel every ten seconds. The old URLs
`/accounts.html` and `/data.html` redirect to it.

The audit trail shows each entry as a sentence ("Marked R RAO absent in CS-101 on Tue 14 Jul 2025,
09:00"), built by `AuditMessages` from the entry's row images. Names are looked up by
`AuditNames` as the reader, so a sentence names nothing the reader couldn't otherwise see.
**Details** on each row shows the raw action, table, key and the before and after images.

## Sync

- **On the phone,** triggers on every synced table queue each change in an outbox, and the app
  pushes it, then pulls.
- **Pull:** the first pull is a snapshot of everything the account may read, sent over several
  pages. After that, the server sends changes since the watermark, read from the audit log, and
  every row is read under the caller's policies.
- **Push:** applied by primary key, and safe to repeat. A row the phone created must carry the
  phone's node in its key.
- **Signing in as a different account** resets the watermark, so the next pull is a fresh snapshot.
- **Signing out** sends the queue (asking before discarding anything unsent), then empties the
  phone's synced tables.

### Pointing a phone at a dev server

The debug build uses `app/src/debug/res/values/strings.xml`, where `sync_base_url` is
`http://10.0.2.2:8080/api/v1` (the emulator's address for the host) and cleartext is allowed. For a
real phone, put this machine's Wi-Fi address there instead:

```sh
ip route get <phone's IP> | grep -oP 'src \K\S+'
```

Don't use `ip route get 1`: with a VPN connected it returns the VPN's address. Release builds have
an empty `sync_base_url`, so the app runs entirely locally until a server is configured.

To test the phone's path by hand, use `-sS --fail-with-body`. With `-s` alone, a server that isn't
running prints nothing, and the next command fails with a confusing JSON error.

```sh
curl -sS --fail-with-body localhost:8080/actuator/health
curl -sS --fail-with-body -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"you@sies.edu.in","door":"STAFF","password":"…","installId":"<uuid>"}'
curl -sS -X POST localhost:8080/api/v1/devices -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"installId":"<uuid>","label":"Pixel 7a"}'
curl -sS -H "Authorization: Bearer $TOKEN" -H "X-Device-Install: <uuid>" localhost:8080/api/v1/sync/pull
```

## Importing a phone's backup

```sh
./server/tools/dev-database.sh
eval "$(./server/tools/dev-database.sh --env)"
./gradlew :server:sampleBackup                 # a sample, if there is no real backup to hand

export SIES_BACKUP_PASSWORD='the backup password'
./gradlew :server:bootRun --args="--spring.profiles.active=import \
    --import.file=$PWD/server/build/sample/sample-device.siesbak \
    --import.label='Dr Rao phone' --import.node=41231 --import.account=1 \
    --import.dry-run=true"
```

Drop `--import.dry-run` to keep the result. `--import.history=true` lists past runs.

- **`--import.account`** is the admin running the import (`select account_id from app_account`).
  Without it the import fails on the first lecture.
- **`--import.node`** is that phone's node number, shown on its diagnostics screen and stored in
  `device_registration.node`. Use the same number every time you import that phone; node 0 is
  refused.
- **Give an absolute path.** Relative paths resolve against `server/`.
- **Never put the password on the command line.** It comes from `SIES_BACKUP_PASSWORD`, a prompt,
  or the variable named by `--import.password-env`.

Each import runs in one transaction and its report is stored in `import_run`. Rows are matched by
key, then by natural key (roll number, course code and so on), and inserted only if neither
matches. Importing the same unchanged backup again changes nothing. A row that breaks a constraint
stops the import: fix the data, not the constraint.
