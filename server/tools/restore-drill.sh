#!/usr/bin/env bash
#
# A disaster-recovery drill for the off-machine copy of the backups. Run from cron on the
# machine that holds the copy. Each run rebuilds production twice from nothing, using the
# steps in server/README.md and nothing else, checks that the service answers, and throws it
# all away:
#
#   1. The copy is fresh: the newest dump and base backup are from the last day or so, and
#      WAL arrived in the last half hour. A copy that stopped updating restores fine and
#      still loses a week.
#   2. From the newest dump ("Restoring a dump", the new-machine path): the database
#      container as in "The database, in Docker", restore-backup.sh --into it, the
#      environment files from the examples, the migrate step, the service started the way
#      its unit starts it, and a health check.
#   3. From point-in-time recovery ("Restoring to a moment", --to latest): pitr-restore.sh
#      --into, the container swapped for the README's docker run on the restored volume,
#      then the same migrate, start and health check.
#
# "The service answers" means /actuator/health is UP, a sign-in with a made-up account is
# refused with 401 sign_in_refused (which needs sies_app to read app_account under its
# policies), and the restored database has at least one account. Each restore must also
# finish within MAX_RESTORE_MINUTES: a backup that takes all day to restore is not one.
#
#   restore-drill.sh                  run the drill, showing everything it does as it goes
#   restore-drill.sh --quiet          for cron: silent on success, a summary on failure
#   restore-drill.sh --config FILE    settings from FILE instead of the default below
#
# Settings are read from ~/.config/sies-attendance/restore-drill.env; see
# tools/restore-drill.env.example for every one. The run's full output always goes to
# ~/.local/state/sies-restore-drill/, and, unless --quiet, to the terminal as well. On
# failure it also shows a desktop notification and writes a summary to stdout, so cron mails
# it too. With --quiet, a successful run prints nothing.
#
# Everything it creates is named sies-drill-*, on ports Docker and this script pick, so it
# never touches a development database or service. Leftovers from a run that was killed are
# removed at the start of the next one.
#
# Where it cannot follow the README to the letter:
#   - The database is published on a free port rather than 5432, and the service runs as
#     you, from a copy of the jar, rather than under systemd as `sies` from /opt. It gets the
#     unit's environment (its EnvironmentFile plus SPRING_FLYWAY_ENABLED=false and
#     FORWARD_HEADERS=framework) and the same java -jar.
#   - After point-in-time recovery it does not run sies-wal-archiving.sh enable or take a
#     base backup: they need root on the server, and would write into the archive.
#   - Point-in-time recovery brings back production's role passwords. Each DB_*_PASSWORD
#     setting that is filled in is checked by signing in as its role over TCP, and the
#     drill fails if production's password is different: the password manager is out of
#     date. A role whose setting is empty gets a throwaway password in the restored copy,
#     set as the superuser, and the log says so. (The dump restore creates the roles
#     itself, from the same settings, so there is nothing there to compare against.)

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER="$(dirname "$HERE")"
REPO="$(dirname "$SERVER")"
DEPLOY="$SERVER/deploy"

config="${XDG_CONFIG_HOME:-$HOME/.config}/sies-attendance/restore-drill.env"
quiet=no
while [[ $# -gt 0 ]]; do
    case "$1" in
        --config) config="${2:?}"; shift 2 ;;
        --quiet) quiet=yes; shift ;;
        -h|--help) sed -n '3,50p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 2 ;;
        *) echo "restore-drill: unknown argument $1" >&2; exit 2 ;;
    esac
done
if [[ -r "$config" ]]; then
    set -a; . "$config"; set +a
fi

BACKUP_DIR="${BACKUP_DIR:-$HOME/sies-backups}"
IDENTITY="${IDENTITY:-}"
JAR="${JAR:-}"
JAVA="${JAVA:-java}"
IMAGE="${PG_IMAGE:-postgres:18}"
MAX_DUMP_AGE_HOURS="${MAX_DUMP_AGE_HOURS:-26}"
MAX_BASE_AGE_HOURS="${MAX_BASE_AGE_HOURS:-26}"
MAX_WAL_AGE_MINUTES="${MAX_WAL_AGE_MINUTES:-30}"
MAX_RESTORE_MINUTES="${MAX_RESTORE_MINUTES:-30}"
NOTIFY_ON_SUCCESS="${NOTIFY_ON_SUCCESS:-no}"
LOG_DIR="${LOG_DIR:-${XDG_STATE_HOME:-$HOME/.local/state}/sies-restore-drill}"
KEEP_LOGS="${KEEP_LOGS:-60}"

DUMP_DB=sies-drill-dump
PITR=sies-drill-pitr            # pitr-restore.sh's container; its volume is $PITR-data
PITR_DB=sies-drill-pitr-db      # the README's docker run on that volume

# cron runs with no session bus, and notify-send needs one.
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"
export DBUS_SESSION_BUS_ADDRESS="${DBUS_SESSION_BUS_ADDRESS:-unix:path=$XDG_RUNTIME_DIR/bus}"

# ── Logging, locking and the outcome ─────────────────────────────────────────

mkdir -p "$LOG_DIR"
started_at="$(date -u +%Y%m%dT%H%M%SZ)"
log="$LOG_DIR/drill-$started_at.log"
exec 3>&1                        # the real stdout: the failure summary goes here
tee_pid=""
if [[ "$quiet" == yes ]]; then
    exec >>"$log" 2>&1
else
    exec > >(tee -a "$log") 2>&1
    tee_pid=$!
fi

exec 9>"$LOG_DIR/.lock"
if ! flock -n 9; then
    echo "another drill is still running; skipping this one"
    exit 0
fi

stage="starting"
service_pid=""
work=""
summary=()

note() { printf '%s  %s\n' "$(date '+%H:%M:%S')" "$*"; }
step() { stage="$1"; echo; note "==> $1"; }
fail() { note "FAILED: $*"; failure="$*"; exit 1; }
failure=""
problems=()                      # found, but not worth stopping the restores for
# The last error line restore-backup.sh or pitr-restore.sh printed, which says why.
tool_error() { grep -E '^(restore-backup|pitr-restore): ' "$log" | tail -1 || true; }

notify() {
    local urgency="$1" title="$2" body="$3"
    command -v notify-send >/dev/null || return 0
    notify-send -u "$urgency" -a "SIES restore drill" -i drive-harddisk "$title" "$body" \
        2>/dev/null || true
}

stop_service() {
    if [[ -n "$service_pid" ]] && kill -0 "$service_pid" 2>/dev/null; then
        kill -TERM "$service_pid" 2>/dev/null || true
        for _ in $(seq 1 60); do kill -0 "$service_pid" 2>/dev/null || break; sleep 1; done
        kill -KILL "$service_pid" 2>/dev/null || true
    fi
    service_pid=""
}

remove_drill_containers() {
    local c v
    for c in "$DUMP_DB" "$PITR" "$PITR-fill" "$PITR_DB"; do
        docker rm -f "$c" >/dev/null 2>&1 || true
    done
    for v in "$DUMP_DB-data" "$PITR-data"; do
        docker volume rm "$v" >/dev/null 2>&1 || true
    done
}

finish() {
    local status=$?
    set +e
    stop_service
    remove_drill_containers
    [[ -n "$work" ]] && rm -rf "$work"
    ls -1t "$LOG_DIR"/drill-*.log 2>/dev/null | tail -n +"$((KEEP_LOGS + 1))" | xargs -r rm -f

    echo
    if [[ $status -eq 0 ]]; then
        note "==> DRILL PASSED"
        printf '  %s\n' "${summary[@]}"
        [[ "$NOTIFY_ON_SUCCESS" == yes ]] \
            && notify normal "Backup restore drill passed" "$(printf '%s\n' "${summary[@]}")"
    else
        local what="${failure:-a command failed (exit $status); see the log}"
        note "==> DRILL FAILED at: $stage"
        notify critical "Backup restore drill FAILED" \
            "At: $stage"$'\n'"$what"$'\n'"Log: $log"
        {
            echo "SIES backup restore drill FAILED"
            echo "  at:  $stage"
            echo "  why: $what"
            echo "  log: $log"
            # Not quiet, the log's tail has just scrolled past on the terminal.
            if [[ "$quiet" == yes ]]; then
                echo
                tail -40 "$log"
            fi
        } >&3
    fi

    # Let tee write the last lines before the script's exit returns the prompt.
    if [[ -n "$tee_pid" ]]; then
        exec >&3 2>&3
        wait "$tee_pid" 2>/dev/null
    fi
}
trap finish EXIT

note "restore drill of $BACKUP_DIR, with $(basename "$SERVER")/README.md as of $(
    git -C "$REPO" log -1 --format='%h %cs' 2>/dev/null || echo 'an unknown commit')"

# ── Preconditions ────────────────────────────────────────────────────────────

step "checking the tools and settings"
for tool in docker age openssl curl "$JAVA"; do
    command -v "$tool" >/dev/null || fail "$tool is not installed or not on PATH ($PATH)"
done
docker info >/dev/null 2>&1 || fail "cannot talk to Docker (is it running, and are you in the docker group?)"
[[ -n "$IDENTITY" ]] || fail "IDENTITY is not set; see tools/restore-drill.env.example"
[[ -r "$IDENTITY" ]] || fail "cannot read the key $IDENTITY"
case "$(realpath "$IDENTITY")/" in
    "$(realpath "$BACKUP_DIR")"/*) fail "the key is inside $BACKUP_DIR; keep it apart from the backups (README, What to copy off the machine)" ;;
esac
[[ -d "$BACKUP_DIR" ]] || fail "$BACKUP_DIR does not exist"

remove_drill_containers
work="$(mktemp -d "$XDG_RUNTIME_DIR/sies-drill.XXXXXX" 2>/dev/null || mktemp -d)"
chmod 700 "$work"

# ── 1. The copy is current ───────────────────────────────────────────────────

stamp_epoch() {   # 20261106T023012Z -> seconds since the epoch
    local s="$1"
    date -u -d "${s:0:4}-${s:4:2}-${s:6:2} ${s:9:2}:${s:11:2}:${s:13:2}" +%s
}
hours_since() { echo $(( ( $(date +%s) - $1 ) / 3600 )); }

step "checking that the copy is current"
newest_dump="$(find "$BACKUP_DIR" -maxdepth 1 -name 'sies-*.dump.age' -printf '%f\n' | sort | tail -1)"
[[ -n "$newest_dump" ]] || fail "no dump (sies-*.dump.age) in $BACKUP_DIR"
dump_stamp="${newest_dump#sies-}"; dump_stamp="${dump_stamp%.dump.age}"
dump_age="$(hours_since "$(stamp_epoch "$dump_stamp")")"
note "newest dump: $newest_dump, ${dump_age}h old"
(( dump_age <= MAX_DUMP_AGE_HOURS )) \
    || problems+=("the newest dump is ${dump_age}h old (limit ${MAX_DUMP_AGE_HOURS}h): the nightly dump or the copy has stopped")

[[ -d "$BACKUP_DIR/base" && -d "$BACKUP_DIR/wal" ]] || fail "$BACKUP_DIR has no base/ and wal/: point-in-time backups are not being copied"
newest_base="$(find "$BACKUP_DIR/base" -maxdepth 1 -name 'base-*.tar.gz.age' -printf '%f\n' | sort | tail -1)"
[[ -n "$newest_base" ]] || fail "no base backup in $BACKUP_DIR/base"
base_stamp="${newest_base#base-}"; base_stamp="${base_stamp%.tar.gz.age}"
base_age="$(hours_since "$(stamp_epoch "$base_stamp")")"
note "newest base backup: $newest_base, ${base_age}h old"
(( base_age <= MAX_BASE_AGE_HOURS )) \
    || problems+=("the newest base backup is ${base_age}h old (limit ${MAX_BASE_AGE_HOURS}h): sies-basebackup or the copy has stopped")

newest_wal="$(find "$BACKUP_DIR/wal" -maxdepth 1 -name '*.gz.age' -printf '%T@ %f\n' | sort -n | tail -1)"
[[ -n "$newest_wal" ]] || fail "no WAL in $BACKUP_DIR/wal"
wal_age=$(( ( $(date +%s) - ${newest_wal%%.*} ) / 60 ))
note "newest WAL: ${newest_wal#* }, arrived ${wal_age} min ago"
(( wal_age <= MAX_WAL_AGE_MINUTES )) \
    || problems+=("no WAL has arrived for ${wal_age} min (limit ${MAX_WAL_AGE_MINUTES}): the shipper or the copy has stopped; a lost server now loses that much")
for p in "${problems[@]}"; do note "PROBLEM: $p"; done
summary+=("copy: dump ${dump_age}h, base ${base_age}h, WAL ${wal_age} min old")

# ── The jar ──────────────────────────────────────────────────────────────────

# Everything run under timeout gets </dev/null. timeout puts its command in a background
# process group, so a command that reads a terminal on stdin (the Gradle client does, after
# the build) is stopped by SIGTTIN and the drill hangs. Under cron there is no terminal.

if [[ -z "$JAR" ]]; then
    step "building the jar (./gradlew :server:bootJar)"
    ( cd "$REPO" && timeout 20m ./gradlew --quiet :server:bootJar </dev/null ) \
        || fail "./gradlew :server:bootJar failed: the drill cannot run, the backups are untested"
    JAR="$SERVER/build/libs/sies-attendance.jar"
fi
[[ -r "$JAR" ]] || fail "cannot read the jar $JAR"
# A copy, so a build in the checkout during the drill cannot swap it underneath.
cp "$JAR" "$work/sies-attendance.jar"
JAR="$work/sies-attendance.jar"

# ── The README's steps, as functions ─────────────────────────────────────────

# "The database, in Docker": the docker run, on a free loopback port instead of 5432.
start_database() {
    local name="$1" volume="$2"
    [[ -f "$work/postgres-password" ]] || {
        openssl rand -hex 24 > "$work/postgres-password"
        chmod 600 "$work/postgres-password"
    }
    docker volume create "$volume" >/dev/null
    docker run -d --name "$name" \
        -e POSTGRES_PASSWORD_FILE=/run/secrets/postgres-password \
        -e POSTGRES_DB=sies \
        -e TZ=UTC \
        -v "$work/postgres-password:/run/secrets/postgres-password:ro" \
        -v "$volume:/var/lib/postgresql" \
        -p 127.0.0.1::5432 \
        "$IMAGE" >/dev/null
    # The README's wait, bounded.
    local ready=no
    for _ in $(seq 1 120); do
        if docker exec "$name" psql -U postgres -d sies -tAc 'SELECT 1' >/dev/null 2>&1; then
            ready=yes; break
        fi
        sleep 1
    done
    if [[ "$ready" != yes ]]; then
        docker logs "$name" 2>&1 | tail -20
        fail "$name did not become ready"
    fi
    db_port="$(docker port "$name" 5432/tcp | head -1)"
    db_port="${db_port##*:}"
    note "$name is up on 127.0.0.1:$db_port"
}

free_port() {
    local p
    for p in $(shuf -i 20000-39999 -n 100); do
        if ! (exec 2>/dev/null; : >"/dev/tcp/127.0.0.1/$p"); then echo "$p"; return; fi
    done
    fail "no free port for the service"
}

# "The service": the two environment files, filled in from the examples.
write_env_files() {
    server_port="$(free_port)"
    sed -e "s/^DB_PORT=.*/DB_PORT=$db_port/" \
        -e "s/^DB_APP_PASSWORD=.*/DB_APP_PASSWORD=$app_password/" \
        -e "s/^SERVER_PORT=.*/SERVER_PORT=$server_port/" \
        "$DEPLOY/sies-attendance.env.example" > "$work/env"
    sed -e "s/^DB_PORT=.*/DB_PORT=$db_port/" \
        -e "s/^DB_OWNER_PASSWORD=.*/DB_OWNER_PASSWORD=$owner_password/" \
        -e "s/^PGPASSWORD=.*/PGPASSWORD=$owner_password/" \
        "$DEPLOY/migrate-env.example" > "$work/migrate-env"
    chmod 600 "$work/env" "$work/migrate-env"
}

# "Migrate as a deliberate step, never on service start"
migrate() {
    ( set -a; . "$work/migrate-env"; set +a
      exec timeout 10m "$JAVA" -jar "$JAR" --spring.profiles.active=migrate </dev/null ) \
        || fail "the migrate step failed"
}

# The service as sies-attendance.service runs it, then the checks.
start_and_check_service() {
    local label="$1" container="$2" deadline health code body accounts
    ( set -a; . "$work/env"; set +a
      export SPRING_FLYWAY_ENABLED=false FORWARD_HEADERS=framework
      exec "$JAVA" -jar "$JAR" </dev/null ) > "$work/service-$label.log" 2>&1 &
    service_pid=$!
    note "service started (pid $service_pid) on port $server_port"

    deadline=$(( $(date +%s) + 180 ))
    health=""
    while (( $(date +%s) < deadline )); do
        if ! kill -0 "$service_pid" 2>/dev/null; then
            tail -40 "$work/service-$label.log"
            fail "the service exited during startup"
        fi
        health="$(curl -sS --max-time 5 "http://localhost:$server_port/actuator/health" 2>/dev/null || true)"
        [[ "$health" == *'"UP"'* ]] && break
        sleep 2
    done
    if [[ "$health" != *'"UP"'* ]]; then
        tail -40 "$work/service-$label.log"
        fail "/actuator/health did not report UP within 3 minutes (last answer: ${health:-none})"
    fi
    note "health: $health"

    body="$(curl -sS --max-time 15 -o - -w '\n%{http_code}' -X POST \
        "http://localhost:$server_port/api/v1/auth/login" -H 'Content-Type: application/json' \
        -d '{"email":"restore-drill@invalid.invalid","door":"STAFF","password":"not-a-password"}' \
        2>&1 || true)"
    code="${body##*$'\n'}"
    if [[ "$code" != 401 || "$body" != *sign_in_refused* ]]; then
        tail -40 "$work/service-$label.log"
        fail "a sign-in that should be refused got HTTP ${code:-nothing}: ${body%$'\n'*}"
    fi
    note "a made-up sign-in is refused with 401, so sies_app reads the accounts table"

    accounts="$(docker exec "$container" psql -U postgres -d sies -tAc 'SELECT count(*) FROM app_account')" \
        || fail "cannot count app_account"
    (( accounts > 0 )) || fail "the restored database has no accounts: it is empty, or the wrong one"
    note "$accounts accounts"

    stop_service
}

# Signs in as role $1 with password $2 the way the service does: over TCP to the published
# port, from outside the container, so pg_hba.conf asks for the password. (Inside the
# container, the socket and localhost are trusted and would accept anything.) The password
# goes to psql through the environment, never the command line. On failure, prints psql's
# error and returns non-zero.
role_can_sign_in() {
    PGPASSWORD="$2" docker run --rm --network host -e PGPASSWORD "$IMAGE" \
        psql "host=127.0.0.1 port=$db_port dbname=sies user=$1 connect_timeout=10" \
        -tAc 'SELECT 1' 2>&1 >/dev/null
}

new_passwords() {
    owner_password="${DB_OWNER_PASSWORD:-$(openssl rand -hex 24)}"
    app_password="${DB_APP_PASSWORD:-$(openssl rand -hex 24)}"
    auditor_password="${DB_AUDITOR_PASSWORD:-$(openssl rand -hex 24)}"
}

check_time() {
    local label="$1" began="$2" took
    took=$(( $(date +%s) - began ))
    note "$label: production was back in $((took / 60)) min $((took % 60)) s"
    summary+=("$label: back up in $((took / 60))m$((took % 60))s")
    (( took <= MAX_RESTORE_MINUTES * 60 )) \
        || fail "$label took $((took / 60)) min, more than MAX_RESTORE_MINUTES ($MAX_RESTORE_MINUTES)"
}

# ── 2. From the newest dump ──────────────────────────────────────────────────

began=$(date +%s)
step "dump: starting the database container ($DUMP_DB)"
start_database "$DUMP_DB" "$DUMP_DB-data"

step "dump: restore-backup.sh --into $DUMP_DB ($newest_dump)"
new_passwords
DB_OWNER_PASSWORD="$owner_password" DB_APP_PASSWORD="$app_password" \
DB_AUDITOR_PASSWORD="$auditor_password" \
    timeout 60m "$HERE/restore-backup.sh" --identity "$IDENTITY" \
        "$BACKUP_DIR/$newest_dump" --into "$DUMP_DB" </dev/null \
    || fail "restore-backup.sh could not restore $newest_dump: $(tool_error)"

step "dump: environment files and the migrate step"
write_env_files
migrate

step "dump: starting the service and checking it"
start_and_check_service dump "$DUMP_DB"
check_time "from the dump" "$began"

docker rm -f "$DUMP_DB" >/dev/null
docker volume rm "$DUMP_DB-data" >/dev/null

# ── 3. Point-in-time, to the latest moment ───────────────────────────────────

began=$(date +%s)
step "point-in-time: pitr-restore.sh --to latest --into $PITR"
timeout 60m "$HERE/pitr-restore.sh" --identity "$IDENTITY" --from "$BACKUP_DIR" \
        --to latest --into "$PITR" </dev/null \
    || fail "pitr-restore.sh could not recover to the latest moment: $(tool_error)"
recovered_to="$(grep -oE 'last completed transaction was at log time [^"]*' "$log" | tail -1 || true)"
[[ -n "$recovered_to" ]] && summary+=("point-in-time: ${recovered_to}")

step "point-in-time: swapping in the README's database container ($PITR_DB)"
docker rm -f "$PITR" >/dev/null
# The README's docker run, with -v $PITR-data:/var/lib/postgresql. The volume already holds
# a cluster, so the password file is not used, as on the server.
start_database "$PITR_DB" "$PITR-data"

step "point-in-time: checking the role passwords in the settings against production's"
new_passwords
declare -A role_password=([sies_owner]="$owner_password" [sies_app]="$app_password"
                          [sies_auditor]="$auditor_password")
declare -A role_setting=([sies_owner]=DB_OWNER_PASSWORD [sies_app]=DB_APP_PASSWORD
                         [sies_auditor]=DB_AUDITOR_PASSWORD)
given=(); throwaway=()
for role in sies_owner sies_app sies_auditor; do
    setting="${role_setting[$role]}"
    if [[ -n "${!setting:-}" ]]; then given+=("$role"); else throwaway+=("$role"); fi
done

if (( ${#given[@]} > 0 )); then
    # A made-up password must be refused first. If it is not, the restored pg_hba.conf
    # trusts the host, and a match below would prove nothing.
    if role_can_sign_in "${given[0]}" "drill-not-the-password-$(openssl rand -hex 8)" >/dev/null; then
        fail "the restored database let ${given[0]} sign in with a made-up password: its pg_hba.conf trusts connections from the host, so the passwords in the settings cannot be checked"
    fi
    for role in "${given[@]}"; do
        setting="${role_setting[$role]}"
        if ! err="$(role_can_sign_in "$role" "${role_password[$role]}")"; then
            [[ "$err" == *"password authentication failed"* ]] \
                && fail "$setting in $config is not production's password for $role: the password manager is out of date, or the setting was mistyped"
            fail "could not sign in as $role to check $setting: $err"
        fi
        note "$setting matches production's password for $role"
    done
    summary+=("role passwords match production: ${given[*]}")
fi

if (( ${#throwaway[@]} > 0 )); then
    note "NOTE: no production password in the settings for ${throwaway[*]}; giving the restored role(s) throwaway ones"
    for role in "${throwaway[@]}"; do
        printf "ALTER ROLE %s PASSWORD '%s';\n" "$role" "${role_password[$role]}"
    done | docker exec -i "$PITR_DB" psql -U postgres -d sies -q -v ON_ERROR_STOP=1 -f - \
        || fail "could not set the restored roles' passwords"
fi

step "point-in-time: environment files and the migrate step"
write_env_files
migrate

step "point-in-time: starting the service and checking it"
start_and_check_service pitr "$PITR_DB"
check_time "from point-in-time" "$began"

stage="checking that the copy is current"
(( ${#problems[@]} == 0 )) || fail "the restores worked, but the copy is stale: $(printf '%s; ' "${problems[@]}")"
stage="cleaning up"
