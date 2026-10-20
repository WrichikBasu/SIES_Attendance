-- A role that was revoked can be granted again.
--
-- V5 created user_role_scope_uq as a plain unique index on
-- (account_id, role, department, academic_year). Its comment says "a role is
-- held once per scope", which is the rule that was wanted. What the index
-- actually enforced is "a role is GRANTED once per scope, for ever": a
-- revoked row keeps its slot, so re-appointing last year's head of
-- Computer Science came back as
--
--   duplicate key value violates unique constraint "user_role_scope_uq"
--
-- and there was no way round it from the administration page at all. Found by
-- somebody who revoked a grant and tried to put it back.
--
-- The index becomes partial, which says what was meant: one LIVE grant per
-- account per role per scope. A re-grant is then a new row with its own
-- user_role_id, which is the right shape for two reasons. The revoke endpoint
-- names a grant by that id, so two grants must be two ids. And "HOD from June
-- to September, then again from January" is two appointments; one row with an
-- overwritten granted_at would answer "who was allowed to do this in July"
-- with the wrong date, and that question is the whole reason nothing here is
-- ever deleted.
--
-- NULLS NOT DISTINCT is kept, and still matters for the same reason: both
-- scope columns are null for every college-wide role, and under the default
-- an account could hold ADMIN any number of times over.

DROP INDEX user_role_scope_uq;

CREATE UNIQUE INDEX user_role_live_scope_uq
    ON user_role (account_id, role, department, academic_year) NULLS NOT DISTINCT
    WHERE revoked_at IS NULL;

COMMENT ON INDEX user_role_live_scope_uq IS
    'One live grant per account per role per scope. Revoked grants keep their rows and do not block a re-grant, which is what the plain index this replaced got wrong.';
