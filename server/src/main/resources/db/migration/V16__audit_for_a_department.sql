-- Who may read which audit entries.
--
-- V14 said: an auditor or an administrator, and nobody else. That is right for
-- the whole trail -- it holds failed sign-ins with the addresses that were
-- tried, password rotations, every role grant in the college -- and it is
-- wrong as the only answer, because the question a professor actually asks is
-- about their own teaching: what did my class representative do to my
-- register, and when did I change that mark myself.
--
-- So the trail becomes readable in three widths, each one being the width of
-- the authority held:
--
--   * An auditor or an administrator reads all of it, as before.
--
--   * A head of department reads the entries their own department caused:
--     their own, and those of the professors in it. Not year-scoped, unlike
--     the HOD grant itself, because an audit entry does not belong to a
--     session -- "who recorded this" is a fact about an afternoon, and asking
--     which academic year an afternoon fell in to decide whether a head may
--     read it would refuse the record of the term they were head for.
--
--   * A professor reads what they did, and what was done under a delegation
--     on one of their own courses. That second half is the whole point: a CR
--     marks a register, the professor is accountable for the course, and
--     "everything a CR did under a grant" is the query audit_log_delegate_idx
--     was built for in V7.
--
-- Everything else stays invisible: an entry with no actor (imported history),
-- a failed sign-in, an administrative action by somebody outside the
-- department. A professor asking for the trail now gets their own slice of it
-- rather than a 403, and nobody gets a row because they asked nicely.

-- actor_heads(department) is V11's, and already means exactly what is wanted
-- here: a live HOD grant for that department, in any session. Flyway refused
-- this migration's first draft for defining it a second time, which is the
-- useful kind of refusal -- V11 wrote it for the roster policies and the
-- reason it is not year-scoped there is the reason it is not here.

-- The department a professor's account belongs to, or null for an account
-- that is not a professor's. Definer, because app_account and professors are
-- not the caller's to read row by row.
CREATE FUNCTION account_department(of_account bigint) RETURNS department
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN (
        SELECT p.department
        FROM app_account a
        JOIN professors p ON p.prof_id = a.prof_id
        WHERE a.account_id = of_account
          AND a.kind       = 'PROFESSOR');

CREATE FUNCTION audit_entry_visible(entry_actor bigint, entry_delegate bigint)
    RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN
        -- The whole trail, for the two roles it is kept for.
        actor_has_role('AUDITOR') OR actor_has_role('ADMIN')
        -- Your own hand, always. An actor reading what they themselves did
        -- discloses nothing they were not already party to.
        OR (entry_actor IS NOT NULL AND entry_actor = current_account_id())
        -- A delegation on a course you teach: what your CR did.
        OR (entry_delegate IS NOT NULL AND actor_instructs(entry_delegate))
        -- Your department's doing, if you are its head.
        OR (entry_actor IS NOT NULL
            AND account_department(entry_actor) IS NOT NULL
            AND actor_heads(account_department(entry_actor)));

COMMENT ON FUNCTION audit_entry_visible(bigint, bigint) IS
    'Whether the current actor may read one audit entry: all of it for an auditor or admin, their own and their CRs for a professor, the department''s for its head.';

-- Whether there is any point offering the trail at all, which is what the
-- console asks before showing the tab. Not the same question as "may you read
-- this entry": a professor with no courses and no grants can read nothing, and
-- a tab that opens on an empty table is a worse answer than no tab.
CREATE OR REPLACE FUNCTION audit_readable() RETURNS boolean
    LANGUAGE sql STABLE
    SET search_path = pg_catalog, public
    RETURN actor_has_role('AUDITOR')
        OR actor_has_role('ADMIN')
        OR current_account_id() IS NOT NULL;

COMMENT ON FUNCTION audit_readable() IS
    'Whether the current actor may ask for the audit trail at all. What they are then shown is audit_entry_visible''s decision, entry by entry.';

-- audit_recent gains the per-entry filter. Replaced whole rather than altered:
-- a function body is not a migration-friendly thing to patch, and the version
-- that was here is in V14 for anybody reading the history.
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
      AND audit_entry_visible(a.actor_account, a.delegate_course)
    ORDER BY a.occurred_at DESC, a.audit_id DESC
    LIMIT least(greatest(coalesce(want_limit, 50), 1), 500);

END $fn$;

-- And the count, which has to be the count of what the asker may see or the
-- page would say "3 of 42468" to somebody who can reach three.
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
    WHERE audit_entry_visible(a.actor_account, a.delegate_course);

    RETURN total;

END $fn$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_app') THEN
        GRANT EXECUTE ON FUNCTION account_department(bigint) TO sies_app;
        GRANT EXECUTE ON FUNCTION audit_entry_visible(bigint, bigint) TO sies_app;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_auditor') THEN
        GRANT EXECUTE ON FUNCTION account_department(bigint) TO sies_auditor;
        GRANT EXECUTE ON FUNCTION audit_entry_visible(bigint, bigint) TO sies_auditor;
    END IF;
END $$;
