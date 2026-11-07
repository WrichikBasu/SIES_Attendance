#!/usr/bin/env bash
#
# Restores a backup written by deploy/sies-backup.sh, on this machine or any other with
# docker and age. The private key never has to be on the server that took the backup.
#
#   restore-backup.sh --identity KEY FILE --check
#       A drill: restores into a throwaway container, prints what came back, and removes
#       it. Touches nothing else. Run it on a backup now and then; a backup nobody has
#       restored is a hope, not a backup.
#
#   restore-backup.sh --identity KEY FILE --into CONTAINER
#       Restores into the database `sies` of a running postgres:18 container that has no
#       tables yet: a new machine set up as in the README, up to but not including the
#       migrate step. Creates the three roles first if they are missing, with passwords
#       from DB_OWNER_PASSWORD, DB_APP_PASSWORD and DB_AUDITOR_PASSWORD (prompted for if
#       unset). Use the passwords in the college's password manager, so the environment
#       files written for the old machine work unchanged on the new one.
#
#   restore-backup.sh --counts CONTAINER
#       Prints every table's row count, the latest migration and the database size. Used
#       by the other two modes and by verify-backup.sh; handy to compare against the source.
#
# KEY is an age identity file (from age-keygen) or an ssh private key whose public half is
# in the server's backup-recipients. Set PG_IMAGE to override postgres:18 for --check.
#
# Nothing is decrypted to disk: age streams into pg_restore inside the container. The
# restore is one transaction, so a failure leaves the target database as empty as it was.

set -euo pipefail

IMAGE="${PG_IMAGE:-postgres:18}"
DATABASE="sies"
AGE="${AGE:-age}"
ROLES_SQL="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/roles.sql"

usage() { sed -n '3,27p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 2; }
die() { printf 'restore-backup: %s\n' "$*" >&2; exit 1; }

psql_in() {
    local container="$1"; shift
    docker exec -i "$container" psql -U postgres -d "$DATABASE" -v ON_ERROR_STOP=1 -q "$@"
}

wait_for() {
    # pg_isready answers before POSTGRES_DB exists; wait for a real query instead.
    local container="$1"
    for _ in $(seq 1 120); do
        if docker exec "$container" psql -U postgres -d "$DATABASE" -tAc 'SELECT 1' \
                >/dev/null 2>&1; then
            return 0
        fi
        read -t 0.5 -r _ < /dev/null 2>/dev/null || true
    done
    docker logs "$container" 2>&1 | tail -20 >&2
    die "$container did not become ready"
}

print_counts() {
    local container="$1"
    # Every table, partitions of audit_log included, counted exactly. As the superuser, so
    # row-level security hides nothing.
    psql_in "$container" -P pager=off -c "
        SELECT c.relname AS table,
               (xpath('/row/n/text()',
                      query_to_xml(format('SELECT count(*) AS n FROM %I.%I',
                                          n.nspname, c.relname), false, true, '')))[1]::text::bigint
                   AS rows
          FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
         ORDER BY c.relname;"
    # Flyway's table exists only where Flyway ran: always in production, not in the
    # verification containers, which apply the migrations with psql. Asked separately
    # because a query naming a missing table fails however it is guarded.
    if [[ "$(psql_in "$container" -tA -c \
            "SELECT to_regclass('flyway_schema_history') IS NOT NULL")" == t ]]; then
        psql_in "$container" -tA -c "
            SELECT 'latest migration: V' || version || ' ' || description
              FROM flyway_schema_history WHERE success
             ORDER BY installed_rank DESC LIMIT 1;"
    fi
}

print_size() {
    psql_in "$1" -tA -c \
        "SELECT 'database size: ' || pg_size_pretty(pg_database_size(current_database()));"
}

ensure_roles() {
    local container="$1" missing
    missing="$(psql_in "$container" -tA -c "
        SELECT count(*) FROM unnest(ARRAY['sies_owner', 'sies_app', 'sies_auditor']) r
         WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r);")"

    if [[ "$missing" == 0 ]]; then
        echo "==> the three roles already exist; keeping their passwords"
        return
    fi
    if [[ "$missing" != 3 ]]; then
        die "only some of sies_owner, sies_app and sies_auditor exist; fix that by hand first"
    fi

    local var
    for var in DB_OWNER_PASSWORD DB_APP_PASSWORD DB_AUDITOR_PASSWORD; do
        if [[ -z "${!var:-}" ]]; then
            read -rsp "$var: " "$var"; echo >&2
            [[ -n "${!var}" ]] || die "$var is empty"
        fi
    done

    echo "==> creating the roles (tools/roles.sql)"
    # Through stdin, so the passwords are not in the process list.
    { printf '\\set owner_password %s\n\\set app_password %s\n\\set auditor_password %s\n' \
          "$DB_OWNER_PASSWORD" "$DB_APP_PASSWORD" "$DB_AUDITOR_PASSWORD"
      cat "$ROLES_SQL"
    } | psql_in "$container" -f -
}

restore_into() {
    local container="$1" identity="$2" file="$3" tables

    tables="$(psql_in "$container" -tA -c "
        SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p');")"
    [[ "$tables" == 0 ]] \
        || die "database $DATABASE in $container already has $tables tables; restore only into an empty one"

    ensure_roles "$container"

    echo "==> restoring $(basename "$file")"
    set +e
    "$AGE" --decrypt --identity="$identity" "$file" \
        | docker exec -i "$container" pg_restore --username=postgres --dbname="$DATABASE" \
              --no-password --exit-on-error --single-transaction
    local status=("${PIPESTATUS[@]}")
    set -e
    if [[ "${status[0]}" -ne 0 ]]; then
        die "age could not decrypt $file (wrong key, or the file is damaged or incomplete)"
    fi
    [[ "${status[1]}" -eq 0 ]] || die "pg_restore failed; nothing was restored"

    # Planner statistics are not in a dump. Without them the first queries plan badly.
    psql_in "$container" -c 'ANALYZE;'
    echo "==> restored"
}

mode="" identity="" file="" container=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --identity) identity="${2:?}"; shift 2 ;;
        --check)    mode=check; shift ;;
        --into)     mode=into; container="${2:?}"; shift 2 ;;
        --counts)   mode=counts; container="${2:?}"; shift 2 ;;
        -h|--help)  usage ;;
        -*)         die "unknown option $1" ;;
        *)          [[ -z "$file" ]] || die "one backup file at a time"; file="$1"; shift ;;
    esac
done

command -v docker >/dev/null || die "docker is not installed"

case "$mode" in
    counts)
        print_counts "$container"
        ;;
    check|into)
        [[ -n "$file" && -n "$identity" ]] || usage
        [[ -r "$file" ]] || die "cannot read $file"
        [[ -r "$identity" ]] || die "cannot read $identity"
        command -v "$AGE" >/dev/null || die "age is not installed (Debian and Ubuntu: apt install age)"

        if [[ "$mode" == check ]]; then
            container="sies-restore-check-$$"
            trap 'docker rm -f "$container" >/dev/null 2>&1 || true' EXIT
            echo "==> starting $IMAGE as $container"
            docker run -d --name "$container" -e POSTGRES_PASSWORD=check \
                -e POSTGRES_DB="$DATABASE" -e TZ=UTC "$IMAGE" >/dev/null
            wait_for "$container"
            # Throwaway passwords: the container is gone in a minute.
            DB_OWNER_PASSWORD="$(openssl rand -hex 16)"
            DB_APP_PASSWORD="$(openssl rand -hex 16)"
            DB_AUDITOR_PASSWORD="$(openssl rand -hex 16)"
        else
            wait_for "$container"
        fi

        restore_into "$container" "$identity" "$file"
        echo
        print_counts "$container"
        print_size "$container"

        if [[ "$mode" == into ]]; then
            cat <<NEXT

Next, on this machine: install the jar, the units and the environment files as in the
README, then run the migrate step. It applies only migrations newer than the backup, so
a backup from an older release is brought up to date. Then start the service.
NEXT
        else
            echo
            echo "==> OK: the backup restores; the throwaway container is being removed"
        fi
        ;;
    *)
        usage
        ;;
esac
