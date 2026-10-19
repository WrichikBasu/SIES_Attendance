-- =====================================================================
-- V10 — The change feed the pull reads, and nothing else.
--
-- Additive. One function, one grant.
--
--
-- WHY THE AUDIT LOG IS THE FEED
--
-- A pull asks "what has changed since I last looked", and until now
-- nothing on this server could answer it: the attendance tables carry a
-- row_version but no ordering anybody could resume from, and a deleted
-- row carries nothing at all.
--
-- The audit log already is that answer. V7 put an AFTER trigger on every
-- table a dispute could turn on, so every insert, update and delete is
-- in there, in order, with its table and its key -- which is exactly a
-- change feed, built for a different reason and better tested than a new
-- one would be. The alternative was a sync_seq column and a bump trigger
-- on each of eleven tables, plus a tombstone table for the deletes,
-- which is three new mechanisms to keep in step with each other.
--
-- Two consequences, both worth knowing:
--
--   * Nothing from before V7 is in the log. A device with no watermark
--     therefore gets a snapshot rather than a replay, which is what a
--     new install needs anyway.
--
--   * The service holds no privilege on audit_log at all -- V7 revoked
--     it, deliberately and completely -- so this function is the only
--     way in, and it hands back keys rather than entries. No actor, no
--     label, no images, no detail: a feed, not the record.
--
--
-- WHAT THE CURSOR IS
--
-- (occurred_at, audit_id), compared row-wise. audit_id alone would be
-- enough on one node and is not a safe sole cursor across partitions;
-- occurred_at alone is clock_timestamp() and two entries can share it.
-- Together they are a total order, and the pair is what the device
-- stores as its opaque watermark.
--
--
-- WHICH TABLES
--
-- lectures and attendance, and only those two. Both are under FORCE ROW
-- LEVEL SECURITY, so the pull can hand their keys to a query that the
-- policies then filter -- the device gets back exactly the rows its own
-- actor may read.
--
-- The other nine tables are left out on purpose. courses, students and
-- student_year have no read policies (V6's header says why at length),
-- so a pull of them would be a way to fetch the college's roster onto a
-- phone -- realising a gap that currently has no endpoint to come
-- through. The feed will widen the day those tables get the roster
-- reachability policy they need, and not before.
-- =====================================================================


-- ── The tables a device may sync ─────────────────────────────────────
--
-- A function rather than a literal in three places, so that widening it
-- later is one edit and the endpoint cannot disagree with the feed.

CREATE FUNCTION synced_tables() RETURNS text[]
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    RETURN ARRAY['lectures', 'attendance'];

COMMENT ON FUNCTION synced_tables() IS
    'The tables a device may push and pull: the ones row-level security can police. Widening this needs read policies on courses, students and student_year first.';


-- ── The feed ─────────────────────────────────────────────────────────

CREATE FUNCTION changes_since(
        after_at    timestamptz,
        after_id    bigint,
        limit_rows  integer DEFAULT 500)
    RETURNS TABLE (cursor_at timestamptz, cursor_id bigint, table_name text,
                   row_key jsonb, action audit_action)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    BEGIN ATOMIC
        SELECT a.occurred_at, a.audit_id, a.table_name, a.row_key, a.action
        FROM audit_log a
        WHERE a.table_name = ANY (synced_tables())
          AND a.action IN ('INSERT', 'UPDATE', 'DELETE')
          -- Row-wise, so the pair is one ordering. A null cursor means
          -- the beginning of time, which the caller turns into a
          -- snapshot rather than a replay.
          AND (a.occurred_at, a.audit_id) > (after_at, after_id)
        ORDER BY a.occurred_at, a.audit_id
        LIMIT least(greatest(limit_rows, 1), 2000);
    END;

COMMENT ON FUNCTION changes_since(timestamptz, bigint, integer) IS
    'The keys that changed after a cursor, oldest first, for the tables a device syncs. SECURITY DEFINER because the service holds no privilege on audit_log; returns keys and not entries, so it is a feed rather than the record.';


-- ── The head of the feed ─────────────────────────────────────────────
--
-- What a device with no watermark is given after its snapshot: the
-- cursor as it stands now, so the next pull starts from here rather than
-- replaying the log from V7.
--
-- A separate function because it is one row and the pull asks for it on
-- a path where it asks for nothing else.

CREATE FUNCTION changes_head() RETURNS TABLE (cursor_at timestamptz, cursor_id bigint)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    BEGIN ATOMIC
        SELECT coalesce(max(a.occurred_at), '-infinity'::timestamptz),
               coalesce(max(a.audit_id), 0)
        FROM audit_log a
        WHERE a.table_name = ANY (synced_tables());
    END;

COMMENT ON FUNCTION changes_head() IS
    'The cursor as it stands, for a device that has just taken a snapshot and should resume from now rather than replay the log.';


-- ── Privileges ───────────────────────────────────────────────────────
--
-- Conditional, like V7's and V8's: tools/verify-schema.sh applies these
-- migrations to a bare container with no roles in it.

DO $$
BEGIN
    EXECUTE 'REVOKE ALL ON FUNCTION changes_since(timestamptz, bigint, integer) FROM PUBLIC';
    EXECUTE 'REVOKE ALL ON FUNCTION changes_head() FROM PUBLIC';

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_app') THEN
        EXECUTE 'GRANT EXECUTE ON FUNCTION changes_since(timestamptz, bigint, integer) '
                'TO sies_app';
        EXECUTE 'GRANT EXECUTE ON FUNCTION changes_head() TO sies_app';
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_auditor') THEN
        -- The auditor reads audit_log directly and has no use for a feed over it.
        EXECUTE 'REVOKE ALL ON FUNCTION changes_since(timestamptz, bigint, integer) '
                'FROM sies_auditor';
        EXECUTE 'REVOKE ALL ON FUNCTION changes_head() FROM sies_auditor';
    END IF;
END $$;
