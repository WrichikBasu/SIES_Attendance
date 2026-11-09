#!/usr/bin/env bash
#
# The phone and the server, joined: OutboxDrain on a real phone pushing to and pulling from a
# real server over the network.
#
# verify-sync.sh tests the two halves apart and ends by saying they have not been joined on a
# phone. This joins them, with nothing shared with development:
#
#   - a throwaway PostgreSQL container on its own port and volume (not sies-pg)
#   - the server jar on its own port (not 8080), against that database
#   - a first administrator made with the bootstrap command, its password changed over HTTP
#   - the port forwarded to the phone with adb reverse, so the phone reaches 127.0.0.1 and
#     nothing depends on the wifi or the laptop's firewall
#   - a fixture of two departments loaded as the database superuser, then accounts made
#     through the accounts API, as the console makes them: a Physics professor, a Physics
#     colleague who teaches nothing, the head of Physics (by designation), the office, and
#     three students appointed representatives of PH-101 by its professor -- one still
#     serving, one whose appointment was revoked through the API, one whose expired
#   - each account's test class run on the phone in turn, then the server checked to show
#     its refusals kept rows off it; then DeviceSyncTest as the administrator, and last
#     ScheduledSyncTest, the administrator's changes synced by SyncScheduler and the real
#     WorkManager rather than by the test. A separate install for each, so each account
#     registers its own phone
#
# The fixture's keys are mirrored in app/src/androidTest/.../device/support/Fixture.java.
#
# All of it is removed afterwards, whether the tests pass or not. The connected test task
# installs the debug build (in.basulabs.siesattendance.debug) and uninstalls it again; a
# release install on the phone is not touched.
#
#   server/tools/verify-device-sync.sh
#
# Needs Docker, adb with exactly one device (or ANDROID_SERIAL set), Java 25 and the Android
# SDK. A test that skips is reported as a failure: a gate that skips is not a pass.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HERE="$ROOT/server/tools"

DB_CONTAINER="sies-pg-devicesync"
DB_VOLUME="sies-pgdata-devicesync"
DB_PORT="${DEVICE_SYNC_DB_PORT:-55436}"
HTTP_PORT="${DEVICE_SYNC_HTTP_PORT:-18080}"

new_password() {
    python3 -c 'import secrets; print(secrets.token_urlsafe(18))'
}

ADMIN_EMAIL="device-sync@sies.edu.in"
FIRST_PASSWORD="$(new_password)"
PASSWORD="$(new_password)"

# The fixture's people below, each with an account. First passwords are changed before use.
PROF_EMAIL="p.phys@sies.edu.in"          # 1001, teaches PH-101
PROF_FIRST_PASSWORD="$(new_password)"
PROF_PASSWORD="$(new_password)"
COLLEAGUE_EMAIL="q.phys@sies.edu.in"     # 1003, Physics, teaches nothing
COLLEAGUE_FIRST_PASSWORD="$(new_password)"
COLLEAGUE_PASSWORD="$(new_password)"
HEAD_EMAIL="h.phys@sies.edu.in"          # 1004, designation HOD
HEAD_FIRST_PASSWORD="$(new_password)"
HEAD_PASSWORD="$(new_password)"
REP_EMAIL="phys.one@student.sies.edu.in" # student 2001, PH-101's representative
REP_FIRST_PASSWORD="$(new_password)"
REP_PASSWORD="$(new_password)"
OFFICE_EMAIL="office@sies.edu.in"        # staff, role OFFICE
OFFICE_FIRST_PASSWORD="$(new_password)"
OFFICE_PASSWORD="$(new_password)"
CHEM_EMAIL="c.chem@sies.edu.in"          # 1002; the office makes them HOD from a phone
REVOKED_EMAIL="phys.two@student.sies.edu.in"   # student 2003, appointment revoked
REVOKED_FIRST_PASSWORD="$(new_password)"
REVOKED_PASSWORD="$(new_password)"
EXPIRED_EMAIL="phys.three@student.sies.edu.in" # student 2004, appointment expired
EXPIRED_FIRST_PASSWORD="$(new_password)"
EXPIRED_PASSWORD="$(new_password)"

# The fixture's keys. Every row on a real server carries a structured key -- rows a backup
# import or a roster CSV brought are keyed under node 0, the server's own -- and the server
# refuses to amend a row whose key predates the scheme ("belongs in a backup import"). Plain
# numbers like 1001 would make every edit of a fixture row fail on its key, before the
# policies were ever asked. So fixture row N has key compose(MIN_COUNTER + N, SERVER_NODE),
# which is 2^55 + N * 2^16; the names below keep N readable, and Fixture.java computes the
# same keys on the phone.
key() {
    echo $(( (1 << 55) + $1 * 65536 ))
}
K_1001="$(key 1001)"
K_1002="$(key 1002)"
K_1003="$(key 1003)"
K_1004="$(key 1004)"
K_2001="$(key 2001)"
K_2002="$(key 2002)"
K_2003="$(key 2003)"
K_2004="$(key 2004)"
K_3001="$(key 3001)"
K_3002="$(key 3002)"
K_4001="$(key 4001)"
K_4002="$(key 4002)"
K_5001="$(key 5001)"

JAR="$ROOT/server/build/libs/sies-attendance.jar"
LOG="$ROOT/server/build/device-sync-server.log"
RESULTS="$ROOT/app/build/outputs/androidTest-results/connected/debug"
SERVER_PID=""

database() {
    NAME="$DB_CONTAINER" VOLUME="$DB_VOLUME" PORT="$DB_PORT" "$HERE/dev-database.sh" "$@"
}

cleanup() {
    local status=$?
    echo
    echo "==> cleaning up"
    if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
        kill "$SERVER_PID" 2>/dev/null || true
        wait "$SERVER_PID" 2>/dev/null || true
    fi
    adb reverse --remove "tcp:$HTTP_PORT" >/dev/null 2>&1 || true
    database --reset >/dev/null 2>&1 || true
    exit "$status"
}
trap cleanup EXIT

# ── Preconditions ────────────────────────────────────────────────────────

if ! docker info >/dev/null 2>&1; then
    echo "FAILED: docker is not available"
    exit 1
fi

devices="$(adb devices | awk 'NR > 1 && $2 == "device"' | wc -l)"
if [ -z "${ANDROID_SERIAL:-}" ] && [ "$devices" -ne 1 ]; then
    echo "FAILED: adb sees $devices devices; connect exactly one or set ANDROID_SERIAL"
    exit 1
fi

for port in "$DB_PORT" "$HTTP_PORT"; do
    if ss -ltn 2>/dev/null | awk '{print $4}' | grep -qE ":$port\$"; then
        echo "FAILED: port $port is already in use; set DEVICE_SYNC_DB_PORT or"
        echo "        DEVICE_SYNC_HTTP_PORT to a free one"
        exit 1
    fi
done

# ── The database ─────────────────────────────────────────────────────────

echo "==> a throwaway database, $DB_CONTAINER on port $DB_PORT"
database --reset >/dev/null
database >/dev/null
eval "$(database --env)"

# ── The server ───────────────────────────────────────────────────────────

echo "==> building the server"
"$ROOT/gradlew" -p "$ROOT" -q :server:bootJar

echo "==> starting it on port $HTTP_PORT (log: $LOG)"
mkdir -p "$(dirname "$LOG")"
# LOGIN_ATTEMPTS raised for this server only. Through adb reverse every phone and every curl
# here arrives from 127.0.0.1, so the limiter sees one address making a dozen sign-ins in a
# few minutes -- which is exactly what it exists to refuse, and the default of 10 does.
LOGIN_ATTEMPTS=200 java --enable-native-access=ALL-UNNAMED -Duser.timezone=UTC -jar "$JAR" \
    --server.port="$HTTP_PORT" >"$LOG" 2>&1 &
SERVER_PID=$!

BASE="http://127.0.0.1:$HTTP_PORT"
for _ in $(seq 1 120); do
    if curl -sf --max-time 2 "$BASE/actuator/health" >/dev/null 2>&1; then
        break
    fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        echo "FAILED: the server exited. The end of its log:"
        tail -30 "$LOG"
        exit 1
    fi
    sleep 1
done
curl -sf --max-time 2 "$BASE/actuator/health" >/dev/null || {
    echo "FAILED: the server did not come up within two minutes. The end of its log:"
    tail -30 "$LOG"
    exit 1
}

# ── The account ──────────────────────────────────────────────────────────

echo "==> the first administrator, $ADMIN_EMAIL"
SIES_ADMIN_PASSWORD="$FIRST_PASSWORD" java --enable-native-access=ALL-UNNAMED \
    -Duser.timezone=UTC -jar "$JAR" --spring.profiles.active=bootstrap \
    --bootstrap.email="$ADMIN_EMAIL" --bootstrap.name="Device Sync" >>"$LOG" 2>&1 || {
    echo "FAILED: the bootstrap command did not make the account. The end of the log:"
    tail -30 "$LOG"
    exit 1
}

# Signs in through a door (STAFF unless given) and prints the token.
login() {
    curl -sS --fail-with-body -X POST "$BASE/api/v1/auth/login" \
        -H 'Content-Type: application/json' \
        -d "$(python3 -c 'import json, sys; print(json.dumps({"email": sys.argv[1], "door": sys.argv[3], "password": sys.argv[2]}))' \
            "$1" "$2" "${3:-STAFF}")" \
        | python3 -c 'import json, sys; print(json.load(sys.stdin)["token"])'
}

# Every new account must change its password before it may do anything else.
rotate() {
    local token
    token="$(login "$1" "$2" "${4:-STAFF}")"
    curl -sS --fail-with-body -o /dev/null -X POST "$BASE/api/v1/auth/password" \
        -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
        -d "$(python3 -c 'import json, sys; print(json.dumps({"currentPassword": sys.argv[1], "newPassword": sys.argv[2]}))' \
            "$2" "$3")"
}

rotate "$ADMIN_EMAIL" "$FIRST_PASSWORD" "$PASSWORD"

# ── The fixture and the professor ─────────────────────────────────────────

psql_super() {
    docker exec -i "$DB_CONTAINER" psql -U postgres -d sies -q -v ON_ERROR_STOP=1 "$@"
}

# Written as SyncTest writes its own: as the superuser, before any phone, so what a phone is
# shown and refused is the policies' doing and not the order things arrived in.
echo "==> the fixture: Physics and Chemistry, four professors, a student and a lecture each"
psql_super <<SQL
INSERT INTO academic_years (academic_year, start_date, end_date) VALUES
    (2025, '2025-06-01', '2026-05-31');

INSERT INTO professors (prof_id, name, email, department, designation) VALUES
    (${K_1001}, 'P PHYS', 'p.phys@sies.edu.in', 'PHYSICS',   'Assistant Professor'),
    (${K_1002}, 'C CHEM', 'c.chem@sies.edu.in', 'CHEMISTRY', 'Assistant Professor'),
    (${K_1003}, 'Q PHYS', 'q.phys@sies.edu.in', 'PHYSICS',   'Assistant Professor'),
    (${K_1004}, 'H PHYS', 'h.phys@sies.edu.in', 'PHYSICS',   'HOD');

INSERT INTO students (student_id, name, pronouns, email) VALUES
    (${K_2001}, 'PHYS ONE',   'she/her',   NULL),
    (${K_2002}, 'CHEM ONE',   'he/him',    NULL),
    (${K_2003}, 'PHYS TWO',   'they/them', NULL),
    (${K_2004}, 'PHYS THREE', 'she/her',   NULL);

INSERT INTO student_year
    (student_id, academic_year, roll_no, year, stream, major_dept, semester) VALUES
    (${K_2001}, 2025, 'PH-FY-001', 'FY', 'BSc', 'PHYSICS',   1),
    (${K_2002}, 2025, 'CH-FY-001', 'FY', 'BSc', 'CHEMISTRY', 1),
    (${K_2003}, 2025, 'PH-FY-002', 'FY', 'BSc', 'PHYSICS',   1),
    (${K_2004}, 2025, 'PH-FY-003', 'FY', 'BSc', 'PHYSICS',   1);

INSERT INTO courses
    (course_id, prof_id, course_code, course_topic, stream,
     offering_department, year, semester, course_type) VALUES
    (${K_3001}, ${K_1001}, 'PH-101', 'Mechanics', 'BSc', 'PHYSICS',   'FY', 1, 'MAJOR'),
    (${K_3002}, ${K_1002}, 'CH-101', 'Inorganic', 'BSc', 'CHEMISTRY', 'FY', 1, 'MAJOR');

INSERT INTO course_instructor (course_id, prof_id) VALUES (${K_3001}, ${K_1001}), (${K_3002}, ${K_1002})
    ON CONFLICT DO NOTHING;

-- PH-101's roll, which is what lets 2001, 2003 and 2004 be appointed its representatives.
INSERT INTO batch_desc (batch_id, batch_name, academic_year, department)
    VALUES (${K_5001}, 'FY PH', 2025, 'PHYSICS');
INSERT INTO batch_student_map (batch_id, student_id, academic_year) VALUES
    (${K_5001}, ${K_2001}, 2025), (${K_5001}, ${K_2003}, 2025), (${K_5001}, ${K_2004}, 2025);
INSERT INTO course_batch_map (course_id, batch_id) VALUES (${K_3001}, ${K_5001});

INSERT INTO lectures
    (lecture_id, course_id, batch_id, start_at, start_zone, end_at, end_zone) VALUES
    (${K_4001}, ${K_3001}, NULL, '2025-07-15 03:30:00+00', 'Asia/Kolkata',
                       '2025-07-15 04:30:00+00', 'Asia/Kolkata'),
    (${K_4002}, ${K_3002}, NULL, '2025-07-14 03:30:00+00', 'Asia/Kolkata',
                       '2025-07-14 04:30:00+00', 'Asia/Kolkata');

INSERT INTO attendance (lecture_id, student_id, present) VALUES
    (${K_4001}, ${K_2001}, true),
    (${K_4002}, ${K_2002}, true);
SQL

ADMIN_TOKEN="$(login "$ADMIN_EMAIL" "$PASSWORD")"

# Makes an account through the API, as the console does: KIND, EMAIL, then one more field and
# its value -- the person's key ("profId" or "studentId"), or "role" for a staff account --
# and the first password.
make_account() {
    curl -sS --fail-with-body -o /dev/null -X POST "$BASE/api/v1/accounts" \
        -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
        -d "$(python3 -c 'import json, sys; print(json.dumps({"kind": sys.argv[1], "displayName": sys.argv[2], "loginEmail": sys.argv[2], sys.argv[3]: sys.argv[4], "password": sys.argv[5]}))' \
            "$1" "$2" "$3" "$4" "$5")"
}

echo "==> the accounts, through the accounts API"
make_account PROFESSOR "$PROF_EMAIL" profId ${K_1001} "$PROF_FIRST_PASSWORD"
rotate "$PROF_EMAIL" "$PROF_FIRST_PASSWORD" "$PROF_PASSWORD"
make_account PROFESSOR "$COLLEAGUE_EMAIL" profId ${K_1003} "$COLLEAGUE_FIRST_PASSWORD"
rotate "$COLLEAGUE_EMAIL" "$COLLEAGUE_FIRST_PASSWORD" "$COLLEAGUE_PASSWORD"
# Designation HOD: making the account makes them head of Physics, with nothing granted by hand.
make_account PROFESSOR "$HEAD_EMAIL" profId ${K_1004} "$HEAD_FIRST_PASSWORD"
rotate "$HEAD_EMAIL" "$HEAD_FIRST_PASSWORD" "$HEAD_PASSWORD"
make_account STUDENT "$REP_EMAIL" studentId ${K_2001} "$REP_FIRST_PASSWORD"
rotate "$REP_EMAIL" "$REP_FIRST_PASSWORD" "$REP_PASSWORD" STUDENT
make_account STAFF "$OFFICE_EMAIL" role OFFICE "$OFFICE_FIRST_PASSWORD"
rotate "$OFFICE_EMAIL" "$OFFICE_FIRST_PASSWORD" "$OFFICE_PASSWORD"
# Never signed in: there only so a designation the office sets has an account to make head.
make_account PROFESSOR "$CHEM_EMAIL" profId ${K_1002} "$(new_password)"
make_account STUDENT "$REVOKED_EMAIL" studentId ${K_2003} "$REVOKED_FIRST_PASSWORD"
rotate "$REVOKED_EMAIL" "$REVOKED_FIRST_PASSWORD" "$REVOKED_PASSWORD" STUDENT
make_account STUDENT "$EXPIRED_EMAIL" studentId ${K_2004} "$EXPIRED_FIRST_PASSWORD"
rotate "$EXPIRED_EMAIL" "$EXPIRED_FIRST_PASSWORD" "$EXPIRED_PASSWORD" STUDENT

# PH-101's professor appoints (or, with /revoke, ends the appointment of) a student.
representative() {
    curl -sS --fail-with-body -o /dev/null -X POST \
        "$BASE/api/v1/courses/${K_3001}/representatives${2:-}" \
        -H "Authorization: Bearer $PROF_TOKEN" -H 'Content-Type: application/json' \
        -d "{\"studentId\": \"$1\", \"academicYear\": 2025, \"reason\": \"device sync test\"}"
}

echo "==> PH-101's professor appoints students ${K_2001}, ${K_2003} and ${K_2004} its representatives"
PROF_TOKEN="$(login "$PROF_EMAIL" "$PROF_PASSWORD")"
representative ${K_2001}
representative ${K_2003}
representative ${K_2004}

echo "==> and revokes ${K_2003}'s appointment through the API"
representative ${K_2003} /revoke

# The API sets no expiry -- an appointment runs until it is revoked -- so the expiry is set
# here, as the superuser. One millisecond after the grant: the table insists on later than
# granted_at, and by the time a phone signs in it is long past.
echo "==> ${K_2004}'s appointment made to have expired"
psql_super -c "UPDATE attendance_delegate SET expires_at = granted_at + interval '1 millisecond'
               WHERE student_id = ${K_2004} AND course_id = ${K_3001}"

# ── The phone ────────────────────────────────────────────────────────────

echo "==> forwarding the phone's 127.0.0.1:$HTTP_PORT to this machine"
adb reverse "tcp:$HTTP_PORT" "tcp:$HTTP_PORT" >/dev/null

ARG="-Pandroid.testInstrumentationRunnerArguments"
STATUS=0

# Runs one test class as its own install, and checks its results. A skip is a failure.
run_on_phone() {
    local class="$1"
    shift
    echo
    echo "==> running $class on the phone"
    rm -rf "$RESULTS"
    "$ROOT/gradlew" -p "$ROOT" -q :app:connectedDebugAndroidTest \
        "$ARG.class=in.basulabs.siesattendance.device.$class" \
        "$ARG.siesServer=http://127.0.0.1:$HTTP_PORT" "$@" || true

    python3 - "$RESULTS" "$class" <<'PYEOF'
import glob
import sys
import xml.etree.ElementTree as ET
from typing import List, Tuple

results_dir: str = sys.argv[1]
wanted: str = sys.argv[2]
tests: int = 0
skipped: int = 0
failed: List[Tuple[str, str]] = []

for path in glob.glob(f"{results_dir}/*.xml"):
    for case in ET.parse(path).getroot().iter("testcase"):
        if not case.get("classname", "").endswith("." + wanted):
            continue
        tests += 1
        if case.find("skipped") is not None:
            skipped += 1
        problem = case.find("failure")
        if problem is None:
            problem = case.find("error")
        if problem is not None:
            first: str = ((problem.get("message") or problem.text or "").splitlines() or [""])[0]
            failed.append((case.get("name", "?"), first[:300]))

print(f"==> {wanted}: {tests} tests, {len(failed)} failed, {skipped} skipped")
for name, message in failed:
    print(f"    FAIL {name}\n         {message}")

if tests == 0:
    print("FAILED: no results at all")
    sys.exit(1)
if skipped:
    print("FAILED: a test was skipped. A gate that skips is not a pass.")
    sys.exit(1)
if failed:
    sys.exit(1)
PYEOF
}

# Prints "ok" or "FAILED" against one count on the server.
expect() {
    local label="$1" want="$2" sql="$3" got
    got="$(docker exec "$DB_CONTAINER" psql -U postgres -d sies -tA -c "$sql")"
    if [ "$got" = "$want" ]; then
        echo "    ok      $label ($got)"
    else
        echo "    FAILED  $label: $got, expected $want"
        return 1
    fi
}

# How many rows of these tables an account inserted or changed, from a phone, on a sign-in:
# the audit log, which is where authorship lives (created_by is never set).
written_by() {
    echo "SELECT count(*) FROM audit_log a JOIN app_account acc ON acc.account_id = a.actor_account
          WHERE a.action IN ('INSERT', 'UPDATE') AND a.table_name IN ($2)
            AND acc.login_email = '$1'
            AND a.device_node IS NOT NULL AND a.session_id IS NOT NULL"
}

run_on_phone ProfessorSyncTest \
    "$ARG.siesProfEmail=$PROF_EMAIL" "$ARG.siesProfPassword=$PROF_PASSWORD" || STATUS=1

echo "==> the server, after the professor"
expect "no professor's lecture on Chemistry's course" 1 \
    "SELECT count(*) FROM lectures WHERE course_id = ${K_3002}" || STATUS=1
expect "Chemistry's mark untouched" 1 \
    "SELECT count(*) FROM attendance WHERE lecture_id = ${K_4002} AND present" || STATUS=1
expect "no student a professor made" 0 \
    "SELECT count(*) FROM students WHERE name LIKE 'NOT ON THE ROLL %'" || STATUS=1
expect "their own register landed" 1 \
    "SELECT count(*) FROM lectures WHERE course_id = ${K_3001} AND lecture_id <> ${K_4001}" || STATUS=1
expect "their lecture, its mark, and two changes to PH-101's existing mark, as theirs" 4 \
    "$(written_by "$PROF_EMAIL" "'lectures', 'attendance'")" || STATUS=1
expect "PH-101's existing mark corrected and put back" 1 \
    "SELECT count(*) FROM attendance WHERE lecture_id = ${K_4001} AND student_id = ${K_2001}
     AND present" || STATUS=1

run_on_phone HeadOfDepartmentSyncTest \
    "$ARG.siesHeadEmail=$HEAD_EMAIL" "$ARG.siesHeadPassword=$HEAD_PASSWORD" || STATUS=1

echo "==> the server, after the head of Physics"
expect "the student they added is on Physics' roll" 1 \
    "SELECT count(*) FROM students s JOIN student_year y USING (student_id)
     WHERE s.name LIKE 'HEAD ADDS %' AND y.major_dept = 'PHYSICS'" || STATUS=1
expect "the student they removed is gone" 0 \
    "SELECT count(*) FROM students WHERE name LIKE 'HEAD REMOVES %'" || STATUS=1
expect "nobody put on Chemistry's roll" 0 \
    "SELECT count(*) FROM student_year WHERE roll_no LIKE 'HC-%'" || STATUS=1
expect "Chemistry's student still there" 1 \
    "SELECT count(*) FROM students WHERE student_id = ${K_2002}" || STATUS=1

run_on_phone ColleagueSyncTest \
    "$ARG.siesColleagueEmail=$COLLEAGUE_EMAIL" \
    "$ARG.siesColleaguePassword=$COLLEAGUE_PASSWORD" || STATUS=1

echo "==> the server, after the colleague who teaches nothing"
expect "PH-101's mark not corrected" 1 \
    "SELECT count(*) FROM attendance WHERE lecture_id = ${K_4001} AND student_id = ${K_2001}
     AND present" || STATUS=1
expect "no lecture or mark written by them" 0 \
    "$(written_by "$COLLEAGUE_EMAIL" "'lectures', 'attendance'")" || STATUS=1

run_on_phone RepresentativeSyncTest \
    "$ARG.siesRepEmail=$REP_EMAIL" "$ARG.siesRepPassword=$REP_PASSWORD" || STATUS=1

echo "==> the server, after the class representative"
expect "their register for PH-101 landed, as theirs" 2 \
    "$(written_by "$REP_EMAIL" "'lectures', 'attendance'")" || STATUS=1
expect "nothing in a session they were not appointed for" 0 \
    "SELECT count(*) FROM lectures WHERE course_id = ${K_3001}
     AND start_at >= '2026-06-01'" || STATUS=1
expect "nothing on Chemistry's course" 1 \
    "SELECT count(*) FROM lectures WHERE course_id = ${K_3002}" || STATUS=1
expect "no student a representative made" 0 \
    "SELECT count(*) FROM students WHERE name LIKE 'REP MADE %'" || STATUS=1

run_on_phone OfficeSyncTest \
    "$ARG.siesOfficeEmail=$OFFICE_EMAIL" "$ARG.siesOfficePassword=$OFFICE_PASSWORD" || STATUS=1

echo "==> the server, after the office"
expect "the student they put on Chemistry's roll" 1 \
    "SELECT count(*) FROM students s JOIN student_year y USING (student_id)
     WHERE s.name LIKE 'OFFICE ADDS %' AND y.major_dept = 'CHEMISTRY'" || STATUS=1
expect "the student they took off is gone" 0 \
    "SELECT count(*) FROM students WHERE name LIKE 'OFFICE REMOVES %'" || STATUS=1
expect "C CHEM heads Chemistry, granted by the office" 1 \
    "SELECT count(*) FROM user_role r
     JOIN app_account head ON head.account_id = r.account_id
     JOIN app_account office ON office.account_id = r.granted_by
     WHERE head.login_email = '$CHEM_EMAIL' AND office.login_email = '$OFFICE_EMAIL'
       AND r.role = 'HOD' AND r.department = 'CHEMISTRY' AND r.revoked_at IS NULL" || STATUS=1
expect "their remark on PH-101" 1 \
    "SELECT count(*) FROM courses WHERE course_id = ${K_3001} AND remarks LIKE 'office %'" || STATUS=1
expect "no lecture or mark written by the office" 0 \
    "$(written_by "$OFFICE_EMAIL" "'lectures', 'attendance'")" || STATUS=1
expect "PH-101's mark still present" 1 \
    "SELECT count(*) FROM attendance WHERE lecture_id = ${K_4001} AND student_id = ${K_2001}
     AND present" || STATUS=1

run_on_phone FormerRepresentativeSyncTest \
    "$ARG.siesFormerEmail=$REVOKED_EMAIL" "$ARG.siesFormerPassword=$REVOKED_PASSWORD" || STATUS=1
run_on_phone FormerRepresentativeSyncTest \
    "$ARG.siesFormerEmail=$EXPIRED_EMAIL" "$ARG.siesFormerPassword=$EXPIRED_PASSWORD" || STATUS=1

echo "==> the server, after the former representatives"
expect "${K_2003}'s appointment was revoked" 1 \
    "SELECT count(*) FROM attendance_delegate WHERE student_id = ${K_2003}
     AND revoked_at IS NOT NULL" || STATUS=1
expect "${K_2004}'s appointment had expired" 1 \
    "SELECT count(*) FROM attendance_delegate WHERE student_id = ${K_2004}
     AND revoked_at IS NULL AND expires_at < now()" || STATUS=1
expect "nothing written by the revoked representative" 0 \
    "$(written_by "$REVOKED_EMAIL" "'lectures', 'attendance'")" || STATUS=1
expect "nothing written by the expired representative" 0 \
    "$(written_by "$EXPIRED_EMAIL" "'lectures', 'attendance'")" || STATUS=1

run_on_phone DeviceSyncTest \
    "$ARG.siesEmail=$ADMIN_EMAIL" "$ARG.siesPassword=$PASSWORD" || STATUS=1

# The one class that goes through SyncScheduler and the real WorkManager rather than calling
# OutboxDrain itself: writes to the live database, synced when WorkManager runs them.
run_on_phone ScheduledSyncTest \
    "$ARG.siesEmail=$ADMIN_EMAIL" "$ARG.siesPassword=$PASSWORD" || STATUS=1

echo "==> the server, after the scheduled syncs"
expect "the change and the burst of ten, all arrived" 11 \
    "SELECT count(*) FROM students WHERE name LIKE 'SCHEDULED %' OR name LIKE 'BURST %'" \
    || STATUS=1
expect "each written once, by the administrator's phone" 11 \
    "SELECT count(*) FROM audit_log a JOIN app_account acc ON acc.account_id = a.actor_account
     WHERE a.table_name = 'students' AND a.action IN ('INSERT', 'UPDATE')
       AND acc.login_email = '$ADMIN_EMAIL' AND a.device_node IS NOT NULL
       AND (a.after_image->>'name' LIKE 'SCHEDULED %' OR a.after_image->>'name' LIKE 'BURST %')" \
    || STATUS=1

echo
echo "==> on the server afterwards"
docker exec "$DB_CONTAINER" psql -U postgres -d sies -tA -F ' ' -c "
    SELECT 'devices registered:', count(*) FROM device_registration
    UNION ALL SELECT 'students:', count(*) FROM students
    UNION ALL SELECT 'lectures:', count(*) FROM lectures
    UNION ALL SELECT 'marks:', count(*) FROM attendance" | sed 's/^/    /' || true

if [ "$STATUS" -eq 0 ]; then
    echo "==> OK: phones of a professor, a colleague, a head of department, a class"
    echo "        representative, two former ones, the office and an administrator pushed"
    echo "        to and pulled from a real server, and the policies held"
else
    echo "==> FAILED"
fi
exit "$STATUS"
