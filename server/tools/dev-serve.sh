#!/usr/bin/env bash
#
# Start the service for development, and prove it is THIS build.
#
#   server/tools/dev-serve.sh            start it on 8080, in the foreground
#   PORT=8081 server/tools/dev-serve.sh  somewhere else
#   server/tools/dev-serve.sh --check    only check what is already running
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

if [ "${1:-}" = "--check" ]; then
    echo "==> what is answering on $PORT"
    curl -sS --fail-with-body --max-time 5 "$BASE/actuator/health" || {
        echo "FAILED: nothing is answering on $PORT"
        exit 1
    }
    echo
    routes || echo "    (mappings are not exposed on this process, so it was not started by
    this script -- restart it with dev-serve.sh to be able to check it)"
    exit 0
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

echo "==> port $PORT is free; starting"
exec "$ROOT/gradlew" -p "$ROOT" :server:bootRun --args="$EXPOSE --server.port=$PORT"
