# SIES Attendance

An attendance system for SIES College: an Android app professors and class representatives use to
take attendance, and a server that holds the college's copy and keeps every phone in step.

The app works fully offline. Attendance is marked and stored on the phone, and changes are queued
and synced when there's a connection. Without a server configured, the app runs on its own as a
local attendance register.

## Modules

| Module | What it is | Toolchain |
|---|---|---|
| `:app` | The Android app: Room database, Material 3 UI, WorkManager sync | Android, Java 17, min SDK 26 |
| `:shared` | Code both programs must agree on: the domain enums and rules, the roster CSV parser, the app's schema migrations, the format of the older phone backups the server can import, and the description of every synced table | Java 21 |
| `:server` | Spring Boot service on PostgreSQL. Permissions are enforced by row-level security. See [`server/README.md`](server/README.md) | Java 25 |

## What the app does

- **Taking attendance.** Pick a course and a batch, mark the roll, and save. A register can be
  corrected, cleared or duplicated to another slot later. Lectures can be browsed by day or listed
  and filtered.
- **The college's structure:**
  - academic sessions
  - students, with an enrolment per session (roll number, year, stream, major, minor and OE
    departments, division)
  - professors
  - courses: type, offering department, coordinator and co-instructors
  - batches: named groups of students, linked to the courses they sit
- **Rolls.** A course with batches linked for the session takes its roll from those batches.
  Otherwise the roll comes from the course type: major courses take the offering department's
  majors, open electives take students whose OE department it is, and so on.
- **Statistics** grouped and filtered in several ways, plus per-student breakdowns with the dates
  of every lecture.
- **Exports:**
  - an attendance register spreadsheet (`.ods`), with lectures held and attended per course and
    students below the threshold shaded
  - mark sheets for a lecture or a day, as `.ods` or CSV
- **Imports:**
  - a student roster CSV (see below)
  - past attendance sheets, shown as a preview before anything is written
- **Account and sync:** signing in and out, changing your password, and the state of the sync.
  The server holds the college's copy, so the app has no backup of its own.

### Who sees what

When a server is configured, everyone signs in before taking attendance:
- **Professors** see and mark their own courses.
- **Heads of department** see and mark their department's courses, and can maintain its students.
- **Class representatives** (student accounts appointed to a course) see only the courses they
  represent, those courses' batches, and the students on those rolls.
- **The office and administrators** manage the roster and accounts from the server's web console.

The server enforces all of this. The app hides what an account can't use, so nobody fills in a
form the server will refuse. The full permission table is in
[`server/README.md`](server/README.md#who-may-do-what).

Signing out sends any unsent changes, asking before discarding anything that still can't be sent,
then clears the phone's data. The next account starts from its own fresh download.

## Building

Requirements: the Android SDK (via Android Studio), and JDK 25 for the server. Gradle fetches the
other JDKs it needs. The server's database and tests also need Docker.

```sh
./gradlew :app:assembleDebug               # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug                # onto a connected device or emulator
./gradlew :server:bootJar                  # server/build/libs/sies-attendance.jar
```

### Connecting the app to a server

- **Release builds** have an empty `sync_base_url` in `app/src/main/res/values/strings.xml`.
  With no server configured, the app runs locally and schedules no sync. A production server also
  needs real certificate pins in `app/src/main/res/xml/network_security_config.xml`.
- **Debug builds** read `sync_base_url` from `app/src/debug/res/values/strings.xml` and allow
  plain HTTP. Set it to `http://<this machine's Wi-Fi address>:8080/api/v1`, or use
  `http://10.0.2.2:8080/api/v1` from the emulator. Then start a local server:

```sh
./server/tools/dev-database.sh
eval "$(./server/tools/dev-database.sh --env)"
./server/tools/dev-serve.sh
```

The first administrator account is created with a one-off command. See
[`server/README.md`](server/README.md#the-first-administrator) for that and for production
deployment.

## Roster CSV

The same file is read by the app's **Students → Import CSV** and by the server's `POST /roster`,
using the same parser:

```
rollNo,name,pronouns,email,year,stream,majorDept,minorDept,oeDept,semester,division
```

- Columns can be in any order.
- `pronouns`, `email`, `minorDept`, `oeDept`, `semester` and `division` may be blank or left out.
- Departments, streams and years accept either the stored name or the display label, so
  `Computer Science` and `COMPUTER_SCIENCE` both work.
- A bad row is reported and skipped; the rest of the file is still imported.
- Importing again updates the students whose roll numbers already exist.

## Tests

```sh
./gradlew test :app:testDebugUnitTest --rerun    # app, shared and server
python3 app/src/test/sql/sql_differ.py           # the app's and server's SQL return the same rows
```

The server's database tests start throwaway PostgreSQL containers. Without Docker, Gradle skips
them; the `server/tools/verify-*.sh` scripts fail instead, so use those when a skipped test
shouldn't pass. `--rerun` forces a fresh run, since Gradle otherwise reuses earlier results.

Some rules are written in more than one place, because the phone has no PostgreSQL. Tests keep the
copies in step:
- `PostgresSchemaDriftTest`: the migrations against the Java enums and rules
- `sql_differ.py`: the export and statistics queries on both databases
- `PullApplyTest`: server-shaped rows written into a real device database

## Layout

```
app/src/main/java/in/basulabs/siesattendance/
  backend/          Room entities and DAOs, migrations, repository, import and export
  backend/sync/     sign-in, the outbox drain, the sync worker, sign-out
  frontend/         fragments, adapters, the view model, permission rules
shared/src/main/java/in/basulabs/siesattendance/backend/
  sync/             DeviceTables, DeviceSql, outbox triggers, the sync wire format
  migration/        the app's schema migrations, also run by the server's importer
  util/             roster CSV parser, backup container and encryption
server/             see server/README.md
```
