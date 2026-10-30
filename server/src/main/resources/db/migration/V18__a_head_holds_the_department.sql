-- A head of department is the head of it, not the head of it for one session.
--
-- V5 made the session compulsory on a HOD grant:
--
--   user_role_session_iff_hod  CHECK ((role = 'HOD') = (academic_year IS NOT NULL))
--
-- and the reasoning was good: a head for 2025-26 should not quietly still be
-- one in 2027-28. What it misread is how a college works. A head is appointed
-- and serves until somebody else is, which may be four years; the appointment
-- is ended by a revocation, not by a January. Requiring a year meant
-- re-granting the same person the same department every June, and a grant
-- nobody re-made was a head who silently stopped being one -- the opposite of
-- the failure it was guarding against.
--
-- So the year becomes optional and means what its absence should mean:
--
--   academic_year = 2026   this department, that session only
--   academic_year = NULL   this department, until it is revoked
--
-- Both are kept because both are real: a head standing in for one term is a
-- year-scoped grant, and that is worth being able to say. The default is now
-- the open one, which is what an appointment normally is.
--
-- A professor needs nothing here. Teaching is not year-scoped anywhere in this
-- schema: course_instructor names a professor and a course, actor_instructs()
-- asks that and nothing about a session, and a course that is no longer taught
-- is marked discontinued rather than expiring. That is the shape V18 gives the
-- head as well.

ALTER TABLE user_role DROP CONSTRAINT user_role_session_iff_hod;

-- Only a head may name a session; a head need not. The first half still
-- matters: an ADMIN with an academic_year would be a college-wide role
-- pretending to a scope it does not have.
ALTER TABLE user_role ADD CONSTRAINT user_role_session_only_for_hod
    CHECK (role = 'HOD' OR academic_year IS NULL);

COMMENT ON CONSTRAINT user_role_session_only_for_hod ON user_role IS
    'A session may be named only on a HOD grant, and need not be: a null session is the ordinary open-ended appointment, and a named one is a head standing in for that year.';

-- The one function that reads the session. A grant with no year answers for
-- every year; a grant with one answers for that year alone.
CREATE OR REPLACE FUNCTION actor_is_hod_of(dept department, session_year integer)
    RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN EXISTS (
        SELECT 1 FROM user_role ur
        WHERE ur.account_id    = current_account_id()
          AND ur.role          = 'HOD'
          AND ur.revoked_at IS NULL
          AND ur.department    = dept
          AND (ur.academic_year = session_year OR ur.academic_year IS NULL));

COMMENT ON FUNCTION actor_is_hod_of(department, integer) IS
    'Whether the current actor heads that department for that session: a grant naming the session, or an open-ended grant naming none.';

-- ── A professor reads their own department's trail ───────────────────
--
-- V16 gave the department's width to its head and V17 gave a professor the
-- entries about the courses they teach. Asked for: a professor reads their
-- DEPARTMENT's. That is the honest line, because a department's teaching is
-- collegial -- the register of a course somebody is covering for you is your
-- business in the way the Chemistry department's is not -- and because the
-- narrower rule produced the odd result that a head who taught nothing saw
-- four entries while a professor who taught one course saw thousands.

CREATE FUNCTION actor_department() RETURNS department
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN (
        SELECT p.department FROM professors p WHERE p.prof_id = current_prof_id());

COMMENT ON FUNCTION actor_department() IS
    'The department of the professor acting, or null when the actor is not one. Not year-scoped: a professor belongs to a department, not to a department-in-a-session.';

CREATE OR REPLACE FUNCTION audit_lecture_is_mine(entry_key jsonb) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN EXISTS (
        SELECT 1
        FROM lectures l
        JOIN courses c ON c.course_id = l.course_id
        WHERE l.lecture_id = (entry_key ->> 'lecture_id')::bigint
          AND (actor_instructs(l.course_id)
               OR actor_heads(c.offering_department)
               OR c.offering_department = actor_department()));

CREATE OR REPLACE FUNCTION audit_row_is_mine(entry_table text, entry_key jsonb)
    RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN
        entry_table IS NOT NULL
        AND entry_key IS NOT NULL
        AND CASE entry_table
            WHEN 'attendance' THEN audit_lecture_is_mine(entry_key)
            WHEN 'lectures'   THEN audit_lecture_is_mine(entry_key)
            WHEN 'courses' THEN EXISTS (
                SELECT 1 FROM courses c
                WHERE c.course_id = (entry_key ->> 'course_id')::bigint
                  AND (actor_instructs(c.course_id)
                       OR actor_heads(c.offering_department)
                       OR c.offering_department = actor_department()))
            ELSE false
        END;

-- And the actor-based branch: an entry made by a professor of my department is
-- my department's doing. V16 asked whether the reader HEADS the actor's
-- department; now a colleague in it is enough.
CREATE OR REPLACE FUNCTION audit_entry_visible(entry_actor bigint, entry_delegate bigint,
                                               entry_table text DEFAULT NULL,
                                               entry_key jsonb DEFAULT NULL)
    RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN
        actor_has_role('AUDITOR') OR actor_has_role('ADMIN')
        OR (entry_actor IS NOT NULL AND entry_actor = current_account_id())
        OR (entry_delegate IS NOT NULL AND actor_instructs(entry_delegate))
        OR (entry_actor IS NOT NULL
            AND account_department(entry_actor) IS NOT NULL
            AND (actor_heads(account_department(entry_actor))
                 OR account_department(entry_actor) = actor_department()))
        OR audit_row_is_mine(entry_table, entry_key);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_app') THEN
        GRANT EXECUTE ON FUNCTION actor_department() TO sies_app;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_auditor') THEN
        GRANT EXECUTE ON FUNCTION actor_department() TO sies_auditor;
    END IF;
END $$;
