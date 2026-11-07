#!/usr/bin/env bash
#
# Turns on WAL archiving in the database container, which is the half of point-in-time
# recovery that runs inside PostgreSQL, and reports whether it is working.
#
#   install -D -m 755 server/deploy/sies-wal-archiving.sh /opt/sies-attendance/sies-wal-archiving.sh
#
#   sies-wal-archiving.sh enable    once, on the server. RESTARTS THE DATABASE CONTAINER: the
#                                   service loses its connections for a few seconds and
#                                   reconnects on its own. Safe to run again.
#   sies-wal-archiving.sh status    whether WAL is being archived and shipped, and how far
#                                   behind the shipper is
#
# How the pieces fit:
#
#   PostgreSQL --archive_command--> wal-staging/ in the container's volume (plaintext)
#   sies-wal-ship.timer, every minute: gzip | age --> BACKUP_DIR/wal/, then deletes the staged copy
#   sies-basebackup.timer, nightly:   pg_basebackup | gzip | age --> BACKUP_DIR/base/
#
# A base backup plus every WAL segment after it replays the database to any moment since.
#
# Why staged in plaintext first: the container has no age and must not hold a key, and an
# archive_command that called out of the container would stall PostgreSQL whenever the host
# side was slow. The staging directory is on the same disk, in the same volume, as the
# database files, which are plaintext too, so it exposes nothing that is not already there.
# What is encrypted is everything that leaves: the files in BACKUP_DIR, which are the ones
# that get copied to other machines.
#
# If the staging directory is missing or full, archive_command fails, and PostgreSQL keeps
# the segment in pg_wal and retries -- it never discards WAL it has not archived. The cost
# of that safety is that pg_wal grows until the disk fills and the database stops. `status`
# shows it long before then: watch "failed" and "waiting".

set -euo pipefail

PG_CONTAINER="${PG_CONTAINER:-sies-pg}"
PG_DATABASE="${PG_DATABASE:-sies}"
BACKUP_DIR="${BACKUP_DIR:-/var/lib/sies-attendance-backups}"
# How long a quiet database may go before PostgreSQL closes a segment so it can be archived:
# the most attendance a lost disk can take with it, on top of the shipper's minute. A
# forced switch still archives a whole 16 MB segment, but with wal_recycle off its unused
# tail is zeros and compresses to almost nothing.
ARCHIVE_TIMEOUT="${ARCHIVE_TIMEOUT:-300}"

STAGE=/var/lib/postgresql/wal-staging
HOOK=/var/lib/postgresql/wal-archive.sh

die() { printf 'sies-wal-archiving: %s\n' "$*" >&2; exit 1; }
psql_c() {
    docker exec "$PG_CONTAINER" psql -U postgres -d "$PG_DATABASE" -v ON_ERROR_STOP=1 -tA -c "$1"
}
wait_for() {
    for _ in $(seq 1 120); do
        psql_c 'SELECT 1' >/dev/null 2>&1 && return 0
        read -t 0.5 -r _ < /dev/null 2>/dev/null || true
    done
    die "$PG_CONTAINER did not come back after the restart"
}

enable() {
    command -v docker >/dev/null || die "docker is not installed"
    psql_c 'SELECT 1' >/dev/null || die "cannot reach database $PG_DATABASE in $PG_CONTAINER"

    echo "==> the staging directory and archive hook, in the container's volume"
    # The hook copies under a temporary name, fsyncs, and renames, so the shipper never sees
    # half a segment. A segment already staged with the same content counts as archived:
    # after a crash between the rename and PostgreSQL recording the success, it asks again,
    # and refusing would stop archiving for good. Different content under the same name is
    # an error, as the PostgreSQL manual asks.
    docker exec -i -u postgres "$PG_CONTAINER" sh -c "
        set -e
        mkdir -p -m 700 '$STAGE'
        cat > '$HOOK.new'
        chmod 700 '$HOOK.new'
        mv '$HOOK.new' '$HOOK'" <<'HOOK'
#!/bin/sh
# Called by PostgreSQL as archive_command: $1 is the segment's path, $2 its file name.
# Written by sies-wal-archiving.sh; edit that, not this.
set -e
stage=/var/lib/postgresql/wal-staging
if [ -f "$stage/$2" ]; then
    cmp -s "$1" "$stage/$2"
    exit $?
fi
cp "$1" "$stage/$2.tmp"
sync "$stage/$2.tmp"
mv "$stage/$2.tmp" "$stage/$2"
sync "$stage"
HOOK

    echo "==> configuring archiving"
    psql_c "ALTER SYSTEM SET archive_mode = 'on'" >/dev/null
    psql_c "ALTER SYSTEM SET archive_command = '$HOOK %p %f'" >/dev/null
    psql_c "ALTER SYSTEM SET archive_timeout = '$ARCHIVE_TIMEOUT'" >/dev/null
    # New segments are created zero-filled instead of reusing old ones, so the tail of a
    # segment closed early by archive_timeout is zeros rather than old WAL. Costs nothing a
    # college register would notice, and keeps a quiet day's archive in kilobytes.
    psql_c "ALTER SYSTEM SET wal_recycle = 'off'" >/dev/null

    if [[ "$(psql_c 'SHOW archive_mode')" != on ]]; then
        echo "==> restarting $PG_CONTAINER (archive_mode only changes on a restart)"
        docker restart "$PG_CONTAINER" >/dev/null
        wait_for
    else
        psql_c 'SELECT pg_reload_conf()' >/dev/null
    fi

    echo "==> testing: closing the current segment and waiting for it to be staged"
    local wanted last
    # A switch closes nothing if the segment is empty, as it is when nothing has been written
    # since the last one -- running this a second time, say. A one-line WAL record first
    # means there is always something to close.
    psql_c "SELECT pg_logical_emit_message(false, 'sies-wal-archiving', 'test')" >/dev/null
    wanted="$(psql_c 'SELECT pg_walfile_name(pg_switch_wal())')"
    for _ in $(seq 1 60); do
        # Segment names sort in the order they are written; empty means nothing yet.
        last="$(psql_c 'SELECT coalesce(last_archived_wal, $$$$) FROM pg_stat_archiver')"
        if [[ -n "$last" && ! "$last" < "$wanted" ]]; then
            echo "  ok   $wanted archived"
            cat <<NEXT

Archiving is on. WAL now collects in the container's $STAGE until
sies-wal-ship.timer moves it out, so enable that next, then take a base backup:

  systemctl enable --now sies-wal-ship.timer sies-basebackup.timer
  systemctl start sies-basebackup
NEXT
            return 0
        fi
        read -t 0.5 -r _ < /dev/null 2>/dev/null || true
    done
    psql_c 'SELECT last_failed_wal, last_failed_time FROM pg_stat_archiver'
    die "the test segment was not archived; see: docker logs $PG_CONTAINER"
}

status() {
    local mode staged
    mode="$(psql_c 'SHOW archive_mode')"
    echo "archive_mode:    $mode"
    [[ "$mode" == on ]] || { echo "archiving is off: run '$0 enable'"; exit 1; }

    psql_c "SELECT 'last archived:   ' || coalesce(last_archived_wal || ' at ' || last_archived_time, 'nothing yet'),
                   'failed:          ' || failed_count
                       || coalesce(' (last ' || last_failed_wal || ' at ' || last_failed_time || ')', ''),
                   'waiting:         ' || (SELECT count(*) FROM pg_ls_archive_statusdir()
                                            WHERE name LIKE '%.ready') || ' segments not yet archived'
              FROM pg_stat_archiver" | tr '|' '\n'

    staged="$(docker exec "$PG_CONTAINER" find "$STAGE" -maxdepth 1 -type f ! -name '*.tmp' 2>/dev/null | wc -l)"
    echo "staged:          $staged segments waiting for the shipper"

    if [[ -d "$BACKUP_DIR/wal" ]]; then
        local newest
        newest="$(find "$BACKUP_DIR/wal" -maxdepth 1 -name '*.gz.age' -printf '%T@ %f\n' \
                     | sort -n | tail -1)"
        if [[ -n "$newest" ]]; then
            echo "newest shipped:  ${newest#* } ($(( $(date +%s) - ${newest%%.*} ))s ago)"
        fi
        echo "base backups:    $(find "$BACKUP_DIR/base" -maxdepth 1 -name 'base-*.tar.gz.age' 2>/dev/null | wc -l)"
    fi
}

case "${1:-}" in
    enable) enable ;;
    status) status ;;
    *) sed -n '3,16p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
