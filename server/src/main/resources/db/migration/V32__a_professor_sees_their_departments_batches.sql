-- A professor reads their own department's batches, not every batch.
--
-- V24 left professors reading every batch in the college. Since V31 a batch
-- belongs to a department, and another department's batches -- who is in
-- which practical, which open elective -- are not a professor's business.
--
-- For a professor, a batch is now readable when:
--
--   it is a batch of their own department, or of one they head;
--
--   it is linked to a course they may read (actor_may_read_course(), which is
--   exactly course_batch_map's read rule). A professor who teaches another
--   department's course takes its attendance from that department's batches,
--   and a link the device reads to a batch it cannot is a row it cannot
--   store; or
--
--   it is the batch of a lecture they may read: one of a course they teach,
--   or of their department's in a session they head it. The same reason V24
--   gave for a class representative -- the batch comes with the lecture.
--
-- Unchanged: a student by V24's rule, and every staff account -- the office,
-- the auditor and an administrator -- reads every batch. Membership rows
-- follow their batch already, through V24's restrictive
-- batch_student_map_batch_visible.

CREATE OR REPLACE FUNCTION actor_may_see_batch(for_batch bigint) RETURNS boolean
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
                EXISTS (
                    SELECT 1 FROM batch_desc b
                    WHERE b.batch_id = for_batch
                      AND (b.department = actor_prof_department()
                           OR actor_heads(b.department)))
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

COMMENT ON FUNCTION actor_may_see_batch(bigint) IS
    'Whether the actor may read a batch. Staff, every batch. A professor, their own department''s and the ones they head, the batches linked to a course they may read, and the batch of a lecture they may read. A student, a batch linked to a course they represent, or the batch of a lecture they may read as its CR.';
