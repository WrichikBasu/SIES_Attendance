#!/usr/bin/env bash
#
# A local PostgreSQL for working on the server: the three roles, every
# migration, and a database that survives a restart.
#
# Not for anything but development. The passwords below are fixed and
# printed to the terminal; a real deployment takes them from somewhere
# that is not a shell script.
#
#   server/tools/dev-database.sh          start it, or re-apply migrations
#   server/tools/dev-database.sh --reset  destroy it and start again
#   server/tools/dev-database.sh --env    print the variables to run with
#
# tools/verify-schema.sh is the other half of this: it throws a container
# away after checking the schema. This one keeps it.

set -euo pipefail

NAME="${NAME:-sies-pg}"
VOLUME="${VOLUME:-sies-pgdata}"
IMAGE="${PG_IMAGE:-postgres:18}"
# 55432 rather than 5432, so this cannot collide with a PostgreSQL already
# running on the machine. application.yml defaults to the same port.
PORT="${PORT:-55432}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER="$(dirname "$HERE")"

OWNER_PASSWORD="devonly-owner"
APP_PASSWORD="devonly-app"
AUDITOR_PASSWORD="devonly-auditor"

print_env() {
    cat <<ENV
export DB_HOST=localhost
export DB_PORT=$PORT
export DB_NAME=sies
export DB_OWNER_USER=sies_owner
export DB_OWNER_PASSWORD=$OWNER_PASSWORD
export DB_APP_USER=sies_app
export DB_APP_PASSWORD=$APP_PASSWORD
ENV
}

if [[ "${1:-}" == "--env" ]]; then
    print_env
    exit 0
fi

if [[ "${1:-}" == "--reset" ]]; then
    echo "Removing $NAME and $VOLUME"
    docker rm -f "$NAME" >/dev/null 2>&1 || true
    docker volume rm "$VOLUME" >/dev/null 2>&1 || true
fi

if ! docker ps -a --format '{{.Names}}' | grep -qx "$NAME"; then
    docker volume create "$VOLUME" >/dev/null
    echo "Starting $NAME ($IMAGE) on port $PORT"
    # postgres:18 moved its data directory to /var/lib/postgresql/18/docker.
    # A volume at the old /var/lib/postgresql/data persists nothing and the
    # container exits with an empty log, so the parent is mounted instead.
    docker run -d --name "$NAME" \
        -e POSTGRES_PASSWORD=devonly \
        -e POSTGRES_DB=sies \
        -e TZ=UTC \
        -p "$PORT:5432" \
        -v "$VOLUME:/var/lib/postgresql" \
        "$IMAGE" >/dev/null
elif ! docker ps --format '{{.Names}}' | grep -qx "$NAME"; then
    echo "Starting existing container $NAME"
    docker start "$NAME" >/dev/null
fi

# pg_isready reports the image's temporary initialisation server as ready
# before POSTGRES_DB exists, so the real database is polled instead.
printf 'Waiting for PostgreSQL'
for _ in $(seq 1 60); do
    if docker exec "$NAME" psql -U postgres -d sies -tAc 'SELECT 1' >/dev/null 2>&1; then
        echo " ready"
        break
    fi
    printf '.'
    sleep 1
done

if ! docker exec "$NAME" psql -U postgres -d sies -tAc 'SELECT 1' >/dev/null 2>&1; then
    echo
    echo "PostgreSQL never came up. Container log:" >&2
    docker logs "$NAME" >&2
    exit 1
fi

# Roles are cluster-wide and survive the database, so creating them twice
# is an error rather than a no-op. Skipped when they are already there.
if ! docker exec "$NAME" psql -U postgres -d sies -tAc \
        "SELECT 1 FROM pg_roles WHERE rolname = 'sies_owner'" | grep -q 1; then
    echo "Creating roles"
    docker exec -i "$NAME" psql -U postgres -d sies -q \
        -v ON_ERROR_STOP=1 \
        -v owner_password="$OWNER_PASSWORD" \
        -v app_password="$APP_PASSWORD" \
        -v auditor_password="$AUDITOR_PASSWORD" \
        -f - < "$HERE/roles.sql"
fi

# The migrations are deliberately NOT applied here by default.
#
# The service runs Flyway on startup with baseline-on-migrate: false, which
# means it refuses a database that already has the schema but no Flyway
# history — on purpose, because adopting an unknown database by assuming it
# matches is how two environments silently diverge. Applying the SQL here
# would produce exactly that database and the service would not start.
#
# So: leave it empty and let the service create it. --migrate is for when
# you want a schema to poke at in psql without running the service, and it
# says what it costs.
if [[ "${MIGRATE:-0}" == "1" ]]; then
    echo "Applying migrations directly as sies_owner"
    # By version, not by name: a lexicographic glob puts V10 before V2.
    for file in $(ls "$SERVER"/src/main/resources/db/migration/V*.sql \
            | sort -t V -k2 -n); do
        docker exec -i "$NAME" psql -U sies_owner -d sies -q -v ON_ERROR_STOP=1 \
            -f - < "$file"
        echo "  $(basename "$file")"
    done

    # A table created before the roles existed would have no grants, and
    # ALTER DEFAULT PRIVILEGES only applies to objects created afterwards.
    docker exec "$NAME" psql -U postgres -d sies -q -c \
        "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO sies_app;
         GRANT SELECT ON ALL TABLES IN SCHEMA public TO sies_auditor;
         GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO sies_app, sies_auditor;"

    echo
    echo "Schema applied without Flyway, so the service will NOT start against"
    echo "this database. Run $0 --reset before trying."
fi

echo
echo "Ready. To run the service against it:"
echo
print_env | sed 's/^/  /'
echo "  ./gradlew :server:bootRun"
echo
echo "Flyway applies every migration on that first start."
echo
echo "A shell on it:     docker exec -it $NAME psql -U postgres -d sies"
echo "Schema, no server: MIGRATE=1 $0 --reset"
echo "Throw it away:     $0 --reset"
