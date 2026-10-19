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
#   * OutboxTriggerTest and DeviceSqlTest -- the device half, against
#     real SQLite. A write queues its own key in the same statement, two
#     edits collapse to one intent, a pull applying rows does not queue
#     them back, and the statements the phone runs are the ones the
#     shared description says they are.
#
#   * PullApplyTest -- every table in the feed, written into a real
#     device database, with foreign keys on as Room has them. A handset
#     found both of the things this now guards, and both had the same
#     symptom: a page that cannot be applied is a watermark that never
#     moves, and "sync failed" is all the phone can say. First, a pulled
#     academic_years row could not be written at all, because startEpoch
#     is NOT NULL on the device and the server does not keep it. Then a
#     pulled row the device already had could not be written either,
#     because INSERT OR REPLACE deletes before it inserts and the
#     device's own rows point at it with RESTRICT.
#
#   * PullOrderTest -- the order a pulled page is applied in, which
#     became load-bearing when V11 widened the feed from two tables to
#     eleven: Room's foreign keys are RESTRICT, so a lecture written
#     before its course is a page that can never be applied.
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
  --tests '*SyncTest*' --tests '*OutboxTriggerTest*' --tests '*DeviceSqlTest*' \
  --tests '*PullApplyTest*'

"$ROOT/gradlew" -p "$ROOT" :app:testDebugUnitTest --rerun \
  --tests '*PullOrderTest*'

echo
echo "==> checking they ran"

python3 - "$RESULTS" "$ROOT/app/build/test-results/testDebugUnitTest" <<'PYEOF'
import glob
import sys
import xml.etree.ElementTree as ET
from typing import Dict

results_dirs: list[str] = sys.argv[1:]

wanted: Dict[str, Dict[str, int]] = {
    "SyncTest": {"tests": 0, "failures": 0, "skipped": 0},
    "OutboxTriggerTest": {"tests": 0, "failures": 0, "skipped": 0},
    "DeviceSqlTest": {"tests": 0, "failures": 0, "skipped": 0},
    "PullApplyTest": {"tests": 0, "failures": 0, "skipped": 0},
    "PullOrderTest": {"tests": 0, "failures": 0, "skipped": 0},
}

for path in [p for d in results_dirs for p in glob.glob(f"{d}/*.xml")]:
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
