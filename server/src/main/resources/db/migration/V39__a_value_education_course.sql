-- ════════════════════════════════════════════════════════════════════
-- V39 — A value education course
-- ════════════════════════════════════════════════════════════════════
--
-- VEC joins AEC and IKS: taken by a whole year and stream, whatever the
-- department, so its offering department records only who teaches it.
--
-- Appended, as V1's contract on enums requires; CourseType gains the same
-- constant last. On its own, because PostgreSQL will not let a value be
-- used in the transaction that added it: V40 puts it in the roll.

ALTER TYPE course_type ADD VALUE 'VEC';
