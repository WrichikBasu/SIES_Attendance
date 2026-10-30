-- Professors: kept by the office and administrators alone, and a professor
-- reads their own department's.
--
-- Writing. V11's professors_office already kept the staff list to the office
-- and administrators, so a head was refused another professor's row. What
-- V11 also left was professors_own: a professor might rewrite their own row,
-- department included. Since V31 a department is an authority -- it decides
-- whose batches a professor keeps and reads -- and a professor who could
-- move themselves into another department could take its batches with them.
-- No screen ever offered it; the policy goes.
--
-- Reading. Every signed-in account read every professor (V11). Now staff
-- still read them all, and a professor reads:
--
--   the professors of their own department, and of any they head; and
--
--   the coordinator and instructors of every course they may read. A phone
--   holds a course with its coordinator and its course_instructor rows, and
--   a row pointing at a professor the device was never sent is a row it
--   cannot store -- so another department's professor comes with the course
--   they teach, and with nothing else.
--
-- A class representative reads the professors of the courses they represent,
-- on the same second rule.

DROP POLICY professors_own ON professors;

CREATE FUNCTION actor_may_see_professor(for_prof bigint, prof_dept department)
    RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN current_account_id() IS NOT NULL
       AND (actor_has_role('ADMIN') OR actor_has_role('OFFICE') OR actor_has_role('AUDITOR')
            OR (current_prof_id() IS NULL AND current_student_id() IS NULL)
            OR for_prof = current_prof_id()
            OR (current_prof_id() IS NOT NULL
                AND (prof_dept = actor_prof_department() OR actor_heads(prof_dept)))
            OR EXISTS (
                SELECT 1 FROM courses c
                WHERE c.prof_id = for_prof
                  AND actor_may_read_course(c.course_id))
            OR EXISTS (
                SELECT 1 FROM course_instructor ci
                WHERE ci.prof_id = for_prof
                  AND actor_may_read_course(ci.course_id)));

COMMENT ON FUNCTION actor_may_see_professor(bigint, department) IS
    'Whether the actor may read a professor. Staff, every one. A professor, themselves, their own department''s and the ones they head, and whoever coordinates or teaches a course they may read. A student, whoever coordinates or teaches a course they represent.';

DROP POLICY professors_read ON professors;

CREATE POLICY professors_read ON professors
    FOR SELECT
    USING (actor_may_see_professor(prof_id, department));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_app') THEN
        GRANT EXECUTE ON FUNCTION actor_may_see_professor(bigint, department) TO sies_app;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_auditor') THEN
        GRANT EXECUTE ON FUNCTION actor_may_see_professor(bigint, department) TO sies_auditor;
    END IF;
END $$;
