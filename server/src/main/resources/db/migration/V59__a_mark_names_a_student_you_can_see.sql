-- ════════════════════════════════════════════════════════════════════
-- V59 — A mark may only name a student you can already see
-- ════════════════════════════════════════════════════════════════════
--
-- Writing a mark was a way of granting yourself a read.
--
-- V21 widened students so that whoever may read a mark may read the
-- student it names: a register that holds somebody the roll no longer
-- does must still reach a phone, or the device refuses a mark for
-- nobody and the page carrying it is retried for ever. That is still
-- right, and it stays.
--
-- What was missing is the other half. The attendance policies in V6 ask
-- only whether the actor may mark that lecture -- actor_instructs,
-- actor_is_hod_of, actor_is_cr_of -- and never ask anything about the
-- student the row names. So any of them could insert a mark naming any
-- student_id at all, and students_marked then handed back that
-- student's name, email and APAAR ID. A class representative, whose
-- whole authority is one course's register, could walk the key space
-- (the keys are minted in sequence, so neighbours are easy to guess)
-- and read the college's roll a row at a time, from a phone. A
-- professor could do the same for another department's students. The
-- audit log recorded it, which is how it would eventually be found, and
-- nothing refused it.
--
-- The rule now: a mark may only name a student the actor can already
-- see. Then the widening in V21 grants nothing, because the student was
-- readable before the mark existed, and a register still carries
-- whoever is in it.
--
--   * Asked of students itself, under the actor's own policies, rather
--     than restated here. Every rule about who may see a student is in
--     those policies -- students_own, students_reachable (the rolls of
--     the courses the actor reaches), students_marked, students_readers
--     (the office, the auditor and an administrator see all of them) --
--     and a second copy of that list in this trigger is a copy that
--     would drift. The database is asked the question it already knows
--     the answer to.
--
--   * SECURITY INVOKER, which is the default and is the point: the
--     question is what the caller can see, so the lookup has to run as
--     the caller. Every other helper a policy calls here is SECURITY
--     DEFINER for the opposite reason -- it asks something the caller
--     must not be able to filter. That students is FORCEd (V11) would
--     today make a definer lookup answer the same, and that is not a
--     property to write a rule on top of. A superuser bypasses the
--     policies and so passes the check trivially; the tests for this
--     rule therefore connect as a non-superuser, as the rest of the
--     policy tests do.
--
--   * BEFORE INSERT OR UPDATE, so a mark moved onto another student by
--     an UPDATE is caught as well as one inserted. A replayed push --
--     INSERT ... ON CONFLICT DO UPDATE, which the sync path uses for
--     every row -- names a student who is already marked, so the mark
--     it is replaying makes them visible and the replay passes. That
--     matters: a phone whose roll has changed under it must still be
--     able to send the register it already took.
--
--   * Reads and deletes are untouched. A mark that is already there
--     stays readable by everyone who could read it before, and
--     clearing a register (the attendance importer replacing a
--     lecture's marks) is a DELETE and does not pass through here.
--
-- What this does not do is decide who is on a roll. A professor may
-- still mark a student of their own department who does not take the
-- course -- they can see that student either way, so nothing is
-- disclosed by it -- and the register export, which lists one row per
-- student per course they are on the roll for, leaves such a mark out.
-- Narrowing the write to the roll itself is a separate rule with its
-- own consequences for old attendance sheets and backup imports, and it
-- is not what this one is for.

CREATE FUNCTION mark_names_a_visible_student() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, public AS $fn$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM students s WHERE s.student_id = NEW.student_id) THEN
        RAISE EXCEPTION
            'A mark may only name a student you can see: that one is not on this course''s roll'
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END $fn$;

COMMENT ON FUNCTION mark_names_a_visible_student() IS
    'Refuses an attendance row naming a student the actor cannot read, asked of students under the actor''s own policies (V59). What stops a write widening a read through students_marked (V21).';

CREATE TRIGGER attendance_names_a_visible_student
    BEFORE INSERT OR UPDATE ON attendance
    FOR EACH ROW EXECUTE FUNCTION mark_names_a_visible_student();

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_app') THEN
        GRANT EXECUTE ON FUNCTION mark_names_a_visible_student() TO sies_app;
    END IF;
END $$;
