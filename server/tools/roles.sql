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
-- connecting as one would quietly have every policy turned off. The
-- service must also not be the owner of the tables: an owner bypasses its
-- own policies unless the table is FORCEd, and relying on remembering to
-- FORCE every table is a worse rule than simply not being the owner.
--
-- Those policies now exist -- V6 added them -- so this split has stopped
-- being a precaution and become load-bearing. Two consequences worth
-- knowing before anything goes wrong:
--
--   * THE IMPORTER NEEDS AN ACTOR. attendance and lectures are under
--     FORCE ROW LEVEL SECURITY, so sies_owner does not bypass them
--     either. An import run with no actor fails on its first lecture
--     with "new row violates row-level security policy" and rolls the
--     whole thing back. Pass the account id of an administrator:
--     BackupImporter.Request takes one, and ImportCommand's
--     --import.account is how it is supplied.
--
--   * THE SERVICE MAY NOT DELETE A SESSION OR A DEVICE. V8 revokes
--     DELETE on app_session and device_registration from sies_app, for
--     the same reason nothing deletes a role or a CR grant: a session
--     is revoked and a device is revoked, because the node a device was
--     given is in the primary key of every row it ever wrote.
--
--   * BOOTSTRAPPING USES sies_owner. user_role's policies let only an
--     ADMIN grant the ADMIN role, and the first grant has nobody to make
--     it, so BootstrapCommand connects as the migration role instead.
--     That is the one legitimate use of that connection, which is why it
--     is a command run by hand and not an endpoint.

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
-- audit_log is the exception, and it turned out stronger than this note
-- originally predicted. V7 revokes EVERYTHING on it from sies_app -- not
-- even INSERT -- because the audit triggers and audit_event() are
-- SECURITY DEFINER and run as the owner. So the service holds no
-- privilege on that table at all and has no statement it can aim at it,
-- which is a stronger claim than "INSERT and nothing else". sies_auditor
-- gets SELECT back, and nobody anywhere gets UPDATE or DELETE.
--
-- THE ORDER OF THIS FILE AND THE MIGRATIONS MATTERS. These defaults have
-- to be in place before Flyway runs, so that each table it creates is
-- reachable; V7's revoke then runs last and takes audit_log back. Grant
-- broadly afterwards instead and the revoke is silently undone -- which
-- is exactly what the test harness did at first, and the test asserting
-- the service cannot rewrite an audit entry passed anyway, for the wrong
-- reason.
--
-- Note what these wide grants do NOT do. sies_app has every verb on every
-- table, and sees almost none of it: what it may read and change is
-- decided by V6's policies, not by these privileges. That is on purpose.
-- A grant answers "may this role touch this table at all"; a policy
-- answers "which rows". Narrowing the grants as well would mean two
-- places to look when a query comes back empty, and the policies are the
-- place the answer should be.
ALTER DEFAULT PRIVILEGES FOR ROLE sies_owner IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO sies_app;

ALTER DEFAULT PRIVILEGES FOR ROLE sies_owner IN SCHEMA public
    GRANT SELECT ON TABLES TO sies_auditor;

ALTER DEFAULT PRIVILEGES FOR ROLE sies_owner IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO sies_app;

-- Functions: session_year_of is called by the statistics queries, and in
-- Phase 4 by the row-level security policies. From Phase 5.5 this also
-- covers the handful of SECURITY DEFINER functions the endpoints reach
-- identity through -- node_for_install, device_session_binding,
-- exportable_courses and the rest -- which exist precisely because
-- verifying a token happens before there is an actor to apply a policy
-- to.
ALTER DEFAULT PRIVILEGES FOR ROLE sies_owner IN SCHEMA public
    GRANT EXECUTE ON FUNCTIONS TO sies_app, sies_auditor;

-- And for anything the owner created before this file ran.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO sies_app;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO sies_auditor;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO sies_app, sies_auditor;
