-- ════════════════════════════════════════════════════════════════════
-- V41 — A course's roll is its batches, and only its batches
-- ════════════════════════════════════════════════════════════════════
--
-- V23 decided a course's roll two ways: the members of the batches linked
-- to it for the session when it had any, and otherwise a set of rules on
-- the course type, matching each student's year, stream and one of their
-- three departments. Attendance is now taken only against a batch, so the
-- rules described a roll nobody could mark, and kept a second opinion of
-- who takes a course alive in the CR picker, in visibility and in the
-- export. Now a course with no batch linked for the session has nobody on
-- its roll.
--
-- The signature is V23's, unchanged: roll_for_course() and
-- actor_reaches_enrolment() call it with the enrolment's year, stream and
-- departments, and keeping it lets both stand as they are. Those
-- arguments are no longer read. DaoStudent.getRollForCourseSync and the
-- two export builders say the same.

CREATE OR REPLACE FUNCTION course_admits(
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
    RETURN EXISTS (
        SELECT 1
        FROM course_batch_map cb
        JOIN batch_student_map m ON m.batch_id = cb.batch_id
        WHERE cb.course_id     = for_course
          AND m.student_id     = e_student
          AND m.academic_year  = session_year);

COMMENT ON FUNCTION course_admits(bigint, integer, bigint, study_year, degree_stream, department, department, department) IS
    'Whether a student is on a course''s roll for a session: a member, that session, of a batch linked to the course (V41). The enrolment arguments are V23''s and are not read.';
