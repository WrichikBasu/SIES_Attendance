-- =====================================================================
-- V6 — Who may see and change which rows.
--
-- Additive. No table or column from V1 to V5 is altered; this file adds
-- functions and policies only.
--
--
-- THE ACTOR
--
-- Three settings, all written on every transaction from one lookup of
-- the authenticated account, before the first statement. Exactly one of
-- app.prof_id and app.student_id holds a number; the other holds an
-- empty string. They are all set always, so that "which of these is
-- populated" is never a question a policy has to ask.
--
-- set_config(..., true) is transaction-scoped, so a pooled connection
-- cannot carry one person's identity into the next request.
--
--
-- WHY THE POLICIES NAME NO ROLE
--
-- The obvious form is "FOR ALL TO sies_app", and it is wrong here: it
-- would make the migration fail on any database where tools/roles.sql
-- has not run, and tools/verify-schema.sh deliberately applies these
-- migrations to a bare container with no roles in it. Unqualified
-- policies apply to every role that is not a superuser and not the
-- table's owner, which is precisely the set that must be constrained.
--
--
-- WHAT IS FORCED AND WHAT IS ONLY ENABLED
--
-- A table's owner bypasses its own policies unless the table is FORCEd,
-- and a superuser bypasses them regardless.
--
--   * attendance and lectures are ENABLED and FORCED. They are what the
--     phase gate is about, and nothing -- not even the role that ran the
--     migration -- should read them unfiltered.
--
--   * user_role and attendance_delegate are ENABLED and NOT forced,
--     deliberately. The helper functions below are SECURITY DEFINER and
--     run as the owner, which is how a policy on attendance can ask
--     "does this account hold a live grant" without the asking being
--     subject to the very policies it is evaluating. Forcing these two
--     would make a definer function see only its caller's rows, and the
--     answer would be wrong rather than merely slow.
--
--   * app_account has NO row-level security at all. Authentication
--     happens before there is an actor, so a policy keyed on the current
--     account would deny the one lookup that establishes it. Routing
--     that through a definer function changes nothing, because the
--     password hash has to come back either way. What protects
--     app_account is privilege and the service's own code, and saying so
--     is better than a policy that looks like protection.
--
--
-- NOT HERE YET: courses AND student_year
--
-- Both need read policies and neither has one, which means both are
-- readable by any authenticated connection. That is a real gap and it is
-- left open on purpose rather than filled badly.
--
-- The reason is that "which students may this professor see" is not a
-- department comparison. A minor or an open elective puts a student from
-- another department on the roll -- that is the whole point of V4 -- so
-- the correct policy is "students reachable through roll_for_course over
-- the courses I instruct", which is a set-returning function per row and
-- will not survive a roster screen without a materialised reachability
-- table. Designing that is its own piece of work; guessing at it would
-- either leak the college's roster or break the roll.
-- =====================================================================


-- ── The actor, as three functions ────────────────────────────────────
--
-- Functions rather than current_setting() inline, because the empty
-- string has to become NULL and getting that wrong is silent. A bare
-- current_setting('app.prof_id') without the second argument RAISES when
-- the setting is absent, so a student's transaction would error out of a
-- professor's policy instead of failing to match it.

CREATE FUNCTION current_account_id() RETURNS bigint
    LANGUAGE sql STABLE PARALLEL SAFE
    RETURN nullif(current_setting('app.account_id', true), '')::bigint;

CREATE FUNCTION current_prof_id() RETURNS bigint
    LANGUAGE sql STABLE PARALLEL SAFE
    RETURN nullif(current_setting('app.prof_id', true), '')::bigint;

CREATE FUNCTION current_student_id() RETURNS bigint
    LANGUAGE sql STABLE PARALLEL SAFE
    RETURN nullif(current_setting('app.student_id', true), '')::bigint;

COMMENT ON FUNCTION current_account_id() IS
    'The authenticated account for this transaction, or NULL when none was set. NULL matches no policy, so an unauthenticated connection sees nothing.';


-- ── What the actor is ────────────────────────────────────────────────
--
-- SECURITY DEFINER, so that a policy on attendance can ask about
-- user_role and attendance_delegate without being subject to their
-- policies while it does.
--
-- SET search_path on every one of them. Without it a caller could create
-- a schema earlier in their own search_path, put a user_role table in
-- it, and have these functions answer from that instead -- the standard
-- definer-function hole, and the reason this is not optional.

CREATE FUNCTION actor_has_role(wanted app_role) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN EXISTS (
        SELECT 1 FROM user_role ur
        WHERE ur.account_id = current_account_id()
          AND ur.role       = wanted
          AND ur.revoked_at IS NULL);

CREATE FUNCTION actor_is_hod_of(dept department, session_year integer) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN EXISTS (
        SELECT 1 FROM user_role ur
        WHERE ur.account_id    = current_account_id()
          AND ur.role          = 'HOD'
          AND ur.revoked_at   IS NULL
          AND ur.department    = dept
          AND ur.academic_year = session_year);

CREATE FUNCTION actor_instructs(for_course bigint) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN EXISTS (
        SELECT 1 FROM course_instructor ci
        WHERE ci.course_id = for_course
          AND ci.prof_id   = current_prof_id());

-- The CR test. Three conditions and all three matter: a live grant, so
-- revoking takes effect on the next statement rather than the next
-- login; an unexpired one; and the grant's session equal to the
-- LECTURE'S session, so last year's CR cannot touch this year's
-- register even if nobody ever revoked them.
CREATE FUNCTION actor_is_cr_of(for_course bigint, session_year integer) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN EXISTS (
        SELECT 1 FROM attendance_delegate d
        WHERE d.course_id     = for_course
          AND d.academic_year = session_year
          AND d.student_id    = current_student_id()
          AND d.revoked_at   IS NULL
          AND (d.expires_at IS NULL OR d.expires_at > now()));

COMMENT ON FUNCTION actor_is_cr_of(bigint, integer) IS
    'Whether the current actor holds a live, unexpired CR grant for this course in this session. SECURITY DEFINER so a policy can ask it without attendance_delegate''s own policies applying to the question.';


-- ── lectures ─────────────────────────────────────────────────────────

ALTER TABLE lectures ENABLE ROW LEVEL SECURITY;
ALTER TABLE lectures FORCE  ROW LEVEL SECURITY;

-- A professor who teaches the course. course_instructor always holds the
-- coordinator, so this never misses one and a team-taught course needs
-- no special case.
CREATE POLICY lectures_instructor ON lectures
    FOR ALL
    USING      (actor_instructs(course_id))
    WITH CHECK (actor_instructs(course_id));

-- The head of the offering department, for the lecture's own session.
CREATE POLICY lectures_hod ON lectures
    FOR ALL
    USING (EXISTS (
        SELECT 1 FROM courses c
        WHERE c.course_id = lectures.course_id
          AND actor_is_hod_of(c.offering_department,
                              session_year_of(lectures.start_at, lectures.start_zone))))
    WITH CHECK (EXISTS (
        SELECT 1 FROM courses c
        WHERE c.course_id = lectures.course_id
          AND actor_is_hod_of(c.offering_department,
                              session_year_of(lectures.start_at, lectures.start_zone))));

-- A CR may read and create the lectures of a course they hold a grant
-- for, and amend one they created the details of -- but NOT delete one.
-- Deleting a lecture destroys its attendance, and that is not what
-- "take attendance on behalf of the professor" means. Three narrow
-- policies rather than one FOR ALL, because FOR ALL would include
-- DELETE.
CREATE POLICY lectures_delegate_select ON lectures
    FOR SELECT
    USING (actor_is_cr_of(course_id, session_year_of(start_at, start_zone)));

CREATE POLICY lectures_delegate_insert ON lectures
    FOR INSERT
    WITH CHECK (actor_is_cr_of(course_id, session_year_of(start_at, start_zone)));

CREATE POLICY lectures_delegate_update ON lectures
    FOR UPDATE
    USING      (actor_is_cr_of(course_id, session_year_of(start_at, start_zone)))
    WITH CHECK (actor_is_cr_of(course_id, session_year_of(start_at, start_zone)));

-- Office and auditor read everything and change nothing.
CREATE POLICY lectures_readers ON lectures
    FOR SELECT
    USING (actor_has_role('OFFICE') OR actor_has_role('AUDITOR'));

CREATE POLICY lectures_admin ON lectures
    FOR ALL
    USING      (actor_has_role('ADMIN'))
    WITH CHECK (actor_has_role('ADMIN'));


-- ── attendance ───────────────────────────────────────────────────────
--
-- Reached through its lecture every time, because attendance carries no
-- course of its own. Every policy here is therefore an EXISTS onto
-- lectures -- and note that the sub-select is NOT itself filtered by
-- lectures' policies: a policy's own query runs without RLS applied to
-- it, which is what stops the two tables' policies from having to agree
-- about visibility before either can be evaluated.

ALTER TABLE attendance ENABLE ROW LEVEL SECURITY;
ALTER TABLE attendance FORCE  ROW LEVEL SECURITY;

CREATE POLICY attendance_instructor ON attendance
    FOR ALL
    USING (EXISTS (
        SELECT 1 FROM lectures l
        WHERE l.lecture_id = attendance.lecture_id
          AND actor_instructs(l.course_id)))
    WITH CHECK (EXISTS (
        SELECT 1 FROM lectures l
        WHERE l.lecture_id = attendance.lecture_id
          AND actor_instructs(l.course_id)));

CREATE POLICY attendance_hod ON attendance
    FOR ALL
    USING (EXISTS (
        SELECT 1 FROM lectures l
        JOIN courses c ON c.course_id = l.course_id
        WHERE l.lecture_id = attendance.lecture_id
          AND actor_is_hod_of(c.offering_department,
                              session_year_of(l.start_at, l.start_zone))))
    WITH CHECK (EXISTS (
        SELECT 1 FROM lectures l
        JOIN courses c ON c.course_id = l.course_id
        WHERE l.lecture_id = attendance.lecture_id
          AND actor_is_hod_of(c.offering_department,
                              session_year_of(l.start_at, l.start_zone))));

-- The CR, which is what this phase exists for. Marking a register is an
-- INSERT and correcting it is an UPDATE; a row is never deleted, for the
-- same reason a lecture is not.
--
-- Note what is NOT here: no condition excluding the actor's own
-- attendance row. A CR may mark themselves, deliberately -- a decision
-- taken with the alternative in hand -- which makes the audit log the
-- only control over it and is why audit.actor must reach students at
-- all.
CREATE POLICY attendance_delegate_select ON attendance
    FOR SELECT
    USING (EXISTS (
        SELECT 1 FROM lectures l
        WHERE l.lecture_id = attendance.lecture_id
          AND actor_is_cr_of(l.course_id,
                             session_year_of(l.start_at, l.start_zone))));

CREATE POLICY attendance_delegate_insert ON attendance
    FOR INSERT
    WITH CHECK (EXISTS (
        SELECT 1 FROM lectures l
        WHERE l.lecture_id = attendance.lecture_id
          AND actor_is_cr_of(l.course_id,
                             session_year_of(l.start_at, l.start_zone))));

CREATE POLICY attendance_delegate_update ON attendance
    FOR UPDATE
    USING (EXISTS (
        SELECT 1 FROM lectures l
        WHERE l.lecture_id = attendance.lecture_id
          AND actor_is_cr_of(l.course_id,
                             session_year_of(l.start_at, l.start_zone))))
    WITH CHECK (EXISTS (
        SELECT 1 FROM lectures l
        WHERE l.lecture_id = attendance.lecture_id
          AND actor_is_cr_of(l.course_id,
                             session_year_of(l.start_at, l.start_zone))));

CREATE POLICY attendance_readers ON attendance
    FOR SELECT
    USING (actor_has_role('OFFICE') OR actor_has_role('AUDITOR'));

CREATE POLICY attendance_admin ON attendance
    FOR ALL
    USING      (actor_has_role('ADMIN'))
    WITH CHECK (actor_has_role('ADMIN'));


-- ── attendance_delegate ──────────────────────────────────────────────
--
-- ENABLED, not FORCED: see the header. The trigger from V5 still checks
-- that the designator held the authority they claimed; these policies
-- decide who may attempt it at all, which is a different question. Both
-- are wanted -- the trigger stops a wrong claim, the policy stops a
-- stranger making one.

ALTER TABLE attendance_delegate ENABLE ROW LEVEL SECURITY;

-- A CR sees their own grants, and nothing else. This is the one policy a
-- student account matches on this table.
CREATE POLICY attendance_delegate_own ON attendance_delegate
    FOR SELECT
    USING (student_id = current_student_id());

CREATE POLICY attendance_delegate_instructor ON attendance_delegate
    FOR SELECT USING (actor_instructs(course_id));

CREATE POLICY attendance_delegate_instructor_grant ON attendance_delegate
    FOR INSERT WITH CHECK (actor_instructs(course_id));

CREATE POLICY attendance_delegate_instructor_revoke ON attendance_delegate
    FOR UPDATE
    USING      (actor_instructs(course_id))
    WITH CHECK (actor_instructs(course_id));

-- The head of the offering department, for the grant's own session.
-- Three policies and not one FOR ALL, for the reason below.
CREATE POLICY attendance_delegate_hod_select ON attendance_delegate
    FOR SELECT
    USING (EXISTS (
        SELECT 1 FROM courses c
        WHERE c.course_id = attendance_delegate.course_id
          AND actor_is_hod_of(c.offering_department,
                              attendance_delegate.academic_year)));

CREATE POLICY attendance_delegate_hod_grant ON attendance_delegate
    FOR INSERT
    WITH CHECK (EXISTS (
        SELECT 1 FROM courses c
        WHERE c.course_id = attendance_delegate.course_id
          AND actor_is_hod_of(c.offering_department,
                              attendance_delegate.academic_year)));

CREATE POLICY attendance_delegate_hod_revoke ON attendance_delegate
    FOR UPDATE
    USING (EXISTS (
        SELECT 1 FROM courses c
        WHERE c.course_id = attendance_delegate.course_id
          AND actor_is_hod_of(c.offering_department,
                              attendance_delegate.academic_year)))
    WITH CHECK (EXISTS (
        SELECT 1 FROM courses c
        WHERE c.course_id = attendance_delegate.course_id
          AND actor_is_hod_of(c.offering_department,
                              attendance_delegate.academic_year)));

CREATE POLICY attendance_delegate_readers ON attendance_delegate
    FOR SELECT
    USING (actor_has_role('OFFICE') OR actor_has_role('AUDITOR'));

CREATE POLICY attendance_delegate_admin_select ON attendance_delegate
    FOR SELECT USING (actor_has_role('ADMIN'));

CREATE POLICY attendance_delegate_admin_grant ON attendance_delegate
    FOR INSERT WITH CHECK (actor_has_role('ADMIN'));

CREATE POLICY attendance_delegate_admin_revoke ON attendance_delegate
    FOR UPDATE
    USING      (actor_has_role('ADMIN'))
    WITH CHECK (actor_has_role('ADMIN'));

-- NO DELETE POLICY, for anybody, including an admin. A grant is never
-- deleted: revoking sets revoked_at, because "who was allowed to do this
-- in March" is the question a dispute asks and a deleted row cannot
-- answer it. An absent policy denies, so this is enforced and not merely
-- intended.
--
-- This is why every policy above names its verbs instead of saying FOR
-- ALL. FOR ALL includes DELETE, so the first draft of this file handed
-- it to the HOD and the admin while the comment here claimed otherwise.
-- RowLevelSecurityTest.aGrantIsNeverDeleted is what caught it.


-- ── user_role ────────────────────────────────────────────────────────
--
-- ENABLED, not FORCED, for the same reason as attendance_delegate: the
-- definer functions above read it.

ALTER TABLE user_role ENABLE ROW LEVEL SECURITY;

-- Everybody may see what they themselves hold. Nothing else reads this
-- table directly; the policies elsewhere go through actor_has_role().
CREATE POLICY user_role_own ON user_role
    FOR SELECT
    USING (account_id = current_account_id());

CREATE POLICY user_role_readers ON user_role
    FOR SELECT
    USING (actor_has_role('AUDITOR'));

-- Granting and revoking a role is the admin's alone, which is the one
-- line of the permission matrix with a single column in it. Verbs spelled
-- out, and no DELETE: a role is revoked exactly as a grant is, and for
-- the same reason.
CREATE POLICY user_role_admin_select ON user_role
    FOR SELECT USING (actor_has_role('ADMIN'));

CREATE POLICY user_role_admin_grant ON user_role
    FOR INSERT WITH CHECK (actor_has_role('ADMIN'));

CREATE POLICY user_role_admin_revoke ON user_role
    FOR UPDATE
    USING      (actor_has_role('ADMIN'))
    WITH CHECK (actor_has_role('ADMIN'));
