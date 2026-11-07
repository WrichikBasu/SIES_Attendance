#!/usr/bin/env bash
#
# Point-in-time recovery, end to end: archiving switched on with deploy/sies-wal-archiving.sh,
# a base backup, writes after it, a moment noted, a mistake made after that moment, the WAL
# shipped -- then tools/pitr-restore.sh asked for the moment before the mistake, and for
# the latest state, and both checked row by row. Then the refusals, and the rotation.
#
# Needs docker, age and a local postgres image. Leaves nothing behind.
#
#   ./server/tools/verify-pitr.sh
#
set -euo pipefail

IMAGE="${PG_IMAGE:-postgres:18}"
AGE="${AGE:-age}"
AGE_KEYGEN="${AGE_KEYGEN:-age-keygen}"
SOURCE="sies-pitr-verify-src-$$"
RESTORED="sies-pitr-verify-dst-$$"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY="$(dirname "$HERE")/deploy"
WORK="$(mktemp -d)"

cleanup() {
    docker rm -f "$SOURCE" "$RESTORED" >/dev/null 2>&1 || true
    docker volume rm "$SOURCE-data" "$RESTORED-data" >/dev/null 2>&1 || true
    rm -rf "$WORK"
}
trap cleanup EXIT

fail() { echo "FAILED: $*" >&2; exit 1; }
ok() { echo "  ok   $*"; }
command -v "$AGE" >/dev/null || fail "age is not installed (Debian and Ubuntu: apt install age)"

sql() { docker exec "$SOURCE" psql -U postgres -d sies -v ON_ERROR_STOP=1 -qtA -c "$1"; }
sql_in() { docker exec "$1" psql -U postgres -d sies -v ON_ERROR_STOP=1 -qtA -c "$2"; }
pause() { read -t "$1" -r _ < /dev/null 2>/dev/null || true; }

mkdir -m 700 "$WORK/backups"
"$AGE_KEYGEN" -o "$WORK/key.txt" 2>/dev/null
"$AGE_KEYGEN" -y "$WORK/key.txt" > "$WORK/recipients"

export BACKUP_DIR="$WORK/backups" BACKUP_RECIPIENTS="$WORK/recipients" PG_CONTAINER="$SOURCE" AGE

ship() { "$DEPLOY/sies-wal-ship.sh" >/dev/null; }
# Close the current segment and wait until it is staged, so the shipper has everything.
flush() {
    local wanted last
    sql "SELECT pg_logical_emit_message(false, 'verify', 'flush')" >/dev/null
    wanted="$(sql 'SELECT pg_walfile_name(pg_switch_wal())')"
    for _ in $(seq 1 60); do
        last="$(sql 'SELECT coalesce(last_archived_wal, $$$$) FROM pg_stat_archiver')"
        [[ -n "$last" && ! "$last" < "$wanted" ]] && { ship; return; }
        pause 0.5
    done
    fail "segment $wanted was never archived"
}
restore() {
    "$HERE/pitr-restore.sh" --identity "$WORK/key.txt" --from "$WORK/backups" "$@"
}

echo "==> starting $SOURCE ($IMAGE)"
docker run -d --name "$SOURCE" -e POSTGRES_PASSWORD=verify -e POSTGRES_DB=sies -e TZ=UTC \
    -v "$SOURCE-data:/var/lib/postgresql" "$IMAGE" >/dev/null
for _ in $(seq 1 120); do sql 'SELECT 1' >/dev/null 2>&1 && break; pause 0.5; done

echo "==> enabling archiving"
"$DEPLOY/sies-wal-archiving.sh" enable >/dev/null
[[ "$(sql 'SHOW archive_mode')" == on ]] || fail "archive_mode is not on"
ok "archive_mode on, test segment archived"
"$DEPLOY/sies-wal-archiving.sh" enable >/dev/null
ok "enabling it a second time is harmless"

echo "==> rows, a base backup, more rows, a moment, then a mistake"
sql "CREATE TABLE marks (id int PRIMARY KEY, note text NOT NULL)"
sql "INSERT INTO marks SELECT g, 'before the base backup' FROM generate_series(1, 1000) g"
flush
"$DEPLOY/sies-basebackup.sh" >/dev/null
ok "base backup taken"

sql "INSERT INTO marks SELECT g, 'after the base backup' FROM generate_series(1001, 2000) g"
# A segment of its own, so the chain after the base backup is long enough to break in the
# middle further down.
flush
pause 1.5
good="$(sql "SELECT to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') || ' +00:00'")"
pause 1.5
sql "DELETE FROM marks"
sql "INSERT INTO marks VALUES (9999, 'after the mistake')"
flush
ok "the good moment is $good"

staged="$(docker exec "$SOURCE" find /var/lib/postgresql/wal-staging -type f | wc -l)"
[[ "$staged" == 0 ]] || fail "$staged files still staged after shipping"
ok "the shipper emptied the staging directory"

for f in "$WORK/backups"/wal/*.gz.age "$WORK/backups"/base/*.tar.gz.age; do
    head -c 21 "$f" | grep -q '^age-encryption.org/v1' || fail "$(basename "$f") is not age-encrypted"
done
ok "every shipped WAL file and the base backup are age-encrypted"

"$DEPLOY/sies-wal-archiving.sh" status > "$WORK/status.log" || { cat "$WORK/status.log"; fail "status failed"; }
grep -q '^staged: *0 ' "$WORK/status.log" && grep -q '^failed: *0' "$WORK/status.log" \
    || { cat "$WORK/status.log"; fail "status does not report a healthy archive"; }
wal_files="$(ls "$WORK/backups/wal" | wc -l)"
ok "status reports nothing failed or waiting; $wal_files WAL files take $(du -sh "$WORK/backups/wal" | cut -f1) shipped"

"$DEPLOY/sies-wal-ship.sh" >/dev/null || fail "an idle shipper run failed"
ok "a run with nothing staged is a no-op"

echo "==> recovering to the moment before the mistake"
restore --to "$good" --into "$RESTORED" > "$WORK/restore.log" 2>&1 \
    || { tail -20 "$WORK/restore.log"; fail "the restore to $good failed"; }
got="$(sql_in "$RESTORED" "SELECT count(*), count(*) FILTER (WHERE note = 'after the base backup'), count(*) FILTER (WHERE id = 9999) FROM marks")"
[[ "$got" == "2000|1000|0" ]] || fail "expected 2000 rows, 1000 of them after the base backup, none after the mistake; got $got"
ok "2000 rows: the base backup's and the writes after it, and not the mistake"
[[ "$(sql_in "$RESTORED" 'SELECT pg_is_in_recovery()')" == f ]] || fail "still in recovery"
[[ "$(sql_in "$RESTORED" 'SHOW archive_mode')" == off ]] || fail "the restored copy archives into the source's archive"
# SHOW would give the value the server started with; the file is what the next start reads.
left="$(sql_in "$RESTORED" "SELECT count(*) FROM pg_file_settings
    WHERE name IN ('restore_command', 'recovery_target_time', 'recovery_target_action')")"
[[ "$left" == 0 ]] || fail "recovery settings were left in postgresql.auto.conf"
ok "promoted, archiving off, recovery settings cleared"
if restore --to "$good" --into "$RESTORED" >/dev/null 2>&1; then
    fail "a restore over an existing container was allowed"
fi
ok "restoring over an existing container is refused"
docker rm -f "$RESTORED" >/dev/null; docker volume rm "$RESTORED-data" >/dev/null

echo "==> recovering to the latest state"
restore --to latest --into "$RESTORED" > "$WORK/restore.log" 2>&1 \
    || { tail -20 "$WORK/restore.log"; fail "the restore to latest failed"; }
got="$(sql_in "$RESTORED" "SELECT count(*), max(id) FROM marks")"
[[ "$got" == "1|9999" ]] || fail "expected only the row written after the mistake; got $got"
ok "the latest state, mistake and all"
docker rm -f "$RESTORED" >/dev/null; docker volume rm "$RESTORED-data" >/dev/null

echo "==> what must be refused"
if restore --to "2001-01-01 00:00 +00:00" --check > "$WORK/refused.log" 2>&1; then
    fail "a moment before any base backup was accepted"
fi
grep -q 'no base backup started before' "$WORK/refused.log" \
    || { cat "$WORK/refused.log"; fail "refused, but not for having no base backup"; }
ok "a moment before the oldest base backup"
"$AGE_KEYGEN" -o "$WORK/other.txt" 2>/dev/null
if "$HERE/pitr-restore.sh" --identity "$WORK/other.txt" --from "$WORK/backups" --to latest --check \
        > "$WORK/refused.log" 2>&1; then
    fail "a restore with the wrong key succeeded"
fi
grep -q 'age could not decrypt' "$WORK/refused.log" \
    || { cat "$WORK/refused.log"; fail "refused, but not for the key"; }
ok "the wrong key"

# Take out the segment just after the base backup's start, leaving later ones in place. A
# missing *newest* segment cannot be told from one never written, which is why `status`
# reports how recently the shipper ran.
start="$(cat "$WORK/backups"/base/*.start)"
middle="$(ls "$WORK/backups/wal" | grep -E '^[0-9A-F]{24}\.gz\.age$' | sort \
          | awk -v s="$start" 'substr($0, 9, 16) > substr(s, 9, 16)' | head -1)"
[[ -n "$middle" ]] || fail "no segment after the base backup to take out"
mv "$WORK/backups/wal/$middle" "$WORK/$middle"
if restore --to latest --check > "$WORK/gap.log" 2>&1; then
    fail "--to latest succeeded with $middle missing, which would have lost everything after it"
fi
grep -q 'WAL is missing' "$WORK/gap.log" || { cat "$WORK/gap.log"; fail "refused, but not for the gap"; }
# With a target after the gap PostgreSQL itself notices it stopped short.
if restore --to "$good" --check > "$WORK/gap.log" 2>&1; then
    fail "a restore to $good succeeded with $middle missing"
fi
grep -q 'recovery ended before configured recovery target' "$WORK/gap.log" \
    || { cat "$WORK/gap.log"; fail "refused, but not because replay stopped short"; }
mv "$WORK/$middle" "$WORK/backups/wal/$middle"
ok "a missing WAL segment, with --to latest and with a time after it"

echo "==> rotation"
sql "INSERT INTO marks VALUES (10000, 'after the second base backup')"
flush
before="$(ls "$WORK/backups/wal" | wc -l)"
KEEP_BASE=1 "$DEPLOY/sies-basebackup.sh" >/dev/null
flush
[[ "$(ls "$WORK/backups/base"/*.tar.gz.age | wc -l)" == 1 ]] || fail "KEEP_BASE=1 kept more than one base backup"
[[ "$(ls "$WORK/backups/wal" | wc -l)" -lt "$before" ]] || fail "no WAL was pruned"
restore --to latest --into "$RESTORED" > "$WORK/restore.log" 2>&1 \
    || { tail -20 "$WORK/restore.log"; fail "the restore after rotation failed"; }
[[ "$(sql_in "$RESTORED" 'SELECT count(*) FROM marks')" == 2 ]] || fail "wrong rows after rotation"
ok "one base backup kept, older WAL pruned, and what is left still restores"

echo
echo "==> OK: recovery reaches the moment asked for, and refuses what it cannot reach"
