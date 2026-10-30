-- =====================================================================
-- V9 — The device schema has a version 11, so a backup may say 11.
--
-- Additive. One constraint replaced, nothing else touched.
--
-- Room schema v11 adds the outbox and the device's sync state: a queue
-- of local changes, filled by triggers, and one row saying where the
-- last pull from the server got to. Neither is attendance data and
-- neither is carried by the importer -- DeviceTables decides what is,
-- and it does not mention them.
--
-- So why does this migration exist at all? Because import_run records
-- the schema version of the backup it read, under a CHECK that spans
-- exactly the versions the shared migrations can bring forward. A phone
-- on v11 now produces a backup that says 11, and the importer would
-- refuse it on that constraint -- having migrated it perfectly well.
--
-- PostgresSchemaDriftTest holds the range against
-- SchemaMigrations.CURRENT_VERSION, across every migration rather than
-- V3's alone, so the next bump fails the build here rather than in an
-- import at the end of term.
-- =====================================================================

ALTER TABLE import_run
    DROP CONSTRAINT import_run_schema_range;

-- Same name, wider range. Dropped and re-added rather than edited in
-- place, because V3 has been applied and Flyway refuses a file that
-- changed after it ran -- which is the behaviour to want: the history of
-- what this database has been told is not a thing to rewrite.
ALTER TABLE import_run
    ADD CONSTRAINT import_run_schema_range
        CHECK (backup_schema BETWEEN 1 AND 11);

COMMENT ON COLUMN import_run.backup_schema IS
    'The Room schema version the backup was written at. 1..11; the shared migrations bring anything in that range up to the current one before a row is read.';
