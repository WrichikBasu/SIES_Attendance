-- =====================================================================
-- V3 — What the importer has to remember between runs.
--
-- Two tables, neither of which holds attendance. They exist so that
-- importing the same device twice is a no-op and importing a second
-- device does not collide with the first.
--
-- Additive, like V2: nothing from V1 or V2 is altered.
--
--
-- WHY id_remap EXISTS
--
-- Five tables have primary keys the app issues rather than the world
-- describing: students, professors, courses, batch_desc and lectures.
-- Keys issued since schema v7 are structured (see StructuredId) and
-- carry the node of the device that minted them, so they are unique
-- across every device and the importer carries them across unchanged.
--
-- Keys from before that are not. They were counted out by SQLite from 1
-- in each file independently, so student 14 on one phone and student 14
-- on another are different people, and the numbers WILL collide — not
-- might. Such a row is given a freshly minted server key instead, and
-- this table records which, so that:
--
--   * a second backup from the same phone lands on the same server rows
--     rather than inserting them again under another new key, and
--   * a child row from that phone pointing at legacy parent 14 can be
--     pointed at the right server row years later.
--
-- The key is (table_name, source_node, source_id). source_node cannot
-- be read out of a legacy id — that is the whole problem with them — so
-- it is the node of the DEVICE the backup came from, which the operator
-- supplies when running the import. Getting it wrong would fuse two
-- devices' legacy rows, so the importer refuses a node it has not seen
-- registered unless told to take it on trust.
-- =====================================================================


CREATE TABLE id_remap (
    table_name  text        NOT NULL,
    source_node integer     NOT NULL,
    source_id   bigint      NOT NULL,
    server_id   bigint      NOT NULL,
    imported_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT id_remap_pkey PRIMARY KEY (table_name, source_node, source_id),

    -- Only the five tables whose keys are issued rather than natural.
    -- PostgresSchemaDriftTest checks this list against
    -- DeviceTables.tablesWithIssuedKeys(), so a sixth such table cannot
    -- be added in Java without this failing.
    CONSTRAINT id_remap_table_known CHECK (table_name IN
        ('students', 'professors', 'courses', 'batch_desc', 'lectures')),

    -- The 16-bit node field. 0 is the server itself
    -- (StructuredId.SERVER_NODE) and can never be a source.
    CONSTRAINT id_remap_source_node_range
        CHECK (source_node BETWEEN 1 AND 65535),

    CONSTRAINT id_remap_source_id_positive CHECK (source_id > 0),

    -- A remapped key is always one the server minted, so it is always
    -- structured. 36028797018963968 is StructuredId.FIRST_STRUCTURED_ID.
    CONSTRAINT id_remap_server_id_structured
        CHECK (server_id >= 36028797018963968),

    -- Replacing a key with itself would mean no remapping was needed and
    -- the row should not be here at all.
    CONSTRAINT id_remap_actually_remaps CHECK (server_id <> source_id)
);

-- Two source rows must never be mapped onto one server row: that would
-- be two people merged into one, which is the exact failure the natural
-- keys exist to avoid, arriving by the back door.
CREATE UNIQUE INDEX id_remap_server_id_uq ON id_remap (table_name, server_id);

-- Reading it backwards: everything this device contributed.
CREATE INDEX id_remap_source_node_idx ON id_remap (source_node);

COMMENT ON TABLE id_remap IS
    'Legacy device keys that had to be replaced, and what they became. Persistent: a second backup from the same device resolves through this.';
COMMENT ON COLUMN id_remap.source_node IS
    'The node of the device the key came from, supplied at import time — a legacy key does not carry one.';
COMMENT ON COLUMN id_remap.server_id IS
    'The key the server minted under node 0, which is what every child row now points at.';


-- ── import_run ───────────────────────────────────────────────────────
--
-- One row per import attempt, successful or not.
--
-- The plan calls for a per-table inserted/updated/skipped report to be
-- reviewed before the next device is imported. A report printed to a
-- terminal is not reviewable a week later, and the gate for this phase
-- is a reconciliation across every device, which means every run's
-- numbers have to still be there at the end. So the report is stored.
--
-- The digest is of the ENCRYPTED backup file as it arrived. It is what
-- lets a re-run say "this is the same file you imported on Tuesday"
-- rather than silently doing nothing and looking broken; idempotence
-- itself comes from the natural keys, not from this.
-- =====================================================================

CREATE TABLE import_run (
    run_id            bigserial   PRIMARY KEY,
    source_label      text        NOT NULL,
    source_node       integer     NOT NULL,
    backup_digest     text        NOT NULL,
    backup_created_at timestamptz,
    backup_schema     integer     NOT NULL,
    started_at        timestamptz NOT NULL DEFAULT now(),
    finished_at       timestamptz,
    succeeded         boolean     NOT NULL DEFAULT false,
    inserted          integer     NOT NULL DEFAULT 0,
    updated           integer     NOT NULL DEFAULT 0,
    skipped           integer     NOT NULL DEFAULT 0,
    report            jsonb,
    failure           text,

    CONSTRAINT import_run_source_node_range
        CHECK (source_node BETWEEN 1 AND 65535),

    -- SHA-256, lower-case hex.
    CONSTRAINT import_run_digest_shape
        CHECK (backup_digest ~ '^[0-9a-f]{64}$'),

    CONSTRAINT import_run_schema_range
        CHECK (backup_schema BETWEEN 1 AND 10),

    -- A run that succeeded has finished, and one that failed says why.
    CONSTRAINT import_run_succeeded_is_finished
        CHECK (NOT succeeded OR finished_at IS NOT NULL),
    CONSTRAINT import_run_counts_not_negative
        CHECK (inserted >= 0 AND updated >= 0 AND skipped >= 0)
);

CREATE INDEX import_run_source_node_idx  ON import_run (source_node, started_at DESC);
CREATE INDEX import_run_backup_digest_idx ON import_run (backup_digest);

COMMENT ON TABLE import_run IS
    'One row per import attempt. Kept so the reconciliation at the end of the migration can be done from the database rather than from terminal scrollback.';
COMMENT ON COLUMN import_run.backup_digest IS
    'SHA-256 of the backup file as it arrived, so a re-import of the identical file can be recognised and said out loud.';
COMMENT ON COLUMN import_run.report IS
    'The per-table inserted/updated counts and every skip reason, as the importer produced them.';
