-- ════════════════════════════════════════════════════════════════════
-- V50 — An AEC is coordinated by a language department's professor
-- ════════════════════════════════════════════════════════════════════
--
-- V49 held an AEC's offering department to English, Hindi or Marathi.
-- Its coordinator, courses.prof_id, is held to the same three here: a
-- professor of another department may still teach one as an instructor,
-- but not coordinate it. That also closes the way in that V49 left open,
-- since courses_write (V11) lets any professor make a course they
-- coordinate.
--
-- The professor's department is on another table, so this is two
-- triggers rather than a CHECK: one on the course, and one on the
-- professor, so that moving an AEC's coordinator to another department is
-- refused as well. Both raise check_violation, which SyncWriter reports
-- to the phone with the sentence below and does not retry.
--
-- SECURITY DEFINER, so the rule reads the professor and the courses
-- whatever the writer can see. The list is V49's function, so there is
-- one list.
--
-- Existing courses are not checked: a course whose coordinator breaks
-- the rule is refused only when it is next written. To find them:
--
--   SELECT c.course_id, c.course_code, p.name, p.department
--   FROM courses c JOIN professors p USING (prof_id)
--   WHERE c.course_type = 'AEC'
--     AND NOT course_aec_offered_by_a_language_department('AEC', p.department);

CREATE FUNCTION courses_aec_coordinator() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = pg_catalog, public AS $fn$
DECLARE
    coordinator_department department;
BEGIN
    IF NEW.course_type <> 'AEC' THEN
        RETURN NEW;
    END IF;

    SELECT p.department INTO coordinator_department
    FROM professors p WHERE p.prof_id = NEW.prof_id;

    -- No such professor: the foreign key says so, in its own words.
    IF NOT FOUND THEN
        RETURN NEW;
    END IF;

    IF NOT course_aec_offered_by_a_language_department('AEC', coordinator_department) THEN
        RAISE EXCEPTION 'An AEC course is coordinated by a professor of English, Hindi or Marathi, not of %',
            coordinator_department
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END $fn$;

COMMENT ON FUNCTION courses_aec_coordinator() IS
    'Refuses an AEC course whose coordinator is not a professor of English, Hindi or Marathi (V50).';

CREATE TRIGGER courses_aec_coordinator
    BEFORE INSERT OR UPDATE OF course_type, prof_id ON courses
    FOR EACH ROW EXECUTE FUNCTION courses_aec_coordinator();

CREATE FUNCTION professors_keep_aec_coordinators() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = pg_catalog, public AS $fn$
BEGIN
    IF NOT course_aec_offered_by_a_language_department('AEC', NEW.department)
       AND EXISTS (SELECT 1 FROM courses c
                   WHERE c.prof_id = NEW.prof_id AND c.course_type = 'AEC') THEN
        RAISE EXCEPTION 'This professor coordinates an AEC course, which a professor of English, Hindi or Marathi coordinates; give the course another coordinator first'
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END $fn$;

COMMENT ON FUNCTION professors_keep_aec_coordinators() IS
    'Refuses moving an AEC course''s coordinator out of English, Hindi and Marathi (V50).';

CREATE TRIGGER professors_keep_aec_coordinators
    BEFORE UPDATE OF department ON professors
    FOR EACH ROW
    WHEN (OLD.department IS DISTINCT FROM NEW.department)
    EXECUTE FUNCTION professors_keep_aec_coordinators();
