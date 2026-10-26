-- Every course's coordinator is one of its instructors, and stays one.
--
-- V37 filed the coordinator as an instructor whenever a course was written, and backfilled the
-- courses missing theirs. Within a day, eight of nine courses had lost the row again: each
-- coordinator change from a phone arrived as the course's update, which V37's trigger answered
-- with the coordinator's row, followed in the same push by a DELETE of that very row. The
-- phone's own course save writes the coordinator back into course_instructor, so a phone that
-- sends the deletion has a local copy that disagrees with itself. Whatever its cause, it should
-- not be able to leave a course its own coordinator does not teach: actor_instructs() reads
-- course_instructor alone, so that coordinator could no longer open the roll, appoint a
-- representative or mark attendance on their own course.
--
-- So the coordinator's row cannot be deleted while they coordinate. A deletion of it is
-- skipped, and a push carrying one is refused with SyncWriter's own sentence for a row
-- that is still referred to, which tells the phone it is out of step instead of quietly agreeing.
-- Every other instructor is removed as before, and so is a former coordinator, once the
-- course names somebody else: a push applies its upserts before its deletes, so the course's
-- new coordinator is in place before the old one's row is asked to go.
--
-- A course being deleted takes its rows with it: the cascade runs after the course row is
-- gone, so there is no coordinator left to keep.
--
-- SECURITY DEFINER for the same reason as V37: the rule follows from the course, and whoever
-- may delete the instructor row must not be able to get past it by not being able to read
-- the course.

CREATE FUNCTION course_instructor_keep_coordinator() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = pg_catalog, public AS $fn$
BEGIN
    IF EXISTS (SELECT 1 FROM courses c
               WHERE c.course_id = OLD.course_id AND c.prof_id = OLD.prof_id) THEN
        RETURN NULL;
    END IF;

    RETURN OLD;
END $fn$;

CREATE TRIGGER course_instructor_keep_coordinator
    BEFORE DELETE ON course_instructor
    FOR EACH ROW EXECUTE FUNCTION course_instructor_keep_coordinator();

-- The courses missing theirs again.
INSERT INTO course_instructor (course_id, prof_id)
SELECT c.course_id, c.prof_id
FROM courses c
ON CONFLICT DO NOTHING;
