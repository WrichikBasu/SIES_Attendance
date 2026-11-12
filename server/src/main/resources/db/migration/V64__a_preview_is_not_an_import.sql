-- ════════════════════════════════════════════════════════════════════
-- V64 — A preview is not an import (C6)
-- ════════════════════════════════════════════════════════════════════
--
-- A backup previewed before it is imported is recorded in import_run
-- like any other run, and a preview that read the file without trouble
-- succeeded. Nothing marked it as a preview, and "has this file been
-- imported before" asked only for a succeeded run of the same digest:
-- so the real import that followed always said "This exact file has
-- been imported before", and the command line added "expect no changes
-- below" over an import that was about to write everything.
--
-- The run row now says whether it was a preview. Previews stay on
-- record -- that somebody looked at a file is worth knowing -- and are
-- left out of "seen before".

ALTER TABLE import_run
    ADD COLUMN dry_run boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN import_run.dry_run IS
    'A preview: the import ran in a transaction that was rolled back, and wrote nothing. Not counted as the file having been imported (V64).';
