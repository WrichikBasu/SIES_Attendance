-- A professor may add and correct the students of their own department.
--
-- Until now the roll was the office's, an administrator's, and since V20 a
-- head's for the departments they head. A professor could read the students
-- on their own courses' rolls and change none of them. Now a professor may
-- add students and enrolments, and correct them, where the enrolment's major
-- department is the professor's own (professors.department) -- by the roster
-- import or one at a time. Removing a student is unchanged: the office's, an
-- administrator's, and a head's.
--
-- "Own department" for a students row is the same careful rule V20 gave a
-- head: every enrolment the student has is in that department, or they have
-- none yet. A student also on Chemistry's roll is Chemistry's as much as
-- Computer Science's, and a CS professor may not rewrite them. A brand-new
-- students row has no enrolment, so inserting one is admitted; its enrolment
-- is then held to the department.
--
-- A professor reads their department's roll as well. Correcting a student
-- means finding them first, and a CS professor who teaches only the first
-- years would otherwise be unable to see the second-year roll they are
-- loading.

CREATE FUNCTION actor_prof_department() RETURNS department
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN (SELECT p.department FROM professors p WHERE p.prof_id = current_prof_id());

COMMENT ON FUNCTION actor_prof_department() IS
    'The department of the professor acting, or NULL when the actor is not a professor.';

CREATE FUNCTION actor_prof_holds_student(of_student bigint) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN actor_prof_department() IS NOT NULL
        AND NOT EXISTS (
            SELECT 1 FROM student_year y
            WHERE y.student_id = of_student
              AND y.major_dept IS DISTINCT FROM actor_prof_department());

COMMENT ON FUNCTION actor_prof_holds_student(bigint) IS
    'Whether every enrolment this student has is in the acting professor''s department, or they have none. The rule V20 gives a head, for one department.';

-- What the roster import asks of each row before writing it, so a row for
-- another department is refused in a sentence rather than by a policy.
CREATE FUNCTION actor_may_enrol_in(dept department) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN actor_may_manage_roster()
        OR actor_heads(dept)
        OR dept = actor_prof_department();

COMMENT ON FUNCTION actor_may_enrol_in(department) IS
    'Whether the actor may add or correct an enrolment in this department: the office, an administrator, its head, or one of its professors.';

-- Whether to offer the roster import at all. Not an authority: the rows are
-- still decided one by one.
CREATE FUNCTION actor_may_import_roster() RETURNS boolean
    LANGUAGE sql STABLE
    SET search_path = pg_catalog, public
    RETURN actor_may_manage_roster() OR actor_heads_anything()
        OR actor_prof_department() IS NOT NULL;

CREATE POLICY student_year_professor_read ON student_year
    FOR SELECT
    USING (major_dept = actor_prof_department());

CREATE POLICY student_year_professor_insert ON student_year
    FOR INSERT
    WITH CHECK (major_dept = actor_prof_department());

CREATE POLICY student_year_professor_update ON student_year
    FOR UPDATE
    USING      (major_dept = actor_prof_department())
    WITH CHECK (major_dept = actor_prof_department());

-- Read: any student with an enrolment in the department. Wider than the
-- write rule on purpose. Every enrolment a professor reads has to come with its
-- student, or a phone receives a row pointing at nobody (V21); a student on
-- two departments' rolls is read by both and corrected by neither.
CREATE FUNCTION actor_prof_teaches_department_of(of_student bigint) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN EXISTS (
        SELECT 1 FROM student_year y
        WHERE y.student_id = of_student
          AND y.major_dept = actor_prof_department());

CREATE POLICY students_professor_read ON students
    FOR SELECT
    USING (actor_prof_teaches_department_of(student_id));

CREATE POLICY students_professor_insert ON students
    FOR INSERT
    WITH CHECK (actor_prof_department() IS NOT NULL);

CREATE POLICY students_professor_update ON students
    FOR UPDATE
    USING      (actor_prof_holds_student(student_id))
    WITH CHECK (actor_prof_holds_student(student_id));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_app') THEN
        GRANT EXECUTE ON FUNCTION actor_prof_department() TO sies_app;
        GRANT EXECUTE ON FUNCTION actor_prof_holds_student(bigint) TO sies_app;
        GRANT EXECUTE ON FUNCTION actor_prof_teaches_department_of(bigint) TO sies_app;
        GRANT EXECUTE ON FUNCTION actor_may_enrol_in(department) TO sies_app;
        GRANT EXECUTE ON FUNCTION actor_may_import_roster() TO sies_app;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_auditor') THEN
        GRANT EXECUTE ON FUNCTION actor_prof_department() TO sies_auditor;
        GRANT EXECUTE ON FUNCTION actor_prof_holds_student(bigint) TO sies_auditor;
        GRANT EXECUTE ON FUNCTION actor_prof_teaches_department_of(bigint) TO sies_auditor;
        GRANT EXECUTE ON FUNCTION actor_may_enrol_in(department) TO sies_auditor;
        GRANT EXECUTE ON FUNCTION actor_may_import_roster() TO sies_auditor;
    END IF;
END $$;
