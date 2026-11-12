-- ════════════════════════════════════════════════════════════════════
-- V62 — A push for a slot already taken is folded into it (C2)
-- ════════════════════════════════════════════════════════════════════
--
-- Two phones that each record the same class offline give it two ids.
-- The first push wins the slot. The second used to be refused with a
-- unique violation that SyncWriter treated as "not yet", so the losing
-- phone sent its lecture, and every mark under it, on every sync for as
-- long as it was signed in, while its screen showed the marks as saved.
-- Two professors typing in the same course code had the same problem.
--
-- The server now folds the second row into the first: it records that
-- the second id means the first, accepts the push without writing the
-- duplicate, and reads every later intent through that record -- a
-- mark under the second lecture lands on the first, a lecture of the
-- second course is filed under the first. The phone learns the first
-- id when the pull brings that row, and re-keys its own copy to it.
--
-- Why a table of its own and not id_remap: id_remap belongs to the
-- backup importer and forbids two source rows mapping onto one server
-- row, which is the right rule for an import and the wrong one here --
-- three phones recording one class is three ids for one lecture.
--
-- Only lectures and courses are folded. A lecture is the same thing
-- under two ids when its slot matches: one class at one time. A course
-- is when its code matches AND it is the same course -- stream,
-- department, year, semester and type; a code reused for a different
-- course is refused in words. A roll number, an email or an APAAR ID
-- that collides is not folded at all: it may be two people, and folding
-- them would merge their records. Those are refused, permanently, with
-- the reason.

CREATE TABLE sync_fold (
    table_name text        NOT NULL,
    from_id    bigint      NOT NULL,
    to_id      bigint      NOT NULL,
    folded_at  timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT sync_fold_pkey PRIMARY KEY (table_name, from_id),

    CONSTRAINT sync_fold_table_known CHECK (table_name IN ('lectures', 'courses')),

    -- Folding a row into itself would mean there was nothing to fold.
    CONSTRAINT sync_fold_actually_folds CHECK (from_id <> to_id)
);

COMMENT ON TABLE sync_fold IS
    'A device row pushed under an id whose natural key another row already held, and the row it was folded into. SyncWriter reads every later intent through it (V62). Written by the service, never changed or removed.';

-- Written once and read back, like id_remap: a fold that could be
-- rewritten could send a mark to some other lecture.
DO $$
BEGIN
    EXECUTE 'REVOKE ALL ON sync_fold FROM PUBLIC';

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_app') THEN
        EXECUTE 'REVOKE ALL ON sync_fold FROM sies_app';
        EXECUTE 'GRANT SELECT, INSERT ON sync_fold TO sies_app';
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_auditor') THEN
        EXECUTE 'REVOKE ALL ON sync_fold FROM sies_auditor';
        EXECUTE 'GRANT SELECT ON sync_fold TO sies_auditor';
    END IF;
END $$;
