-- A professor makes and keeps batches of their own department and no other.
--
-- V31 let a professor keep the batches of their own department and of any
-- department they head. A head is a professor of one department; heading
-- another is an appointment over its roll and its courses, not a reason to
-- make its batches. So a batch is kept by the professors of its department
-- and by an administrator, and the phone no longer asks a professor which
-- department a batch is for: it is theirs.
--
-- Reading is V32's and unchanged: a head still reads the batches of the
-- departments they head.

CREATE OR REPLACE FUNCTION actor_may_keep_batches_of(dept department) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN actor_has_role('ADMIN')
        OR (dept IS NOT NULL AND dept = actor_prof_department());

COMMENT ON FUNCTION actor_may_keep_batches_of(department) IS
    'Whether the actor may make, change or remove a batch of this department: its own professors, or an administrator. Only an administrator, for NULL.';
