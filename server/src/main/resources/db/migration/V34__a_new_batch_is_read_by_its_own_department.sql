-- A batch is read by its own columns, so a professor can push a new one.
--
-- V32 made batch_desc_read ask actor_may_see_batch(batch_id), which looks the
-- batch up to find its department. A push is INSERT ... ON CONFLICT DO
-- UPDATE, and PostgreSQL holds the proposed row of such an insert to the
-- table's SELECT policies as well as its INSERT ones. The row is not in the
-- table yet, so the lookup found nothing, and every batch a professor made on
-- a phone was refused -- and with it each of its members and course links.
--
-- So the read rule is asked of the row's own department, the way V11 asks
-- student_year's rule of that row's columns. actor_may_see_batch(bigint)
-- stays, for batch_student_map's restrictive policy, which is asked about a
-- batch already in the table; it looks the department up and asks the same
-- question.

CREATE FUNCTION actor_may_see_batch_of(for_batch bigint, batch_dept department)
    RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN current_account_id() IS NOT NULL
       AND CASE
           WHEN current_student_id() IS NOT NULL THEN
                EXISTS (
                    SELECT 1 FROM course_batch_map cb
                    WHERE cb.batch_id = for_batch
                      AND actor_represents(cb.course_id))
             OR EXISTS (
                    SELECT 1 FROM lectures l
                    WHERE l.batch_id = for_batch
                      AND actor_is_cr_of(l.course_id,
                                         session_year_of(l.start_at, l.start_zone)))
           WHEN current_prof_id() IS NOT NULL
                AND NOT (actor_has_role('ADMIN') OR actor_has_role('OFFICE')
                         OR actor_has_role('AUDITOR')) THEN
                batch_dept = actor_prof_department()
             OR actor_heads(batch_dept)
             OR EXISTS (
                    SELECT 1 FROM course_batch_map cb
                    WHERE cb.batch_id = for_batch
                      AND actor_may_read_course(cb.course_id))
             OR EXISTS (
                    SELECT 1 FROM lectures l
                    JOIN courses c ON c.course_id = l.course_id
                    WHERE l.batch_id = for_batch
                      AND (actor_instructs(l.course_id)
                           OR actor_is_hod_of(c.offering_department,
                                              session_year_of(l.start_at, l.start_zone))))
           ELSE TRUE
       END;

COMMENT ON FUNCTION actor_may_see_batch_of(bigint, department) IS
    'Whether the actor may read a batch, given its id and department. Staff, every batch. A professor, their own department''s and the ones they head, the batches linked to a course they may read, and the batch of a lecture they may read. A student, a batch linked to a course they represent, or the batch of a lecture they may read as its CR.';

CREATE OR REPLACE FUNCTION actor_may_see_batch(for_batch bigint) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN actor_may_see_batch_of(for_batch,
               (SELECT b.department FROM batch_desc b WHERE b.batch_id = for_batch));

COMMENT ON FUNCTION actor_may_see_batch(bigint) IS
    'actor_may_see_batch_of() for a batch already in the table, its department looked up. For the policies on the tables that point at a batch.';

DROP POLICY batch_desc_read ON batch_desc;

CREATE POLICY batch_desc_read ON batch_desc
    FOR SELECT
    USING (actor_may_see_batch_of(batch_id, department));

-- The same, for a membership. A professor may put any student they can read
-- in their own department's batch (V31), but reading the membership row went
-- through the student alone: reached through a course, which a student of
-- another stream taking this department's open elective is not until the
-- batch is linked -- and the link comes after the members in a push. So the
-- batch's own professors read its members on the terms they may write them.
CREATE POLICY batch_student_map_keeper_read ON batch_student_map
    FOR SELECT
    USING (actor_may_keep_batch(batch_id)
           AND (actor_may_see_student(student_id)
                OR actor_prof_teaches_department_of(student_id)));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_app') THEN
        GRANT EXECUTE ON FUNCTION actor_may_see_batch_of(bigint, department) TO sies_app;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_auditor') THEN
        GRANT EXECUTE ON FUNCTION actor_may_see_batch_of(bigint, department) TO sies_auditor;
    END IF;
END $$;
