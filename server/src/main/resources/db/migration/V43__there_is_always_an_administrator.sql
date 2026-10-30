-- ════════════════════════════════════════════════════════════════════
-- V43 — There is always an administrator
-- ════════════════════════════════════════════════════════════════════
--
-- Only an administrator grants ADMIN, so a college whose last one is gone
-- has nobody who can make another: accounts, roles, backups and sessions
-- would wait on somebody with shell access running the bootstrap command
-- against the database. The console already refuses an administrator
-- disabling or deleting their own account; revoking their own ADMIN went
-- through, and that is the one way the last of them could disappear.
--
-- AccountsController now refuses that revocation with a reason. This is
-- the rule underneath it, for every way a statement can arrive: no change
-- may leave the system without a live ADMIN grant on an account that can
-- sign in. It is asked only when an administrator is being removed --
-- their grant revoked, changed or deleted, or their account disabled --
-- so a database that has none yet, before the bootstrap, is not refused
-- everything else.
--
-- Not deferred: handing over is granting the new administrator first,
-- which is the order the console offers anyway.

CREATE FUNCTION keep_an_administrator() RETURNS trigger
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
    'Refuses a change that removes the last live ADMIN grant on an enabled account (V43).';

CREATE TRIGGER user_role_keep_an_administrator
    AFTER UPDATE ON user_role
    FOR EACH ROW
    WHEN (OLD.role = 'ADMIN' AND OLD.revoked_at IS NULL)
    EXECUTE FUNCTION keep_an_administrator();

CREATE TRIGGER user_role_keep_an_administrator_on_delete
    AFTER DELETE ON user_role
    FOR EACH ROW
    WHEN (OLD.role = 'ADMIN' AND OLD.revoked_at IS NULL)
    EXECUTE FUNCTION keep_an_administrator();

CREATE TRIGGER app_account_keep_an_administrator
    AFTER UPDATE OF disabled_at ON app_account
    FOR EACH ROW
    WHEN (OLD.disabled_at IS NULL AND NEW.disabled_at IS NOT NULL)
    EXECUTE FUNCTION keep_an_administrator();
