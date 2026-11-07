-- ════════════════════════════════════════════════════════════════════
-- V54 — A professor edits every course their department offers
-- ════════════════════════════════════════════════════════════════════
--
-- V53 let a professor read every course their department offers. Asked
-- for next: they may edit them too, whether or not they coordinate the
-- course or head the department.
--
-- Editing, and no more than editing:
--
--   the course    its code, topic, stream, year, semester, type,
--                 coordinator and remarks, and discontinuing it. Not
--                 adding one or removing one, which stay as courses_write
--                 has them. The row must stay in the professor's
--                 department, so they cannot move a course out of reach
--                 of the department it belongs to.
--
--                 A push writes every row as INSERT ... ON CONFLICT DO
--                 UPDATE, and PostgreSQL holds the proposed row to the
--                 INSERT policies even when it ends as an update. So the
--                 edit needs an INSERT policy as well as an UPDATE one,
--                 and the INSERT policy admits only a course that already
--                 exists: an edit passes it, a new course does not.
--
--   what hangs    who teaches it (course_instructor) and, for an OE, AEC,
--   off it        IKS or VEC course, linking any department's batch to it
--                 (V52). Both ask actor_may_write_course(), which now
--                 includes the department's professors. Linking the
--                 department's own batches was already theirs (V33).
--
-- Not the registers. Taking attendance, correcting a mark and deleting a
-- lecture stay with the course's instructors and the head (V6). Because
-- an editor may change who teaches a course, a professor who adds
-- themselves as an instructor then takes its registers too; that is what
-- being its instructor means, and the audit trail records who did it.
--
-- V46 still holds: an IKS or VEC course is an administrator's alone, by a
-- restrictive policy that this permissive one does not get past. V50's
-- rule on who may coordinate an AEC is a trigger and holds too.

CREATE OR REPLACE FUNCTION actor_may_write_course(for_course bigint) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN actor_has_role('ADMIN')
        OR actor_has_role('OFFICE')
        OR actor_instructs(for_course)
        OR EXISTS (
            SELECT 1 FROM courses c
            WHERE c.course_id = for_course
              AND (c.prof_id = current_prof_id()
                OR actor_heads(c.offering_department)
                OR actor_reads_department_of(c.offering_department)));

COMMENT ON FUNCTION actor_may_write_course(bigint) IS
    'Whether the current actor may change a course and what hangs off it: staff, its instructors and coordinator, the head and (V54) every professor of its offering department. Not the auditor and not a CR: one changes nothing, the other takes attendance. Not the registers, which the lecture and attendance policies decide.';

-- Whether a course of that key exists, whoever may read it. SECURITY
-- DEFINER because a policy on courses cannot read courses through its own
-- policies.
CREATE FUNCTION course_exists(for_course bigint) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN EXISTS (SELECT 1 FROM courses c WHERE c.course_id = for_course);

COMMENT ON FUNCTION course_exists(bigint) IS
    'Whether a course of that key exists. What lets courses_department_edit admit the INSERT half of an upsert that edits a course, and not one that adds a course (V54).';

-- Beside V11's courses_write, in the row's own columns for the reason
-- given there. No DELETE.
CREATE POLICY courses_department_edit ON courses
    FOR INSERT
    WITH CHECK (actor_reads_department_of(offering_department)
                AND course_exists(course_id));

CREATE POLICY courses_department_update ON courses
    FOR UPDATE
    USING      (actor_reads_department_of(offering_department))
    WITH CHECK (actor_reads_department_of(offering_department));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_app') THEN
        GRANT EXECUTE ON FUNCTION course_exists(bigint) TO sies_app;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_auditor') THEN
        GRANT EXECUTE ON FUNCTION course_exists(bigint) TO sies_auditor;
    END IF;
END $$;
