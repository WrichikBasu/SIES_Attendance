-- A course's coordinator is always one of its instructors.
--
-- The phone has kept this since course_instructor arrived (DaoCourse: the
-- coordinator is written into course_instructor with the course, and every
-- edit folds them back in), and everything that asks who teaches a course
-- reads course_instructor alone: actor_instructs(), and through it who may
-- appoint a representative, read the roll and mark attendance. But the two
-- rows reach the server as two intents, and a course whose coordinator row
-- never arrived -- refused once, and dropped by the phone as refused for
-- good -- was a course its own coordinator did not teach. A head who made a
-- course for one of their professors left that professor unable to open its
-- roll on the console.
--
-- So the server keeps the rule itself: a course inserted, or given a new
-- coordinator, files the coordinator as an instructor. The phone's own
-- intent for the same row then finds it there and changes nothing. A former
-- coordinator stays an instructor, as on the phone: removing one is an edit
-- of the instructor list, which states the whole of it.
--
-- SECURITY DEFINER because the row follows from the course and from nothing
-- else: whoever may write the course may name its coordinator, and the
-- course_instructor row is that same fact. The audit trigger on
-- course_instructor records it against whoever wrote the course.

CREATE FUNCTION courses_file_coordinator() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = pg_catalog, public AS $fn$
BEGIN
    INSERT INTO course_instructor (course_id, prof_id)
    VALUES (NEW.course_id, NEW.prof_id)
    ON CONFLICT DO NOTHING;

    RETURN NULL;
END $fn$;

CREATE TRIGGER courses_file_coordinator
    AFTER INSERT OR UPDATE OF prof_id ON courses
    FOR EACH ROW EXECUTE FUNCTION courses_file_coordinator();

-- The courses already missing theirs.
INSERT INTO course_instructor (course_id, prof_id)
SELECT c.course_id, c.prof_id
FROM courses c
WHERE NOT EXISTS (
    SELECT 1 FROM course_instructor ci
    WHERE ci.course_id = c.course_id AND ci.prof_id = c.prof_id);
