-- ════════════════════════════════════════════════════════════════════
-- V45 — A minor may be in the major department
-- ════════════════════════════════════════════════════════════════════
--
-- V2 refused a student whose minor department is their major one. The
-- college allows it: a student may take their minor in the department
-- they major in. So the constraint goes.
--
-- The open elective stays apart from both (student_year_oe_not_major and
-- student_year_oe_not_minor, V2). An open elective is by definition taken
-- outside the student's own subjects.
--
-- Nothing else depends on the two being different. roll_for_course (V5)
-- already admits the major department on the minor branch, and every
-- reachability function tests membership (IN (major, minor, oe)), which a
-- repeated department does not change.

ALTER TABLE student_year
    DROP CONSTRAINT student_year_minor_not_major;
