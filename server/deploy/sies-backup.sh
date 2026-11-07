#!/usr/bin/env bash
#
# One encrypted, whole-database backup of the server, and the rotation that keeps the
# directory from growing forever. Run by sies-backup.timer; safe to run by hand too.
#
#   install -D -m 755 server/deploy/sies-backup.sh /opt/sies-attendance/sies-backup.sh
#
# What it writes: BACKUP_DIR/sies-<UTC timestamp>.dump.age, which is `pg_dump -Fc` of the
# whole database -- accounts, roles, CR appointments, devices and the audit trail included,
# unlike a .siesbak -- encrypted with age to the public keys in BACKUP_RECIPIENTS.
#
# Why each choice:
#
#   * AS THE SUPERUSER, INSIDE THE CONTAINER. attendance and lectures are under FORCE ROW
#     LEVEL SECURITY, so even sies_owner sees only the rows a policy allows, and pg_dump
#     refuses to dump a table a policy would filter rather than write a partial copy. Only
#     a superuser reads everything. `docker exec` reaches it over the container's own
#     socket with no password, and runs the container's own pg_dump, so the client can
#     never be older than the server (an older pg_dump refuses a newer server).
#
#   * PUBLIC-KEY ENCRYPTION. The file holds every student's attendance and the password
#     hashes of every account, and it is meant to be copied off this machine. Only the
#     public key lives here; the private key is kept by a person, off the server. Someone
#     who takes this machine, or any copy of a backup, cannot read one.
#
#   * NO PLAINTEXT ON DISK. The dump streams straight into age. The only file written is
#     the ciphertext, under a .partial name, and renamed into place once both halves of
#     the pipe have succeeded, so a half-written backup never looks like a good one. age
#     authenticates every chunk, so a file truncated or altered later fails to decrypt
#     rather than restoring rubbish.
#
#   * NO ROLES. The roles are created by tools/roles.sql before a restore, with passwords
#     from the password manager, exactly as on a fresh install. Keeping role passwords out
#     of the backup means a restore never needs them from a file.
#
# Configuration comes from the environment (the unit reads /etc/sies-attendance/backup-env);
# every variable has a default that matches the documented install.

set -euo pipefail
umask 077

BACKUP_DIR="${BACKUP_DIR:-/var/lib/sies-attendance-backups}"
BACKUP_RECIPIENTS="${BACKUP_RECIPIENTS:-/etc/sies-attendance/backup-recipients}"
PG_CONTAINER="${PG_CONTAINER:-sies-pg}"
PG_DATABASE="${PG_DATABASE:-sies}"
# The newest KEEP_DAILY backups are kept, and so is the first backup of each of the last
# KEEP_MONTHLY months. With a daily timer the defaults keep two weeks of days and a year of
# months: about 26 files.
KEEP_DAILY="${KEEP_DAILY:-14}"
KEEP_MONTHLY="${KEEP_MONTHLY:-12}"
AGE="${AGE:-age}"

log() { printf '%s\n' "$*"; }
die() { printf 'sies-backup: %s\n' "$*" >&2; exit 1; }

[[ "$KEEP_DAILY" =~ ^[0-9]+$ && "$KEEP_DAILY" -ge 1 ]] || die "KEEP_DAILY must be 1 or more"
[[ "$KEEP_MONTHLY" =~ ^[0-9]+$ ]] || die "KEEP_MONTHLY must be a number"
command -v "$AGE" >/dev/null || die "age is not installed (Debian and Ubuntu: apt install age)"
command -v docker >/dev/null || die "docker is not installed"
[[ -r "$BACKUP_RECIPIENTS" ]] || die "no recipients file at $BACKUP_RECIPIENTS"
grep -qE '^(age1|ssh-)' "$BACKUP_RECIPIENTS" \
    || die "$BACKUP_RECIPIENTS has no age or ssh public key in it"
[[ -d "$BACKUP_DIR" ]] || die "$BACKUP_DIR does not exist"

# One run at a time: a manual run during the timer's would otherwise race the rotation.
exec 9>"$BACKUP_DIR/.lock"
flock -n 9 || die "another backup is running"

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
final="$BACKUP_DIR/sies-$stamp.dump.age"
partial="$final.partial"

# A .partial left by a run that was killed (power cut, OOM) is worthless; clear it so it
# cannot fill the disk one night at a time.
find "$BACKUP_DIR" -maxdepth 1 -name 'sies-*.dump.age.partial' -delete

log "backing up $PG_DATABASE from container $PG_CONTAINER to $final"

# --no-password: never wait on a prompt under a timer. --no-sync is not used: the dump goes
# to a pipe, and the file is fsynced below before the rename.
set +e
docker exec "$PG_CONTAINER" \
        pg_dump --username=postgres --dbname="$PG_DATABASE" --no-password \
        --format=custom --compress=6 \
    | "$AGE" --encrypt --recipients-file="$BACKUP_RECIPIENTS" --output="$partial"
status=("${PIPESTATUS[@]}")
set -e

if [[ "${status[0]}" -ne 0 || "${status[1]}" -ne 0 ]]; then
    rm -f "$partial"
    die "failed: pg_dump exited ${status[0]}, age exited ${status[1]}"
fi

# An empty or tiny file means pg_dump wrote nothing useful, whatever it exited with. A
# migrated, empty database already dumps to tens of kilobytes.
size="$(stat -c %s "$partial")"
if [[ "$size" -lt 4096 ]]; then
    rm -f "$partial"
    die "failed: the backup is only $size bytes"
fi

sync "$partial"
mv "$partial" "$final"
sync "$BACKUP_DIR"

log "wrote $(basename "$final") ($size bytes)"

# ── Rotation ─────────────────────────────────────────────────────────────────
#
# The names sort by time, so the listing below is oldest to newest. A file is kept if it is
# one of the newest KEEP_DAILY, or the first of its month and its month is one of the newest
# KEEP_MONTHLY months that have a backup. Everything else is deleted.

mapfile -t backups < <(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'sies-*.dump.age' \
                           -printf '%f\n' | sort)

declare -A keep=()
count="${#backups[@]}"

for ((i = count - KEEP_DAILY; i < count; i++)); do
    (( i >= 0 )) && keep["${backups[i]}"]=1
done

declare -A first_of_month=()
for name in "${backups[@]}"; do
    month="${name:5:6}"                       # sies-YYYYMM...
    [[ -n "${first_of_month[$month]:-}" ]] || first_of_month[$month]="$name"
done

if (( KEEP_MONTHLY > 0 )); then
    mapfile -t months < <(printf '%s\n' "${!first_of_month[@]}" | sort -r | head -n "$KEEP_MONTHLY")
    for month in "${months[@]}"; do
        keep["${first_of_month[$month]}"]=1
    done
fi

removed=0
for name in "${backups[@]}"; do
    if [[ -z "${keep[$name]:-}" ]]; then
        rm -f "$BACKUP_DIR/$name"
        removed=$((removed + 1))
    fi
done

log "kept ${#keep[@]} backups, removed $removed"
