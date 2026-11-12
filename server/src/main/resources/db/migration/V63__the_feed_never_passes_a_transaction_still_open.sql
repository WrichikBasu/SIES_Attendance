-- ════════════════════════════════════════════════════════════════════
-- V63 — The feed never passes a transaction that is still open (C1)
-- ════════════════════════════════════════════════════════════════════
--
-- The pull's cursor was (occurred_at, audit_id), both fixed when the
-- audit trigger fires -- inside the writing transaction. The entry
-- becomes visible only when that transaction commits. A pull in
-- between saw later entries from shorter transactions, moved the
-- phone's watermark past them, and nothing ever looked behind a
-- watermark: the change never reached that phone until it started
-- over. An attendance import, a roster import, a backup import and a
-- large push are each one transaction, so the window was real. The same
-- hole was open across a first snapshot.
--
-- The cursor is now the transaction, not the position:
--
--   * Every audit entry records the top-level transaction that wrote it
--     (txid, from pg_current_xact_id()).
--
--   * A pull's watermark is a frontier: the oldest transaction that was
--     still open when the pull ran (pg_snapshot_xmin of its snapshot).
--     Every transaction older than that had finished, so all of its
--     entries were visible and were sent. Every transaction that was
--     still open, or had not started, is at or past the frontier, so the
--     next pull sends it whenever it commits.
--
--   * The next pull sends every visible entry at or past the frontier.
--     That repeats entries of transactions that had committed but were
--     younger than an open one; a phone applies a row by key and the
--     repeat costs that row and nothing else. With nothing long-running
--     open, the frontier is simply "now".
--
-- changes_since() and changes_head() stay: a phone that last pulled
-- before this migration holds a position, and SyncReader finishes that
-- phone's catch-up by position once before moving it to a frontier.


-- ── Which transaction wrote each entry ───────────────────────────────

-- Existing entries are given the migrating transaction's id. They are
-- history: every one of them committed long ago, and a frontier taken
-- after this migration is past them.
ALTER TABLE audit_log
    ADD COLUMN txid xid8 NOT NULL DEFAULT pg_current_xact_id();

COMMENT ON COLUMN audit_log.txid IS
    'The top-level transaction that wrote the entry. The pull''s cursor (V63): an entry is visible only once this commits, so a watermark is a transaction frontier rather than a position.';

-- What the feed reads in: entries of one transaction and after, in
-- transaction order. On the parent, so every partition gets its own.
CREATE INDEX audit_log_txid_idx ON audit_log (txid, audit_id);


-- ── The feed by transaction ──────────────────────────────────────────

CREATE FUNCTION changes_after(
        frontier    xid8,
        after_txid  xid8,
        after_id    bigint,
        limit_rows  integer DEFAULT 500)
    RETURNS TABLE (cursor_txid xid8, cursor_id bigint, table_name text,
                   row_key jsonb, action audit_action)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    BEGIN ATOMIC
        SELECT a.txid, a.audit_id, a.table_name, a.row_key, a.action
        FROM audit_log a
        WHERE a.table_name = ANY (synced_tables())
          AND a.action IN ('INSERT', 'UPDATE', 'DELETE')
          AND a.txid >= frontier
          -- Resuming inside one pass, when a page filled. Null on the
          -- first page of a pass, which starts at the frontier itself.
          AND (after_txid IS NULL OR (a.txid, a.audit_id) > (after_txid, after_id))
        ORDER BY a.txid, a.audit_id
        LIMIT least(greatest(limit_rows, 1), 2000);
    END;

COMMENT ON FUNCTION changes_after(xid8, xid8, bigint, integer) IS
    'The keys written by every transaction at or past a frontier, in transaction order, for the tables a device syncs (V63). SECURITY DEFINER because the service holds no privilege on audit_log; returns keys and not entries.';


-- ── The frontier ─────────────────────────────────────────────────────
--
-- The oldest transaction still open, as this pull's snapshot sees it.
-- Every transaction before it has finished. Not SECURITY DEFINER: it
-- reads nothing but the caller's own snapshot.

CREATE FUNCTION changes_frontier() RETURNS xid8
    LANGUAGE sql STABLE
    SET search_path = pg_catalog, public
    RETURN pg_snapshot_xmin(pg_current_snapshot());

COMMENT ON FUNCTION changes_frontier() IS
    'The oldest transaction still open when the calling pull''s snapshot was taken: a pull''s watermark (V63).';


-- ── Privileges ───────────────────────────────────────────────────────

DO $$
BEGIN
    EXECUTE 'REVOKE ALL ON FUNCTION changes_after(xid8, xid8, bigint, integer) FROM PUBLIC';
    EXECUTE 'REVOKE ALL ON FUNCTION changes_frontier() FROM PUBLIC';

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_app') THEN
        EXECUTE 'GRANT EXECUTE ON FUNCTION changes_after(xid8, xid8, bigint, integer) '
                'TO sies_app';
        EXECUTE 'GRANT EXECUTE ON FUNCTION changes_frontier() TO sies_app';
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_auditor') THEN
        EXECUTE 'REVOKE ALL ON FUNCTION changes_after(xid8, xid8, bigint, integer) '
                'FROM sies_auditor';
    END IF;
END $$;
