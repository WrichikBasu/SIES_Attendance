-- =====================================================================
-- V12 — Who may create an account.
--
-- Additive: one function.
--
--
-- WHY A FUNCTION AND NOT A POLICY
--
-- app_account has no row-level security at all, and V6's header says
-- why at length: authentication happens before there is an actor, so a
-- policy keyed on the current account would deny the one lookup that
-- establishes it. The login path also UPDATEs the row it just read --
-- the failure counter, the last-login stamp -- on a connection with no
-- actor announced, and a policy that refused those would turn signing in
-- into a silent no-op rather than an error anybody saw.
--
-- So this is a function the endpoint calls, exactly like
-- actor_may_manage_roster() in V8 and exportable_courses() before it.
-- The rule still lives in SQL, in one place, where it can be read
-- without reading Java -- and V6 is honest about what that does not buy:
-- what protects app_account is privilege and the service's own code.
--
--
-- WHY ADMIN AND NOT THE OFFICE
--
-- An account is not a record about a person, it is an authority. A
-- PROFESSOR account can mark every register of every course that
-- professor coordinates, from the moment it exists, with no role grant
-- at all -- V6's lectures_instructor policy asks about course_instructor
-- and not about user_role. Creating one is therefore closer to granting
-- a role than to editing a roster, and granting a role is the admin's
-- alone: user_role's policies say so and the permission matrix has one
-- column in that row.
--
-- An office that could create accounts but not grant roles would be a
-- confusing half-power, and the argument for widening this later is
-- real -- onboarding is office work in most colleges. That is why it is
-- one line here rather than a condition spelled out at three call
-- sites.
-- =====================================================================

CREATE FUNCTION actor_may_manage_accounts() RETURNS boolean
    LANGUAGE sql STABLE
    RETURN actor_has_role('ADMIN');

COMMENT ON FUNCTION actor_may_manage_accounts() IS
    'Whether the current actor may create accounts and read the account list. Admins only: an account is an authority rather than a record, and granting one is closer to granting a role than to editing a roster. Checked by the endpoint, because app_account has no policies -- see V6 and this file''s header.';
