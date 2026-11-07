#!/usr/bin/env bash
#
# Moves WAL that PostgreSQL has archived into the container's staging directory out to
# BACKUP_DIR/wal/, compressed and encrypted to the same age keys as every other backup.
# Run every minute by sies-wal-ship.timer. See sies-wal-archiving.sh for the whole picture.
#
#   install -D -m 755 server/deploy/sies-wal-ship.sh /opt/sies-attendance/sies-wal-ship.sh
#
# A staged file is deleted only after its encrypted copy is fsynced and renamed into place,
# so a crash at any point leaves the segment in one place or both, never neither. Shipping
# the same segment twice overwrites it with identical content.

set -euo pipefail
umask 077

BACKUP_DIR="${BACKUP_DIR:-/var/lib/sies-attendance-backups}"
BACKUP_RECIPIENTS="${BACKUP_RECIPIENTS:-/etc/sies-attendance/backup-recipients}"
PG_CONTAINER="${PG_CONTAINER:-sies-pg}"
AGE="${AGE:-age}"

STAGE=/var/lib/postgresql/wal-staging
WAL="$BACKUP_DIR/wal"

die() { printf 'sies-wal-ship: %s\n' "$*" >&2; exit 1; }

command -v "$AGE" >/dev/null || die "age is not installed (Debian and Ubuntu: apt install age)"
[[ -r "$BACKUP_RECIPIENTS" ]] || die "no recipients file at $BACKUP_RECIPIENTS"
[[ -d "$BACKUP_DIR" ]] || die "$BACKUP_DIR does not exist"
mkdir -p -m 700 "$WAL"

# Shared with sies-basebackup.sh's pruning, so a prune never races a segment being written.
exec 9>"$BACKUP_DIR/.wal.lock"
flock -w 600 9 || die "the WAL directory is locked by something that has not finished"

# Leftovers from a run that was killed: the staged original is still there and will be
# shipped again below.
find "$WAL" -maxdepth 1 -name '*.partial' -delete

mapfile -t staged < <(docker exec "$PG_CONTAINER" \
    find "$STAGE" -maxdepth 1 -type f ! -name '*.tmp' -printf '%f\n' | sort)

[[ ${#staged[@]} -gt 0 ]] || exit 0

shipped=0
for name in "${staged[@]}"; do
    # Only what PostgreSQL names its archive files: segments, .history, .backup, .partial.
    [[ "$name" =~ ^[0-9A-F]{8}(\.history|[0-9A-F]{16}(\.[0-9A-F]{8}\.backup|\.partial)?)$ ]] \
        || die "unexpected file in staging: $name"

    target="$WAL/$name.gz.age"
    set +e
    docker exec "$PG_CONTAINER" cat "$STAGE/$name" \
        | gzip -6 \
        | "$AGE" --encrypt --recipients-file="$BACKUP_RECIPIENTS" --output="$target.partial"
    status=("${PIPESTATUS[@]}")
    set -e
    if [[ "${status[0]}" -ne 0 || "${status[1]}" -ne 0 || "${status[2]}" -ne 0 ]]; then
        rm -f "$target.partial"
        die "failed on $name: cat ${status[0]}, gzip ${status[1]}, age ${status[2]}; it stays staged"
    fi

    sync "$target.partial"
    mv "$target.partial" "$target"
    sync "$WAL"

    docker exec "$PG_CONTAINER" rm -f "$STAGE/$name"
    shipped=$((shipped + 1))
done

echo "shipped $shipped: ${staged[0]} .. ${staged[-1]}"
