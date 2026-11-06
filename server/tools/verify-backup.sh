#!/usr/bin/env bash
#
# The backup, end to end: deploy/sies-backup.sh against a migrated database holding real
# rows, then tools/restore-backup.sh into a second, empty one, and the two compared table by
# table and object by object. Then the rotation, and the failures that must not leave a file
# that looks like a backup.
#
# Needs docker, age and a local postgres image. Leaves nothing behind.
#
#   ./server/tools/verify-backup.sh
#
set -euo pipefail

IMAGE="${PG_IMAGE:-postgres:18}"
AGE="${AGE:-age}"
AGE_KEYGEN="${AGE_KEYGEN:-age-keygen}"
SOURCE="sies-backup-verify-src-$$"
TARGET="sies-backup-verify-dst-$$"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER="$(dirname "$HERE")"
MIGRATIONS="$SERVER/src/main/resources/db/migration"
SMOKE="$SERVER/src/test/sql/constraint_smoke_test.sql"
BACKUP="$SERVER/deploy/sies-backup.sh"
RESTORE="$HERE/restore-backup.sh"

WORK="$(mktemp -d)"
cleanup() {
    docker rm -f "$SOURCE" "$TARGET" >/dev/null 2>&1 || true
    rm -rf "$WORK"
}
trap cleanup EXIT

fail() { echo "FAILED: $*" >&2; exit 1; }
ok() { echo "  ok   $*"; }

command -v "$AGE" >/dev/null || fail "age is not installed (Debian and Ubuntu: apt install age)"

start() {
    docker run -d --name "$1" -e POSTGRES_PASSWORD=verify -e POSTGRES_DB=sies \
        -e TZ=UTC "$IMAGE" >/dev/null
}
psql_in() {
    local container="$1"; shift
    docker exec -i "$container" psql -U postgres -d sies -v ON_ERROR_STOP=1 -q "$@"
}
wait_for() {
    for _ in $(seq 1 120); do
        docker exec "$1" psql -U postgres -d sies -tAc 'SELECT 1' >/dev/null 2>&1 && return 0
        read -t 0.5 -r _ < /dev/null 2>/dev/null || true
    done
    docker logs "$1" 2>&1 | tail -20
    fail "$1 did not become ready"
}

echo "==> starting $SOURCE and $TARGET ($IMAGE)"
start "$SOURCE"
start "$TARGET"
wait_for "$SOURCE"
wait_for "$TARGET"

echo "==> the source: roles, every migration, and the smoke test's rows, kept"
{ printf '\\set owner_password verify\n\\set app_password verify\n\\set auditor_password verify\n'
  cat "$HERE/roles.sql"
} | psql_in "$SOURCE" -f -

# As the owner, the way Flyway applies them, so the objects are owned as in production.
for file in $(ls "$MIGRATIONS"/V*.sql | sort -t V -k2 -n); do
    { echo 'SET ROLE sies_owner;'; cat "$file"; } | psql_in "$SOURCE" -f -
done

# The smoke test's legitimate chain -- students, courses, batches, lectures, attendance --
# committed instead of rolled back. Rows in lectures and attendance matter most: they are
# under FORCE ROW LEVEL SECURITY, the reason the backup runs as the superuser.
{ sed -n '1,/^-- ── The ID scheme/p' "$SMOKE"; echo 'COMMIT;'; } \
    | psql_in "$SOURCE" -f - 2>/dev/null

rows="$(psql_in "$SOURCE" -tA -c 'SELECT (SELECT count(*) FROM lectures) * (SELECT count(*) FROM attendance)')"
[[ "$rows" -gt 0 ]] || fail "the seed left no lectures or attendance to back up"
ok "source holds lectures and attendance"

echo "==> a key, and a backup"
"$AGE_KEYGEN" -o "$WORK/key.txt" 2>/dev/null
"$AGE_KEYGEN" -y "$WORK/key.txt" > "$WORK/recipients"
mkdir -m 700 "$WORK/backups"

backup() {
    BACKUP_DIR="$WORK/backups" BACKUP_RECIPIENTS="$WORK/recipients" \
        PG_CONTAINER="$SOURCE" AGE="$AGE" "$BACKUP" "$@"
}
backup >/dev/null

shopt -s nullglob
files=("$WORK/backups"/sies-*.dump.age)
[[ ${#files[@]} -eq 1 ]] || fail "expected one backup, found ${#files[@]}"
file="${files[0]}"
ok "wrote $(basename "$file")"

if head -c 5 "$file" | grep -q PGDMP || grep -qa 'B RAO' "$file"; then
    fail "the backup is readable without the key"
fi
ok "it is encrypted: no pg_dump header and no student name in it"

echo "==> restoring into $TARGET"
# The target has no roles yet, so the restore creates them; these stand in for the
# passwords from the password manager.
DB_OWNER_PASSWORD=verify DB_APP_PASSWORD=verify DB_AUDITOR_PASSWORD=verify \
    "$RESTORE" --identity "$WORK/key.txt" "$file" --into "$TARGET" < /dev/null \
    > "$WORK/restore.log" 2>&1 \
    || { tail -20 "$WORK/restore.log"; fail "the restore failed"; }

counts() { "$RESTORE" --counts "$1"; }
diff <(counts "$SOURCE") <(counts "$TARGET") > "$WORK/counts.diff" \
    || { cat "$WORK/counts.diff"; fail "row counts differ after the restore"; }
ok "every table has the same number of rows"

# Parentheses are dropped before comparing. A SQL-standard function body (BEGIN ATOMIC) is
# stored parsed, so pg_dump prints it deparsed, and the restore parses that text again:
# `(a OR b) OR c` comes back as `a OR b OR c`. Same function, different text. Anything that
# matters -- a missing table, policy, grant, owner or trigger -- is a missing or changed line
# either way. The \restrict lines carry a random key that differs on every run.
schema() {
    docker exec "$1" pg_dump -U postgres -d sies --schema-only \
        | grep -v '^\\restrict\|^\\unrestrict' | tr -d '()'
}
diff <(schema "$SOURCE") <(schema "$TARGET") > "$WORK/schema.diff" \
    || { head -40 "$WORK/schema.diff"; fail "the schema differs after the restore"; }
ok "the schema, policies, grants and owners are identical"

data() {
    docker exec "$1" pg_dump -U postgres -d sies --data-only --table=attendance --table=lectures \
        | grep -v '^\\restrict\|^\\unrestrict'
}
diff <(data "$SOURCE") <(data "$TARGET") > "$WORK/data.diff" \
    || { head -20 "$WORK/data.diff"; fail "attendance or lectures differ"; }
ok "attendance and lectures are identical, row for row"

policy="$(psql_in "$TARGET" -tA -c "SELECT relforcerowsecurity FROM pg_class WHERE relname = 'attendance'")"
[[ "$policy" == t ]] || fail "attendance lost FORCE ROW LEVEL SECURITY"
ok "row-level security is still forced"

echo "==> restoring into a database that is not empty is refused"
if "$RESTORE" --identity "$WORK/key.txt" "$file" --into "$TARGET" >/dev/null 2>&1; then
    fail "a second restore over the first was allowed"
fi
ok "refused"

echo "==> the wrong key cannot restore it"
"$AGE_KEYGEN" -o "$WORK/other.txt" 2>/dev/null
if "$RESTORE" --identity "$WORK/other.txt" "$file" --check >/dev/null 2>&1; then
    fail "a backup restored with a key it was not encrypted to"
fi
ok "refused"

echo "==> a damaged backup cannot restore"
cp "$file" "$WORK/damaged.age"
size="$(stat -c %s "$WORK/damaged.age")"
printf '\xff' | dd of="$WORK/damaged.age" bs=1 seek=$((size / 2)) conv=notrunc 2>/dev/null
if "$RESTORE" --identity "$WORK/key.txt" "$WORK/damaged.age" --check >/dev/null 2>&1; then
    fail "a damaged backup restored"
fi
head -c $((size - 100)) "$file" > "$WORK/truncated.age"
if "$RESTORE" --identity "$WORK/key.txt" "$WORK/truncated.age" --check >/dev/null 2>&1; then
    fail "a truncated backup restored"
fi
ok "a flipped byte and a truncated file are both refused"

echo "==> a failed dump leaves no backup behind"
before="$(ls "$WORK/backups" | wc -l)"
if BACKUP_DIR="$WORK/backups" BACKUP_RECIPIENTS="$WORK/recipients" \
        PG_CONTAINER="$SOURCE" PG_DATABASE=no_such_database AGE="$AGE" \
        "$BACKUP" >/dev/null 2>&1; then
    fail "a backup of a database that does not exist succeeded"
fi
[[ "$(ls "$WORK/backups" | wc -l)" == "$before" ]] || fail "the failed run left a file"
ok "exited non-zero and left nothing"

echo "==> rotation"
rm -f "$WORK/backups"/sies-*.dump.age
# Ninety made-up daily backups, 1 January to 30 March 2020, then one real run on top, which
# is dated now and so is in a later month than all of them.
for day in $(seq 0 89); do
    : > "$WORK/backups/sies-$(date -u -d "2020-01-01 +$day days" +%Y%m%d)T023000Z.dump.age"
done
KEEP_DAILY=5 KEEP_MONTHLY=3 backup >/dev/null
# Its name sorts last, being the only one not from 2020.
real="$(ls "$WORK/backups" | sort | tail -1)"
# The newest five: four made-up days and the real one. The first of each of the newest three
# months: the real one again, 1 March and 1 February. January goes.
expected="$(printf '%s\n' sies-20200201T023000Z.dump.age sies-20200301T023000Z.dump.age \
    sies-20200327T023000Z.dump.age sies-20200328T023000Z.dump.age \
    sies-20200329T023000Z.dump.age sies-20200330T023000Z.dump.age "$real" | sort)"
actual="$(ls "$WORK/backups" | sort)"
[[ "$actual" == "$expected" ]] \
    || fail "rotation kept the wrong files:"$'\n'"$actual"$'\n'"expected:"$'\n'"$expected"
ok "kept 7 of 91: the newest five, and the first of each of the newest three months"

echo
echo "==> OK: a backup restores to an identical database, and only with its key"
