-- =====================================================================
-- V11 — The roster, reachable. Which closes the sync pair.
--
-- Additive: functions and policies, plus one CREATE OR REPLACE of
-- synced_tables(). No table or column is altered.
--
--
-- WHAT WAS WRONG
--
-- V10 let a device push and pull lectures and attendance and nothing
-- else, because those were the only two tables row-level security
-- policed. Both V6 and V10 stated the consequence rather than papering
-- over it: a new install could not populate itself from the server, and
-- a phone whose professor had created a course could not send a lecture
-- on it -- the foreign key refused a lecture whose course the server had
-- never heard of, which is the right answer to the wrong question.
--
-- The missing piece was never an endpoint. It was this file. Nine tables
-- had no policies, so widening the pair would have meant the service
-- deciding who may rewrite a roster, in Java, which is the thing this
-- design keeps out of Java. What follows is that decision, in the only
-- place where a forgotten WHERE clause cannot get round it.
--
--
-- THE RULE, A LINE PER TABLE
--
--   academic_years     every signed-in actor reads the calendar;
--                      the office writes it
--   professors         every signed-in actor reads the staff list; the
--                      office writes it, and a professor may amend their
--                      own row
--   students           readable when the actor can reach the student --
--                      see below -- or it is their own row; the office
--                      writes
--   student_year       the same reachability, asked of the enrolment's
--                      own columns; the office writes
--   courses            the coordinator, an instructor, the head of the
--                      offering department, a CR of it; and the office
--   course_instructor  whoever may read or write the course
--   batch_desc         every signed-in actor reads a batch name; any
--                      professor writes one
--   batch_student_map  readable when the student is; a professor may
--                      place a student they can reach
--   course_batch_map   whoever may read or write the course
--
-- Writes to the roll are the office and an admin, and that is
-- actor_may_manage_roster() from V8 -- the function that file said these
-- policies should be written in terms of, so that there is one answer to
-- "who may edit the roll" rather than two that have to agree. The roster
-- endpoint calls the same function the policies do, and now it is the
-- policies that decide.
--
--
-- REACHABILITY, WHICH IS WHY THIS WAITED
--
-- V6 left these tables open rather than guess, and named the reason:
-- "which students may this professor see" is not a department
-- comparison. A minor or an open elective puts a student from another
-- department on the roll -- that is what V4 exists for -- so the real
-- question is "is this student on the roll of a course I teach", and the
-- roll is roll_for_course's four-way match on course_type.
--
-- That match is written out once more here, and only once: it takes an
-- enrolment's own columns, so
--
--   * student_year's policy can ask it without the question reading
--     student_year, which is what lets the table be policed without
--     asking about itself; and
--
--   * students and batch_student_map ask it through
--     actor_may_see_student(), which joins the one student's enrolments.
--
-- The duplication of V5's CASE is deliberate and is the one thing in
-- this file to keep an eye on: roll_for_course() returns a set for one
-- course and this is a predicate for one enrolment, so neither can be
-- written in terms of the other without a lateral join per row. The
-- drift test holds the two to the same course_type lists.
--
-- Nothing is materialised. student_year_class_idx is
-- (academic_year, year, stream, major_dept) and courses is a few hundred
-- rows, so the predicate is an index scan and a handful of role lookups;
-- for a dozen professors and a few thousand enrolments that is the right
-- call. A roster screen that lists every student in the college would
-- want the materialised reachability table V6 imagined, and that screen
-- does not exist.
--
--
-- WHAT IS FORCED AND WHAT IS ONLY ENABLED
--
-- students is FORCED, like lectures and attendance. The personal records
-- are what this file exists to stop leaking and nothing -- not even the
-- role that ran the migration -- should read them unfiltered. Its
-- policies call actor_may_see_student(), which reads student_year and
-- courses and NOT students, so forcing it cannot make the question
-- answer itself.
--
-- The other eight are ENABLED and not FORCED, for the reason V6 gives
-- for user_role and attendance_delegate: the definer helpers below read
-- courses and student_year to work out what an actor may reach, and a
-- FORCED table filters even the owner, so forcing those would make a
-- definer function see its caller's rows and give a wrong answer rather
-- than a slow one. Only Flyway is ever the owner; the service is not,
-- and the service is what serves requests.
--
--
-- WHAT IS STILL NOT THE DATABASE'S
--
-- One rule stays in Java, and it is the same one V10 named: a key a
-- device issues has to carry that device's node. SQL cannot know which
-- phone holds the token. V11 narrows it to what it was always for --
-- creating a row, not writing one that is already there -- so that a
-- device may amend a roster row it pulled from the server, which is the
-- whole point of pulling one.
-- =====================================================================


-- ── Two questions V6 asks with a session, asked without one ──────────
--
-- A course carries no academic year: it has a study year and a semester,
-- and which session it is taught in is a property of its lectures. So
-- reading a course asks "do you head this department at all" and "are
-- you a CR of it at all", deliberately broader than the rule on
-- lectures, which has a session to be precise about. A head who moved
-- department still sees the courses of the one they headed, and that is
-- the lesser of the two wrongs -- the alternative is a head who cannot
-- see this year's courses because the session is not written down
-- anywhere on them.

CREATE FUNCTION actor_heads(dept department) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN EXISTS (
        SELECT 1 FROM user_role ur
        WHERE ur.account_id  = current_account_id()
          AND ur.role        = 'HOD'
          AND ur.revoked_at IS NULL
          AND ur.department  = dept);

COMMENT ON FUNCTION actor_heads(department) IS
    'Whether the current actor heads this department in any session. For rows that carry no session of their own; actor_is_hod_of() is the session-exact form and is what the lecture and enrolment rules use.';

CREATE FUNCTION actor_represents(for_course bigint) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN EXISTS (
        SELECT 1 FROM attendance_delegate d
        WHERE d.course_id   = for_course
          AND d.student_id  = current_student_id()
          AND d.revoked_at IS NULL
          AND (d.expires_at IS NULL OR d.expires_at > now()));

COMMENT ON FUNCTION actor_represents(bigint) IS
    'Whether the current actor holds a live CR grant for this course in any session. The session-exact actor_is_cr_of() is what decides a register; this decides whether the course itself is visible.';


-- ── Is this enrolment on the roll of a course I reach ────────────────
--
-- V5's roll_for_course(), turned inside out: that function takes a
-- course and returns its roll, this takes a row of the roll and asks
-- whether any course the actor reaches admits it. The course_type arms
-- are the same four and have to stay the same four.
--
-- A head of the student's own major department sees the enrolment
-- directly, in that session: a head is responsible for their department's
-- students whether or not they teach them. Everything else goes through
-- a course -- which is what puts a Physics student on a Chemistry head's
-- roll when they take Chemistry as a minor, and keeps them off it
-- otherwise.

CREATE FUNCTION actor_reaches_enrolment(
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
            WHERE c.year   = e_year
              AND c.stream = e_stream
              AND (
                  (c.course_type IN ('MAJOR', 'MAJOR_PRACTICALS', 'DSE', 'DSE_PRACTICALS')
                       AND e_major = c.offering_department)
               OR (c.course_type IN ('MINOR', 'MINOR_PRACTICALS')
                       AND (e_minor = c.offering_department
                         OR e_major = c.offering_department))
               OR (c.course_type = 'OE' AND e_oe = c.offering_department)
               OR  c.course_type IN ('AEC', 'IKS', 'VSC', 'VSC_PRACTICALS',
                                     'SEC', 'SEC_PRACTICALS'))
              AND (actor_instructs(c.course_id)
                OR actor_is_cr_of(c.course_id, session_year)
                OR actor_is_hod_of(c.offering_department, session_year)));

COMMENT ON FUNCTION actor_reaches_enrolment(study_year, degree_stream, department, department, department, integer) IS
    'Whether the current actor reaches a student_year row: the head of its major department in that session, or anyone who instructs, represents or heads the offering department of a course the row is on the roll of. roll_for_course() inverted, and the one predicate the roster policies are written in terms of.';

CREATE FUNCTION actor_may_see_student(for_student bigint) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN EXISTS (
        SELECT 1 FROM student_year y
        WHERE y.student_id = for_student
          AND actor_reaches_enrolment(y.year, y.stream, y.major_dept, y.minor_dept,
                                      y.oe_dept, y.academic_year));

COMMENT ON FUNCTION actor_may_see_student(bigint) IS
    'Whether the current actor reaches a student through any of their enrolments. A student with no enrolment is reachable by nobody but the office, which is correct: there is nothing to say whose student they are.';


-- ── A course, read and written ───────────────────────────────────────
--
-- The read side is exportable_courses() asked about one course instead
-- of listed for a session, and the two must agree: a course the export
-- narrows to and the policies then hide would produce an empty file and
-- no error. The write side drops the auditor and the CR -- an auditor
-- changes nothing anywhere, and a CR takes attendance rather than
-- running a course.

CREATE FUNCTION actor_may_read_course(for_course bigint) RETURNS boolean
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
                OR actor_heads(c.offering_department)));

COMMENT ON FUNCTION actor_may_read_course(bigint) IS
    'Whether the current actor may see a course at all. exportable_courses() asked one course at a time; the two have to agree or an export narrows to a course the policies then hide.';

CREATE FUNCTION actor_may_write_course(for_course bigint) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN actor_has_role('ADMIN')
        OR actor_has_role('OFFICE')
        OR actor_instructs(for_course)
        OR EXISTS (
            SELECT 1 FROM courses c
            WHERE c.course_id = for_course
              AND (c.prof_id = current_prof_id()
                OR actor_heads(c.offering_department)));

COMMENT ON FUNCTION actor_may_write_course(bigint) IS
    'Whether the current actor may change a course and what hangs off it. Not the auditor and not a CR: one changes nothing, the other takes attendance.';


-- ── academic_years ───────────────────────────────────────────────────
--
-- The session calendar: a year and two dates. Every device needs it --
-- a session is what a lecture's date resolves against -- and there is
-- nothing in it about a person, so every signed-in actor reads it.

ALTER TABLE academic_years ENABLE ROW LEVEL SECURITY;

CREATE POLICY academic_years_read ON academic_years
    FOR SELECT
    USING (current_account_id() IS NOT NULL);

CREATE POLICY academic_years_office ON academic_years
    FOR ALL
    USING      (actor_may_manage_roster())
    WITH CHECK (actor_may_manage_roster());


-- ── professors ───────────────────────────────────────────────────────
--
-- A staff list: name, email, department, designation. Readable by
-- everyone signed in, because a student seeing who teaches them is the
-- point of it, and written by the office -- a professor's own row
-- excepted, which they may amend but not create or remove. That a
-- professor cannot add a colleague is deliberate: a professor row is
-- what courses and lectures are attributed to, and a second row for
-- somebody who already has one splits their record in two.

ALTER TABLE professors ENABLE ROW LEVEL SECURITY;

CREATE POLICY professors_read ON professors
    FOR SELECT
    USING (current_account_id() IS NOT NULL);

CREATE POLICY professors_own ON professors
    FOR UPDATE
    USING      (prof_id = current_prof_id())
    WITH CHECK (prof_id = current_prof_id());

CREATE POLICY professors_office ON professors
    FOR ALL
    USING      (actor_may_manage_roster())
    WITH CHECK (actor_may_manage_roster());


-- ── students ─────────────────────────────────────────────────────────
--
-- ENABLED and FORCED: see the header.

ALTER TABLE students ENABLE ROW LEVEL SECURITY;
ALTER TABLE students FORCE  ROW LEVEL SECURITY;

CREATE POLICY students_own ON students
    FOR SELECT
    USING (student_id = current_student_id());

CREATE POLICY students_reachable ON students
    FOR SELECT
    USING (actor_may_see_student(student_id));

CREATE POLICY students_readers ON students
    FOR SELECT
    USING (actor_has_role('OFFICE') OR actor_has_role('AUDITOR')
        OR actor_has_role('ADMIN'));

-- The roll is the office's, and V8 said so before this file existed. A
-- professor may not rewrite the roll of their own class, because a roll
-- number is what every other session's records are matched by.
CREATE POLICY students_roster ON students
    FOR ALL
    USING      (actor_may_manage_roster())
    WITH CHECK (actor_may_manage_roster());


-- ── student_year ─────────────────────────────────────────────────────
--
-- The enrolment, and the roll number on it. The reachability predicate
-- is asked of this row's own columns, so this policy never reads this
-- table -- which is what lets it be policed at all.

ALTER TABLE student_year ENABLE ROW LEVEL SECURITY;

CREATE POLICY student_year_own ON student_year
    FOR SELECT
    USING (student_id = current_student_id());

CREATE POLICY student_year_reachable ON student_year
    FOR SELECT
    USING (actor_reaches_enrolment(year, stream, major_dept, minor_dept, oe_dept,
                                   academic_year));

CREATE POLICY student_year_readers ON student_year
    FOR SELECT
    USING (actor_has_role('OFFICE') OR actor_has_role('AUDITOR')
        OR actor_has_role('ADMIN'));

CREATE POLICY student_year_roster ON student_year
    FOR ALL
    USING      (actor_may_manage_roster())
    WITH CHECK (actor_may_manage_roster());


-- ── courses ──────────────────────────────────────────────────────────
--
-- Written in the row's own columns rather than through
-- actor_may_read_course(), which reads this table: a policy that called
-- it would be asking courses about courses. The two say the same thing
-- and the drift test checks that they do.
--
-- prof_id is in the write rule as well as course_instructor, and that
-- matters for one sequence in particular: a device creating a course
-- inserts the course before the course_instructor row, so at the moment
-- of the insert actor_instructs() is still false and the coordinator
-- column is the only thing that says whose course it is.

ALTER TABLE courses ENABLE ROW LEVEL SECURITY;

CREATE POLICY courses_read ON courses
    FOR SELECT
    USING (actor_has_role('ADMIN') OR actor_has_role('OFFICE')
        OR actor_has_role('AUDITOR')
        OR prof_id = current_prof_id()
        OR actor_instructs(course_id)
        OR actor_represents(course_id)
        OR actor_heads(offering_department));

CREATE POLICY courses_write ON courses
    FOR ALL
    USING      (actor_has_role('ADMIN') OR actor_has_role('OFFICE')
             OR prof_id = current_prof_id()
             OR actor_instructs(course_id)
             OR actor_heads(offering_department))
    WITH CHECK (actor_has_role('ADMIN') OR actor_has_role('OFFICE')
             OR prof_id = current_prof_id()
             OR actor_instructs(course_id)
             OR actor_heads(offering_department));


-- ── course_instructor ────────────────────────────────────────────────
--
-- Who teaches what. A professor sees their own rows whatever else is
-- true, which is what makes actor_instructs() answerable for a course
-- they can otherwise not read.

ALTER TABLE course_instructor ENABLE ROW LEVEL SECURITY;

CREATE POLICY course_instructor_own ON course_instructor
    FOR SELECT
    USING (prof_id = current_prof_id());

CREATE POLICY course_instructor_read ON course_instructor
    FOR SELECT
    USING (actor_may_read_course(course_id));

CREATE POLICY course_instructor_write ON course_instructor
    FOR ALL
    USING      (actor_may_write_course(course_id))
    WITH CHECK (actor_may_write_course(course_id));


-- ── batch_desc ───────────────────────────────────────────────────────
--
-- A practical batch: a name, a description and a session. No personal
-- data, so everyone signed in reads it, and any professor may create
-- one -- which is how the app works today, where a professor splits a
-- class into batches on their own phone.
--
-- Worth being plain about the looseness: a batch is identified by its
-- name and session, nothing records which professor set it up, so one
-- professor may rename another's batch. The alternative needs an owner
-- column on the table and the device does not have one. What stops this
-- mattering is that a rename is in the audit log with a name against it.

ALTER TABLE batch_desc ENABLE ROW LEVEL SECURITY;

CREATE POLICY batch_desc_read ON batch_desc
    FOR SELECT
    USING (current_account_id() IS NOT NULL);

CREATE POLICY batch_desc_write ON batch_desc
    FOR ALL
    USING      (current_prof_id() IS NOT NULL OR actor_may_manage_roster())
    WITH CHECK (current_prof_id() IS NOT NULL OR actor_may_manage_roster());


-- ── batch_student_map ────────────────────────────────────────────────
--
-- Which student is in which batch, which is roster data however small
-- the row is. Read when the student is readable; written by a professor
-- for a student they can reach, so a professor cannot discover a
-- stranger's key by putting it in a batch.

ALTER TABLE batch_student_map ENABLE ROW LEVEL SECURITY;

CREATE POLICY batch_student_map_own ON batch_student_map
    FOR SELECT
    USING (student_id = current_student_id());

CREATE POLICY batch_student_map_read ON batch_student_map
    FOR SELECT
    USING (actor_has_role('OFFICE') OR actor_has_role('AUDITOR')
        OR actor_has_role('ADMIN')
        OR actor_may_see_student(student_id));

CREATE POLICY batch_student_map_write ON batch_student_map
    FOR ALL
    USING      (actor_may_manage_roster()
             OR (current_prof_id() IS NOT NULL AND actor_may_see_student(student_id)))
    WITH CHECK (actor_may_manage_roster()
             OR (current_prof_id() IS NOT NULL AND actor_may_see_student(student_id)));


-- ── course_batch_map ─────────────────────────────────────────────────

ALTER TABLE course_batch_map ENABLE ROW LEVEL SECURITY;

CREATE POLICY course_batch_map_read ON course_batch_map
    FOR SELECT
    USING (actor_may_read_course(course_id));

CREATE POLICY course_batch_map_write ON course_batch_map
    FOR ALL
    USING      (actor_may_write_course(course_id))
    WITH CHECK (actor_may_write_course(course_id));


-- ── The tables a device may sync ─────────────────────────────────────
--
-- All eleven, in the order DeviceTables.inDependencyOrder() returns
-- them, which is not decoration: SyncReader reads this array in order to
-- build a first snapshot, so a device applying that page in the order it
-- arrives gets a course before a lecture on it and a student before a
-- mark against them. The drift test holds the array to that order.
--
-- Eleven and not nine: id_remap and import_run are the importer's
-- bookkeeping and mean nothing on a phone, and app_account, user_role,
-- app_session, device_registration and audit_log are the server's own.

CREATE OR REPLACE FUNCTION synced_tables() RETURNS text[]
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    RETURN ARRAY['students', 'professors', 'academic_years', 'student_year',
                 'courses', 'batch_desc', 'batch_student_map', 'course_batch_map',
                 'course_instructor', 'lectures', 'attendance'];

COMMENT ON FUNCTION synced_tables() IS
    'The tables a device may push and pull, in dependency order: every table a device holds, now that V11 gives all of them policies. The order is what makes a first snapshot applicable in the order it arrives.';
