-- A batch belongs to a department.
--
-- Its members still come from anywhere: an open elective's batch is the
-- students across the college who chose it. What the department says is
-- whose batch it is:
--
--   batch_desc         made, renamed and removed by the department's
--                      professors, its head, or an administrator. V22 let
--                      any professor keep any batch; now a CS professor
--                      keeps CS's and reads everybody else's.
--   course_batch_map   a batch is linked only to its own department's
--                      courses, by whoever keeps the batch.
--   batch_student_map  who is in it, written by whoever keeps the batch,
--                      for any student they can read. The office and a
--                      head keep their V11 and V20 rules, which removing a
--                      student from the roll needs.
--
-- The column is nullable because the batches already here have no
-- department, and not every one of them can be given one honestly.
-- batch_department_guess() decides it from what the batch is used for: the
-- department of the courses it is linked to or was taught under, when that
-- is one department; with no course at all, its members' major, when that
-- is one department. Anything else is left NULL for an administrator to
-- settle in the console (BatchesController), and nobody but an
-- administrator may change a batch while it is NULL.
--
-- The phone's v12 to v13 migration (SchemaMigrations.v12ToV13) works out the
-- same guess on its own copy, for a phone with no server and for a backup
-- an older phone wrote. It then pulls the server's answer, which wins.
--
-- Batch names stay unique per session across the college (V2). Making them
-- unique per department would change how a backup's batches are matched.

ALTER TABLE batch_desc ADD COLUMN department department;

COMMENT ON COLUMN batch_desc.department IS
    'The department whose batch this is: its professors and head keep it, and it is linked only to that department''s courses. NULL for a batch from before V31 that could not be placed; an administrator sets it.';


-- ── The guess ────────────────────────────────────────────────────────

CREATE FUNCTION batch_department_guess(for_batch bigint) RETURNS department
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    AS $$
        WITH taught AS (
            SELECT c.offering_department AS dept
            FROM course_batch_map cb
            JOIN courses c ON c.course_id = cb.course_id
            WHERE cb.batch_id = for_batch
            UNION
            SELECT c.offering_department
            FROM lectures l
            JOIN courses c ON c.course_id = l.course_id
            WHERE l.batch_id = for_batch
        ), majors AS (
            SELECT DISTINCT y.major_dept AS dept
            FROM batch_student_map m
            JOIN student_year y ON y.student_id    = m.student_id
                               AND y.academic_year = m.academic_year
            WHERE m.batch_id = for_batch
        )
        SELECT CASE
            WHEN (SELECT count(*) FROM taught) = 1 THEN (SELECT min(dept) FROM taught)
            WHEN EXISTS (SELECT 1 FROM taught)    THEN NULL
            WHEN (SELECT count(*) FROM majors) = 1 THEN (SELECT min(dept) FROM majors)
        END
    $$;

COMMENT ON FUNCTION batch_department_guess(bigint) IS
    'The department a batch from before V31 most likely belongs to: its courses'' department when that is one, otherwise its members'' major when that is one, otherwise NULL.';

UPDATE batch_desc
SET department = batch_department_guess(batch_id)
WHERE department IS NULL
  AND batch_department_guess(batch_id) IS NOT NULL;


-- ── Who keeps a batch ────────────────────────────────────────────────

CREATE FUNCTION actor_may_keep_batches_of(dept department) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN actor_has_role('ADMIN')
        OR (dept IS NOT NULL
            AND (dept = actor_prof_department() OR actor_heads(dept)));

COMMENT ON FUNCTION actor_may_keep_batches_of(department) IS
    'Whether the actor may make, change or remove a batch of this department: its professors, its head, or an administrator. Only an administrator, for NULL.';

-- SECURITY DEFINER because the policies on batch_student_map and
-- course_batch_map ask it, and batch_desc's own read policy is not theirs.
CREATE FUNCTION actor_may_keep_batch(for_batch bigint) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN actor_has_role('ADMIN')
        OR actor_may_keep_batches_of(
            (SELECT b.department FROM batch_desc b WHERE b.batch_id = for_batch));

COMMENT ON FUNCTION actor_may_keep_batch(bigint) IS
    'actor_may_keep_batches_of() for that batch''s department.';

DROP POLICY batch_desc_write ON batch_desc;

CREATE POLICY batch_desc_insert ON batch_desc
    FOR INSERT
    WITH CHECK (actor_may_keep_batches_of(department));

CREATE POLICY batch_desc_update ON batch_desc
    FOR UPDATE
    USING      (actor_may_keep_batches_of(department))
    WITH CHECK (actor_may_keep_batches_of(department));

CREATE POLICY batch_desc_delete ON batch_desc
    FOR DELETE
    USING (actor_may_keep_batches_of(department));

DROP POLICY course_batch_map_insert ON course_batch_map;
DROP POLICY course_batch_map_update ON course_batch_map;
DROP POLICY course_batch_map_delete ON course_batch_map;

CREATE POLICY course_batch_map_insert ON course_batch_map
    FOR INSERT
    WITH CHECK (actor_may_keep_batch(batch_id));

CREATE POLICY course_batch_map_update ON course_batch_map
    FOR UPDATE
    USING      (actor_may_keep_batch(batch_id))
    WITH CHECK (actor_may_keep_batch(batch_id));

CREATE POLICY course_batch_map_delete ON course_batch_map
    FOR DELETE
    USING (actor_may_keep_batch(batch_id));

-- Membership: the batch's keeper, for a student they may read at all --
-- reached through a course, or on their department's roll as a major, minor
-- or open elective (V27, V30). The office keeps the roll and may take anyone
-- out of any batch, as before.
DROP POLICY batch_student_map_write ON batch_student_map;

CREATE POLICY batch_student_map_write ON batch_student_map
    FOR ALL
    USING      (actor_may_manage_roster() OR actor_may_keep_batch(batch_id))
    WITH CHECK (actor_may_manage_roster()
             OR (actor_may_keep_batch(batch_id)
                 AND (actor_may_see_student(student_id)
                      OR actor_prof_teaches_department_of(student_id))));


-- ── A batch is linked to its own department's courses ────────────────
--
-- A trigger rather than a policy, so it holds for everyone: an
-- administrator and a backup import included. A batch with no department
-- yet is exempt, since there is nothing to compare; it gets one only
-- through the trigger below.

CREATE FUNCTION course_batch_map_same_department() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = pg_catalog, public AS $fn$
DECLARE
    batch_dept  department;
    course_dept department;
BEGIN
    SELECT b.department INTO batch_dept FROM batch_desc b WHERE b.batch_id = NEW.batch_id;
    SELECT c.offering_department INTO course_dept FROM courses c
    WHERE c.course_id = NEW.course_id;

    IF batch_dept IS NOT NULL AND course_dept IS DISTINCT FROM batch_dept THEN
        RAISE EXCEPTION 'A % batch can only be linked to a % course, not a % one',
            batch_dept, batch_dept, course_dept
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END $fn$;

CREATE TRIGGER course_batch_map_same_department
    BEFORE INSERT OR UPDATE ON course_batch_map
    FOR EACH ROW EXECUTE FUNCTION course_batch_map_same_department();

-- And a batch cannot move to a department while it is linked to another's
-- courses. The console unlinks those first, saying which, when an
-- administrator places a batch.
CREATE FUNCTION batch_desc_department_fits_links() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = pg_catalog, public AS $fn$
BEGIN
    IF NEW.department IS NOT NULL AND EXISTS (
            SELECT 1
            FROM course_batch_map cb
            JOIN courses c ON c.course_id = cb.course_id
            WHERE cb.batch_id = NEW.batch_id
              AND c.offering_department <> NEW.department) THEN
        RAISE EXCEPTION 'Batch % is linked to courses outside %; unlink them first',
            NEW.batch_name, NEW.department
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END $fn$;

CREATE TRIGGER batch_desc_department_fits_links
    BEFORE INSERT OR UPDATE OF department ON batch_desc
    FOR EACH ROW EXECUTE FUNCTION batch_desc_department_fits_links();


-- ── A phone's backup is now at version 13 ────────────────────────────
--
-- The device schema gained batch_desc.department (SchemaMigrations.v12ToV13),
-- and the import record refuses any version outside the range the shared
-- migrations cover, so the range moves with them, as V26 moved it to 12.

ALTER TABLE import_run
    DROP CONSTRAINT import_run_schema_range;

ALTER TABLE import_run
    ADD CONSTRAINT import_run_schema_range
        CHECK (backup_schema BETWEEN 1 AND 13);

COMMENT ON COLUMN import_run.backup_schema IS
    'The Room schema version the backup was written at. 1..13; the shared migrations bring anything in that range up to the current one before a row is read.';


DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_app') THEN
        GRANT EXECUTE ON FUNCTION batch_department_guess(bigint) TO sies_app;
        GRANT EXECUTE ON FUNCTION actor_may_keep_batches_of(department) TO sies_app;
        GRANT EXECUTE ON FUNCTION actor_may_keep_batch(bigint) TO sies_app;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_auditor') THEN
        GRANT EXECUTE ON FUNCTION batch_department_guess(bigint) TO sies_auditor;
        GRANT EXECUTE ON FUNCTION actor_may_keep_batches_of(department) TO sies_auditor;
        GRANT EXECUTE ON FUNCTION actor_may_keep_batch(bigint) TO sies_auditor;
    END IF;
END $$;
