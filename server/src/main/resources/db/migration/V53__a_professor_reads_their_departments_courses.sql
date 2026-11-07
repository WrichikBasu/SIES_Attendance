-- ════════════════════════════════════════════════════════════════════
-- V53 — A professor reads every course their department offers
-- ════════════════════════════════════════════════════════════════════
--
-- Until now a professor read the courses they coordinate or teach, and
-- the head read the whole department's. Asked for: every professor reads
-- every course their department offers, with its lectures and their
-- registers, whether or not they teach it. It is V18's reasoning about
-- the audit trail applied to the courses themselves: a department's
-- teaching is collegial, and the register of a course a colleague is
-- covering is a professor's business in the way another department's is
-- not.
--
-- Read, and only read. Nothing here touches a write policy: changing the
-- course, taking a register, correcting a mark or deleting a lecture is
-- still for its instructors, its coordinator, the head, the office and
-- an administrator. /me now lists the courses an account reads and may
-- not change (MeController.readsOnly), so the phone shows them as such
-- rather than offering edits the server will refuse.
--
-- "Their department" is the professor's own (actor_prof_department()),
-- compared with the course's offering department. Not year-scoped: a
-- professor belongs to a department, not to a department-in-a-session,
-- so they read its past sessions' courses too.
--
-- Everything that hangs off a course follows it, or the phone could not
-- store what it is sent:
--
--   courses, course_instructor, course_batch_map
--                 courses_department_read; the other two already ask
--                 actor_may_read_course().
--   lectures, attendance
--                 lectures_department_read, attendance_department_read.
--   students, student_year
--                 an open elective of this department is taken by other
--                 departments' students. They are reached through the
--                 course's roll (actor_reaches_enrolment) and through
--                 their marks (actor_reads_a_mark_of), as for a head.
--   batch_desc    a lecture names its batch, which may belong to another
--                 department (V52); actor_may_see_batch_of reaches it.
--
-- exportable_courses() is widened to agree with actor_may_read_course(),
-- as V11 requires of the two.
--
-- The access fingerprint (V51) changes for everybody, once, so every
-- phone downloads everything again and receives the courses, lectures
-- and marks that were already there before this rule. The pull is
-- incremental, and rows that have not changed would otherwise never be
-- sent.

CREATE FUNCTION actor_reads_department_of(dept department) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN dept IS NOT NULL AND dept = actor_prof_department();

COMMENT ON FUNCTION actor_reads_department_of(department) IS
    'Whether the actor is a professor of that department, and so reads the courses it offers, their lectures and their registers (V53). Read only.';


-- ── The course ───────────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION actor_may_read_course(for_course bigint) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN actor_has_role('ADMIN')
        OR actor_has_role('OFFICE')
        OR actor_has_role('AUDITOR')
        OR actor_instructs(for_course)
        OR actor_represents(for_course)
        OR EXISTS (
            SELECT 1 FROM courses c
            WHERE c.course_id = for_course
              AND (c.prof_id = current_prof_id()
                OR actor_heads(c.offering_department)
                OR actor_reads_department_of(c.offering_department)));

COMMENT ON FUNCTION actor_may_read_course(bigint) IS
    'Whether the current actor may see a course at all: staff, its instructors and coordinator, its CRs, the head and (V53) every professor of its offering department. exportable_courses() asked one course at a time; the two have to agree or an export narrows to a course the policies then hide.';

-- Beside V11's courses_read, which is written in the row's own columns
-- for the reason given there; so is this.
CREATE POLICY courses_department_read ON courses
    FOR SELECT
    USING (actor_reads_department_of(offering_department));

CREATE OR REPLACE FUNCTION exportable_courses(session_year integer) RETURNS SETOF bigint
    LANGUAGE sql STABLE
    BEGIN ATOMIC
        SELECT c.course_id
        FROM courses c
        WHERE actor_has_role('ADMIN')
           OR actor_has_role('OFFICE')
           OR actor_has_role('AUDITOR')
           OR actor_instructs(c.course_id)
           OR actor_is_hod_of(c.offering_department, session_year)
           OR actor_is_cr_of(c.course_id, session_year)
           OR actor_reads_department_of(c.offering_department);
    END;

COMMENT ON FUNCTION exportable_courses(integer) IS
    'The courses the current actor may export in a session: everything for an admin, office or auditor; a professor''s own and (V53) their department''s; a head''s department; a CR''s granted courses. The export endpoint intersects the requested course list with this.';


-- ── Its lectures and registers ───────────────────────────────────────

CREATE POLICY lectures_department_read ON lectures
    FOR SELECT
    USING (EXISTS (
        SELECT 1 FROM courses c
        WHERE c.course_id = lectures.course_id
          AND actor_reads_department_of(c.offering_department)));

CREATE POLICY attendance_department_read ON attendance
    FOR SELECT
    USING (EXISTS (
        SELECT 1 FROM lectures l
        JOIN courses c ON c.course_id = l.course_id
        WHERE l.lecture_id = attendance.lecture_id
          AND actor_reads_department_of(c.offering_department)));


-- ── The students on them ─────────────────────────────────────────────

CREATE OR REPLACE FUNCTION actor_reads_a_mark_of(for_student bigint) RETURNS boolean
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
                              session_year_of(l.start_at, l.start_zone))
            OR actor_reads_department_of(c.offering_department)));

COMMENT ON FUNCTION actor_reads_a_mark_of(bigint) IS
    'Whether the actor may read any attendance row for this student: attendance_instructor, attendance_hod, attendance_delegate_select and (V53) attendance_department_read asked about one student. What keeps a mark from reaching a device without the student it is for.';

CREATE OR REPLACE FUNCTION actor_reaches_enrolment(
        e_student      bigint,
        e_year         study_year,
        e_stream       degree_stream,
        e_major        department,
        e_minor        department,
        e_oe           department,
        session_year   integer) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN actor_is_hod_of(e_major, session_year)
        OR EXISTS (
            SELECT 1 FROM courses c
            WHERE (actor_instructs(c.course_id)
                OR actor_is_cr_of(c.course_id, session_year)
                OR actor_is_hod_of(c.offering_department, session_year)
                OR actor_reads_department_of(c.offering_department))
              AND course_admits(c.course_id, session_year, e_student, e_year, e_stream,
                                e_major, e_minor, e_oe));

COMMENT ON FUNCTION actor_reaches_enrolment(bigint, study_year, degree_stream, department, department, department, integer) IS
    'Whether the current actor reaches a student_year row: the head of its major department in that session, or anyone who instructs, represents or heads the offering department of a course the row is on the roll of, or (V53) is a professor of that department, as course_admits() decides it.';


-- ── The batches its lectures name ────────────────────────────────────
--
-- As V34 left it, with the department's courses beside the instructor's
-- and the head's in the lecture clause. The link clause already asks
-- actor_may_read_course().

CREATE OR REPLACE FUNCTION actor_may_see_batch_of(for_batch bigint, batch_dept department)
    RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN current_account_id() IS NOT NULL
       AND CASE
           WHEN current_student_id() IS NOT NULL THEN
               EXISTS (SELECT 1 FROM course_batch_map cb
                       WHERE cb.batch_id = for_batch
                         AND actor_represents(cb.course_id))
               OR EXISTS (SELECT 1 FROM lectures l
                          WHERE l.batch_id = for_batch
                            AND actor_is_cr_of(l.course_id,
                                               session_year_of(l.start_at, l.start_zone)))
           WHEN current_prof_id() IS NOT NULL
                AND NOT (actor_has_role('ADMIN') OR actor_has_role('OFFICE')
                         OR actor_has_role('AUDITOR')) THEN
               batch_dept = actor_prof_department()
               OR actor_heads(batch_dept)
               OR EXISTS (SELECT 1 FROM course_batch_map cb
                          WHERE cb.batch_id = for_batch
                            AND actor_may_read_course(cb.course_id))
               OR EXISTS (SELECT 1 FROM lectures l
                          JOIN courses c ON c.course_id = l.course_id
                          WHERE l.batch_id = for_batch
                            AND (actor_instructs(l.course_id)
                                 OR actor_is_hod_of(c.offering_department,
                                                    session_year_of(l.start_at, l.start_zone))
                                 OR actor_reads_department_of(c.offering_department)))
           ELSE true
       END;


-- ── The phone ────────────────────────────────────────────────────────
--
-- As V52 left it, with one constant added. Every digest changes once, so
-- every phone downloads everything again and is sent what this rule
-- opened to it. The constant changes nothing after that.

CREATE OR REPLACE FUNCTION actor_access_fingerprint() RETURNS text
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN md5(concat_ws('|',
        'V53',

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
    'A digest of what decides the current actor''s own access: the rules'' version (V53), account, roles, department, designation, courses coordinated and taught, the batches linked to those courses (V52), live CR grants. Sent with every pull; a phone that sees it change downloads everything again and removes what it may no longer read (V51).';


DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_app') THEN
        GRANT EXECUTE ON FUNCTION actor_reads_department_of(department) TO sies_app;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_auditor') THEN
        GRANT EXECUTE ON FUNCTION actor_reads_department_of(department) TO sies_auditor;
    END IF;
END $$;
