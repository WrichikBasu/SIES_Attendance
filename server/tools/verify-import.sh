#!/usr/bin/env bash
#
# The Phase 3 gate, as far as code can carry it: the importer, run against
# a real PostgreSQL with the real migrations applied.
#
# The tests build genuine .siesbak files from genuine SQLite databases —
# one at schema version 6, so the migration path and the legacy keys that
# id_remap exists for are both exercised — import them, and look at the
# rows afterwards.
#
# This script exists because those tests SKIP themselves when Docker is
# absent, which is right for `./gradlew test` and wrong for a gate. Here,
# no Docker is a failure.
#
#   ./server/tools/verify-import.sh
#
# PG_IMAGE overrides the PostgreSQL version, as it does for the schema
# verifier and the differ.
#
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

if ! docker info >/dev/null 2>&1; then
  echo "FAIL: Docker is not available, and the importer cannot be verified without a" >&2
  echo "      real PostgreSQL. Every test here would skip itself and the gate would" >&2
  echo "      pass having checked nothing." >&2
  exit 1
fi

echo "==> importing into ${PG_IMAGE:-postgres:18}"

# --rerun so a cached green result from a previous run cannot stand in for
# this one. The gate has to actually execute.
./gradlew :server:test --tests '*BackupImporterTest*' --rerun

RESULTS="server/build/test-results/test/TEST-in.basulabs.siesattendance.server.importer.BackupImporterTest.xml"

if [[ ! -f "$RESULTS" ]]; then
  echo "FAIL: no test results at $RESULTS" >&2
  exit 1
fi

# A skipped suite is the failure mode this script is here to catch: the
# build goes green, and nothing ran.
read -r TESTS SKIPPED FAILURES ERRORS < <(
  python3 - "$RESULTS" <<'PY'
import sys, xml.etree.ElementTree as ET
r = ET.parse(sys.argv[1]).getroot()
print(r.get("tests", "0"), r.get("skipped", "0"),
      r.get("failures", "0"), r.get("errors", "0"))
PY
)

echo
echo "==> $TESTS tests, $FAILURES failures, $ERRORS errors, $SKIPPED skipped"

if [[ "$TESTS" -eq 0 || "$SKIPPED" -ne 0 ]]; then
  echo "FAIL: the importer tests did not all run." >&2
  exit 1
fi

if [[ "$FAILURES" -ne 0 || "$ERRORS" -ne 0 ]]; then
  echo "FAIL: the importer did not behave as specified." >&2
  exit 1
fi

echo "==> OK: every importer test ran against a real PostgreSQL and passed"
