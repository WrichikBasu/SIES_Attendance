-- The three roles the service connects as, and what each may do.
--
-- Run once against a fresh cluster, as a superuser, before the first
-- migration:
--
--   psql -U postgres -d sies \
--     -v owner_password="$DB_OWNER_PASSWORD" \
--     -v app_password="$DB_APP_PASSWORD" \
--     -v auditor_password="$DB_AUDITOR_PASSWORD" \
--     -f server/tools/roles.sql
--
-- The split is the thing that makes the audit log real rather than a
-- convention. If the service could UPDATE or DELETE an audit row, the log
-- would be a record the application is free to rewrite, which is not a
-- record at all. It is enforced by privilege, not by the application
-- remembering not to.
--
-- None of these is a superuser, and none has BYPASSRLS. A superuser
-- ignores row-level security even when the table is FORCEd, so a service
-- connecting as one would quietly have every policy in Phase 4 turned off.
-- The service must also not be the owner of the tables: an owner bypasses
-- its own policies unless the table is FORCEd, and relying on remembering
-- to FORCE every table is a worse rule than simply not being the owner.

\set ON_ERROR_STOP on

-- Flyway, and nothing else. Owns every object.
CREATE ROLE sies_owner LOGIN PASSWORD :'owner_password';

-- The service.
CREATE ROLE sies_app LOGIN PASSWORD :'app_password';

-- Audit queries, read only.
CREATE ROLE sies_auditor LOGIN PASSWORD :'auditor_password';

-- The owner creates the schema's objects; nobody else creates anything.
GRANT CREATE, USAGE ON SCHEMA public TO sies_owner;
GRANT USAGE ON SCHEMA public TO sies_app, sies_auditor;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- Privileges on objects that do not exist yet. Flyway creates every table
-- as sies_owner, so these defaults are what grant the service access to a
-- table added by a future migration without anyone remembering to.
--
-- audit_log arrives in Phase 6 and is the exception: it will be granted
-- INSERT alone, explicitly, after the default below has been revoked for
-- it. A grant is easier to add than to take away, so the comment is here
-- now rather than in the migration that needs it.
ALTER DEFAULT PRIVILEGES FOR ROLE sies_owner IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sies_app;

ALTER DEFAULT PRIVILEGES FOR ROLE sies_owner IN SCHEMA public
    GRANT SELECT ON TABLES TO sies_auditor;

ALTER DEFAULT PRIVILEGES FOR ROLE sies_owner IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO sies_app;

-- Functions: session_year_of is called by the statistics queries, and in
-- Phase 4 by the row-level security policies.
ALTER DEFAULT PRIVILEGES FOR ROLE sies_owner IN SCHEMA public
    GRANT EXECUTE ON FUNCTIONS TO sies_app, sies_auditor;

-- And for anything the owner created before this file ran.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO sies_app;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO sies_auditor;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO sies_app, sies_auditor;
