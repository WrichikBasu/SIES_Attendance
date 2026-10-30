-- A professor whose designation becomes HOD is made head of their department.
--
-- The console grants it when it creates a professor's account (see
-- AccountsController and Designations). The phone's professor form sets the
-- designation too -- the office's and an administrator's, since V35 -- and
-- the row arrives by sync, where no endpoint is there to grant anything. So
-- the rule moves to where every path meets: a professors row whose
-- designation is set to HOD, or which moves department while it is HOD,
-- makes that professor's account head of the department, until revoked.
--
-- Granting only. A designation changed away from HOD leaves the role where
-- it is: ending an appointment is an administrator's act on the console,
-- with a reason, and a typo on a phone should not quietly take a head's
-- authority away.
--
-- SECURITY DEFINER because user_role is written by administrators alone
-- (user_role_admin_grant), and the office may set the designation that
-- carries the role. The grant is recorded against whoever made the change,
-- and the audit trigger on user_role records it like any other grant. A
-- change with no actor -- a migration, the superuser -- grants nothing.

CREATE FUNCTION designation_is_heads(designation text) RETURNS boolean
    LANGUAGE sql IMMUTABLE
    RETURN lower(btrim(coalesce(designation, ''))) IN ('hod', 'head of department');

COMMENT ON FUNCTION designation_is_heads(text) IS
    'Whether a designation is a head''s: HOD, or "Head of Department" as typed before the list was fixed. Designations.isHeads() is the same rule in Java.';

CREATE FUNCTION professors_grant_head() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = pg_catalog, public AS $fn$
BEGIN
    IF NOT designation_is_heads(NEW.designation) OR current_account_id() IS NULL THEN
        RETURN NULL;
    END IF;

    IF TG_OP = 'UPDATE' AND designation_is_heads(OLD.designation)
            AND OLD.department = NEW.department THEN
        RETURN NULL;
    END IF;

    INSERT INTO user_role (account_id, role, department, academic_year, granted_by, reason)
    SELECT a.account_id, 'HOD', NEW.department, NULL, current_account_id(),
           'designation set to HOD'
    FROM app_account a
    WHERE a.prof_id = NEW.prof_id
      AND NOT EXISTS (
          SELECT 1 FROM user_role r
          WHERE r.account_id = a.account_id
            AND r.role = 'HOD'
            AND r.department = NEW.department
            AND r.revoked_at IS NULL);

    RETURN NULL;
END $fn$;

CREATE TRIGGER professors_grant_head
    AFTER INSERT OR UPDATE OF designation, department ON professors
    FOR EACH ROW EXECUTE FUNCTION professors_grant_head();

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_app') THEN
        GRANT EXECUTE ON FUNCTION designation_is_heads(text) TO sies_app;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_auditor') THEN
        GRANT EXECUTE ON FUNCTION designation_is_heads(text) TO sies_auditor;
    END IF;
END $$;
