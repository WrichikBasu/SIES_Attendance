#!/usr/bin/env bash
#
# Phase 4's gate: that the row-level security policies stop a read the
# grants would have allowed, and that the credential layer behaves.
#
# Runs the two test classes that need a real PostgreSQL, then INSISTS they
# actually ran. A skipped security test is worse than a failing one: it is
# a green build that proves nothing, and the tests skip themselves when
# Docker is missing. This script is what turns that into a failure.
#
#   ./server/tools/verify-rls.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RESULTS="$ROOT/server/build/test-results/test"

if ! docker info >/dev/null 2>&1; then
  echo "FAILED: docker is not available, so the policies cannot be tested"
  echo "        against a real PostgreSQL. These tests skip themselves"
  echo "        without it, which would make this script pass while"
  echo "        proving nothing."
  exit 1
fi

echo "==> running the policy and credential tests against postgres"
"$ROOT/gradlew" -p "$ROOT" :server:test --rerun \
  --tests '*RowLevelSecurityTest*' \
  --tests '*AccountDirectoryTest*'

echo
echo "==> checking they ran"

python3 - "$RESULTS" <<'PYEOF'
import glob
import sys
import xml.etree.ElementTree as ET

results_dir: str = sys.argv[1]
wanted: tuple[str, ...] = ("RowLevelSecurityTest", "AccountDirectoryTest")

total: int = 0
failures: int = 0
skipped: int = 0
seen: set[str] = set()

for path in glob.glob(f"{results_dir}/*.xml"):
    root = ET.parse(path).getroot()
    name: str = root.get("name", "")
    if not any(name.endswith(suffix) for suffix in wanted):
        continue
    seen.add(name.rsplit(".", 1)[-1])
    total += int(root.get("tests", 0))
    failures += int(root.get("failures", 0)) + int(root.get("errors", 0))
    skipped += int(root.get("skipped", 0))

missing: list[str] = [suffix for suffix in wanted if suffix not in seen]

print(f"==> {total} tests, {failures} failures, {skipped} skipped")

if missing:
    print(f"FAILED: these test classes produced no results at all: {', '.join(missing)}")
    sys.exit(1)
if total == 0:
    print("FAILED: no tests ran")
    sys.exit(1)
if failures:
    print("FAILED: a policy or credential test failed")
    sys.exit(1)
if skipped:
    print("FAILED: a test was skipped. A security test that skips is not a pass.")
    sys.exit(1)

print("==> OK: every policy test ran against a real PostgreSQL and passed")
PYEOF
