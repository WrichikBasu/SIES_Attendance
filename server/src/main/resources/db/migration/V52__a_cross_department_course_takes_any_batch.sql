-- ════════════════════════════════════════════════════════════════════
-- V52 — An OE, AEC, IKS or VEC course takes any department's batches
-- ════════════════════════════════════════════════════════════════════
--
-- V31 linked a batch only to its own department's courses, and V41 made
-- a course's roll its batches and nothing else. Together they left no way
-- to give a cross-department course a roll. An Environmental Science open
-- elective is taken by students of Commerce, History and Computer Science,
-- and an AEC, IKS or VEC course by a whole year and stream. Their batches
-- are those departments' batches, and the trigger refused every link.
--
-- So for those four types:
--
--   the link     any department's batch may be linked. Every other type
--                keeps V31's rule.
--
--   who links    whoever may change the course (actor_may_write_course:
--                an administrator, the office, its coordinator, its
--                instructors, the head of its offering department), as
--                well as the batch's own keepers, as before. Linking
--                decides only this course's roll. The batch, its name
--                and its members stay with its department.
--
--   choosing     linkable_batches() lists every batch of a session, by
--                name, department and size and not by member, for any
--                professor or staff account. A professor's phone holds
--                only its own department's batches (V32), so this is the
--                only way it can know what there is to link.
--
--   the phone    a batch linked to a course the actor may read is already
--                one they may read (V32). The link is a row in another
--                table, so the batch itself never reaches the feed. The
--                access fingerprint (V51) now covers the links on the
--                actor's own courses, and the phone downloads everything
--                again when one changes. That also closes the same gap for
--                links a department makes to a course somebody else
--                teaches.
--
-- A course cannot then become a type that does not take other
-- departments' batches, or move to another department, while such a link
-- would break V31. Changing that is unlinking first, as V31 already asks
-- of a batch that changes department.
--
-- CourseType.takesAnyDepartmentsBatches() is the same list, for the app.

CREATE FUNCTION course_takes_any_departments_batches(t course_type) RETURNS boolean
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    RETURN t IN ('OE', 'AEC', 'IKS', 'VEC');

COMMENT ON FUNCTION course_takes_any_departments_batches(course_type) IS
    'Whether a course of that type may be linked to any department''s batches, not only its offering department''s (V52).';


-- ── The link ─────────────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION course_batch_map_same_department() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = pg_catalog, public AS $fn$
DECLARE
    batch_dept  department;
    course_dept department;
    course_kind course_type;
BEGIN
    SELECT b.department INTO batch_dept FROM batch_desc b WHERE b.batch_id = NEW.batch_id;
    SELECT c.offering_department, c.course_type INTO course_dept, course_kind
    FROM courses c WHERE c.course_id = NEW.course_id;

    IF batch_dept IS NOT NULL
       AND course_dept IS DISTINCT FROM batch_dept
       AND NOT coalesce(course_takes_any_departments_batches(course_kind), false) THEN
        RAISE EXCEPTION 'A % batch can only be linked to a % course, not a % one, unless the course is an OE, AEC, IKS or VEC',
            batch_dept, batch_dept, course_dept
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END $fn$;

CREATE OR REPLACE FUNCTION batch_desc_department_fits_links() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = pg_catalog, public AS $fn$
BEGIN
    IF NEW.department IS NOT NULL AND EXISTS (
            SELECT 1
            FROM course_batch_map cb
            JOIN courses c ON c.course_id = cb.course_id
            WHERE cb.batch_id = NEW.batch_id
              AND c.offering_department <> NEW.department
              AND NOT course_takes_any_departments_batches(c.course_type)) THEN
        RAISE EXCEPTION 'Batch % is linked to courses outside %; unlink them first',
            NEW.batch_name, NEW.department
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END $fn$;

-- The course side of the same rule. Asked only when the type or the
-- department actually changes: a push sets every column on every write.
CREATE FUNCTION courses_batches_fit_department() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = pg_catalog, public AS $fn$
BEGIN
    IF (NEW.course_type IS DISTINCT FROM OLD.course_type
        OR NEW.offering_department IS DISTINCT FROM OLD.offering_department)
       AND NOT course_takes_any_departments_batches(NEW.course_type)
       AND EXISTS (
            SELECT 1
            FROM course_batch_map cb
            JOIN batch_desc b ON b.batch_id = cb.batch_id
            WHERE cb.course_id = NEW.course_id
              AND b.department IS NOT NULL
              AND b.department <> NEW.offering_department) THEN
        RAISE EXCEPTION 'Course % is linked to batches of other departments, which only an OE, AEC, IKS or VEC course may be; unlink them first',
            NEW.course_code
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END $fn$;

COMMENT ON FUNCTION courses_batches_fit_department() IS
    'Refuses changing a course''s type or department while it is linked to batches V31 would no longer allow (V52).';

CREATE TRIGGER courses_batches_fit_department
    BEFORE UPDATE OF course_type, offering_department ON courses
    FOR EACH ROW EXECUTE FUNCTION courses_batches_fit_department();


-- ── Who links ────────────────────────────────────────────────────────

CREATE FUNCTION actor_links_any_batch_to(for_course bigint) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN EXISTS (
               SELECT 1 FROM courses c
               WHERE c.course_id = for_course
                 AND course_takes_any_departments_batches(c.course_type))
       AND actor_may_write_course(for_course);

COMMENT ON FUNCTION actor_links_any_batch_to(bigint) IS
    'Whether the actor may link any batch to that course: it is an OE, AEC, IKS or VEC course and they may change it (V52).';

-- Permissive, beside V31's: the batch's keepers still link it as before.
CREATE POLICY course_batch_map_course_keeper_insert ON course_batch_map
    FOR INSERT
    WITH CHECK (actor_links_any_batch_to(course_id));

CREATE POLICY course_batch_map_course_keeper_update ON course_batch_map
    FOR UPDATE
    USING      (actor_links_any_batch_to(course_id))
    WITH CHECK (actor_links_any_batch_to(course_id));

CREATE POLICY course_batch_map_course_keeper_delete ON course_batch_map
    FOR DELETE
    USING (actor_links_any_batch_to(course_id));


-- ── Choosing ─────────────────────────────────────────────────────────

CREATE FUNCTION linkable_batches(session_year integer)
    RETURNS TABLE (batch_id bigint, batch_name text, department department,
                   academic_year integer, members bigint)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    AS $$
        SELECT b.batch_id, b.batch_name, b.department, b.academic_year,
               (SELECT count(*) FROM batch_student_map m
                WHERE m.batch_id = b.batch_id AND m.academic_year = b.academic_year)
        FROM batch_desc b
        WHERE b.academic_year = session_year
          AND (current_prof_id() IS NOT NULL
               OR actor_has_role('ADMIN') OR actor_has_role('OFFICE'))
        ORDER BY b.batch_name
    $$;

COMMENT ON FUNCTION linkable_batches(integer) IS
    'Every batch of a session, by name, department and size, for a professor or staff account choosing batches for an OE, AEC, IKS or VEC course. No members (V52).';


-- ── The phone ────────────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION actor_access_fingerprint() RETURNS text
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN md5(concat_ws('|',
        current_account_id(),
        current_prof_id(),
        current_student_id(),

        (SELECT string_agg(concat_ws(':', ur.role, ur.department, ur.academic_year), ','
                           ORDER BY ur.role, ur.department, ur.academic_year)
         FROM user_role ur
         WHERE ur.account_id = current_account_id()
           AND ur.revoked_at IS NULL),

        (SELECT concat_ws(':', p.department, designation_is_heads(p.designation))
         FROM professors p
         WHERE p.prof_id = current_prof_id()),

        (SELECT string_agg(ci.course_id::text, ',' ORDER BY ci.course_id)
         FROM course_instructor ci
         WHERE ci.prof_id = current_prof_id()),

        (SELECT string_agg(c.course_id::text, ',' ORDER BY c.course_id)
         FROM courses c
         WHERE c.prof_id = current_prof_id()),

        (SELECT string_agg(concat_ws(':', d.course_id, d.academic_year), ','
                           ORDER BY d.course_id, d.academic_year)
         FROM attendance_delegate d
         WHERE d.student_id = current_student_id()
           AND d.revoked_at IS NULL
           AND (d.expires_at IS NULL OR d.expires_at > now())),

        -- V52: the batches linked to the courses the actor coordinates or
        -- teaches, which they read through the link.
        (SELECT string_agg(concat_ws(':', cb.course_id, cb.batch_id), ','
                           ORDER BY cb.course_id, cb.batch_id)
         FROM course_batch_map cb
         WHERE current_prof_id() IS NOT NULL
           AND (EXISTS (SELECT 1 FROM courses c
                        WHERE c.course_id = cb.course_id
                          AND c.prof_id = current_prof_id())
                OR EXISTS (SELECT 1 FROM course_instructor ci
                           WHERE ci.course_id = cb.course_id
                             AND ci.prof_id = current_prof_id())))));

COMMENT ON FUNCTION actor_access_fingerprint() IS
    'A digest of what decides the current actor''s own access: account, roles, department, designation, courses coordinated and taught, the batches linked to those courses (V52), live CR grants. Sent with every pull; a phone that sees it change downloads everything again and removes what it may no longer read (V51).';


DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_app') THEN
        GRANT EXECUTE ON FUNCTION course_takes_any_departments_batches(course_type) TO sies_app;
        GRANT EXECUTE ON FUNCTION actor_links_any_batch_to(bigint) TO sies_app;
        GRANT EXECUTE ON FUNCTION linkable_batches(integer) TO sies_app;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_auditor') THEN
        GRANT EXECUTE ON FUNCTION course_takes_any_departments_batches(course_type) TO sies_auditor;
        GRANT EXECUTE ON FUNCTION actor_links_any_batch_to(bigint) TO sies_auditor;
    END IF;
END $$;
