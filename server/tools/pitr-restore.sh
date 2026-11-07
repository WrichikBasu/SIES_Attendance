#!/usr/bin/env bash
#
# Point-in-time recovery: the database as it was at a chosen moment, from a base backup and
# the WAL after it. Runs on any machine with docker and age; needs the private key and a
# copy of the backup directory (its base/ and wal/), and nothing from the server itself.
#
#   pitr-restore.sh --identity KEY --from DIR --to TIME --check
#       Replays into a throwaway container, prints where recovery stopped and every
#       table's row count, then removes it. Use it to find the right moment before
#       restoring for real, and now and then as a drill.
#
#   pitr-restore.sh --identity KEY --from DIR --to TIME --into NAME
#       Replays into a new container NAME on a new volume NAME-data, and leaves it running
#       (on no published port) for you to inspect and then put in place. Refuses if
#       either exists. Nothing that already exists is touched.
#
# TIME is anything GNU date understands; give a zone, e.g. '2026-11-06 14:30 +05:30'.
# Recovery stops just before the first transaction that committed after it. `--to latest`
# replays every WAL file there is.
#
# --base FILE picks a base backup instead of the newest one that started before TIME.
#
# Roles and their passwords come back with everything else: this is a copy of the whole
# cluster, not a dump. The restored copy has archiving switched off, so it cannot write
# into the archive it came from; turn it back on when it becomes the live database.
#
# The WAL is decrypted straight into the new volume, never onto this machine's disk
# elsewhere, and deleted from the volume once recovery is done.

set -euo pipefail

IMAGE="${PG_IMAGE:-postgres:18}"
AGE="${AGE:-age}"
DATABASE=sies
PGDATA=/var/lib/postgresql/18/docker
WAL_IN=/var/lib/postgresql/wal-restore
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() { sed -n '3,29p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 2; }
die() { printf 'pitr-restore: %s\n' "$*" >&2; exit 1; }

identity="" from="" to="" base="" mode="" name=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --identity) identity="${2:?}"; shift 2 ;;
        --from)     from="${2:?}"; shift 2 ;;
        --to)       to="${2:?}"; shift 2 ;;
        --base)     base="${2:?}"; shift 2 ;;
        --check)    mode=check; shift ;;
        --into)     mode=into; name="${2:?}"; shift 2 ;;
        -h|--help)  usage ;;
        *)          die "unknown argument $1" ;;
    esac
done
[[ -n "$identity" && -n "$from" && -n "$to" && -n "$mode" ]] || usage
[[ -r "$identity" ]] || die "cannot read $identity"
[[ -d "$from/base" && -d "$from/wal" ]] || die "$from has no base/ and wal/ directories"
command -v docker >/dev/null || die "docker is not installed"
command -v "$AGE" >/dev/null || die "age is not installed (Debian and Ubuntu: apt install age)"

# ── The target moment, and the base backup to start from ─────────────────────

if [[ "$to" == latest ]]; then
    target_stamp="99999999T999999Z"
    target_sql=""
else
    target_stamp="$(date -u -d "$to" +%Y%m%dT%H%M%SZ)" || die "cannot read the time '$to'"
    # Handed to PostgreSQL in UTC, written out, so that it and GNU date cannot read the
    # same words differently.
    target_sql="$(date -u -d "$to" '+%Y-%m-%d %H:%M:%S+00')"
    [[ "$target_stamp" < "$(date -u +%Y%m%dT%H%M%SZ)" ]] || die "$to is in the future"
fi

if [[ -z "$base" ]]; then
    base="$(find "$from/base" -maxdepth 1 -name 'base-*.tar.gz.age' -printf '%f\n' | sort \
            | awk -v t="base-$target_stamp" '$0 < t' | tail -1)"
    [[ -n "$base" ]] || die "no base backup started before $to; the oldest is $(
        find "$from/base" -maxdepth 1 -name 'base-*.tar.gz.age' -printf '%f\n' | sort | head -1)"
    base="$from/base/$base"
fi
[[ -r "$base" ]] || die "cannot read $base"
start_file="${base%.tar.gz.age}.start"
start_segment="$(cat "$start_file" 2>/dev/null)" || die "no $start_file beside the base backup"
[[ "$start_segment" =~ ^[0-9A-F]{24}$ ]] || die "$start_file does not name a WAL segment"

echo "==> base backup $(basename "$base"), replaying from $start_segment to ${target_sql:-the end of the WAL}"

# A missing segment ends recovery at the gap. With a target time PostgreSQL notices that it
# stopped short and refuses to finish; with `latest` there is no target to fall short of,
# and it would promote at the gap and report success. So check the chain here: every
# position from the start segment to the newest must be present, on some timeline. Each
# position is the log number times 256 plus the segment, for the default 16 MB segments.
position() { echo $(( 16#${1:8:8} * 256 + 16#${1:16:8} )); }
first="$(position "$start_segment")"
mapfile -t positions < <(find "$from/wal" -maxdepth 1 -regextype posix-extended \
        -regex '.*/[0-9A-F]{24}(\.partial)?\.gz\.age' -printf '%f\n' \
    | while read -r f; do position "$f"; done | sort -nu)
expected="$first"
gap=""
for p in "${positions[@]}"; do
    (( p < first )) && continue
    if (( p != expected )); then
        gap="$(printf '%08X%08X' $((expected / 256)) $((expected % 256)))"
        break
    fi
    expected=$((p + 1))
done
(( expected > first )) || die "no WAL in $from/wal from $start_segment on: is this the right directory?"
if [[ -n "$gap" && -z "$target_sql" ]]; then
    die "WAL is missing at log/segment $gap, so replay would stop there. Pass --to a time before it was written, or recover the missing file."
fi

# ── A volume, and a helper container to fill it ──────────────────────────────

if [[ "$mode" == check ]]; then
    name="sies-pitr-check-$$"
fi
volume="$name-data"
helper="$name-fill"

docker container inspect "$name" >/dev/null 2>&1 && die "a container named $name already exists"
docker volume inspect "$volume" >/dev/null 2>&1 && die "a volume named $volume already exists"

cleanup() {
    docker rm -f "$helper" >/dev/null 2>&1 || true
    if [[ "$mode" == check || "${keep:-no}" != yes ]]; then
        docker rm -f "$name" >/dev/null 2>&1 || true
        docker volume rm "$volume" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

docker volume create "$volume" >/dev/null
# The image runs any command other than postgres as given, so this is a shell with the
# volume mounted and no server.
docker run -d --name "$helper" -v "$volume:/var/lib/postgresql" "$IMAGE" sleep infinity >/dev/null
in_helper() { docker exec -i "$helper" "$@"; }

echo "==> unpacking the base backup"
in_helper sh -c "mkdir -p '$PGDATA' '$WAL_IN'"
set +e
"$AGE" --decrypt --identity="$identity" "$base" | gunzip | in_helper tar -x -C "$PGDATA"
status=("${PIPESTATUS[@]}")
set -e
[[ "${status[0]}" -eq 0 ]] || die "age could not decrypt $(basename "$base") (wrong key, or damaged)"
[[ "${status[1]}" -eq 0 && "${status[2]}" -eq 0 ]] || die "the base backup did not unpack"

echo -n "==> decrypting WAL into the volume"
count=0
while IFS= read -r file; do
    segment="${file%.gz.age}"
    # Every .history file, and every segment from the base backup's start on.
    if [[ "$segment" != *.history ]]; then
        [[ ! "${segment:8:16}" < "${start_segment:8:16}" ]] || continue
    fi
    set +e
    "$AGE" --decrypt --identity="$identity" "$from/wal/$file" | gunzip \
        | in_helper sh -c "cat > '$WAL_IN/$segment'"
    status=("${PIPESTATUS[@]}")
    set -e
    [[ "${status[0]}" -eq 0 && "${status[1]}" -eq 0 && "${status[2]}" -eq 0 ]] \
        || die "could not decrypt $file (wrong key, or damaged)"
    count=$((count + 1))
    (( count % 20 == 0 )) && echo -n "."
done < <(find "$from/wal" -maxdepth 1 -name '*.gz.age' -printf '%f\n' | sort)
echo " $count files"

echo "==> recovery settings"
# Later lines in postgresql.auto.conf win, so these override what the live server set with
# ALTER SYSTEM. Archiving off: this copy must not write into the archive it came from.
#
# Any recovery settings already in the file go first. A database that was itself once
# restored by this script carries them if they were never reset, and a stale
# recovery_target_time would quietly stop a `--to latest` replay at the old moment.
in_helper sed -i -E '/^(restore_command|recovery_target[a-z_]*) *=/d; /^# Added by pitr-restore/d' \
    "$PGDATA/postgresql.auto.conf"
{
    echo "# Added by pitr-restore.sh"
    echo "restore_command = 'cp $WAL_IN/%f \"%p\"'"
    [[ -n "$target_sql" ]] && echo "recovery_target_time = '$target_sql'"
    echo "recovery_target_action = 'promote'"
    echo "archive_mode = 'off'"
} | in_helper sh -c "cat >> '$PGDATA/postgresql.auto.conf'; touch '$PGDATA/recovery.signal'
                     chown -R postgres:postgres /var/lib/postgresql; chmod 700 '$PGDATA'"
docker rm -f "$helper" >/dev/null

echo "==> replaying"
docker run -d --name "$name" -e TZ=UTC -v "$volume:/var/lib/postgresql" "$IMAGE" >/dev/null

done_replaying=no
for _ in $(seq 1 600); do
    if ! docker container inspect -f '{{.State.Running}}' "$name" 2>/dev/null | grep -q true; then
        break
    fi
    if [[ "$(docker exec "$name" psql -U postgres -d "$DATABASE" -tAc \
             'SELECT NOT pg_is_in_recovery()' 2>/dev/null)" == t ]]; then
        done_replaying=yes
        break
    fi
    read -t 0.5 -r _ < /dev/null 2>/dev/null || true
done

if [[ "$done_replaying" != yes ]]; then
    docker logs "$name" 2>&1 | grep -E 'FATAL|ERROR|PANIC|recovery|redo' | tail -15 >&2
    die "recovery did not finish"
fi

echo
docker logs "$name" 2>&1 | grep -E 'recovery stopping|last completed transaction|redo done|archive recovery complete' \
    | sed 's/^.*LOG: */  /'
docker exec "$name" rm -rf "$WAL_IN"
# Recovery is over; take the settings that drove it back out, so that they do not ride
# along in the next base backup of this database. archive_mode stays off until
# sies-wal-archiving.sh enable turns it on.
for setting in restore_command recovery_target_time recovery_target_action; do
    docker exec "$name" psql -U postgres -d "$DATABASE" -qtAc "ALTER SYSTEM RESET $setting"
done
echo
"$HERE/restore-backup.sh" --counts "$name"

if [[ "$mode" == into ]]; then
    keep=yes
    cat <<NEXT

The database as of ${target_sql:-the end of the WAL} is running in container $name, on volume
$volume, on no published port. Look at it with:

  docker exec -it $name psql -U postgres -d sies

To make it the live database (as root, on the server):

  systemctl stop sies-attendance
  docker stop sies-pg && docker rename sies-pg sies-pg-before-restore
  docker rm -f $name
  # the README's docker run for sies-pg, with -v $volume:/var/lib/postgresql
  /opt/sies-attendance/sies-wal-archiving.sh enable
  systemctl start sies-basebackup sies-attendance

The restore starts a new timeline, so take that base backup at once: WAL from the old
timeline after this moment does not replay onto the new one.
NEXT
else
    echo
    echo "==> OK: recovered; the throwaway container and volume are being removed"
fi
