-- Whoever can read a mark can read the student it is for.
--
-- A class representative's phone could not finish its first sync:
--
--   FOREIGN KEY constraint failed (code 787 SQLITE_CONSTRAINT_FOREIGNKEY)
--
-- attendance_delegate_select lets a CR read every mark on their course's
-- lectures. students_reachable lets them read a student only through an
-- enrolment the course's roll admits -- actor_reaches_enrolment(), which is
-- roll_for_course() inverted. The two disagree whenever a register holds a
-- student the roll does not: somebody whose enrolment has since been removed,
-- or one marked on a lecture before the roll was what it is now. The server
-- then sends the mark and withholds the student it points at, the phone
-- refuses to store a mark for nobody, and the page that carries it is retried
-- for ever. The same holds for an instructor or a head; the CR is only the
-- one a handset happened to find.
--
-- Widened on the students side rather than narrowed on the attendance side.
-- A mark that exists is a fact about a lecture somebody may read, and hiding
-- it would make the register shorter than the room was. The student it names
-- is a name and pronouns, which anyone reading the register sees anyway.
--
-- The rule is every read policy on attendance that is not already a read of
-- every student (the office, the auditor and the administrator see the whole
-- of students through students_readers), asked about one student. SECURITY
-- DEFINER, like every other function a policy calls here: the question is
-- "is there a mark I may read", and it must be answered from the marks
-- themselves rather than through policies that would have to agree first.

CREATE FUNCTION actor_reads_a_mark_of(for_student bigint) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN EXISTS (
        SELECT 1
        FROM attendance a
        JOIN lectures l ON l.lecture_id = a.lecture_id
        JOIN courses  c ON c.course_id  = l.course_id
        WHERE a.student_id = for_student
          AND (actor_instructs(l.course_id)
            OR actor_is_hod_of(c.offering_department,
                               session_year_of(l.start_at, l.start_zone))
            OR actor_is_cr_of(l.course_id,
                              session_year_of(l.start_at, l.start_zone))));

COMMENT ON FUNCTION actor_reads_a_mark_of(bigint) IS
    'Whether the actor may read any attendance row for this student: attendance_instructor, attendance_hod and attendance_delegate_select asked about one student. What keeps a mark from reaching a device without the student it is for.';

CREATE POLICY students_marked ON students
    FOR SELECT
    USING (actor_reads_a_mark_of(student_id));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_app') THEN
        GRANT EXECUTE ON FUNCTION actor_reads_a_mark_of(bigint) TO sies_app;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_auditor') THEN
        GRANT EXECUTE ON FUNCTION actor_reads_a_mark_of(bigint) TO sies_auditor;
    END IF;
END $$;
