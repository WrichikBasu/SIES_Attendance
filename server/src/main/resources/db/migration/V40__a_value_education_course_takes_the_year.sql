-- ════════════════════════════════════════════════════════════════════
-- V40 — A value education course is on the roll of the whole year
-- ════════════════════════════════════════════════════════════════════
--
-- V23's course_admits, with VEC beside AEC and IKS: a course with no
-- batches admits every enrolment of its year and stream. Unchanged
-- otherwise. DaoStudent.getRollForCourseSync and the two export builders
-- carry the same list.

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
               OR  c.course_type IN ('AEC', 'IKS', 'VEC', 'VSC', 'VSC_PRACTICALS',
                                     'SEC', 'SEC_PRACTICALS')))
    END;
