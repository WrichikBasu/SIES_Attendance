-- =====================================================================
-- Phase 1 smoke test.
--
-- "The schema applies" is a weak gate. This asserts the constraints
-- actually reject what they claim to, and — just as important — that
-- they do NOT reject the legitimate shapes the app relies on.
--
-- Run against a database with V1 and V2 applied:
--   psql -v ON_ERROR_STOP=1 -f constraint_smoke_test.sql
--
-- Every statement runs inside one transaction that is rolled back at the
-- end, so the database is left empty.
-- =====================================================================

BEGIN;

-- Every assertion reports through RAISE NOTICE, which goes to stderr.
-- The SELECTs themselves return nothing useful, so drop their output.
\o /dev/null

CREATE FUNCTION assert_rejected(stmt text, what text) RETURNS void
    LANGUAGE plpgsql AS $fn$
BEGIN
    BEGIN
        EXECUTE stmt;
    EXCEPTION
        WHEN check_violation
          OR foreign_key_violation
          -- ON DELETE RESTRICT raises restrict_violation (23001), which is
          -- NOT the same condition as foreign_key_violation (23503). Both
          -- are listed because the schema uses RESTRICT throughout.
          OR restrict_violation
          OR unique_violation
          OR exclusion_violation
          OR invalid_text_representation
          -- Writing a GENERATED ALWAYS column raises generated_always
          -- (428C9), which is none of the above.
          OR generated_always
        THEN
            RAISE NOTICE '  ok   rejected: %', what;
            RETURN;
    END;
    RAISE EXCEPTION 'NOT REJECTED, but should have been: %', what;
END $fn$;

CREATE FUNCTION assert_equal(got anyelement, want anyelement, what text) RETURNS void
    LANGUAGE plpgsql AS $fn$
BEGIN
    IF got IS DISTINCT FROM want THEN
        RAISE EXCEPTION '% : got %, want %', what, got, want;
    END IF;
    RAISE NOTICE '  ok   %', what;
END $fn$;


-- ── A legitimate chain, end to end ───────────────────────────────────
\echo '-- valid data'

INSERT INTO academic_years (academic_year, start_date, end_date)
    VALUES (2025, '2025-06-01', '2026-05-31'),
           (2024, '2024-06-01', '2025-05-31');

INSERT INTO professors (prof_id, name, email, department, designation)
    VALUES (1001, 'A Mehta', 'a.mehta@example.ac.in', 'PHYSICS', 'Assistant Professor');

INSERT INTO students (student_id, name, pronouns, email)
    VALUES (2001, 'B RAO', 'she/her', 'b.rao@example.ac.in'),
           (2002, 'C SHAH', 'he/him', NULL),
           (2003, 'D IYER', 'they/them', NULL),
           (2004, 'E NAIR', 'she/her', NULL);   -- three NULL emails: allowed

INSERT INTO student_year
    (student_id, academic_year, roll_no, year, stream, major_dept, minor_dept, oe_dept, semester, division)
    VALUES (2001, 2025, 'PHY-FY-001', 'FY', 'BSc', 'PHYSICS', 'MATHEMATICS', 'ECONOMICS', 1, 'A'),
           (2002, 2025, 'PHY-FY-002', 'FY', 'BSc', 'PHYSICS', NULL, NULL, NULL, NULL),
           (2003, 2025, 'CS-FY-001',  'FY', 'BSc_unaided', 'COMPUTER_SCIENCE', NULL, NULL, 2, NULL);

INSERT INTO courses
    (course_id, prof_id, course_code, course_topic, stream, offering_department,
     year, semester, course_type)
    VALUES (3001, 1001, 'PHYS101', 'Mechanics', 'BSc', 'PHYSICS', 'FY', 1, 'MAJOR'),
           (3002, 1001, 'PHYS101P', 'Mechanics Lab', 'BSc', 'PHYSICS', 'FY', 1, 'MAJOR_PRACTICALS');

-- Courses taught by a department other than the students' own. V2 forbade
-- every one of these, because it required a course's stream to be one its
-- offering department offers; V4 exempts the types whose offering
-- department is not the students' major. Mathematics is studied as BSc and
-- never BSc_unaided, Economics and English are BA/MA departments, and all
-- three rows below are ordinary teaching.
INSERT INTO courses
    (course_id, prof_id, course_code, course_topic, stream, offering_department,
     year, semester, course_type)
    VALUES (3011, 1001, 'MATH-MIN-1', 'Discrete Mathematics', 'BSc_unaided', 'MATHEMATICS',
            'FY', 1, 'MINOR'),
           (3012, 1001, 'ECO-OE-1',   'Basic Economics',      'BSc',         'ECONOMICS',
            'FY', 1, 'OE'),
           (3013, 1001, 'ENG-AEC-1',  'Communication Skills', 'BSc_unaided', 'ENGLISH',
            'FY', 1, 'AEC');

SELECT assert_equal(
    (SELECT count(*) FROM courses
      WHERE course_id IN (3011, 3012, 3013) AND own_department_stream IS NULL),
    3::bigint,
    'a cross-department course has no own_department_stream to be constrained on');

SELECT assert_equal(
    (SELECT own_department_stream FROM courses WHERE course_id = 3001),
    'BSc'::degree_stream,
    'a major carries its stream into the generated column');

INSERT INTO course_instructor (course_id, prof_id) VALUES (3001, 1001), (3002, 1001);

INSERT INTO batch_desc (batch_id, batch_name, batch_desc, academic_year)
    VALUES (4001, 'Batch A', 'Lab 1', 2025),
           (4002, 'Batch B', NULL, 2025),
           (4003, 'Batch A', NULL, 2024);   -- same name, different session: allowed

INSERT INTO batch_student_map (batch_id, student_id, academic_year)
    VALUES (4001, 2001, 2025), (4001, 2002, 2025);

INSERT INTO course_batch_map (course_id, batch_id) VALUES (3002, 4001), (3002, 4002);

INSERT INTO lectures (lecture_id, course_id, batch_id, start_at, start_zone, end_at, end_zone, notes)
    VALUES
    -- whole-class lecture
    (5001, 3001, NULL,
     '2025-07-14 09:00:00+05:30', 'Asia/Kolkata',
     '2025-07-14 10:00:00+05:30', 'Asia/Kolkata', 'Newton''s laws'),
    -- a second whole-class lecture for the same course at a different time
    (5002, 3001, NULL,
     '2025-07-15 09:00:00+05:30', 'Asia/Kolkata',
     '2025-07-15 10:00:00+05:30', 'Asia/Kolkata', NULL),
    -- a batch practical in the SAME slot as lecture 5001: legitimate
    (5003, 3002, 4001,
     '2025-07-14 09:00:00+05:30', 'Asia/Kolkata',
     '2025-07-14 11:00:00+05:30', 'Asia/Kolkata', NULL),
    -- the other batch, same slot: also legitimate
    (5004, 3002, 4002,
     '2025-07-14 09:00:00+05:30', 'Asia/Kolkata',
     '2025-07-14 11:00:00+05:30', 'Asia/Kolkata', NULL);

INSERT INTO attendance (lecture_id, student_id, present)
    VALUES (5001, 2001, true), (5001, 2002, false);

SELECT assert_equal((SELECT count(*)::int FROM lectures), 4, 'four lectures accepted');

-- A roll number is reissued when a student is promoted, so the SAME roll
-- number in a DIFFERENT session is not a duplicate. This is the reason
-- student_year is a table of its own, and it has to keep working.
INSERT INTO student_year (student_id, academic_year, roll_no, year, stream, major_dept)
    VALUES (2004, 2024, 'PHY-FY-001', 'FY', 'BSc', 'PHYSICS');
SELECT assert_equal((SELECT count(*)::int FROM student_year WHERE roll_no = 'PHY-FY-001'),
                    2, 'one roll number reused in a later session');


-- ── The ID scheme, ported to SQL ─────────────────────────────────────
\echo '-- IdGenerator helpers'

-- A key built as IdGenerator would: node 5, 2025-06-01T00:00:00Z, seq 0.
SELECT assert_equal(id_node(187354526515200005), 5, 'id_node reads the node field');
SELECT assert_equal(id_created_at(187354526515200005),
                    '2025-06-01 00:00:00+00'::timestamptz,
                    'id_created_at reads the time field');
SELECT assert_equal(id_is_structured(187354526515200005), true, 'structured key recognised');

-- A legacy key, counted out by SQLite before schema version 7.
SELECT assert_equal(id_is_structured(14), false, 'legacy key not mistaken for structured');
SELECT assert_equal(id_node(14), -1, 'legacy key carries no node');
SELECT assert_equal(id_created_at(14), NULL::timestamptz, 'legacy key carries no time');


-- ── Session derivation ───────────────────────────────────────────────
\echo '-- session_year_of'

SELECT assert_equal(session_year_of('2025-07-14 09:00:00+05:30', 'Asia/Kolkata'), 2025,
                    'a July lecture falls in the 2025-26 session');
SELECT assert_equal(session_year_of('2025-05-14 09:00:00+05:30', 'Asia/Kolkata'), 2024,
                    'a May lecture falls in the 2024-25 session');
SELECT assert_equal(session_year_of('2030-01-01 09:00:00+05:30', 'Asia/Kolkata'), NULL::integer,
                    'a lecture outside every session belongs to none');
SELECT assert_equal((SELECT count(*)::int FROM lectures_without_session), 0,
                    'no orphan lectures in the fixture');

-- The zone is a parameter, not an ambient setting. 2025-06-01T00:00+05:30
-- is still 31 May in UTC, so the two answers differ — which is the whole
-- reason startEpoch/endEpoch were dropped.
SELECT assert_equal(session_year_of('2025-06-01 00:00:00+05:30', 'Asia/Kolkata'), 2025,
                    'session boundary resolved in the lecture''s own zone');
SELECT assert_equal(session_year_of('2025-06-01 00:00:00+05:30', 'UTC'), 2024,
                    'the same instant resolves differently in UTC, as it must');


-- ── What must be rejected ────────────────────────────────────────────
\echo '-- rejections'

SELECT assert_rejected($$
    INSERT INTO academic_years (academic_year, start_date, end_date)
    VALUES (2026, '2026-04-01', '2027-03-31')$$,
    'an academic session overlapping another');

SELECT assert_rejected($$
    INSERT INTO academic_years (academic_year, start_date, end_date)
    VALUES (2027, '2028-06-01', '2027-05-31')$$,
    'an academic session ending before it starts');

SELECT assert_rejected($$
    INSERT INTO student_year (student_id, academic_year, roll_no, year, stream, major_dept)
    VALUES (2001, 2099, 'X-1', 'FY', 'BSc', 'PHYSICS')$$,
    'a student filed under an undefined academic session');

SELECT assert_rejected($$
    INSERT INTO courses (course_id, prof_id, course_code, course_topic, stream,
                         offering_department, year, semester, course_type)
    VALUES (3099, 1001, 'COM999', 'Nonsense', 'BSc', 'COMMERCE', 'FY', 1, 'MAJOR')$$,
    'Commerce taught as a BSc (department does not offer the stream)');

-- The same impossible pairing as a MAJOR is still refused. A major's roll
-- IS the offering department's own majors, and BSc_unaided Mathematics has
-- none, so the row describes a class that cannot exist.
SELECT assert_rejected($$
    INSERT INTO courses (course_id, prof_id, course_code, course_topic, stream,
                         offering_department, year, semester, course_type)
    VALUES (3094, 1001, 'MATH-MAJ-X', 'Impossible major', 'BSc_unaided', 'MATHEMATICS',
            'FY', 1, 'MAJOR')$$,
    'a major in a stream its own department does not offer');

SELECT assert_rejected($$
    INSERT INTO courses (course_id, prof_id, course_code, course_topic, stream,
                         offering_department, year, semester, course_type)
    VALUES (3093, 1001, 'MATH-DSEP-X', 'Impossible DSE lab', 'BSc_unaided', 'MATHEMATICS',
            'TY', 5, 'DSE_PRACTICALS')$$,
    'a DSE practical in a stream its own department does not offer');

-- Nobody may write the generated column, which is what keeps the importer
-- from ever being able to supply it.
SELECT assert_rejected($$
    INSERT INTO courses (course_id, prof_id, course_code, course_topic, stream,
                         offering_department, year, semester, course_type,
                         own_department_stream)
    VALUES (3092, 1001, 'PHYS-GEN-X', 'Generated written', 'BSc', 'PHYSICS',
            'FY', 1, 'MAJOR', 'BSc')$$,
    'an explicit value for the generated own_department_stream column');

SELECT assert_rejected($$
    INSERT INTO student_year (student_id, academic_year, roll_no, year, stream, major_dept)
    VALUES (2002, 2024, 'X-2', 'Masters_P1', 'BSc', 'PHYSICS')$$,
    'a master''s year on an undergraduate stream');

SELECT assert_rejected($$
    INSERT INTO student_year (student_id, academic_year, roll_no, year, stream, major_dept, semester)
    VALUES (2002, 2024, 'X-3', 'FY', 'BSc', 'PHYSICS', 3)$$,
    'semester 3 in the first year');

SELECT assert_rejected($$
    INSERT INTO courses (course_id, prof_id, course_code, course_topic, stream,
                         offering_department, year, semester, course_type)
    VALUES (3098, 1001, 'PHYS777', 'Wrong semester', 'BSc', 'PHYSICS', 'TY', 2, 'MAJOR')$$,
    'a third-year course in semester 2');

SELECT assert_rejected($$
    INSERT INTO student_year (student_id, academic_year, roll_no, year, stream, major_dept, division)
    VALUES (2002, 2024, 'X-4', 'FY', 'BSc_unaided', 'COMPUTER_SCIENCE', 'A')$$,
    'a division on a stream that has none');

SELECT assert_rejected($$
    INSERT INTO student_year (student_id, academic_year, roll_no, year, stream, major_dept, minor_dept)
    VALUES (2002, 2024, 'X-5', 'FY', 'BSc', 'PHYSICS', 'PHYSICS')$$,
    'a student minoring in their own major department');

SELECT assert_rejected($$
    INSERT INTO student_year (student_id, academic_year, roll_no, year, stream, major_dept, oe_dept)
    VALUES (2002, 2024, 'X-6', 'FY', 'BSc', 'PHYSICS', 'PHYSICS')$$,
    'an open elective in the student''s own major department');

SELECT assert_rejected($$
    INSERT INTO batch_desc (batch_id, batch_name, academic_year)
    VALUES (4099, 'Batch A', 2025)$$,
    'a second batch called "Batch A" in the same session');

SELECT assert_rejected($$
    INSERT INTO lectures (lecture_id, course_id, batch_id, start_at, start_zone, end_at, end_zone)
    VALUES (5099, 3001, NULL,
            '2025-07-14 09:00:00+05:30', 'Asia/Kolkata',
            '2025-07-14 10:00:00+05:30', 'Asia/Kolkata')$$,
    'a duplicate WHOLE-CLASS lecture (the gap SQLite left open)');

SELECT assert_rejected($$
    INSERT INTO lectures (lecture_id, course_id, batch_id, start_at, start_zone, end_at, end_zone)
    VALUES (5098, 3002, 4001,
            '2025-07-14 09:00:00+05:30', 'Asia/Kolkata',
            '2025-07-14 11:00:00+05:30', 'Asia/Kolkata')$$,
    'a duplicate batch lecture');

SELECT assert_rejected($$
    INSERT INTO lectures (lecture_id, course_id, batch_id, start_at, start_zone, end_at, end_zone)
    VALUES (5097, 3001, NULL,
            '2025-07-20 10:00:00+05:30', 'Asia/Kolkata',
            '2025-07-20 09:00:00+05:30', 'Asia/Kolkata')$$,
    'a lecture ending before it starts');

SELECT assert_rejected($$
    INSERT INTO courses (course_id, prof_id, course_code, course_topic, stream,
                         offering_department, year, semester, course_type)
    VALUES (3097, 1001, 'PHYS101', 'Duplicate code', 'BSc', 'PHYSICS', 'FY', 1, 'MAJOR')$$,
    'a duplicate course code');

SELECT assert_rejected($$
    INSERT INTO students (student_id, name, pronouns, email)
    VALUES (2099, 'E DUPE', 'she/her', 'b.rao@example.ac.in')$$,
    'a duplicate student email');

SELECT assert_rejected($$
    INSERT INTO student_year (student_id, academic_year, roll_no, year, stream, major_dept)
    VALUES (2004, 2025, 'PHY-FY-001', 'FY', 'BSc', 'PHYSICS')$$,
    'a roll number reused within one session');

SELECT assert_rejected($$
    INSERT INTO professors (prof_id, name, email, department, designation)
    VALUES (1099, 'F NEW', 'f.new@example.ac.in', 'ASTROLOGY', 'Professor')$$,
    'a department that is not in the enum');

SELECT assert_rejected($$
    INSERT INTO courses (course_id, prof_id, course_code, course_topic, stream,
                         offering_department, year, semester, course_type,
                         date_discontinued, date_discontinued_zone)
    VALUES (3096, 1001, 'PHYS888', 'Zone unpaired', 'BSc', 'PHYSICS', 'FY', 1, 'MAJOR',
            '2025-01-01 00:00:00+05:30', NULL)$$,
    'a converted timestamp with no zone recorded beside it');

-- The pairing holds the other way round too.
SELECT assert_rejected($$
    INSERT INTO courses (course_id, prof_id, course_code, course_topic, stream,
                         offering_department, year, semester, course_type,
                         date_introduced, date_introduced_zone)
    VALUES (3095, 1001, 'PHYS889', 'Zone orphaned', 'BSc', 'PHYSICS', 'FY', 1, 'MAJOR',
            NULL, 'Asia/Kolkata')$$,
    'a recorded zone with no timestamp beside it');

SELECT assert_rejected($$
    INSERT INTO attendance (lecture_id, student_id, present)
    VALUES (5001, 2001, false)$$,
    'a second attendance row for one student in one lecture');

-- Blocked by student_year before attendance is even reached: every
-- reference onto students is RESTRICT, which is what keeps a roster
-- correction from quietly erasing a term of attendance.
SELECT assert_rejected($$DELETE FROM students WHERE student_id = 2001$$,
    'deleting a student who has any dependent record');

SELECT assert_rejected($$DELETE FROM lectures WHERE lecture_id = 5001$$,
    'deleting a lecture that has attendance recorded');

SELECT assert_rejected($$DELETE FROM batch_desc WHERE batch_id = 4001$$,
    'deleting a batch that has members and lectures');

SELECT assert_rejected($$DELETE FROM professors WHERE prof_id = 1001$$,
    'deleting a professor who coordinates a course');


-- ── V8: sessions and devices ─────────────────────────────────────────
\echo '-- the wire'

-- An account to hang them off. STAFF, so it points at neither a
-- professor nor a student, which is what app_account_exactly_one_person
-- allows for office staff.
INSERT INTO app_account (account_id, kind, display_name, login_email, password_hash)
    VALUES (9001, 'STAFF', 'Smoke Clerk', 'smoke@sies.edu.in', '{argon2id}$not-a-real-hash');

INSERT INTO device_registration (device_id, install_id, node, provisional_node, label,
                                 registered_by)
    VALUES (9101, '11111111-1111-1111-1111-111111111111', 7, 40000, 'Smoke phone', 9001);

SELECT assert_equal(
    (SELECT node_for_install('11111111-1111-1111-1111-111111111111')), 7,
    'node_for_install answers for a live registration');

-- Zero is the server's own node and is outside the assignable range, so
-- no device may ever hold it.
SELECT assert_rejected($$
    INSERT INTO device_registration (install_id, node, label, registered_by)
    VALUES ('22222222-2222-2222-2222-222222222222', 0, 'Zero', 9001)$$,
    'a device claiming the server''s own node');

-- The halves do not overlap in either direction: an assigned node may
-- not come from the self-picked half, and a provisional node may not
-- come from the assigned half.
SELECT assert_rejected($$
    INSERT INTO device_registration (install_id, node, label, registered_by)
    VALUES ('22222222-2222-2222-2222-222222222222', 40001, 'Too high', 9001)$$,
    'an assigned node from the half a device picks for itself');

SELECT assert_rejected($$
    INSERT INTO device_registration (install_id, provisional_node, label, registered_by)
    VALUES ('22222222-2222-2222-2222-222222222222', 17, 'Wrong half', 9001)$$,
    'a provisional node from the assignable half');

SELECT assert_rejected($$
    INSERT INTO device_registration (install_id, label, registered_by)
    VALUES ('11111111-1111-1111-1111-111111111111', 'The same install twice', 9001)$$,
    'registering one install twice');

-- Not deleted, revoked: the node is in the key of every row that install
-- ever wrote.
SELECT assert_rejected($$
    UPDATE device_registration SET revoked_at = now() WHERE device_id = 9101$$,
    'revoking a device without saying who revoked it');

INSERT INTO app_session (session_id, account_id, token_hash, device_id, expires_at)
    VALUES ('33333333-3333-3333-3333-333333333333', 9001, '\x0001'::bytea, 9101,
            now() + interval '14 days');

SELECT assert_rejected($$
    INSERT INTO app_session (account_id, token_hash, expires_at)
    VALUES (9001, '\x0002'::bytea, now() - interval '1 day')$$,
    'a session that expired before it was issued');

SELECT assert_rejected($$
    INSERT INTO app_session (account_id, token_hash, expires_at)
    VALUES (9001, '\x0001'::bytea, now() + interval '1 day')$$,
    'two sessions sharing a token');


\o

\echo '-- ALL ASSERTIONS PASSED'

ROLLBACK;
