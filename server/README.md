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
./server/tools/verify-backup.sh            # a backup restores to an identical database (needs age)
./server/tools/verify-pitr.sh              # recovery to a chosen moment (needs age)
```

The database tests need Docker. Tests that touch row-level security connect as a non-superuser,
because a superuser bypasses every policy. `PG_IMAGE` overrides the PostgreSQL image.

The Gradle build may reuse earlier test results. Add `--rerun` to the test task when you need a
fresh run.

## Production

`dev-serve.sh` is for a laptop only: it compiles the working tree, exposes `/actuator/mappings`,
and migrates on start.

### The database, in Docker

`dev-database.sh` is no use here either: its passwords are fixed and printed. On the server, run
the container by hand, once:

```sh
# The superuser's password, in a file only root reads, not on the command line or in
# `docker inspect`.
install -d -m 700 /etc/sies-attendance
openssl rand -hex 24 > /etc/sies-attendance/postgres-password
chmod 600 /etc/sies-attendance/postgres-password

docker volume create sies-pgdata
docker run -d --name sies-pg --restart unless-stopped \
    -e POSTGRES_PASSWORD_FILE=/run/secrets/postgres-password \
    -e POSTGRES_DB=sies \
    -e TZ=UTC \
    -v /etc/sies-attendance/postgres-password:/run/secrets/postgres-password:ro \
    -v sies-pgdata:/var/lib/postgresql \
    -p 127.0.0.1:5432:5432 \
    postgres:18

# Wait for the database itself; pg_isready answers before POSTGRES_DB exists.
until docker exec sies-pg psql -U postgres -d sies -tAc 'SELECT 1' >/dev/null 2>&1; do sleep 1; done
```

Then the three roles, before anything else touches the database (`tools/roles.sql` explains why the
order matters). The passwords are hex so they need no quoting, and go in through stdin so they don't
show up in the process list:

```sh
OWNER=$(openssl rand -hex 24); APP=$(openssl rand -hex 24); AUDITOR=$(openssl rand -hex 24)

{ printf '\\set owner_password %s\n\\set app_password %s\n\\set auditor_password %s\n' \
      "$OWNER" "$APP" "$AUDITOR"
  cat server/tools/roles.sql
} | docker exec -i sies-pg psql -U postgres -d sies -q -v ON_ERROR_STOP=1 -f -

printf 'sies_owner   %s\nsies_app     %s\nsies_auditor %s\n' "$OWNER" "$APP" "$AUDITOR"
unset OWNER APP AUDITOR
```

Put `sies_app`'s password in `env` and `sies_owner`'s in `migrate-env` (twice, as
`DB_OWNER_PASSWORD` and `PGPASSWORD`) in the next step, and keep all three in the college's
password manager. Leave the database empty: the migrate step creates the schema.

- **`postgres:18`, mounted at `/var/lib/postgresql`.** That is the image the tests use. Version 18
  keeps its data in `/var/lib/postgresql/18/docker`, so a volume at the older
  `/var/lib/postgresql/data` saves nothing and the container exits with an empty log.
- **`127.0.0.1` only.** A port Docker publishes on every interface skips the host firewall (`ufw`
  included). The service, the migrate step and the partition job all reach it on `localhost:5432`.
- **The partition job runs the host's `psql`,** so install a client there (Debian and Ubuntu:
  `postgresql-client`).
- **The units say `After=postgresql.service`,** which Docker doesn't provide. Order them after
  Docker instead, with `systemctl edit sies-attendance` (and the same for
  `sies-audit-partitions`):

  ```ini
  [Unit]
  After=docker.service
  Wants=docker.service
  ```
- **A shell on it:** `docker exec -it sies-pg psql -U postgres -d sies`. That is the superuser, and
  it ignores every policy, so use it for repairs and not to look at data.

### The service

```sh
./gradlew :server:bootJar                  # build/libs/sies-attendance.jar → /opt/sies-attendance/

useradd --system --no-create-home --shell /usr/sbin/nologin sies   # both units run as it

install -D -m 644 server/deploy/sies-attendance.service /etc/systemd/system/sies-attendance.service
install -D -m 644 server/deploy/sies-audit-partitions.service /etc/systemd/system/sies-audit-partitions.service
install -D -m 644 server/deploy/sies-audit-partitions.timer   /etc/systemd/system/sies-audit-partitions.timer
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
- a different login rate limiter if more than one instance runs: `LoginRateLimiter` counts in memory

### Backups

There are two kinds of backup. Both are encrypted with [age](https://age-encryption.org) to the
same public key, written to the same directory, and restored with the same private key. They
answer different questions, and you need both.

| | **Dumps** | **Point-in-time (base backups + WAL)** |
|---|---|---|
| What it is | `pg_dump`: the database as SQL-level data | `pg_basebackup`: a copy of the database files, plus every change since (the WAL) |
| Brings back | the database as it was at 02:30 on one night | the database as it was at **any second** in the last 7 days |
| Taken | nightly, about 02:30 (`sies-backup.timer`) | base backup nightly at about 03:00 (`sies-basebackup.timer`); WAL every minute (`sies-wal-ship.timer`) |
| Kept on the server | newest 14, plus the first of each of the last 12 months | the last 7 base backups and the WAL after the oldest of them |
| Files | one self-contained file per night | a set: a base backup reaches no further than the night it was taken without the WAL after it, and WAL is useless without a base backup before it |
| Restores into | any PostgreSQL 18 or later, on any machine | PostgreSQL 18 only, on the same kind of CPU (x86-64 to x86-64) |
| Roles and passwords | not included: recreated from the password manager | included: the whole cluster comes back as it was |
| Restored with | `tools/restore-backup.sh` | `tools/pitr-restore.sh` |

Point-in-time recovery is the one for "someone deleted a register at 11:40": go back to 11:39,
and keep the rest of the morning's attendance. The dumps cover what it can't: anything older than
a week, a move to a new PostgreSQL version or another kind of machine, and a last resort if the
WAL chain is broken.

#### What is in the backup directory

```
/var/lib/sies-attendance-backups/            root's, mode 700
  sies-20261106T023012Z.dump.age             a dump: restore on its own
  base/
    base-20261106T030411Z.tar.gz.age         a base backup
    base-20261106T030411Z.start              the WAL file replay starts from (a file name, not secret)
  wal/
    000000010000000000000042.gz.age          WAL segments, one every few minutes
    00000002.history.gz.age                  ...and timeline files, after a recovery
  .lock  .wal.lock  .base.lock               locks: ignore
  *.partial                                  a run in progress or killed: ignore
```

Two other places hold backup data and must **not** be copied off the machine:
`wal-staging/` inside the database container's volume, which is WAL waiting for the shipper
(unencrypted, and gone within a minute), and the private key, which is never on the server at all.

#### What to copy off the machine

A backup on the same disk as the database doesn't survive that disk. **Copy the whole directory**,
`/var/lib/sies-attendance-backups/`, except the locks and `.partial` files, to at least one other
machine, **every five minutes**. That one copy covers both kinds. The dumps only change nightly,
but WAL arrives all day, and only WAL that has left the machine survives losing it. Everything in
the directory is encrypted, so the destination doesn't need to be trusted with the data.

Pull from the other machine rather than push from the server, so the server holds no credentials
for wherever the copies end up:

```sh
# on the other machine, in cron: */5 * * * *
rsync -a --ignore-existing --exclude='.*lock' --exclude='*.partial' --rsync-path='sudo rsync' \
    admin@server:/var/lib/sies-attendance-backups/ ~/sies-backups/
```

- **`admin` needs `sudo rsync` without a password** (a one-line sudoers rule), because the
  directory is root's.
- **`--ignore-existing`, never `--delete`.** The copy keeps what the server has rotated away, so a
  wiped or compromised server can't empty the copies too. Prune the copy yourself. Delete old dumps
  freely. Delete a base backup's `.tar.gz.age` and `.start` together, and WAL only if it's older
  than the oldest `.start` you still keep. Or let it grow: a quiet day's WAL is under 10 MB.
- **Keep `base/` and `wal/` together.** `pitr-restore.sh` needs both: the base backup to start
  from, and every WAL file after it to reach the moment you ask for.

Keep these elsewhere, not with the backups:

| | where | needed for |
|---|---|---|
| the private key (`sies-backup-key.txt`) | password manager, plus one copy offline | every restore. Stored next to the backups, it would undo the encryption |
| the three role passwords | password manager | restoring a dump (point-in-time brings the roles back itself) |
| `/etc/sies-attendance/env` and `migrate-env` | password manager | rebuilding the service on a new machine |

#### Which to restore

| What happened | Restore | From |
|---|---|---|
| Data deleted or changed by mistake, in the last 7 days | point-in-time, `--to` just before it | the server's own directory |
| The server or its disk is gone | point-in-time `--to latest`, which loses at most the last few minutes. If that fails, the newest dump, which loses everything since 02:30 | the off-machine copy |
| Something older than 7 days | the dump from that night, or that month | the off-machine copy, if the server has rotated it away |
| A new machine with a newer PostgreSQL, or another kind of CPU | a dump | either |
| Checking the backups work | `--check` on both, now and then | either |

Every restore goes into a new, empty database or container, never over the live one. Look at
the result first, then swap it in. Both scripts print the steps.

#### The key

Make it on your own computer, not on the server, and keep the private half off the server.
Without it no backup can be read, so keep a second copy somewhere safe:

```sh
age-keygen -o sies-backup-key.txt          # the private key: password manager, and a copy offline
age-keygen -y sies-backup-key.txt          # prints the public key, age1...
```

#### Setting up the dumps

```sh
sudo apt install age                       # Debian and Ubuntu

# The public key(s), one per line. A second line (a spare key kept in a safe) means either can decrypt.
echo 'age1...' | sudo install -D -m 644 /dev/stdin /etc/sies-attendance/backup-recipients

sudo install -D -m 755 server/deploy/sies-backup.sh      /opt/sies-attendance/sies-backup.sh
sudo install -D -m 644 server/deploy/sies-backup.service /etc/systemd/system/sies-backup.service
sudo install -D -m 644 server/deploy/sies-backup.timer   /etc/systemd/system/sies-backup.timer
sudo systemctl daemon-reload
sudo systemctl enable --now sies-backup.timer

sudo systemctl start sies-backup && journalctl -u sies-backup -n 20   # take one now
```

- **It runs as root, through `docker exec`, as the database superuser.** `attendance` and
  `lectures` are under `FORCE ROW LEVEL SECURITY`, so no other role can read every row, and
  `pg_dump` refuses to write a partial copy. It needs no password.
- **Nothing is written in plaintext.** The dump streams into age. A run that fails leaves no file.
- **Retention and location** are set in `deploy/backup-env.example`.

#### Setting up point-in-time recovery

After the dumps, since it uses the same key and `age`:

```sh
sudo install -D -m 755 server/deploy/sies-wal-archiving.sh /opt/sies-attendance/sies-wal-archiving.sh
sudo install -D -m 755 server/deploy/sies-wal-ship.sh      /opt/sies-attendance/sies-wal-ship.sh
sudo install -D -m 755 server/deploy/sies-basebackup.sh    /opt/sies-attendance/sies-basebackup.sh
for unit in sies-wal-ship.service sies-wal-ship.timer sies-basebackup.service sies-basebackup.timer; do
    sudo install -D -m 644 server/deploy/$unit /etc/systemd/system/$unit
done
sudo systemctl daemon-reload

sudo /opt/sies-attendance/sies-wal-archiving.sh enable     # restarts sies-pg: a few seconds' outage
sudo systemctl enable --now sies-wal-ship.timer sies-basebackup.timer
sudo systemctl start sies-basebackup                       # the first base backup; recovery starts here

sudo /opt/sies-attendance/sies-wal-archiving.sh status     # any time
```

- **How WAL gets out.** PostgreSQL hands every finished WAL segment to a hook, which stages it
  in the container's volume. A quiet database closes a segment at least every 5 minutes
  (`archive_timeout`). Every minute the shipper gzips and encrypts what is staged into `wal/`,
  then deletes the staged copy. Each nightly base backup deletes the base backups beyond
  `KEEP_BASE` (7), and the WAL only they needed.
- **Watch `status`.** If staging stops working, PostgreSQL keeps every segment it couldn't archive
  in `pg_wal` and retries. Nothing is lost, but the disk fills, and then the database stops.
  `failed` and `waiting` show it starting long before then. The same goes for `staged` if the
  shipper stops.
- **Staged WAL is plaintext,** in the database's own volume next to the database files, which
  are plaintext too. Everything in the backup directory is encrypted.

#### Restoring a dump

On this machine or any other. Use `--check` first: it restores into a throwaway container,
prints every table's row count and the latest migration, and removes the container:

```sh
./server/tools/restore-backup.sh --identity sies-backup-key.txt sies-20261106T023012Z.dump.age --check
```

To move to a new machine, or replace a lost database: start the container as in
[The database, in Docker](#the-database-in-docker), skip the roles step, then:

```sh
./server/tools/restore-backup.sh --identity sies-backup-key.txt sies-...dump.age --into sies-pg
```

It asks for the three role passwords (or reads `DB_OWNER_PASSWORD`, `DB_APP_PASSWORD` and
`DB_AUDITOR_PASSWORD`), and refuses a database that already has tables. The restore runs in one
transaction. Then carry on from [The service](#the-service): install the jar and units, write the
environment files, and run the migrate step. It applies only migrations newer than the backup.

#### Restoring to a moment

Point `--from` at a directory holding `base/` and `wal/`: the server's own directory, or the
off-machine copy. Find the right moment with `--check`, which replays into a throwaway
container, prints where it stopped and every table's row count, and removes the container:

```sh
./server/tools/pitr-restore.sh --identity sies-backup-key.txt \
    --from /var/lib/sies-attendance-backups --to '2026-11-06 11:39 +05:30' --check
```

Recovery stops just before the first transaction that committed after `--to`. Always give a
zone. `--to latest` replays everything that was shipped. With `--into NAME` instead of `--check`,
it leaves the result running in a new container `NAME` on a new volume `NAME-data`. It touches
nothing that already exists, and prints the steps to make that copy the live database.

- **It refuses rather than guess.** A moment before the oldest base backup, a gap in the WAL, a
  moment later than the last shipped transaction, or the wrong key: each one stops it with an
  error. It never brings up a database that silently stops short.
- **The restored copy has archiving off,** so it can't write into the archive it came from.
  Once it's live, run `sies-wal-archiving.sh enable` and take a base backup straight away. The
  recovery started a new timeline, and the old timeline's WAL after that moment doesn't replay
  onto it.

### Updating

The service doesn't run from the checkout, so `git pull` and a restart change nothing. It runs
copies: the jar in `/opt/sies-attendance/`, the units in `/etc/systemd/system/`, and the
environment files in `/etc/sies-attendance/`. See what a pull brought in, then do only the steps
that apply:

```sh
git diff --stat ORIG_HEAD HEAD -- server/

# Java code or resources changed (application*.yml and console.html included):
./gradlew :server:bootJar
sudo install -m 644 server/build/libs/sies-attendance.jar /opt/sies-attendance/sies-attendance.jar

# A new migration in src/main/resources/db/migration/, after copying the jar and before
# restarting:
sudo bash -c 'set -a; . /etc/sies-attendance/migrate-env; set +a
    java -jar /opt/sies-attendance/sies-attendance.jar --spring.profiles.active=migrate'

# A unit in deploy/ changed (copy whichever did):
sudo install -m 644 server/deploy/sies-attendance.service /etc/systemd/system/
sudo install -m 755 server/deploy/sies-*.sh /opt/sies-attendance/        # the backup scripts
sudo systemctl daemon-reload

sudo systemctl restart sies-attendance
curl -fsS http://localhost:8080/actuator/health && echo " ok"
```

- **The service never migrates itself.** A new jar against an old schema can fail at startup or on
  the first request that needs the new column.
- **Never copy an `.example` over `/etc/sies-attendance/env` or `migrate-env`.** They hold the
  passwords. A new variable in an example is added to the real file by hand; the service names any
  that is missing when it starts.
- **Logs are in the journal**, not in a file: `journalctl -u sies-attendance -n 100`, or `-f` to
  follow.

## The first administrator

Only a command on the machine can create the first one, and it refuses to run a second time.
Later administrators are granted from the console.

```sh
SIES_ADMIN_PASSWORD=... ./gradlew :server:bootRun \
    --args='--spring.profiles.active=bootstrap \
            --bootstrap.email=admin@sies.edu.in --bootstrap.name="A Administrator"'
```

**In production**, run the installed jar instead, with the owner's credentials from
`migrate-env`, as root since that file is mode 600:

```sh
set -a; . /etc/sies-attendance/migrate-env; set +a
java -jar /opt/sies-attendance/sies-attendance.jar --spring.profiles.active=bootstrap \
    --bootstrap.email=admin@sies.edu.in --bootstrap.name="A Administrator"
```

- **Leave `SIES_ADMIN_PASSWORD` unset** and the command prompts for the password without echoing
  it, which keeps it out of shell history.
- **The service can stay running.** The bootstrap profile starts no web server, so this is a
  short-lived process beside it, connecting as `sies_owner`.
- **Run the migrate step first.** The bootstrap profile has Flyway enabled, so against an
  unmigrated database it applies the pending migrations before creating the account.

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

**A forgotten password** is reset by an administrator, from the account's row in the console.
The server makes up a temporary password and shows it once. The person signs in with it and has
to choose their own straight away. The reset also clears any lock and signs the account out
everywhere. An administrator can't reset their own password; another administrator does it.

**An administrator with no other administrator to ask** is reset on the server, with the
bootstrap command. The password comes from the environment or a prompt, never an argument, and
must be changed at the next sign-in:

```sh
SIES_ADMIN_PASSWORD=... ./gradlew :server:bootRun \
    --args='--spring.profiles.active=bootstrap --bootstrap.reset=admin@sies.edu.in'
```

In production, as above, with the installed jar and `migrate-env`:

```sh
set -a; . /etc/sies-attendance/migrate-env; set +a
java -jar /opt/sies-attendance/sies-attendance.jar --spring.profiles.active=bootstrap \
    --bootstrap.reset=admin@sies.edu.in
```

## Who may do what

Everything below is enforced in SQL. `GET /api/v1/me` tells the phone and the console what to
offer, but whatever they offer is still checked row by row. An account's permissions are the
union of its kind, its roles (`HOD`, `OFFICE`, `AUDITOR`, `ADMIN`) and its CR appointments.

| | student (CR) | professor                                                                                              | head of department | office | auditor | admin |
|---|---|--------------------------------------------------------------------------------------------------------|---|---|---|---|
| sign in on the phone | ✓ | ✓                                                                                                      | ✓ | ✓ | ✓ | ✓ |
| sign in on the web console | — | ✓                                                                                                      | ✓ | ✓ | ✓ | ✓ |
| take and edit a register | courses they represent | their courses                                                                                          | the department's | — | — | ✓ |
| read students | their courses' rolls | the rolls of their department's courses, and every student with their department as major, minor or OE | the department's | all | all | all |
| add or correct students and enrolments | — | their own department                                                                                   | the department's | ✓ | — | ✓ |
| remove students | — | —                                                                                                      | the department's | ✓ | — | ✓ |
| add or correct a professor | — | —                                                                                                      | — | ✓ | — | ✓ |
| read professors | those teaching their courses | their own department's, and those teaching courses they read                                           | the same, and the departments they head | all | all | all |
| add a course | — | for their own department                                                                               | the department's | ✓ | — | ✓ |
| remove a course | — | —                                                                                                      | the department's | ✓ | — | ✓ |
| edit or discontinue a course, change who teaches it | — | every course their department offers                                                                   | the department's | ✓ | — | ✓ |
| add, change or withdraw an IKS or VEC course | — | —                                                                                                      | — | — | — | ✓ |
| open a new academic session | — | —                                                                                                      | — | — | — | ✓ |
| correct or remove a session | — | —                                                                                                      | — | ✓ | — | ✓ |
| create, edit, delete batches; link them to courses | — | their own department's                                                                                 | their own department's | — | — | ✓ |
| rename a batch and change its members | — | their own department's, and those serving only courses they coordinate                                 | the same | — | — | ✓ |
| read batches | those linked to their courses | their own department's, and those their courses use                                                    | the department's, and those their courses use | all | all | all |
| import students (CSV) | — | their own department                                                                                   | the department's | ✓ | — | ✓ |
| import attendance (CSV) | — | their courses                                                                                          | the department's | — | — | ✓ |
| appoint a CR | — | courses their department offers                                                                        | the department's | — | — | ✓ |
| create accounts, grant or revoke roles | — | —                                                                                                      | — | — | — | ✓ |
| import or export a backup (.siesbak) | — | —                                                                                                      | — | — | — | ✓ |
| read the audit trail | their own actions | their department's teaching                                                                            | their department's | their own actions | all | all |
| read courses, their lectures and registers | courses they represent | every course their department offers                                                                   | the department's | all | all | all |
| export a register | courses they represent | every course their department offers                                                                   | the department's | all | all | all |

- **A professor reads their whole department's teaching** (V53): every course it offers, who
  teaches it, its batches, its lectures and their registers, in every session. They may also
  edit those courses (V54): the details, the coordinator, who teaches them, discontinuing them,
  and the batches of an OE, AEC, IKS or VEC. They may add courses to their department too,
  with any coordinator (V55), but not remove one or move one to another department. The registers stay with the course's instructors and the head, and the
  phone shows them read-only to everybody else.
- **A student account can do nothing until someone appoints it CR** of a course
  (`attendance_delegate`). Appointments are per course and per session.
- **A HOD grant names a department and, optionally, a session.** With no session it lasts until
  revoked.
- **Nothing is deleted to make room.** Every foreign key is `RESTRICT`. Roles and appointments
  are revoked, never deleted, and accounts cannot be removed.
- **Nobody can alter the audit log**, the service included.
- **There is always an administrator.** An administrator cannot revoke their own ADMIN role,
  disable their own account or delete it; another administrator has to. To hand over, grant ADMIN
  to the new administrator first. Underneath that, the database refuses any change that would leave
  no live ADMIN grant on an enabled account (V43).
- **An account keeps its kind.** A professor's, student's or staff account stays one; the
  database refuses changing it (V44). Somebody who is now a different kind of person gets a new
  account, and the old one is disabled.
- **A course's coordinator arranges its batches,** whichever department made them, when every
  course a batch is linked to is one they coordinate (`courses.prof_id`). That covers renaming it
  and moving students in or out. Making, removing and linking a batch stay with its department
  (V42). Its other instructors read the batch and change nothing.

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
GET  /sync/keys?table=&after=&limit=      the keys of every row in a table the account may read

GET  /export                              a register, as JSON rows
POST /roster?year=                        a roster CSV (professors for their own department,
                                          heads for theirs, the office and admins for any)
POST /attendance/import?dryRun=           an attendance sheet: preview (true) or import (false)
GET  /import/formats                      both CSV formats, described, with examples
GET  /data, /data/{table}?after=&limit=   browse tables, under your own policies
GET  /audit?limit=&beforeAt=&beforeId=    the audit trail, as far as you may read it

GET  /accounts                            accounts with their live roles and appointments
GET  /accounts/options, /accounts/people?kind=, /accounts/sessions, /accounts/courses
POST /accounts                            create an account (optionally with a role, or a new professor)
POST /accounts/{id}/roles                 grant a role
POST /accounts/{id}/roles/{grantId}/revoke
POST /accounts/{id}/sessions/end           sign an account out everywhere (admin)
POST /accounts/{id}/password/reset         give an account a temporary password, and end its sessions (admin)
POST /accounts/{id}/disable                stop an account signing in, and end its sessions (admin)
POST /accounts/{id}/enable                 let a disabled account sign in again (admin)
DELETE /accounts/{id}                      delete an account that has never been used; anything else is disabled (admin)
POST /backup/import?label=&node=&dryRun=   import a .siesbak, the file as the body (admin)
POST /backup/export                        {"password"}: download everything a phone holds (admin)
GET  /progress/{id}                        how far your import or export sent with X-Progress-Id has got

GET  /batches/unplaced                     batches with no department yet, and their courses and majors (admin)
GET  /batches/linkable?academicYear=      every batch of a session, by name, department and size, for an OE/AEC/IKS/VEC course
POST /batches/{id}/department              {"department"}: place one, unlinking other departments' courses (admin)

GET  /courses/{id}/roll?academicYear=     a course's roll, for appointing a CR
POST /courses/{id}/representatives        appoint a CR
POST /courses/{id}/representatives/revoke
```

Every refused sign-in gets the same response, whatever the reason. After five failures in a row
an account is locked, and each address gets `LOGIN_ATTEMPTS` tries per five minutes.

## The web console

`/console.html` holds the Accounts, Data, Representatives, Import and Audit trail panels. Import
has a student import (pick the session, choose the file) and an attendance import (preview, then
import), each with its CSV format written out; for an admin, it also imports a `.siesbak`
(preview, then import) and exports one. Only the staff
sign-in is accepted, so students cannot use it. The page shows only the panels `/me` says the
account can use, and refreshes the visible panel every ten seconds.

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
- **Signing in as a different account** empties the phone first, as signing out would, so
  nothing from the previous account survives a session that expired or was ended. If the
  previous account left changes unsent, the sign-in asks before discarding them. Either way the
  next pull is a fresh snapshot.
- **Signing out** sends the queue (asking before discarding anything unsent), then empties the
  phone's synced tables.

### Pointing a phone at a dev server

The debug build syncs to `sies.debugSyncUrl`, which defaults to `http://10.0.2.2:8080/api/v1`
(the emulator's address for the host), and cleartext is allowed. For a real phone, set it in
`local.properties` to this machine's Wi-Fi address instead:

```sh
ip route get <phone's IP> | grep -oP 'src \K\S+'
```

Don't use `ip route get 1`: with a VPN connected it returns the VPN's address. Release builds use
`sies.releaseSyncUrl` from `local.properties`, empty unless set, so the app runs entirely locally until a server is
configured.

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

An admin can do this from the console's Import tab, with the same preview and the same rules
below. The password travels in the `X-Backup-Password` header, as Base64 of its UTF-8. The
command line:

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

**Exporting** (`POST /backup/export`, or the console) writes what a phone signed in as the admin
would hold after its first sync, encrypted if a password is given, and imports back unchanged. It
has no accounts, roles, CR appointments, devices or audit trail, so it is not a backup of the
server: `pg_dump` is.

Both take an optional `X-Progress-Id` header, an id the caller makes up. While the request runs,
`GET /progress/{id}` answers `{"stage", "done", "total"}` (a total of 0 means the stage has no
count) to the account that sent it, and 404 once it has finished. The console uses it for its
progress bars. It is held in memory, so it is per server process.
