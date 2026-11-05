-- ════════════════════════════════════════════════════════════════════
-- V49 — An AEC is offered by a language department
-- ════════════════════════════════════════════════════════════════════
--
-- An ability enhancement course is a language course: English, Hindi or
-- Marathi offers it, whichever class takes it. A check on the row rather
-- than on who writes it, so it holds for everybody, an administrator and
-- the command-line import included.
--
-- Who may make one follows from that, through courses_write (V11): the
-- head of English, Hindi or Marathi, the office or an administrator, and
-- the professor named as its coordinator.
--
-- CourseType.offeringDepartments() is the same list, for the app;
-- PostgresSchemaDriftTest holds the two together.
--
-- Added NOT VALID, then validated only if nothing already breaks it. A
-- database with an AEC from another department still migrates, and the
-- rule applies to every course written from now on. Changing such a
-- course will then be refused until its department is corrected. To find
-- them:
--
--   SELECT course_id, course_code, offering_department FROM courses
--   WHERE NOT course_aec_offered_by_a_language_department(course_type,
--                                                          offering_department);
--
-- and once they are fixed:
--
--   ALTER TABLE courses VALIDATE CONSTRAINT courses_aec_language_department;

CREATE FUNCTION course_aec_offered_by_a_language_department(t course_type, d department)
    RETURNS boolean
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    RETURN t <> 'AEC' OR d IN ('ENGLISH', 'HINDI', 'MARATHI');

COMMENT ON FUNCTION course_aec_offered_by_a_language_department(course_type, department) IS
    'Whether a course of that type may be offered by that department: an AEC by English, Hindi or Marathi alone (V49).';

ALTER TABLE courses
    ADD CONSTRAINT courses_aec_language_department
        CHECK (course_aec_offered_by_a_language_department(course_type, offering_department))
        NOT VALID;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM courses
                   WHERE NOT course_aec_offered_by_a_language_department(
                             course_type, offering_department)) THEN
        ALTER TABLE courses VALIDATE CONSTRAINT courses_aec_language_department;
    ELSE
        RAISE WARNING 'courses_aec_language_department left NOT VALID: there are AEC courses offered by other departments. See V49.';
    END IF;
END $$;
