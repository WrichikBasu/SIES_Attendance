-- A professor reads every student their department has a hand in: those
-- with it as their major, and now also those with it as their minor or open
-- elective.
--
-- V27 gave a professor their department's roll, read by major alone. A
-- Mathematics minor in a Computer Science class was readable only through
-- reachability (V11, V23): on a roll the professor teaches, in a session they
-- teach it. A Chemistry professor with no minor course this term could not
-- see the students who will take one, and could not look one up to check
-- what they had chosen.
--
-- Reading only. Writing stays as V27 left it, by major: a minor is chosen in
-- the student's own department, and a professor of the minor's department
-- has no business rewriting the enrolment that records it.

CREATE POLICY student_year_professor_read_minor_oe ON student_year
    FOR SELECT
    USING (actor_prof_department() IN (minor_dept, oe_dept));

-- The student comes with any enrolment the professor may read, as V27 said:
-- a phone that receives an enrolment pointing at nobody has nothing to show.
CREATE OR REPLACE FUNCTION actor_prof_teaches_department_of(of_student bigint) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN EXISTS (
        SELECT 1 FROM student_year y
        WHERE y.student_id = of_student
          AND actor_prof_department() IN (y.major_dept, y.minor_dept, y.oe_dept));

COMMENT ON FUNCTION actor_prof_teaches_department_of(bigint) IS
    'Whether this student has an enrolment with the acting professor''s department as its major, minor or open elective.';
