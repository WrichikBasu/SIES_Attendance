-- ════════════════════════════════════════════════════════════════════
-- V42 — A coordinator keeps the batches that serve their courses alone
-- ════════════════════════════════════════════════════════════════════
--
-- A batch is its department's (V31, V33). A value education course is
-- offered by one department and taught by professors of several, and its
-- coordinator -- courses.prof_id -- may be one of the others. They read its
-- batches (V32) and, until now, could not move a student from B1 to B2.
--
-- So a professor also keeps a batch when they coordinate every course it
-- is linked to, and it is linked to at least one. "Every" and not "any":
-- since V41 a batch's members are the roll of each course it serves, and
-- a batch shared with a course somebody else coordinates is a roll that is
-- not theirs. An instructor who does not coordinate keeps nothing by it;
-- three people moving the same students about is what the coordinator is
-- for.
--
-- What a coordinator may do with such a batch:
--
--   batch_desc         rename it and change its description. Not make one,
--                      remove one, or give it another department: those
--                      stay with the department, whose batch it remains.
--                      The INSERT policy is there for the phone's push,
--                      which is INSERT ... ON CONFLICT DO UPDATE and is
--                      held to INSERT policies as well; it admits only a
--                      batch already linked to their courses, which is
--                      one that already exists.
--   batch_student_map  add a student they may read, or take one out.
--   course_batch_map   nothing. Linking it to a course would decide
--                      another course's roll, and unlinking is the
--                      department's call.
--
-- An administrator and the department's own professors keep the batch as
-- before; these policies are permissive and only add to theirs.

CREATE FUNCTION actor_coordinates_batch(for_batch bigint) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN current_prof_id() IS NOT NULL
       AND EXISTS (
               SELECT 1 FROM course_batch_map cb
               WHERE cb.batch_id = for_batch)
       AND NOT EXISTS (
               SELECT 1
               FROM course_batch_map cb
               JOIN courses c ON c.course_id = cb.course_id
               WHERE cb.batch_id = for_batch
                 AND c.prof_id IS DISTINCT FROM current_prof_id());

COMMENT ON FUNCTION actor_coordinates_batch(bigint) IS
    'Whether the actor coordinates every course a batch is linked to, and it is linked to one: a coordinator renames such a batch and changes its members, and nothing more (V42).';

CREATE POLICY batch_desc_coordinator_insert ON batch_desc
    FOR INSERT
    WITH CHECK (department IS NOT NULL AND actor_coordinates_batch(batch_id));

CREATE POLICY batch_desc_coordinator_update ON batch_desc
    FOR UPDATE
    USING      (department IS NOT NULL AND actor_coordinates_batch(batch_id))
    WITH CHECK (department IS NOT NULL AND actor_coordinates_batch(batch_id));

-- On the terms V31 gives the department's own professors: a student they
-- may read. Split by verb rather than FOR ALL, which would be a read
-- policy too (V24's correction to V22).
CREATE POLICY batch_student_map_coordinator_insert ON batch_student_map
    FOR INSERT
    WITH CHECK (actor_coordinates_batch(batch_id)
                AND (actor_may_see_student(student_id)
                     OR actor_prof_teaches_department_of(student_id)));

CREATE POLICY batch_student_map_coordinator_delete ON batch_student_map
    FOR DELETE
    USING (actor_coordinates_batch(batch_id));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_app') THEN
        GRANT EXECUTE ON FUNCTION actor_coordinates_batch(bigint) TO sies_app;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_auditor') THEN
        GRANT EXECUTE ON FUNCTION actor_coordinates_batch(bigint) TO sies_auditor;
    END IF;
END $$;
