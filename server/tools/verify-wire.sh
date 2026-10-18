#!/usr/bin/env bash
#
# Phase 5.5's gate: that the endpoints exist, that a token is the only way
# to reach them, and that what comes back is what the caller's authority
# allows.
#
# The whole service, booted on a random port, over real HTTP, against a
# real PostgreSQL -- connecting as sies_app, so every policy from V6 and
# every function from V8 applies to it exactly as in production. Not
# MockMvc: what is being tested is the chain, and a mocked servlet layer
# would skip the filter registration, which is one of the things that can
# be wrong.
#
# Runs the tests, then INSISTS they ran. They skip themselves when Docker
# is missing, and a skipped gate is a green build that proves nothing.
#
#   ./server/tools/verify-wire.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RESULTS="$ROOT/server/build/test-results/test"

if ! docker info >/dev/null 2>&1; then
  echo "FAILED: docker is not available, so the endpoints cannot be tested"
  echo "        against a real PostgreSQL. WireTest skips itself without"
  echo "        it, which would make this script pass while proving"
  echo "        nothing."
  exit 1
fi

echo "==> running the endpoint tests against postgres"
"$ROOT/gradlew" -p "$ROOT" :server:test --rerun \
  --tests '*WireTest*' --tests '*LoginRateLimiterTest*'

echo
echo "==> checking they ran"

python3 - "$RESULTS" <<'PYEOF'
import glob
import sys
import xml.etree.ElementTree as ET
from typing import Dict

results_dir: str = sys.argv[1]

# Both classes are required. WireTest is the gate; the rate limiter is
# tested on its own because every request in WireTest comes from
# 127.0.0.1, so a limit low enough to trip would trip on the fixtures.
wanted: Dict[str, Dict[str, int]] = {
    "WireTest": {"tests": 0, "failures": 0, "skipped": 0},
    "LoginRateLimiterTest": {"tests": 0, "failures": 0, "skipped": 0},
}

for path in glob.glob(f"{results_dir}/*.xml"):
    root = ET.parse(path).getroot()
    name: str = root.get("name", "")
    for suite in wanted:
        if name.endswith(suite):
            wanted[suite]["tests"] += int(root.get("tests", 0))
            wanted[suite]["failures"] += int(root.get("failures", 0)) + int(
                root.get("errors", 0)
            )
            wanted[suite]["skipped"] += int(root.get("skipped", 0))

failed: bool = False

for suite, counts in wanted.items():
    print(
        f"==> {suite}: {counts['tests']} tests, "
        f"{counts['failures']} failures, {counts['skipped']} skipped"
    )
    if counts["tests"] == 0:
        print(f"FAILED: {suite} produced no results at all")
        failed = True
    if counts["failures"]:
        print(f"FAILED: a test in {suite} failed")
        failed = True
    if counts["skipped"]:
        print(f"FAILED: a test in {suite} was skipped. A gate that skips is not a pass.")
        failed = True

if failed:
    sys.exit(1)

print("==> OK: the endpoints were tested over HTTP against a real PostgreSQL")
PYEOF
