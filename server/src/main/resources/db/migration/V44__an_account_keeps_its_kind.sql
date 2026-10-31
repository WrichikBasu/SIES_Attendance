-- ════════════════════════════════════════════════════════════════════
-- V44 — An account keeps its kind
-- ════════════════════════════════════════════════════════════════════
--
-- An account is a professor's, a student's or a staff one from the day it
-- is made, and it stays that. The console used to turn a professor's
-- account into a staff one and back; those endpoints are gone, because a
-- professor is not made office staff and nobody is made a professor by
-- having their account changed. This is the rule underneath, for every way
-- a statement can arrive.
--
-- Only kind is held. app_account_exactly_one_person (V5) already ties
-- prof_id and student_id to it, so neither can be emptied or filled while
-- kind stays put. They are not frozen as well: both keys are ON UPDATE
-- CASCADE, and a professor or student renumbered on the server carries
-- their account along with them.
--
-- Somebody who really is now a different kind of person gets a new
-- account, and the old one is disabled with its history intact.

CREATE FUNCTION keep_account_kind() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, public AS $fn$
BEGIN
    RAISE EXCEPTION 'An account''s kind does not change; make a new account instead'
        USING ERRCODE = 'check_violation';
END $fn$;

COMMENT ON FUNCTION keep_account_kind() IS
    'Refuses changing app_account.kind: a professor''s, student''s or staff account stays one (V44).';

CREATE TRIGGER app_account_keep_kind
    BEFORE UPDATE OF kind ON app_account
    FOR EACH ROW
    WHEN (OLD.kind IS DISTINCT FROM NEW.kind)
    EXECUTE FUNCTION keep_account_kind();
