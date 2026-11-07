-- ════════════════════════════════════════════════════════════════════
-- V55 — A professor adds courses to their department
-- ════════════════════════════════════════════════════════════════════
--
-- V54 let a professor edit every course their department offers and
-- held them back from adding one, admitting the INSERT half of a push's
-- upsert only for a course that already existed. Asked for next: they
-- may add courses under their department too, coordinated by whoever
-- they choose. Until now a professor could add a course only with
-- themselves as its coordinator (V11's courses_write).
--
-- So the INSERT policy loses that condition and admits any course whose
-- offering department is the professor's own. Everything else stands:
--
--   removing a course stays with the head, the office and an
--   administrator. There is still no DELETE policy for the department.
--
--   the course stays in the professor's department, on the way in as on
--   every edit (courses_department_update).
--
--   V46's restrictive policies keep IKS and VEC courses an
--   administrator's, and V49 and V50 keep an AEC a language department's
--   with a language professor as its coordinator.

DROP POLICY courses_department_edit ON courses;

CREATE POLICY courses_department_insert ON courses
    FOR INSERT
    WITH CHECK (actor_reads_department_of(offering_department));

-- Nothing asks it now.
DROP FUNCTION course_exists(bigint);
