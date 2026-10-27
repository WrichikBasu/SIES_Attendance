# SIES Attendance (Unofficial)
_An attendance system for SIES College of Arts, Science & Commerce, Mumbai, 
developed by students from the Dept. of Computer Science._

The app worked offline till v1.3.0; thereafter, a server is necessary to start the process. Attendance is marked and stored on the phone, and changes are queued
and synced when there's a connection.

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
  - batches: named groups of students, linked to the courses they sit. Each batch belongs to a
    department and is linked only to that department's courses. Its students may come from any
    department
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

### Permissions

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

The server address is set when the app is built, from `local.properties`, which is never
committed. `-P` on the command line overrides either address for one build.

```properties
sies.releaseSyncUrl=https://<production host>/api/v1
sies.debugSyncUrl=http://<this machine's Wi-Fi address>:8080/api/v1
```

- **Release builds** use `sies.releaseSyncUrl`, which is empty unless set. With no server
  configured, the app runs locally and schedules no sync. A production server also needs real
  certificate pins in `app/src/main/res/xml/network_security_config.xml`.
- **Debug builds** use `sies.debugSyncUrl` and allow plain HTTP. Unset, it is
  `http://10.0.2.2:8080/api/v1`, the emulator's address for this machine. Then start a local
  server:

```sh
./server/tools/dev-database.sh
eval "$(./server/tools/dev-database.sh --env)"
./server/tools/dev-serve.sh
```

The first administrator account is created with a one-off command. See
[`server/README.md`](server/README.md#the-first-administrator) for that and for production
deployment.

## Roster CSV

The same file is read by the app's **Students → Import CSV**, the web console's **Import** tab and
the server's `POST /roster`, using the same parser. Professors import students into their own
department, heads into the departments they head, and the office and administrators into any.
Students can't import. Both screens show the full format; this is the summary:

```
rollNo,name,pronouns,email,year,stream,majorDept,minorDept,oeDept,semester,division,
apaarId,joinDate,leaveDate
```

- Columns can be in any order.
- `pronouns`, `email`, `minorDept`, `oeDept`, `semester`, `division`, `apaarId`, `joinDate` and
  `leaveDate` may be blank or left out.
- Dates can be written as `2026-06-15`, `15/06/2026` or `15-06-2026`.
- Departments, streams and years accept either the stored name or the display label, so
  `Computer Science` and `COMPUTER_SCIENCE` both work.
- A bad row is reported and skipped; the rest of the file is still imported.
- Importing again updates the students whose roll numbers already exist. A blank APAAR ID or
  date keeps the one already stored.
- A student is matched by roll number in that session, then by APAAR ID, then by email. The
  APAAR ID is what recognises the same person in a new session under a new roll number.

### Attendance sheets

Imported from **Attendance → Import attendance** in the app, or the console's **Import** tab. The
first four rows name each class (course code, date, start time, end time) and every row after
that is a student: their roll number, then `P`, `AB`, or blank for "not in this class". The blank
cells decide which batch was in the room. Nothing is saved until the preview has been confirmed.

### Joining late and leaving

- **Join date:** empty means the start of the session. A late joiner's attendance, in the
  statistics and the register export, counts only lectures from their join date.
- **Leave date:** once set, the student is off that session's rolls, batch lists and exports,
  and hidden on the Students tab unless "Show students who have left" is on. Their attendance
  before leaving is kept.

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
