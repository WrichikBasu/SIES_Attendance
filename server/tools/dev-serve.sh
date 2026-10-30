#!/usr/bin/env bash
#
# Start the service for development, and prove it is THIS build.
#
#   server/tools/dev-serve.sh            start it on 8080, in the foreground
#   PORT=8081 server/tools/dev-serve.sh  somewhere else
#   server/tools/dev-serve.sh --check    only check what is already running
#
# Before starting, it makes sure the database is answering. The dev database
# is the sies-pg container from dev-database.sh, and Docker does not restart
# it after a reboot unless told to, so it is started here when it is stopped
# rather than leaving bootRun to fail with "Connection to localhost:55432
# refused". DB_HOST, DB_PORT and DB_CONTAINER override where to look.
#
# This exists because of a mistake made twice: a bootRun that fails with
# "Port 8080 was already in use" leaves the OLD process serving, and
# /actuator/health answers exactly the same from both. A stale server
# therefore looks healthy, serves the new static pages from disk, and
# answers 404 for every endpoint added since it started -- which is a
# confusing bug to be handed.
#
# So: refuse to start when the port is taken, and after starting, ask the
# running process which routes it has rather than whether it is alive.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PORT="${PORT:-8080}"
BASE="http://127.0.0.1:$PORT"

# The mappings endpoint is what makes the check sound. It is exposed only
# by this script, for development, and application.yml is untouched.
EXPOSE="--management.endpoints.web.exposure.include=health,mappings"

routes() {
    curl -sS --fail-with-body --max-time 10 "$BASE/actuator/mappings" | python3 -c '
import json, sys
from typing import List

data = json.load(sys.stdin)
found: List[str] = []

for context in data["contexts"].values():
    for mapping in context["mappings"]["dispatcherServlets"]["dispatcherServlet"]:
        predicate: str = mapping.get("predicate", "")
        if "/api/v1" in predicate:
            found.append(predicate.strip("{}"))

for route in sorted(found):
    print("   ", route)
print(f"\n    {len(found)} endpoints under /api/v1")
'
}

holder() {
    # ss needs privilege for the pid, so fall back to naming neither.
    ss -ltnp 2>/dev/null | awk -v port=":$PORT\$" '$4 ~ port {print $0}' | head -1
}

holder_pid() {
    ss -ltnp 2>/dev/null | awk -v port=":$PORT\$" '$4 ~ port {print $0}' \
        | sed -n 's/.*pid=\([0-9]*\).*/\1/p' | head -1
}

# Whether the build on disk is newer than the process serving it.
#
# The second half of the mistake this script exists to prevent, and it took a
# second evening to learn. Spring serves src/main/resources/static off the
# classpath DIRECTORY, read per request, so editing a page shows up without a
# restart -- while the classes beside it were loaded once, at startup. A
# rebuild without a restart therefore leaves today's page talking to an older
# controller, and the route table is no help because the routes did not
# change. What it looked like: a department dropdown of blank options, because
# the page asked for a label the old controller had never heard of.
staleness() {

    local pid
    pid="$(holder_pid)"

    if [ -z "$pid" ] || [ ! -d "/proc/$pid" ]; then
        echo "    (cannot tell which process holds the port, so cannot say whether it is"
        echo "    this build; restart it with this script to be sure)"
        return 0
    fi

    # /proc/<pid> is stamped with the moment the process started.
    local newer
    newer="$(find "$ROOT/server/build/classes/java/main" "$ROOT/shared/build/classes" \
        -name '*.class' -newer "/proc/$pid" -print -quit 2>/dev/null)"

    if [ -n "$newer" ]; then
        echo
        echo "    STALE: compiled classes are newer than the process on $PORT (pid $pid)."
        echo "           The pages under static/ are read off disk and ARE current; the"
        echo "           classes are not. A page that asks the server for something added"
        echo "           since it started gets an older answer -- and the routes look"
        echo "           identical, so nothing else here will tell you."
        echo
        echo "           kill $pid   then start it again."
        return 1
    fi

    echo "    The process on $PORT started after the last compile, so it is this build."
    return 0
}

if [ "${1:-}" = "--check" ]; then
    echo "==> what is answering on $PORT"
    curl -sS --fail-with-body --max-time 5 "$BASE/actuator/health" || {
        echo "FAILED: nothing is answering on $PORT"
        exit 1
    }
    echo
    routes || echo "    (mappings are not exposed on this process, so it was not started by
    this script -- restart it with dev-serve.sh to be able to check it)"
    echo
    staleness
    exit $?
fi

if curl -sf --max-time 2 "$BASE/actuator/health" >/dev/null 2>&1; then
    echo "FAILED: something is already answering on $PORT, and starting now would leave it"
    echo "        serving while this build goes nowhere. That is the mistake this script"
    echo "        exists to prevent: the old process answers /actuator/health exactly as a"
    echo "        new one would, and 404s every endpoint added since it started."
    echo
    echo "    $(holder)"
    echo
    echo "    Stop it, then run this again. --check says what it has."
    exit 1
fi

# ── The database ────────────────────────────────────────────────────

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-55432}"
DB_CONTAINER="${DB_CONTAINER:-sies-pg}"

db_answers() {
    # Bash's own TCP, so this needs neither psql nor nc on the machine. Under a
    # timeout, because a host that drops packets leaves connect() waiting for minutes.
    timeout 3 bash -c 'exec 3<>"/dev/tcp/$0/$1"' "$DB_HOST" "$DB_PORT" 2>/dev/null
}

ensure_database() {

    if db_answers; then
        echo "==> the database is answering on $DB_HOST:$DB_PORT"
        return 0
    fi

    if [ "$DB_HOST" != "localhost" ] && [ "$DB_HOST" != "127.0.0.1" ]; then
        echo "FAILED: nothing is answering on $DB_HOST:$DB_PORT, and that is not a local"
        echo "        container this script can start."
        return 1
    fi

    if ! command -v docker >/dev/null 2>&1; then
        echo "FAILED: nothing is answering on $DB_HOST:$DB_PORT, and docker is not installed"
        echo "        to start $DB_CONTAINER with."
        return 1
    fi

    local state
    state="$(docker inspect -f '{{.State.Status}}' "$DB_CONTAINER" 2>/dev/null || true)"

    if [ -z "$state" ]; then
        echo "FAILED: nothing is answering on $DB_HOST:$DB_PORT, and there is no container"
        echo "        called $DB_CONTAINER. Create it with:"
        echo
        echo "    ./server/tools/dev-database.sh"
        return 1
    fi

    if [ "$state" != "running" ]; then
        echo "==> $DB_CONTAINER is $state; starting it"
        docker start "$DB_CONTAINER" >/dev/null
    fi

    # Up is not ready: PostgreSQL takes a few seconds to accept connections, and the
    # port is published before it does.
    local waited=0
    until docker exec "$DB_CONTAINER" pg_isready -q -U postgres >/dev/null 2>&1 && db_answers; do
        if [ "$waited" -ge 30 ]; then
            echo "FAILED: $DB_CONTAINER did not accept connections on $DB_HOST:$DB_PORT within"
            echo "        30 seconds. docker logs $DB_CONTAINER says why."
            return 1
        fi
        sleep 1
        waited=$((waited + 1))
    done

    echo "==> the database is answering on $DB_HOST:$DB_PORT"
}

ensure_database || exit 1

echo "==> port $PORT is free; starting"
exec "$ROOT/gradlew" -p "$ROOT" :server:bootRun --args="$EXPOSE --server.port=$PORT"
