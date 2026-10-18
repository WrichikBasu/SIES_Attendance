-- =====================================================================
-- V5 — Who may sign in, what they are, and who may mark a register.
--
-- Additive. Nothing from V1 to V4 is altered.
--
-- There is no login through the college's identity provider, so this
-- server holds credentials itself. That decision is the reason for
-- password_hash, must_rotate, failed_logins and locked_until below, none
-- of which would exist against an external provider.
--
--
-- WHY ONE ACCOUNT TABLE AND NOT TWO
--
-- A row-level security policy that has to ask "is this shaped like a
-- professor or like a student" in two different shapes is a policy that
-- gets written twice and wrong once. One table, one actor id, and a
-- CHECK that the row points at exactly the person its kind says it does.
--
--
-- WHAT A CLASS REPRESENTATIVE IS
--
-- Not a role. A role is open ended; a CR's authority is not. It is a set
-- of grants in attendance_delegate, one per course per session, each
-- naming who gave it and in what capacity. Several rows for one student
-- is a CR of several courses. Several rows for one course is several CRs
-- of that course, which is how a class with a lecture CR and a lab CR
-- works without a second table.
--
-- Nothing is ever deleted from it. A revoked grant keeps its row with
-- revoked_at set, because the question a dispute asks is "who was
-- allowed to do this in March", and a deleted row cannot answer it.
--
-- APPLIED TO AN EMPTY DATABASE. Every table here starts empty; the first
-- admin account is created by BootstrapCommand under the "bootstrap"
-- profile, not by a migration, because a migration is a committed file
-- and a committed file with a password in it is a password everybody
-- with the repository has.
-- =====================================================================


-- ── The three new enum types ─────────────────────────────────────────
--
-- Values and order are AccountKind, AppRole and GrantAuthority exactly;
-- PostgresSchemaDriftTest fails if they drift.

CREATE TYPE account_kind AS ENUM (
    'PROFESSOR',
    'STUDENT',
    'STAFF'
);

-- No PROFESSOR here: being a professor is account_kind plus the
-- course_instructor rows that say which courses are yours, and a role
-- saying the same thing would be a second answer to one question. No CR
-- either, for the reason in the header.
CREATE TYPE app_role AS ENUM (
    'HOD',
    'OFFICE',
    'AUDITOR',
    'ADMIN'
);

CREATE TYPE grant_authority AS ENUM (
    'INSTRUCTOR',
    'HOD',
    'ADMIN'
);


-- ── app_account ──────────────────────────────────────────────────────
--
-- bigserial, not a structured id: nothing on a phone issues an account,
-- so there is no node to encode and no collision to avoid. Same reason
-- import_run uses one.

CREATE TABLE app_account (
    account_id    bigserial PRIMARY KEY,
    kind          account_kind NOT NULL,

    -- Exactly one of these, decided by kind. STAFF has neither: office
    -- and registry staff are not professors, and giving them a
    -- professors row to hang an account on would corrupt every query
    -- that counts teaching staff or offers a coordinator to pick.
    prof_id       bigint,
    student_id    bigint,

    display_name  text NOT NULL,

    -- What you type. NOT NULL even though students.email is nullable:
    -- an account with no login identity is unusable, so the requirement
    -- belongs to the account and not to the person. A student with no
    -- address on file therefore has no account until somebody puts one
    -- there, which is part of designating them a CR.
    login_email   text NOT NULL,

    -- Argon2id, in Spring Security's {argon2id}$... encoding, so the
    -- parameters a hash was made with travel with the hash and can be
    -- raised later without a flag day.
    password_hash text NOT NULL,
    must_rotate   boolean NOT NULL DEFAULT true,

    failed_logins integer NOT NULL DEFAULT 0,
    locked_until  timestamptz,
    last_login_at timestamptz,
    disabled_at   timestamptz,

    created_at    timestamptz NOT NULL DEFAULT now(),
    created_by    bigint,
    updated_at    timestamptz,
    updated_by    bigint,

    -- Which of the two login screens this account answers on. Derived,
    -- because it is a restatement of kind and must never disagree with
    -- it, and it exists so the uniqueness below can be per door.
    student_login boolean NOT NULL
        GENERATED ALWAYS AS (kind = 'STUDENT') STORED,

    CONSTRAINT app_account_exactly_one_person CHECK (
        (kind = 'PROFESSOR' AND prof_id IS NOT NULL AND student_id IS NULL) OR
        (kind = 'STUDENT'   AND prof_id IS NULL     AND student_id IS NOT NULL) OR
        (kind = 'STAFF'     AND prof_id IS NULL     AND student_id IS NULL)),

    -- No constraint tying locked_until to failed_logins: a successful
    -- login clears the counter while a spent lock timestamp may still be
    -- sitting there, and making that an error would turn signing in into
    -- a two-statement dance. An administrative lock is disabled_at.
    CONSTRAINT app_account_failures_not_negative CHECK (failed_logins >= 0),

    -- Cheap, and it is the shape of every address in the college. A
    -- login identity that is not an address is a support call.
    CONSTRAINT app_account_login_email_shape CHECK (
        login_email ~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'),

    CONSTRAINT app_account_prof_id_fkey FOREIGN KEY (prof_id)
        REFERENCES professors (prof_id) ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT app_account_student_id_fkey FOREIGN KEY (student_id)
        REFERENCES students (student_id) ON UPDATE CASCADE ON DELETE RESTRICT
);

-- Unique per door, not globally. The app knows which screen you came in
-- by before it looks you up, so one address may hold a student account
-- and a staff account. Lower-cased, unlike students.email and
-- professors.email, which V1 left case-sensitive to match Room exactly:
-- a person typing their own address at a login box will not reproduce
-- its capitalisation, and treating two casings as two accounts is how
-- somebody gets locked out of the one they have.
CREATE UNIQUE INDEX app_account_login_uq
    ON app_account (student_login, lower(login_email));

-- One account per person. Partial, because STAFF rows have neither.
CREATE UNIQUE INDEX app_account_prof_uq
    ON app_account (prof_id)    WHERE prof_id    IS NOT NULL;
CREATE UNIQUE INDEX app_account_student_uq
    ON app_account (student_id) WHERE student_id IS NOT NULL;

COMMENT ON TABLE app_account IS
    'One row per principal that can sign in. kind decides which person table it points at and which of the two login screens it answers on.';
COMMENT ON COLUMN app_account.student_login IS
    'Derived from kind. Exists only to make app_account_login_uq per-door, so one address can hold both a student and a staff account.';
COMMENT ON COLUMN app_account.password_hash IS
    'Spring Security {argon2id} encoding. The parameters travel with the hash, so they can be raised without rehashing everything at once.';


-- ── user_role ────────────────────────────────────────────────────────
--
-- Keyed on account_id and NOT on prof_id. An admin, an auditor or an
-- office clerk need not be a professor at all, so there is no prof_id to
-- key off; keying off one and making it nullable, which is what an
-- earlier draft of the plan did, leaves every policy joining through a
-- column that is null for the people it is about.

CREATE TABLE user_role (
    -- A surrogate, because the natural key below contains nulls and a
    -- PRIMARY KEY may not. Also what the audit log points at.
    user_role_id  bigserial,

    account_id    bigint   NOT NULL,
    role          app_role NOT NULL,

    -- HOD only. AppRole.isDepartmentScoped() is the Java side of this.
    department    department,

    -- HOD only, for the same reason: a head of department for 2025-26
    -- should not quietly still be one in 2027-28. The college-wide roles
    -- are not appointed per session, so theirs is null and means "until
    -- revoked".
    academic_year integer,

    granted_by    bigint      NOT NULL,
    granted_at    timestamptz NOT NULL DEFAULT now(),
    revoked_at    timestamptz,
    revoked_by    bigint,
    reason        text,

    PRIMARY KEY (user_role_id),

    CONSTRAINT user_role_department_iff_hod CHECK (
        (role = 'HOD') = (department IS NOT NULL)),
    CONSTRAINT user_role_session_iff_hod CHECK (
        (role = 'HOD') = (academic_year IS NOT NULL)),
    CONSTRAINT user_role_revocation_complete CHECK (
        (revoked_at IS NULL) = (revoked_by IS NULL)),

    CONSTRAINT user_role_account_id_fkey FOREIGN KEY (account_id)
        REFERENCES app_account (account_id) ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT user_role_granted_by_fkey FOREIGN KEY (granted_by)
        REFERENCES app_account (account_id) ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT user_role_revoked_by_fkey FOREIGN KEY (revoked_by)
        REFERENCES app_account (account_id) ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT user_role_academic_year_fkey FOREIGN KEY (academic_year)
        REFERENCES academic_years (academic_year) ON UPDATE CASCADE ON DELETE RESTRICT
);

-- A role is held once per scope. NULLS NOT DISTINCT is what makes this
-- work: department and academic_year are null for every college-wide
-- role, and under the default NULLS DISTINCT an account could be granted
-- ADMIN any number of times over.
CREATE UNIQUE INDEX user_role_scope_uq
    ON user_role (account_id, role, department, academic_year)
    NULLS NOT DISTINCT;

-- What every policy looks up: the live roles of one account. Partial, so
-- revoked history costs the lookup nothing.
CREATE INDEX user_role_live_idx
    ON user_role (account_id, role) WHERE revoked_at IS NULL;

-- And the other direction, for "who is the head of Physics".
CREATE INDEX user_role_department_idx
    ON user_role (department, academic_year, role) WHERE revoked_at IS NULL;

COMMENT ON TABLE user_role IS
    'Roles granted on top of what an account already is. No PROFESSOR row: that is account_kind plus course_instructor. No CR row: that is attendance_delegate.';


-- ── roll_for_course ──────────────────────────────────────────────────
--
-- The roster, as a function, because three things need it and two of
-- them are not Java: the grant trigger below, the API, and the export.
--
-- Translated from DaoStudent.getRollForCourseSync. Read that query's
-- javadoc before changing this one -- which of a student's three
-- departments has to match depends on how they are taking the course,
-- and the minor branch admits the student's major department on purpose,
-- because the minor field on the student form is optional and often
-- blank. An empty roll means attendance cannot be taken at all, which is
-- worse than a roll that is slightly too wide.
--
-- PostgresSchemaDriftTest holds the course-type lists here against
-- CourseType, so a constant added to the enum and not accounted for here
-- fails the build rather than silently matching nobody.

CREATE FUNCTION roll_for_course(for_course bigint, session_year integer)
    RETURNS TABLE (student_id bigint, roll_no text)
    LANGUAGE sql STABLE
    BEGIN ATOMIC
        SELECT y.student_id, y.roll_no
        FROM courses c
        JOIN student_year y
          ON y.year   = c.year
         AND y.stream = c.stream
         AND y.academic_year = session_year
        WHERE c.course_id = for_course
          AND (
              (c.course_type IN ('MAJOR', 'MAJOR_PRACTICALS', 'DSE', 'DSE_PRACTICALS')
                   AND y.major_dept = c.offering_department)
           OR (c.course_type IN ('MINOR', 'MINOR_PRACTICALS')
                   AND (y.minor_dept = c.offering_department
                     OR y.major_dept = c.offering_department))
           OR (c.course_type = 'OE' AND y.oe_dept = c.offering_department)
           OR  c.course_type IN ('AEC', 'IKS', 'VSC', 'VSC_PRACTICALS',
                                 'SEC', 'SEC_PRACTICALS'));
    END;

COMMENT ON FUNCTION roll_for_course(bigint, integer) IS
    'Every student taking a course in a session. Translated from DaoStudent.getRollForCourseSync; a course type missing from every branch matches nobody, so any addition to CourseType has to be accounted for here.';


-- ── attendance_delegate ──────────────────────────────────────────────

CREATE TABLE attendance_delegate (
    student_id      bigint  NOT NULL,
    course_id       bigint  NOT NULL,
    academic_year   integer NOT NULL,

    -- Who designated, and in what capacity. An account and not a
    -- prof_id, because an admin need not be a professor.
    granted_by      bigint          NOT NULL,
    granted_as      grant_authority NOT NULL,
    granted_at      timestamptz     NOT NULL DEFAULT now(),
    expires_at      timestamptz,

    -- Whoever pulled it, which may be a head of department or an admin
    -- rather than the professor who granted it. A grant has to be
    -- revocable when the person who made it is on leave, has left, or is
    -- the problem.
    revoked_at      timestamptz,
    revoked_by      bigint,
    reason          text,

    PRIMARY KEY (student_id, course_id, academic_year),

    CONSTRAINT attendance_delegate_revocation_complete CHECK (
        (revoked_at IS NULL) = (revoked_by IS NULL)),
    CONSTRAINT attendance_delegate_expiry_after_grant CHECK (
        expires_at IS NULL OR expires_at > granted_at),

    CONSTRAINT attendance_delegate_student_id_fkey FOREIGN KEY (student_id)
        REFERENCES students (student_id) ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT attendance_delegate_course_id_fkey FOREIGN KEY (course_id)
        REFERENCES courses (course_id) ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT attendance_delegate_academic_year_fkey FOREIGN KEY (academic_year)
        REFERENCES academic_years (academic_year) ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT attendance_delegate_granted_by_fkey FOREIGN KEY (granted_by)
        REFERENCES app_account (account_id) ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT attendance_delegate_revoked_by_fkey FOREIGN KEY (revoked_by)
        REFERENCES app_account (account_id) ON UPDATE CASCADE ON DELETE RESTRICT
);

-- What the policy looks up on every attendance statement a CR makes:
-- the live grants for one course and session. Partial, so revoked
-- history costs it nothing.
CREATE INDEX attendance_delegate_live_idx
    ON attendance_delegate (course_id, academic_year, student_id)
    WHERE revoked_at IS NULL;

-- And "what is this student a CR of", for their own home screen.
CREATE INDEX attendance_delegate_student_idx
    ON attendance_delegate (student_id, academic_year)
    WHERE revoked_at IS NULL;

COMMENT ON TABLE attendance_delegate IS
    'One row per CR per course per session. Never deleted: revoking sets revoked_at, because "who was allowed to do this in March" is the question a dispute asks.';


-- ── The two rules the table cannot state ─────────────────────────────
--
-- That the designator held the authority they claimed, and that the
-- student is on the course's roll at all. Neither fits in a CHECK: the
-- first is a disjunction over three different joins, the second needs
-- roll_for_course.
--
-- Had only a course's own professors been able to designate, the first
-- would have been a composite foreign key onto course_instructor, which
-- is keyed on exactly (course_id, prof_id). With three authorities it is
-- not, and a constraint covering only the INSTRUCTOR branch would read
-- as a guarantee while being a third of one. So: one trigger, all three
-- branches, no foreign key pretending to be the whole rule.

CREATE FUNCTION attendance_delegate_authorised() RETURNS trigger
    LANGUAGE plpgsql AS $fn$
DECLARE
    allowed boolean;
BEGIN
    SELECT CASE NEW.granted_as

        -- Teaches this course. course_instructor always contains the
        -- coordinator, so this never misses one.
        WHEN 'INSTRUCTOR' THEN EXISTS (
            SELECT 1
            FROM app_account a
            JOIN course_instructor ci ON ci.prof_id = a.prof_id
            WHERE a.account_id = NEW.granted_by
              AND a.kind       = 'PROFESSOR'
              AND ci.course_id = NEW.course_id)

        -- Head of the department that OFFERS the course, for the session
        -- the grant is for. Both halves matter: a head of Physics may
        -- not designate for a Chemistry course, and a 2025-26 head may
        -- not designate for 2027-28.
        WHEN 'HOD' THEN EXISTS (
            SELECT 1
            FROM user_role ur
            JOIN courses c ON c.course_id = NEW.course_id
            WHERE ur.account_id    = NEW.granted_by
              AND ur.role          = 'HOD'
              AND ur.revoked_at IS NULL
              AND ur.department    = c.offering_department
              AND ur.academic_year = NEW.academic_year)

        WHEN 'ADMIN' THEN EXISTS (
            SELECT 1
            FROM user_role ur
            WHERE ur.account_id = NEW.granted_by
              AND ur.role       = 'ADMIN'
              AND ur.revoked_at IS NULL)
    END INTO allowed;

    -- COALESCE, not a bare NOT: an authority no branch above handles
    -- leaves allowed NULL, and "IF NOT NULL" is not true, so the check
    -- would have been skipped rather than failed. Fail closed.
    IF NOT COALESCE(allowed, false) THEN
        RAISE EXCEPTION
            'account % cannot designate a CR for course % as %',
            NEW.granted_by, NEW.course_id, NEW.granted_as
            USING ERRCODE = 'check_violation';
    END IF;

    -- Whatever the authority, the CR has to be on the roll. A CR of
    -- CS-101 who does not take CS-101 is a mistake, not a
    -- configuration.
    IF NOT EXISTS (
        SELECT 1 FROM roll_for_course(NEW.course_id, NEW.academic_year) r
        WHERE r.student_id = NEW.student_id)
    THEN
        RAISE EXCEPTION
            'student % is not on the roll of course % in %',
            NEW.student_id, NEW.course_id, NEW.academic_year
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END $fn$;

-- On UPDATE too, not only INSERT: moving a grant to another course or
-- another session is the same claim made again, and has to be checked
-- again.
CREATE TRIGGER attendance_delegate_authorised_trg
    BEFORE INSERT OR UPDATE OF student_id, course_id, academic_year, granted_by, granted_as
    ON attendance_delegate
    FOR EACH ROW EXECUTE FUNCTION attendance_delegate_authorised();

COMMENT ON FUNCTION attendance_delegate_authorised() IS
    'Both rules attendance_delegate cannot state declaratively: that the designator held the claimed authority, and that the CR is on the course roll.';
