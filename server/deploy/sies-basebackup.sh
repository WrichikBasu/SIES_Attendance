#!/usr/bin/env bash
#
# A physical base backup -- the starting point point-in-time recovery replays WAL onto --
# and the pruning of WAL no kept base backup needs. Run nightly by sies-basebackup.timer.
#
#   install -D -m 755 server/deploy/sies-basebackup.sh /opt/sies-attendance/sies-basebackup.sh
#
# Writes, under BACKUP_DIR/base/:
#   base-<UTC time>.tar.gz.age   the data directory, as pg_basebackup's tar, encrypted
#   base-<UTC time>.start        the WAL segment replay starts from (not secret: a file name)
#
# The recovery window is therefore the last KEEP_BASE base backups: with a nightly timer
# and the default of 7, any moment in the past week. Further back, the monthly pg_dumps
# from sies-backup.sh are what is left.
#
# This complements sies-backup.sh and does not replace it. A base backup is a copy of the
# files, readable only by PostgreSQL 18 on the same CPU architecture; a pg_dump restores
# into any later PostgreSQL, anywhere, and is the better copy to hand to another machine.
#
# -X fetch puts the WAL the backup needs to be consistent inside the tar, so a base backup
# restores on its own even if every WAL file after it were lost.

set -euo pipefail
umask 077

BACKUP_DIR="${BACKUP_DIR:-/var/lib/sies-attendance-backups}"
BACKUP_RECIPIENTS="${BACKUP_RECIPIENTS:-/etc/sies-attendance/backup-recipients}"
PG_CONTAINER="${PG_CONTAINER:-sies-pg}"
PG_DATABASE="${PG_DATABASE:-sies}"
KEEP_BASE="${KEEP_BASE:-7}"
AGE="${AGE:-age}"

BASE="$BACKUP_DIR/base"
WAL="$BACKUP_DIR/wal"

die() { printf 'sies-basebackup: %s\n' "$*" >&2; exit 1; }
psql_c() {
    docker exec "$PG_CONTAINER" psql -U postgres -d "$PG_DATABASE" -v ON_ERROR_STOP=1 -tA -c "$1"
}

[[ "$KEEP_BASE" =~ ^[0-9]+$ && "$KEEP_BASE" -ge 1 ]] || die "KEEP_BASE must be 1 or more"
command -v "$AGE" >/dev/null || die "age is not installed (Debian and Ubuntu: apt install age)"
[[ -r "$BACKUP_RECIPIENTS" ]] || die "no recipients file at $BACKUP_RECIPIENTS"
[[ -d "$BACKUP_DIR" ]] || die "$BACKUP_DIR does not exist"
[[ "$(psql_c 'SHOW archive_mode')" == on ]] \
    || die "WAL archiving is off, so a base backup could not be replayed forward; run sies-wal-archiving.sh enable"
mkdir -p -m 700 "$BASE" "$WAL"

exec 8>"$BACKUP_DIR/.base.lock"
flock -n 8 || die "another base backup is running"

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
final="$BASE/base-$stamp.tar.gz.age"
partial="$final.partial"
log="$(mktemp)"
trap 'rm -f "$log" "$partial"' EXIT

find "$BASE" -maxdepth 1 -name '*.partial' -delete

echo "base backup of $PG_CONTAINER to $final"

# --checkpoint=fast: start now rather than at the next scheduled checkpoint. -v is what
# prints the start point, which names the first WAL segment a restore needs.
set +e
docker exec "$PG_CONTAINER" \
        pg_basebackup --username=postgres --no-password --pgdata=- --format=tar \
        --wal-method=fetch --checkpoint=fast --verbose 2>"$log" \
    | gzip -6 \
    | "$AGE" --encrypt --recipients-file="$BACKUP_RECIPIENTS" --output="$partial"
status=("${PIPESTATUS[@]}")
set -e

if [[ "${status[0]}" -ne 0 || "${status[1]}" -ne 0 || "${status[2]}" -ne 0 ]]; then
    cat "$log" >&2
    die "failed: pg_basebackup ${status[0]}, gzip ${status[1]}, age ${status[2]}"
fi

start_lsn="$(sed -n 's/.*write-ahead log start point: \([0-9A-F]*\/[0-9A-F]*\) on timeline.*/\1/p' "$log")"
[[ -n "$start_lsn" ]] || { cat "$log" >&2; die "pg_basebackup did not report its start point"; }
start_segment="$(psql_c "SELECT pg_walfile_name('$start_lsn')")"
[[ "$start_segment" =~ ^[0-9A-F]{24}$ ]] || die "could not name the start segment of $start_lsn"

sync "$partial"
mv "$partial" "$final"
printf '%s\n' "$start_segment" > "$BASE/base-$stamp.start"
sync "$BASE"

echo "wrote $(basename "$final") ($(stat -c %s "$final") bytes), replay starts at $start_segment"

# ── Rotation ─────────────────────────────────────────────────────────────────

mapfile -t bases < <(find "$BASE" -maxdepth 1 -name 'base-*.tar.gz.age' -printf '%f\n' | sort)
removed=0
for ((i = 0; i < ${#bases[@]} - KEEP_BASE; i++)); do
    name="${bases[i]%.tar.gz.age}"
    rm -f "$BASE/$name.tar.gz.age" "$BASE/$name.start"
    removed=$((removed + 1))
done

# WAL from before the oldest kept base backup can never be replayed onto anything. Compared
# on the log and segment numbers alone, so that a segment of a later timeline is weighed by
# its position and not by its timeline. .history files are tiny and every restore after a
# promotion needs them, so they are always kept.
oldest="$(find "$BASE" -maxdepth 1 -name 'base-*.tar.gz.age' -printf '%f\n' | sort | head -1)"
oldest_start="$(cat "$BASE/${oldest%.tar.gz.age}.start")"
[[ "$oldest_start" =~ ^[0-9A-F]{24}$ ]] || die "$oldest has no readable .start file; pruning skipped"
cutoff="${oldest_start:8:16}"

exec 9>"$BACKUP_DIR/.wal.lock"
flock -w 600 9 || die "the WAL directory stayed locked; pruning skipped"

pruned=0
while IFS= read -r file; do
    [[ "$file" =~ ^[0-9A-F]{24} ]] || continue
    position="${file:8:16}"
    if [[ "$position" < "$cutoff" ]]; then
        rm -f "$WAL/$file"
        pruned=$((pruned + 1))
    fi
done < <(find "$WAL" -maxdepth 1 -name '*.gz.age' -printf '%f\n')

echo "kept $(( ${#bases[@]} - removed )) base backups, removed $removed; pruned $pruned WAL files from before $oldest_start"
