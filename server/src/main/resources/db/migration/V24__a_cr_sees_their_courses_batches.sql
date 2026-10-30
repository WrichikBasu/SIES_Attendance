-- A class representative sees the batches of the courses they represent, and
-- no others.
--
-- Until now every signed-in actor read every batch (V11: "no personal data,
-- so everyone signed in reads it"). That stopped being true at V23. A batch
-- is now a course's roll, and a CR of one course reading every batch in the
-- college could see who sits which course for every course there is -- the
-- membership rows were already narrowed to students the CR may see, but the
-- batch names and which batches exist were not.
--
-- For a student account, a batch is readable when:
--
--   it is linked to a course they represent -- actor_represents(), a live
--   appointment in any session, which is exactly what makes
--   course_batch_map's own read policy admit the link; and
--
--   it is the batch of a lecture they may read. A lecture may be held for any
--   batch in the session, linked to its course or not (DaoCourse explains
--   why the picker never insisted), and the CR reads every lecture of the
--   course they represent. A lecture whose batch the device could not read
--   is a row it cannot store -- the jam OutboxDrain's notes describe -- so the
--   batch comes with the lecture. The app's lists still show only the linked
--   ones; this is what keeps the feed whole, not what the screens offer.
--
-- Everybody else reads every batch, as before: staff and professors are not
-- narrowed by this.
--
-- And a membership row goes with its batch. batch_student_map's read policies
-- are permissive and some of them -- batch_student_map_own above all, a
-- student's own memberships -- would admit a row for a batch the student may
-- not read. A restrictive policy is the only way to narrow a table whose
-- permissive policies say yes: it has to hold as well as one of them.

CREATE FUNCTION actor_may_see_batch(for_batch bigint) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN current_account_id() IS NOT NULL
       AND (current_student_id() IS NULL
            OR EXISTS (
                SELECT 1 FROM course_batch_map cb
                WHERE cb.batch_id = for_batch
                  AND actor_represents(cb.course_id))
            OR EXISTS (
                SELECT 1 FROM lectures l
                WHERE l.batch_id = for_batch
                  AND actor_is_cr_of(l.course_id,
                                     session_year_of(l.start_at, l.start_zone))));

COMMENT ON FUNCTION actor_may_see_batch(bigint) IS
    'Whether the actor may read a batch. Everyone signed in who is not a student; a student only for a batch linked to a course they represent, or the batch of a lecture they may read as its CR.';

DROP POLICY batch_desc_read ON batch_desc;

CREATE POLICY batch_desc_read ON batch_desc
    FOR SELECT
    USING (actor_may_see_batch(batch_id));

CREATE POLICY batch_student_map_batch_visible ON batch_student_map
    AS RESTRICTIVE
    FOR SELECT
    USING (actor_may_see_batch(batch_id));

-- And a correction to V22, which the read sweep in RowLevelSecurityTest found.
-- V22 wrote the professors' write rule on course_batch_map as FOR ALL, and FOR
-- ALL is SELECT as well: every professor could read every link, including
-- links to courses they cannot read -- a row a device cannot store, pointing
-- at a course it was never sent. Reading a link is course_batch_map_read's
-- alone (whoever may read the course), so the write rule is split into the
-- three verbs it was meant to be. batch_desc's write rule is FOR ALL too and
-- harmless: everyone it admits already reads every batch.
DROP POLICY course_batch_map_write ON course_batch_map;

CREATE POLICY course_batch_map_insert ON course_batch_map
    FOR INSERT
    WITH CHECK (current_prof_id() IS NOT NULL);

CREATE POLICY course_batch_map_update ON course_batch_map
    FOR UPDATE
    USING      (current_prof_id() IS NOT NULL)
    WITH CHECK (current_prof_id() IS NOT NULL);

CREATE POLICY course_batch_map_delete ON course_batch_map
    FOR DELETE
    USING (current_prof_id() IS NOT NULL);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_app') THEN
        GRANT EXECUTE ON FUNCTION actor_may_see_batch(bigint) TO sies_app;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_auditor') THEN
        GRANT EXECUTE ON FUNCTION actor_may_see_batch(bigint) TO sies_auditor;
    END IF;
END $$;
