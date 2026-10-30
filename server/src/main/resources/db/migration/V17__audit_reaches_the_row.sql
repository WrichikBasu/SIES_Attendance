-- The half of V16 that could never fire.
--
-- V16 let a professor read "what was done under a delegation on one of my
-- courses" by testing audit_log.delegate_course. The column is the right idea
-- and V7 built an index for it. It is also, in this database,
--
--   delegate_course set on: 0 of 13756 row entries
--
-- because ActorSession only carries it for a caller that builds an actor by
-- hand, and the sync path -- which is how every mark a CR makes arrives --
-- announces the request's actor instead. So the branch was dead code, and a
-- professor asking what their class representative did to their register got
-- their own entries and nothing else.
--
-- Rather than teach the push to stamp a course onto each entry -- one push
-- covers several courses, and the column holds one -- the predicate asks the
-- question the professor is actually asking: is this entry ABOUT a lecture or
-- a mark of a course I teach? That is answerable from what the trigger already
-- records, because row_key carries the lecture:
--
--   attendance | {"lecture_id": 370919062403809287, "student_id": 3706669656…}
--
-- and a lecture names its course. Whoever made the entry, a professor may read
-- it if it happened to their teaching. That is a wider rule than "what my CR
-- did" and the right one: an edit to my register by the office, by a head of
-- department or by another instructor is exactly as much my business as my
-- CR's edit is.
--
-- V16's delegate_course branch is kept rather than dropped. It is precise where
-- it is set, it costs nothing where it is not, and a later path that does carry
-- the course should not have to add it back.

-- Defined before the function that calls it: a SQL-bodied function (RETURN rather
-- than a plpgsql block) is parsed and resolved when it is created, so a forward
-- reference fails with "function does not exist" at migration time rather than at
-- call time. plpgsql would have let it through and failed later, which would have
-- been worse.
CREATE FUNCTION audit_lecture_is_mine(entry_key jsonb) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN EXISTS (
        SELECT 1
        FROM lectures l
        JOIN courses c ON c.course_id = l.course_id
        WHERE l.lecture_id = (entry_key ->> 'lecture_id')::bigint
          AND (actor_instructs(l.course_id)
               OR actor_heads(c.offering_department)));

CREATE FUNCTION audit_row_is_mine(entry_table text, entry_key jsonb) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN
        entry_table IS NOT NULL
        AND entry_key IS NOT NULL
        AND CASE entry_table

            -- Both carry the lecture, which is what names the course.
            WHEN 'attendance' THEN audit_lecture_is_mine(entry_key)
            WHEN 'lectures'   THEN audit_lecture_is_mine(entry_key)

            -- A course itself: renamed, discontinued, its coordinator changed.
            WHEN 'courses' THEN EXISTS (
                SELECT 1 FROM courses c
                WHERE c.course_id = (entry_key ->> 'course_id')::bigint
                  AND (actor_instructs(c.course_id)
                       OR actor_heads(c.offering_department)))

            -- Everything else is the office's or the college's: a student's
            -- enrolment, a batch, a professors row, a session. A professor
            -- reads those rows through the policies on them and does not need
            -- the record of who typed them.
            ELSE false
        END;

COMMENT ON FUNCTION audit_row_is_mine(text, jsonb) IS
    'Whether an audit entry is about a lecture, a mark or a course the current actor teaches or heads. Answered from row_key, because delegate_course is not set on the path a CR''s marks actually arrive by.';

CREATE OR REPLACE FUNCTION audit_entry_visible(entry_actor bigint, entry_delegate bigint,
                                               entry_table text DEFAULT NULL,
                                               entry_key jsonb DEFAULT NULL)
    RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN
        actor_has_role('AUDITOR') OR actor_has_role('ADMIN')
        OR (entry_actor IS NOT NULL AND entry_actor = current_account_id())
        OR (entry_delegate IS NOT NULL AND actor_instructs(entry_delegate))
        OR (entry_actor IS NOT NULL
            AND account_department(entry_actor) IS NOT NULL
            AND actor_heads(account_department(entry_actor)))
        -- V17: the entry is about my teaching, whoever made it.
        OR audit_row_is_mine(entry_table, entry_key);

CREATE OR REPLACE FUNCTION audit_recent(want_limit integer,
                                        before_at timestamptz DEFAULT NULL,
                                        before_id bigint DEFAULT NULL)
    RETURNS TABLE (
        audit_id        bigint,
        occurred_at     timestamptz,
        actor_account   bigint,
        actor_label     text,
        action          text,
        table_name      text,
        row_key         text,
        session_id      uuid,
        device_node     integer,
        client_ip       text,
        app_version     text,
        before_image    text,
        after_image     text,
        detail          text)
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $fn$
BEGIN

    IF NOT audit_readable() THEN
        RAISE EXCEPTION 'the audit trail is read by somebody who is signed in'
            USING ERRCODE = 'insufficient_privilege';
    END IF;

    RETURN QUERY
    SELECT a.audit_id,
           a.occurred_at,
           a.actor_account,
           a.actor_label,
           a.action::text,
           a.table_name,
           a.row_key::text,
           a.session_id,
           a.device_node,
           host(a.client_ip),
           a.app_version,
           a.before_image::text,
           a.after_image::text,
           a.detail::text
    FROM audit_log a
    WHERE (before_at IS NULL
           OR (a.occurred_at, a.audit_id) < (before_at, coalesce(before_id, 9223372036854775807)))
      AND audit_entry_visible(a.actor_account, a.delegate_course, a.table_name, a.row_key)
    ORDER BY a.occurred_at DESC, a.audit_id DESC
    LIMIT least(greatest(coalesce(want_limit, 50), 1), 500);

END $fn$;

CREATE OR REPLACE FUNCTION audit_size() RETURNS bigint
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $fn$
DECLARE
    total bigint;
BEGIN

    IF NOT audit_readable() THEN
        RAISE EXCEPTION 'the audit trail is read by somebody who is signed in'
            USING ERRCODE = 'insufficient_privilege';
    END IF;

    SELECT count(*) INTO total
    FROM audit_log a
    WHERE audit_entry_visible(a.actor_account, a.delegate_course, a.table_name, a.row_key);

    RETURN total;

END $fn$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_app') THEN
        GRANT EXECUTE ON FUNCTION audit_row_is_mine(text, jsonb) TO sies_app;
        GRANT EXECUTE ON FUNCTION audit_lecture_is_mine(jsonb) TO sies_app;
        GRANT EXECUTE ON FUNCTION audit_entry_visible(bigint, bigint, text, jsonb) TO sies_app;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_auditor') THEN
        GRANT EXECUTE ON FUNCTION audit_row_is_mine(text, jsonb) TO sies_auditor;
        GRANT EXECUTE ON FUNCTION audit_lecture_is_mine(jsonb) TO sies_auditor;
        GRANT EXECUTE ON FUNCTION audit_entry_visible(bigint, bigint, text, jsonb)
            TO sies_auditor;
    END IF;
END $$;
