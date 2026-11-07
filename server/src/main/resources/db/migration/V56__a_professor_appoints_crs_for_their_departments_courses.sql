-- ════════════════════════════════════════════════════════════════════
-- V56 — A professor appoints CRs for their department's courses
-- ════════════════════════════════════════════════════════════════════
--
-- A class representative was appointed by the course's instructors
-- (which includes its coordinator, who always teaches it: V37, V38), by
-- the head of the offering department for that session, or by an
-- administrator. Asked for: any professor of the offering department may
-- appoint one too, as they may now read (V53), edit (V54) and add (V55)
-- that department's courses.
--
-- A grant records the capacity it was made in (GrantAuthority), and
-- attendance_delegate_authorised() checks that capacity against the
-- account recorded on the grant. So this is a fourth capacity,
-- DEPARTMENT, checked as "granted_by is a professor whose department
-- offers the course". Not year-scoped, like INSTRUCTOR: a professor
-- belongs to a department, not to a department-in-a-session.
--
-- The appointment is still only to the course's roll, which the trigger
-- checks whatever the capacity. Revoking follows granting, as it does for
-- every other capacity.
--
-- GrantAuthority.DEPARTMENT is the same constant, appended last for the
-- reason CourseType gives: an enum here can only grow at the end.

ALTER TYPE grant_authority ADD VALUE 'DEPARTMENT';

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

        -- Head of the department that OFFERS the course, for the grant's
        -- session; account_is_hod_of decides both halves (V19).
        WHEN 'HOD' THEN EXISTS (
            SELECT 1 FROM courses c
            WHERE c.course_id = NEW.course_id
              AND account_is_hod_of(NEW.granted_by, c.offering_department,
                                    NEW.academic_year))

        -- V56: a professor of the department that offers the course.
        WHEN 'DEPARTMENT' THEN EXISTS (
            SELECT 1
            FROM app_account a
            JOIN professors p ON p.prof_id = a.prof_id
            JOIN courses c    ON c.offering_department = p.department
            WHERE a.account_id = NEW.granted_by
              AND a.kind       = 'PROFESSOR'
              AND c.course_id  = NEW.course_id)

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


-- ── Who appoints, and who sees the appointments ──────────────────────
--
-- Beside V6's instructor, head and administrator policies. Seeing them is
-- what lets the department's professors see who represents a course
-- they read (V53), and revoking is an UPDATE of revoked_at.

CREATE POLICY attendance_delegate_department_select ON attendance_delegate
    FOR SELECT
    USING (EXISTS (
        SELECT 1 FROM courses c
        WHERE c.course_id = attendance_delegate.course_id
          AND actor_reads_department_of(c.offering_department)));

CREATE POLICY attendance_delegate_department_grant ON attendance_delegate
    FOR INSERT
    WITH CHECK (EXISTS (
        SELECT 1 FROM courses c
        WHERE c.course_id = attendance_delegate.course_id
          AND actor_reads_department_of(c.offering_department)));

CREATE POLICY attendance_delegate_department_revoke ON attendance_delegate
    FOR UPDATE
    USING (EXISTS (
        SELECT 1 FROM courses c
        WHERE c.course_id = attendance_delegate.course_id
          AND actor_reads_department_of(c.offering_department)))
    WITH CHECK (EXISTS (
        SELECT 1 FROM courses c
        WHERE c.course_id = attendance_delegate.course_id
          AND actor_reads_department_of(c.offering_department)));
