-- ════════════════════════════════════════════════════════════════════
-- V61 — The audit log is not the service's, partition by partition (C4)
-- ════════════════════════════════════════════════════════════════════
--
-- V7 revoked every privilege on audit_log from sies_app, and the log's
-- whole claim to be a record rests on that: the service writes it only
-- through SECURITY DEFINER triggers and audit_event(), and can neither
-- read it nor change a word of it.
--
-- The revoke was on the parent table only. A partition is a table of
-- its own, with privileges of its own, and every one of them -- the
-- monthly partitions V7 made, audit_log_overflow, and every month the
-- partition timer has added since -- was created by the owner, so
-- tools/roles.sql's ALTER DEFAULT PRIVILEGES handed sies_app SELECT,
-- INSERT, UPDATE and DELETE on each. Going through the parent is
-- refused; naming the partition is not. On the dev database sies_app
-- read audit_log_2026_11 directly and could have rewritten it.
--
-- The same default grant gave sies_app full write access to four
-- bookkeeping tables nothing in the service needs to change that way:
--
--   flyway_schema_history  the migrations' own ledger. Written by
--                          Flyway as the owner; the service has no
--                          business with it at all.
--   id_remap               written once per row a backup import has to
--                          re-key, and read back. Never changed or
--                          removed: a remapping that could be rewritten
--                          could point an imported mark at somebody else.
--   import_run             one row per backup import, opened and then
--                          closed with its counts. Never removed: it is
--                          how "this exact file has been imported before"
--                          is answered.
--   department_stream      which streams a department offers. Read by
--                          the checks; changed only by a migration.
--
-- What this does:
--
--   * Revokes everything on every existing audit_log partition from
--     sies_app and from PUBLIC. sies_auditor keeps SELECT, which it has
--     on the parent anyway.
--
--   * Redefines audit_log_add_months() to do the same to each partition
--     it creates, so the next month the timer adds is born without the
--     grant. The revoke is in the function rather than in a default
--     privilege because default privileges cannot name one table, and
--     the default grant is right for every other table the owner makes.
--
--   * Cuts the four tables down to what is used: id_remap SELECT and
--     INSERT, import_run SELECT, INSERT and UPDATE, department_stream
--     SELECT, flyway_schema_history nothing.
--
--   * Takes EXECUTE on audit_log_add_months() away from sies_app. It
--     could not create a table anyway (no CREATE on the schema), but a
--     maintenance function is the owner's.
--
-- Conditional on the roles existing, like V7's: tools/verify-schema.sh
-- applies the migrations to a bare container with no roles in it.


-- ── Every partition there is now ────────────────────────────────────

DO $$
DECLARE
    partition regclass;
BEGIN
    FOR partition IN
        SELECT i.inhrelid::regclass
        FROM pg_inherits i
        WHERE i.inhparent = 'audit_log'::regclass
    LOOP
        EXECUTE format('REVOKE ALL ON %s FROM PUBLIC', partition);

        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_app') THEN
            EXECUTE format('REVOKE ALL ON %s FROM sies_app', partition);
        END IF;

        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_auditor') THEN
            -- Read-only, as on the parent. Anything more came from the
            -- same default grant and was never meant.
            EXECUTE format('REVOKE ALL ON %s FROM sies_auditor', partition);
            EXECUTE format('GRANT SELECT ON %s TO sies_auditor', partition);
        END IF;
    END LOOP;
END $$;


-- ── Every partition to come ──────────────────────────────────────────

CREATE OR REPLACE FUNCTION audit_log_add_months(start_month date, how_many integer)
    RETURNS integer
    LANGUAGE plpgsql AS $fn$
DECLARE
    made   integer := 0;
    month  date;
    name   text;
BEGIN
    FOR offset_months IN 0 .. how_many - 1 LOOP

        month := date_trunc('month', start_month + (offset_months || ' months')::interval);
        name  := 'audit_log_' || to_char(month, 'YYYY_MM');

        CONTINUE WHEN EXISTS (
            SELECT 1 FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relname = name AND n.nspname = 'public');

        EXECUTE format(
            'CREATE TABLE %I PARTITION OF audit_log FOR VALUES FROM (%L) TO (%L)',
            name, month, month + interval '1 month');

        -- Born with the owner's default grants, which give the service
        -- write access to this one table. Taken back before anything can
        -- be written to it (V61).
        EXECUTE format('REVOKE ALL ON %I FROM PUBLIC', name);

        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_app') THEN
            EXECUTE format('REVOKE ALL ON %I FROM sies_app', name);
        END IF;

        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_auditor') THEN
            EXECUTE format('REVOKE ALL ON %I FROM sies_auditor', name);
            EXECUTE format('GRANT SELECT ON %I TO sies_auditor', name);
        END IF;

        made := made + 1;
    END LOOP;

    RETURN made;
END $fn$;

COMMENT ON FUNCTION audit_log_add_months(date, integer) IS
    'Creates monthly audit_log partitions, skipping those that exist, and takes the default grants back from each so the service holds no rights on it (V61). Idempotent, so a scheduled job can simply run it.';


-- ── The bookkeeping tables, and the maintenance function ─────────────

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_app') THEN

        EXECUTE 'REVOKE ALL ON id_remap FROM sies_app';
        EXECUTE 'GRANT SELECT, INSERT ON id_remap TO sies_app';

        EXECUTE 'REVOKE ALL ON import_run FROM sies_app';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE ON import_run TO sies_app';

        EXECUTE 'REVOKE ALL ON department_stream FROM sies_app';
        EXECUTE 'GRANT SELECT ON department_stream TO sies_app';

        -- Flyway's own table, which the service never touches.
        IF to_regclass('flyway_schema_history') IS NOT NULL THEN
            EXECUTE 'REVOKE ALL ON flyway_schema_history FROM sies_app';
        END IF;

        EXECUTE 'REVOKE ALL ON FUNCTION audit_log_add_months(date, integer) FROM sies_app';
    END IF;

    EXECUTE 'REVOKE ALL ON FUNCTION audit_log_add_months(date, integer) FROM PUBLIC';
END $$;
