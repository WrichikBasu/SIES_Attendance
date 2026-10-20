-- A head with an open-ended grant could not appoint a class representative.
--
--   ERROR: account 10 cannot designate a CR for course 370666966312026112 as HOD
--
-- V18 made the session optional on a HOD grant and taught actor_is_hod_of()
-- that a grant naming no session answers for every session. It did not teach
-- attendance_delegate_authorised(), which asks the same question in its own
-- words:
--
--   AND ur.academic_year = NEW.academic_year
--
-- NULL = 2026 is not false, it is unknown, so the EXISTS was false, so the
-- trigger refused the appointment -- as check_violation, which reaches the
-- page as "the server would not accept one of those values". A head appointing
-- a representative for a course in their own department was told, in effect,
-- that they were not the head.
--
-- The instance is easy to patch and the class is not: this is the second rule
-- in this schema written twice and found to have drifted, after an index whose
-- comment described a constraint it was not enforcing. So the rule moves into
-- one function and both askers call it.
--
-- They cannot share actor_is_hod_of(), and that is the point of the split: the
-- trigger asks about NEW.granted_by -- whoever is recorded as having made the
-- grant -- and not about whoever is connected. "The designator held the
-- authority they claimed" is a question about the row, and answering it with
-- the current actor would make a grant authorised by whoever happened to be
-- replaying it.

CREATE FUNCTION account_is_hod_of(of_account bigint, dept department,
                                  session_year integer) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN EXISTS (
        SELECT 1 FROM user_role ur
        WHERE ur.account_id    = of_account
          AND ur.role          = 'HOD'
          AND ur.revoked_at IS NULL
          AND ur.department    = dept
          -- The session, or every session. See V18.
          AND (ur.academic_year = session_year OR ur.academic_year IS NULL));

COMMENT ON FUNCTION account_is_hod_of(bigint, department, integer) IS
    'Whether that account heads that department for that session. The one definition: actor_is_hod_of() asks it about the actor, attendance_delegate_authorised() asks it about the account recorded on the grant.';

-- Now a thin wrapper, which is all it should ever have been.
CREATE OR REPLACE FUNCTION actor_is_hod_of(dept department, session_year integer)
    RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN account_is_hod_of(current_account_id(), dept, session_year);

CREATE OR REPLACE FUNCTION attendance_delegate_authorised() RETURNS trigger
    LANGUAGE plpgsql AS $fn$
DECLARE
    allowed boolean;
BEGIN
    SELECT CASE NEW.granted_as

        -- Teaches this course. course_instructor always contains the
        -- coordinator, so this never misses one. No session here, and none
        -- wanted: teaching is not year-scoped anywhere in this schema.
        WHEN 'INSTRUCTOR' THEN EXISTS (
            SELECT 1
            FROM app_account a
            JOIN course_instructor ci ON ci.prof_id = a.prof_id
            WHERE a.account_id = NEW.granted_by
              AND a.kind       = 'PROFESSOR'
              AND ci.course_id = NEW.course_id)

        -- Head of the department that OFFERS the course. Both halves of the
        -- old rule still hold -- a head of Physics may not designate for a
        -- Chemistry course, and a head appointed for 2025-26 alone may not
        -- designate for 2027-28 -- and the second is now account_is_hod_of's
        -- to decide, in the one place it is decided.
        WHEN 'HOD' THEN EXISTS (
            SELECT 1 FROM courses c
            WHERE c.course_id = NEW.course_id
              AND account_is_hod_of(NEW.granted_by, c.offering_department,
                                    NEW.academic_year))

        WHEN 'ADMIN' THEN EXISTS (
            SELECT 1
            FROM user_role ur
            WHERE ur.account_id = NEW.granted_by
              AND ur.role       = 'ADMIN'
              AND ur.revoked_at IS NULL)
    END INTO allowed;

    -- COALESCE, not a bare NOT: an authority no branch above handles leaves
    -- allowed NULL, and "IF NOT NULL" is not true, so the check would have
    -- been skipped rather than failed. Fail closed.
    IF NOT COALESCE(allowed, false) THEN
        RAISE EXCEPTION
            'account % cannot designate a CR for course % as %',
            NEW.granted_by, NEW.course_id, NEW.granted_as
            USING ERRCODE = 'check_violation';
    END IF;

    -- Whatever the authority, the CR has to be on the roll. A CR of CS-101 who
    -- does not take CS-101 is a mistake, not a configuration.
    IF NOT EXISTS (
        SELECT 1 FROM roll_for_course(NEW.course_id, NEW.academic_year) r
        WHERE r.student_id = NEW.student_id)
    THEN
        RAISE EXCEPTION
            'student % is not on the roll of course % in %',
            NEW.student_id, NEW.course_id, NEW.academic_year
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END $fn$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_app') THEN
        GRANT EXECUTE ON FUNCTION account_is_hod_of(bigint, department, integer) TO sies_app;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_auditor') THEN
        GRANT EXECUTE ON FUNCTION account_is_hod_of(bigint, department, integer)
            TO sies_auditor;
    END IF;
END $$;
