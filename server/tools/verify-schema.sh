#!/usr/bin/env bash
#
# Applies the Flyway migrations to a throwaway PostgreSQL container and
# reports what landed. This is the Phase 1 gate: "the schema applies from
# empty".
#
# Needs only docker and a local postgres image. Leaves nothing behind.
#
#   ./server/tools/verify-schema.sh            # apply and summarise
#   ./server/tools/verify-schema.sh --dump     # also print the dumped DDL
#
set -euo pipefail

IMAGE="${PG_IMAGE:-postgres:18}"
NAME="sies-schema-verify-$$"
MIGRATIONS="$(cd "$(dirname "${BASH_SOURCE[0]}")/../src/main/resources/db/migration" && pwd)"

cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "==> starting $IMAGE as $NAME"
docker run -d --name "$NAME" \
  -e POSTGRES_PASSWORD=verify \
  -e POSTGRES_DB=sies \
  "$IMAGE" >/dev/null

# pg_isready alone is not enough: the image runs a temporary server while it
# initialises, and answers "ready" on it before POSTGRES_DB has been created.
# Wait for a real query against the real database instead.
echo -n "==> waiting for the server"
ready=no
for _ in $(seq 1 120); do
  if docker exec "$NAME" psql -U postgres -d sies -tAc 'SELECT 1' >/dev/null 2>&1; then
    echo " ready"
    ready=yes
    break
  fi
  echo -n "."
  read -t 0.5 -r _discard < /dev/null 2>/dev/null || true
done
if [[ "$ready" != yes ]]; then
  echo " FAILED"
  docker logs "$NAME" 2>&1 | tail -30
  exit 1
fi

psql_run() {
  docker exec -i "$NAME" psql -U postgres -d sies -v ON_ERROR_STOP=1 -q "$@"
}

for file in "$MIGRATIONS"/V*.sql; do
  echo "==> applying $(basename "$file")"
  psql_run -f - < "$file"
done

TESTS="$(cd "$(dirname "${BASH_SOURCE[0]}")/../src/test/sql" && pwd)"
for file in "$TESTS"/*.sql; do
  [[ -e "$file" ]] || continue
  echo "==> running $(basename "$file")"
  psql_run -f - < "$file"
done

echo
echo "==> tables"
psql_run -c "\dt"
echo "==> enum types and their values"
psql_run -c "
  SELECT t.typname AS type, string_agg(e.enumlabel, ', ' ORDER BY e.enumsortorder) AS values
  FROM pg_type t JOIN pg_enum e ON e.enumtypid = t.oid
  GROUP BY t.typname ORDER BY t.typname;"
echo "==> check constraints"
psql_run -c "
  SELECT conrelid::regclass AS table, conname AS constraint
  FROM pg_constraint WHERE contype IN ('c','x') AND connamespace = 'public'::regnamespace
  ORDER BY 1, 2;"
echo "==> counts"
psql_run -c "
  SELECT
    (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
       WHERE c.relkind = 'r' AND n.nspname = 'public')                        AS tables,
    (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
       WHERE c.relkind = 'i' AND n.nspname = 'public')                        AS indexes,
    (SELECT count(*) FROM pg_constraint WHERE contype = 'f'
       AND connamespace = 'public'::regnamespace)                             AS foreign_keys,
    (SELECT count(*) FROM department_stream)                                  AS department_stream_rows;"

if [[ "${1:-}" == "--dump" ]]; then
  echo "==> schema dump"
  docker exec "$NAME" pg_dump -U postgres -d sies --schema-only --no-owner
fi

echo
echo "==> OK: both migrations applied to an empty database"
