-- ════════════════════════════════════════════════════════════════════
-- V58 — One administrator is removed at a time
-- ════════════════════════════════════════════════════════════════════
--
-- V43's trigger asks "is there still a live administrator?" after each
-- removal, and refuses if not. It asked without a lock, so two removals
-- running at once could each see the other's administrator still live:
-- two administrators disabling each other, both checks pass, both
-- commit, and there are none. Each transaction's change was invisible
-- to the other until it committed, and by then both had been allowed.
--
-- The check now first takes a transaction-scoped advisory lock, one key
-- for the whole question. A second removal waits at the lock until the
-- first commits or rolls back. Under READ COMMITTED, which is what the
-- service runs at, every statement in a PL/pgSQL function takes a fresh
-- snapshot, so the check that follows the wait sees the first removal
-- and refuses the second. SERIALIZABLE would catch the same skew on its
-- own; REPEATABLE READ would not, and nothing here uses it.
--
-- The lock is taken only once the change is known to remove a live
-- administrator, which is where V43's early return already stands, so
-- disabling any other account never waits on it.

CREATE OR REPLACE FUNCTION keep_an_administrator() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = pg_catalog, public AS $fn$
BEGIN
    -- An account disabled that held no live ADMIN removes no administrator.
    IF TG_TABLE_NAME = 'app_account' AND NOT EXISTS (
            SELECT 1 FROM user_role r
            WHERE r.account_id = OLD.account_id
              AND r.role       = 'ADMIN'
              AND r.revoked_at IS NULL) THEN
        RETURN NULL;
    END IF;

    -- One removal of an administrator at a time; see the header. The key is
    -- arbitrary and used for nothing else: 43, for the migration whose rule
    -- this is.
    PERFORM pg_advisory_xact_lock(43);

    IF NOT EXISTS (
            SELECT 1
            FROM user_role r
            JOIN app_account a ON a.account_id = r.account_id
            WHERE r.role        = 'ADMIN'
              AND r.revoked_at IS NULL
              AND a.disabled_at IS NULL) THEN
        RAISE EXCEPTION 'That would leave no administrator; make another one first'
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END $fn$;

COMMENT ON FUNCTION keep_an_administrator() IS
    'Refuses a change that removes the last live ADMIN grant on an enabled account (V43), one such change at a time (V58).';
