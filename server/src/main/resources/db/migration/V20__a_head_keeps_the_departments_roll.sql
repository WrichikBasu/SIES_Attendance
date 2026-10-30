-- A head of department may add and remove their department's students.
--
-- Until now the roll was the office's alone: students_roster and
-- student_year_roster are FOR ALL on actor_may_manage_roster(), which is
-- OFFICE or ADMIN. A head of department could read their department's roll and
-- change nothing on it, so a student who had left, or one the office had not
-- entered yet, was a message to somebody else and a wait.
--
-- The department is the unit of authority here, and the question "whose
-- student is this" is answered by the enrolment, not by the students row: a
-- students row is a person, and student_year is what puts them on a roll in a
-- department in a session. So:
--
--   student_year   a head may write an enrolment in their own department
--   students       a head may write a students row with no enrolment outside
--                  the departments they head
--
-- The second is the careful half. "No enrolment outside my departments" rather
-- than "an enrolment inside one of them", and the difference is the student
-- enrolled in two departments: the head of Computer Science may not delete a
-- student who is also on Chemistry's roll, because that row is Chemistry's as
-- much as theirs. It also makes the common cases work: a brand-new students
-- row has no enrolment at all, so inserting one is admitted, and so is
-- removing one that was added by mistake before its enrolment existed.
--
-- A plain professor is unchanged and still reads only. Teaching a course is
-- not keeping the roll -- the roll is what says who is on the course in the
-- first place, and a professor correcting it would be a professor deciding
-- which of their students exist.

CREATE FUNCTION actor_heads_anything() RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN EXISTS (
        SELECT 1 FROM user_role ur
        WHERE ur.account_id    = current_account_id()
          AND ur.role          = 'HOD'
          AND ur.revoked_at IS NULL);

COMMENT ON FUNCTION actor_heads_anything() IS
    'Whether the actor heads any department at all. What a client asks to know whether to offer the roster screens; which rows they may actually change is still decided row by row.';

CREATE FUNCTION actor_heads_student(of_student bigint) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN actor_heads_anything()
        -- No enrolment that is not mine. A student on two departments' rolls is
        -- not one department's to remove, and a student with no enrolment at
        -- all is nobody's -- which is what a students row looks like in the
        -- moment between being created and being enrolled.
        AND NOT EXISTS (
            SELECT 1 FROM student_year y
            WHERE y.student_id = of_student
              AND NOT actor_heads(y.major_dept));

COMMENT ON FUNCTION actor_heads_student(bigint) IS
    'Whether every enrolment this student has is in a department the actor heads. False for a student enrolled anywhere else, because that row is that department''s too.';

CREATE POLICY students_head ON students
    FOR ALL TO PUBLIC
    USING      (actor_heads_student(student_id))
    WITH CHECK (actor_heads_student(student_id));

CREATE POLICY student_year_head ON student_year
    FOR ALL TO PUBLIC
    USING      (actor_heads(major_dept))
    WITH CHECK (actor_heads(major_dept));

-- The batch membership follows the student: a head who may remove a student
-- from the roll has to be able to take them out of the batches that point at
-- them, or the foreign key refuses the deletion they are allowed to make.
CREATE POLICY batch_student_map_head ON batch_student_map
    FOR ALL TO PUBLIC
    USING      (actor_heads_student(student_id))
    WITH CHECK (actor_heads_student(student_id));

-- What a client asks before offering the roster screens. Not an authority:
-- which rows may be changed is the policies' decision, row by row, and a head
-- who is offered the screen still cannot touch another department's student.
CREATE FUNCTION actor_may_keep_a_roll() RETURNS boolean
    LANGUAGE sql STABLE
    SET search_path = pg_catalog, public
    RETURN actor_may_manage_roster() OR actor_heads_anything();

COMMENT ON FUNCTION actor_may_keep_a_roll() IS
    'Whether to offer somebody the roster screens at all: the office, an administrator, or the head of a department. Which rows they may change is still decided per row.';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_app') THEN
        GRANT EXECUTE ON FUNCTION actor_heads_anything() TO sies_app;
        GRANT EXECUTE ON FUNCTION actor_heads_student(bigint) TO sies_app;
        GRANT EXECUTE ON FUNCTION actor_may_keep_a_roll() TO sies_app;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_auditor') THEN
        GRANT EXECUTE ON FUNCTION actor_heads_anything() TO sies_auditor;
        GRANT EXECUTE ON FUNCTION actor_heads_student(bigint) TO sies_auditor;
        GRANT EXECUTE ON FUNCTION actor_may_keep_a_roll() TO sies_auditor;
    END IF;
END $$;
