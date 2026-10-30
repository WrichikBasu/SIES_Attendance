-- ════════════════════════════════════════════════════════════════════
-- V29 — A backup import may write batches and their links
-- ════════════════════════════════════════════════════════════════════
--
-- Batches and their links to courses are written by professors and
-- nobody else (V22). A backup import runs as an administrator, so a
-- phone's batches were refused: "new row violates row-level security
-- policy for table batch_desc", and the whole import rolled back.
--
-- Rather than let administrators manage batches, which is the
-- professors' call, an administrator may write the two tables only
-- inside an import. BackupImporter marks its transaction with
-- app.import_run, the run's id, through ActorSession.announceImport.
-- Nothing else sets it: WireTest refuses set_config anywhere outside
-- ActorSession, and a phone sends rows rather than SQL.
--
-- Insert and update only. An import never deletes.

CREATE OR REPLACE FUNCTION actor_is_importing() RETURNS boolean
    LANGUAGE sql STABLE
    RETURN actor_has_role('ADMIN')
       AND coalesce(current_setting('app.import_run', true), '') <> '';

COMMENT ON FUNCTION actor_is_importing() IS
    'True inside a backup import run by an administrator; see BackupImporter and V29.';

CREATE POLICY batch_desc_import_insert ON batch_desc
    FOR INSERT WITH CHECK (actor_is_importing());

CREATE POLICY batch_desc_import_update ON batch_desc
    FOR UPDATE USING (actor_is_importing()) WITH CHECK (actor_is_importing());

CREATE POLICY course_batch_map_import_insert ON course_batch_map
    FOR INSERT WITH CHECK (actor_is_importing());

CREATE POLICY course_batch_map_import_update ON course_batch_map
    FOR UPDATE USING (actor_is_importing()) WITH CHECK (actor_is_importing());

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_app') THEN
        EXECUTE 'GRANT EXECUTE ON FUNCTION actor_is_importing() TO sies_app';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_auditor') THEN
        EXECUTE 'GRANT EXECUTE ON FUNCTION actor_is_importing() TO sies_auditor';
    END IF;
END
$$;
