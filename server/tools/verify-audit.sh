#!/usr/bin/env bash
#
# Phase 5's gate: that a change leaves a complete, readable before-and-
# after record, and that the application role cannot alter one.
#
# The second half is what makes the first worth anything, so the tests
# connect as sies_app and sies_auditor rather than as the superuser. A log
# the application can rewrite is not evidence, and the only way to know it
# cannot is to try.
#
# Runs the audit tests, then INSISTS they ran. They skip themselves when
# Docker is missing, and a skipped evidence test is a green build that
# proves nothing.
#
#   ./server/tools/verify-audit.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RESULTS="$ROOT/server/build/test-results/test"

if ! docker info >/dev/null 2>&1; then
  echo "FAILED: docker is not available, so the audit trail cannot be tested"
  echo "        against a real PostgreSQL. These tests skip themselves"
  echo "        without it, which would make this script pass while"
  echo "        proving nothing."
  exit 1
fi

echo "==> running the audit tests against postgres"
"$ROOT/gradlew" -p "$ROOT" :server:test --rerun --tests '*AuditLogTest*'

echo
echo "==> checking they ran"

python3 - "$RESULTS" <<'PYEOF'
import glob
import sys
import xml.etree.ElementTree as ET

results_dir: str = sys.argv[1]

total: int = 0
failures: int = 0
skipped: int = 0
seen: bool = False

for path in glob.glob(f"{results_dir}/*.xml"):
    root = ET.parse(path).getroot()
    if not root.get("name", "").endswith("AuditLogTest"):
        continue
    seen = True
    total += int(root.get("tests", 0))
    failures += int(root.get("failures", 0)) + int(root.get("errors", 0))
    skipped += int(root.get("skipped", 0))

print(f"==> {total} tests, {failures} failures, {skipped} skipped")

if not seen:
    print("FAILED: AuditLogTest produced no results at all")
    sys.exit(1)
if total == 0:
    print("FAILED: no tests ran")
    sys.exit(1)
if failures:
    print("FAILED: an audit test failed")
    sys.exit(1)
if skipped:
    print("FAILED: a test was skipped. An evidence test that skips is not a pass.")
    sys.exit(1)

print("==> OK: the audit trail was tested against a real PostgreSQL and holds")
PYEOF
