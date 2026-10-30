-- Where a course has batches, its roll is who is in them.
--
-- Until now a course's roll was worked out from rules: a major course is the
-- offering department's majors in that year and stream, an open elective is
-- everybody in that year and stream whose oe_dept is the offering department,
-- and so on. The rules are a guess, and three of the guesses are wrong:
--
--   - An open elective is taken across streams. y.stream = c.stream left out
--     every BA or BCom student who chose a Physics elective filed under BSc.
--   - AEC, IKS, VSC and SEC courses matched the whole year and stream across
--     every department, so the CR of one section saw everybody.
--   - A practical course taught in two halves had one roll for both.
--
-- A batch is not a guess. Somebody put those students in it, and V22 made
-- linking it to a course a professor's to do. So the rule is now:
--
--   a course with at least one batch linked for the session
--       its roll is the members of those batches, in that session -- from any
--       department, year or stream, which is the point
--   a course with none
--       the rules, exactly as before
--
-- Linked "for the session" because course_batch_map has no session and a
-- batch does: a course linked to last year's Practical B1 has said nothing
-- about this year's roll, and falls back to the rules until it is linked to
-- this year's batches.
--
-- One definition, course_admits(), and both askers call it. roll_for_course()
-- lists a course's roll; actor_reaches_enrolment() asks whether an enrolment
-- is on the roll of a course the actor reaches. They were two copies of the
-- same four-way match until now, kept in step by a drift test. A batch is a
-- question about the student, not about the enrolment's columns, so the copy
-- would have had to grow a second half as well -- and this is the second rule
-- in this schema to be written twice (see V19). So the copy goes.
--
-- The app has the same rule in DaoStudent.getRollForCourseSync and in both
-- export builders, which cannot call this: the device has no PostgreSQL, and
-- the differ that holds the builders to each other runs on V1 and V2 alone.
-- Those three change in the same commit as this file.

CREATE FUNCTION course_has_batches(for_course bigint, session_year integer)
    RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN EXISTS (
        SELECT 1
        FROM course_batch_map cb
        JOIN batch_desc b ON b.batch_id = cb.batch_id
        WHERE cb.course_id    = for_course
          AND b.academic_year = session_year);

COMMENT ON FUNCTION course_has_batches(bigint, integer) IS
    'Whether a course has any batch linked for that session, which is what decides whether its roll is its batches or the rules. See course_admits().';

-- SECURITY DEFINER for the reason every policy helper here is: this is asked
-- from inside the policies on student_year and students, and it reads
-- batch_student_map, whose own read policy asks actor_may_see_student(),
-- which asks this. Under the caller's policies that is a loop.
CREATE FUNCTION course_admits(
        for_course     bigint,
        session_year   integer,
        e_student      bigint,
        e_year         study_year,
        e_stream       degree_stream,
        e_major        department,
        e_minor        department,
        e_oe           department) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN CASE
        WHEN course_has_batches(for_course, session_year) THEN EXISTS (
            SELECT 1
            FROM course_batch_map cb
            JOIN batch_student_map m ON m.batch_id = cb.batch_id
            WHERE cb.course_id     = for_course
              AND m.student_id     = e_student
              AND m.academic_year  = session_year)
        ELSE EXISTS (
            SELECT 1 FROM courses c
            WHERE c.course_id = for_course
              AND c.year      = e_year
              AND c.stream    = e_stream
              AND (
                  (c.course_type IN ('MAJOR', 'MAJOR_PRACTICALS', 'DSE', 'DSE_PRACTICALS')
                       AND e_major = c.offering_department)
               OR (c.course_type IN ('MINOR', 'MINOR_PRACTICALS')
                       AND (e_minor = c.offering_department
                         OR e_major = c.offering_department))
               OR (c.course_type = 'OE' AND e_oe = c.offering_department)
               OR  c.course_type IN ('AEC', 'IKS', 'VSC', 'VSC_PRACTICALS',
                                     'SEC', 'SEC_PRACTICALS')))
    END;

COMMENT ON FUNCTION course_admits(bigint, integer, bigint, study_year, degree_stream, department, department, department) IS
    'Whether this enrolment is on the roll of that course in that session. The members of the batches linked to the course for the session when it has any, and the course-type rules when it has none. The one definition of a roll: roll_for_course() and actor_reaches_enrolment() both ask it, and DaoStudent.getRollForCourseSync and the two export builders are its translations.';

-- The same function, listed rather than asked. Not SECURITY DEFINER, as
-- before: which enrolments it can see is still the caller's policies'.
CREATE OR REPLACE FUNCTION roll_for_course(for_course bigint, session_year integer)
    RETURNS TABLE (student_id bigint, roll_no text)
    LANGUAGE sql STABLE
    BEGIN ATOMIC
        SELECT y.student_id, y.roll_no
        FROM student_year y
        WHERE y.academic_year = session_year
          AND course_admits(for_course, session_year, y.student_id, y.year, y.stream,
                            y.major_dept, y.minor_dept, y.oe_dept);
    END;

-- The reachability predicate, now with the student it is about. A batch is
-- a list of students, and the old signature had only the enrolment's columns.
CREATE FUNCTION actor_reaches_enrolment(
        e_student      bigint,
        e_year         study_year,
        e_stream       degree_stream,
        e_major        department,
        e_minor        department,
        e_oe           department,
        session_year   integer) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN actor_is_hod_of(e_major, session_year)
        OR EXISTS (
            SELECT 1 FROM courses c
            WHERE (actor_instructs(c.course_id)
                OR actor_is_cr_of(c.course_id, session_year)
                OR actor_is_hod_of(c.offering_department, session_year))
              AND course_admits(c.course_id, session_year, e_student, e_year, e_stream,
                                e_major, e_minor, e_oe));

COMMENT ON FUNCTION actor_reaches_enrolment(bigint, study_year, degree_stream, department, department, department, integer) IS
    'Whether the current actor reaches a student_year row: the head of its major department in that session, or anyone who instructs, represents or heads the offering department of a course the row is on the roll of, as course_admits() decides it.';

CREATE OR REPLACE FUNCTION actor_may_see_student(for_student bigint) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN EXISTS (
        SELECT 1 FROM student_year y
        WHERE y.student_id = for_student
          AND actor_reaches_enrolment(y.student_id, y.year, y.stream, y.major_dept,
                                      y.minor_dept, y.oe_dept, y.academic_year));

DROP POLICY student_year_reachable ON student_year;

CREATE POLICY student_year_reachable ON student_year
    FOR SELECT
    USING (actor_reaches_enrolment(student_id, year, stream, major_dept, minor_dept,
                                   oe_dept, academic_year));

-- The old signature, which nothing calls now. Dropped rather than left: a
-- second predicate that still answers by the rules alone is exactly the copy
-- this migration exists to remove.
DROP FUNCTION actor_reaches_enrolment(study_year, degree_stream, department, department,
                                      department, integer);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_app') THEN
        GRANT EXECUTE ON FUNCTION course_has_batches(bigint, integer) TO sies_app;
        GRANT EXECUTE ON FUNCTION course_admits(bigint, integer, bigint, study_year,
            degree_stream, department, department, department) TO sies_app;
        GRANT EXECUTE ON FUNCTION actor_reaches_enrolment(bigint, study_year, degree_stream,
            department, department, department, integer) TO sies_app;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_auditor') THEN
        GRANT EXECUTE ON FUNCTION course_has_batches(bigint, integer) TO sies_auditor;
        GRANT EXECUTE ON FUNCTION course_admits(bigint, integer, bigint, study_year,
            degree_stream, department, department, department) TO sies_auditor;
        GRANT EXECUTE ON FUNCTION actor_reaches_enrolment(bigint, study_year, degree_stream,
            department, department, department, integer) TO sies_auditor;
    END IF;
END $$;
