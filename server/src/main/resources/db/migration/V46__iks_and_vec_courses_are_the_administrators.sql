-- ════════════════════════════════════════════════════════════════════
-- V46 — IKS and VEC courses are an administrator's
-- ════════════════════════════════════════════════════════════════════
--
-- An Indian Knowledge System course and a value education course are
-- taken by a whole year and stream, across departments, so no department
-- owns one. Only an administrator creates, changes or removes one. Before
-- this, courses_write (V11) let anybody in it do so: the office, a head of
-- the offering department, and the course's own coordinator and
-- instructors.
--
-- Restrictive policies, so they narrow courses_write rather than widen
-- it: an administrator still needs courses_write as well, which they
-- have. One per command rather than FOR ALL, because a FOR ALL policy's
-- USING applies to SELECT too, and everybody who reads these courses
-- today still does.
--
-- UPDATE is held on both sides: a course cannot be changed into one of
-- these types (WITH CHECK, the new row) or out of one (USING, the old
-- row).
--
-- Row-level security, rather than a trigger, for two reasons. The
-- referential actions do not go through it, so an office renumbering a
-- professor still carries their IKS course along (ON UPDATE CASCADE).
-- And the owner is not subject to it, so the command-line backup import
-- and the migrations stay as they were.
--
-- What is untouched: who teaches the course, its batches, its
-- representatives and its attendance. Those stay with the people who
-- teach it.
--
-- CourseType.keptByAdministrators() is the same list, for the app.

CREATE FUNCTION course_type_is_administrators(t course_type) RETURNS boolean
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    RETURN t IN ('IKS', 'VEC');

COMMENT ON FUNCTION course_type_is_administrators(course_type) IS
    'Whether courses of that type are created and changed by an administrator alone (V46).';

CREATE POLICY courses_administrators_insert ON courses
    AS RESTRICTIVE FOR INSERT
    WITH CHECK (NOT course_type_is_administrators(course_type)
                OR actor_has_role('ADMIN'));

CREATE POLICY courses_administrators_update ON courses
    AS RESTRICTIVE FOR UPDATE
    USING      (NOT course_type_is_administrators(course_type)
                OR actor_has_role('ADMIN'))
    WITH CHECK (NOT course_type_is_administrators(course_type)
                OR actor_has_role('ADMIN'));

CREATE POLICY courses_administrators_delete ON courses
    AS RESTRICTIVE FOR DELETE
    USING (NOT course_type_is_administrators(course_type)
           OR actor_has_role('ADMIN'));
