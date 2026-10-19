#!/usr/bin/env bash
#
# Phase 6's gate, in the two halves this environment can run.
#
# The gate as written is the airplane-mode test: three lectures' marks
# taken offline, reconnect, exactly those rows on the server once each
# with the right authorship. Joining the two halves needs a handset, and
# that part is run by hand.
#
# What runs here:
#
#   * SyncTest -- the server half, over real HTTP against a real
#     PostgreSQL as sies_app. Three lectures' marks pushed, replayed,
#     with a forged node refused, another department's lecture refused by
#     the policies, and the audit entries checked for the account, the
#     session and the device.
#
#   * OutboxTriggerTest -- the device half, against real SQLite. A write
#     queues its own key in the same statement, two edits collapse to one
#     intent, and a pull applying rows does not queue them back.
#
# Both halves insist they ran: they skip themselves without Docker, and a
# skipped gate is a green build that proves nothing.
#
#   ./server/tools/verify-sync.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RESULTS="$ROOT/server/build/test-results/test"

if ! docker info >/dev/null 2>&1; then
  echo "FAILED: docker is not available, so the server half cannot be tested"
  echo "        against a real PostgreSQL. SyncTest skips itself without it."
  exit 1
fi

echo "==> running both halves"
"$ROOT/gradlew" -p "$ROOT" :server:test --rerun \
  --tests '*SyncTest*' --tests '*OutboxTriggerTest*'

echo
echo "==> checking they ran"

python3 - "$RESULTS" <<'PYEOF'
import glob
import sys
import xml.etree.ElementTree as ET
from typing import Dict

results_dir: str = sys.argv[1]

wanted: Dict[str, Dict[str, int]] = {
    "SyncTest": {"tests": 0, "failures": 0, "skipped": 0},
    "OutboxTriggerTest": {"tests": 0, "failures": 0, "skipped": 0},
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

print("==> OK: both halves hold. The two have not been joined on a phone;")
print("        that is the airplane-mode test, and it is run by hand.")
PYEOF
