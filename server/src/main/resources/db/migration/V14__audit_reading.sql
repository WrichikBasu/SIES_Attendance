-- Reading the audit trail from the service, which until now it could not do
-- at all.
--
-- sies_app holds no privilege on audit_log -- not even SELECT. That is
-- deliberate and is what makes the trail real: the role that writes the
-- attendance cannot read, rewrite or delete the record of having written it.
-- The rows get there through audit_row() and audit_event(), both SECURITY
-- DEFINER, and nothing but sies_auditor can read them.
--
-- So a screen that shows the trail needs a definer function of its own, and
-- the question is what it checks. It checks the actor's role, not the
-- connection's: the service connects as one role for everybody, and "who may
-- read the audit log" is a property of the person, which is exactly the
-- distinction every policy in this schema is built on.
--
-- Why a function and not a GRANT: a grant to sies_app would let any
-- authenticated request read every audit row, with the filtering left to
-- application code. One mistake in a WHERE clause would then be a disclosure
-- of every mark in the college and who recorded it. Here the filter cannot be
-- forgotten, because there is nowhere else to put it.

CREATE FUNCTION audit_readable() RETURNS boolean
    LANGUAGE sql STABLE
    SET search_path = pg_catalog, public
    RETURN actor_has_role('AUDITOR') OR actor_has_role('ADMIN');

COMMENT ON FUNCTION audit_readable() IS
    'Whether the current actor may read the audit trail: an auditor or an administrator, and nobody else.';

-- The page reads newest first and asks for the next page with the last row it
-- was given, so the cursor is (occurred_at, audit_id) compared row-wise. Both
-- halves are needed: clock_timestamp() can repeat within one statement, and a
-- cursor on the timestamp alone would either skip or repeat the rows that
-- share one.
CREATE FUNCTION audit_recent(want_limit integer,
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

    -- Refused loudly rather than answered with nothing. An empty result would
    -- be indistinguishable from an empty log, which is the mistake that cost
    -- this project a day in Phase 5.5: a filtered-away row reads as "there is
    -- nothing there" and sends somebody looking for the wrong bug.
    IF NOT audit_readable() THEN
        RAISE EXCEPTION 'the audit trail is readable by an auditor or an administrator'
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
    WHERE before_at IS NULL
       OR (a.occurred_at, a.audit_id) < (before_at, coalesce(before_id, 9223372036854775807))
    ORDER BY a.occurred_at DESC, a.audit_id DESC
    LIMIT least(greatest(coalesce(want_limit, 50), 1), 500);

END $fn$;

COMMENT ON FUNCTION audit_recent(integer, timestamptz, bigint) IS
    'One page of the audit trail, newest first, for an auditor or an administrator. Refuses with insufficient_privilege for anybody else rather than answering with nothing.';

-- Counted separately, because "how much is there" is the one thing a page
-- cannot work out from a page of rows, and a count over a partitioned table
-- of this shape is cheap enough for a screen that asks once.
CREATE FUNCTION audit_size() RETURNS bigint
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $fn$
DECLARE
    total bigint;
BEGIN

    IF NOT audit_readable() THEN
        RAISE EXCEPTION 'the audit trail is readable by an auditor or an administrator'
            USING ERRCODE = 'insufficient_privilege';
    END IF;

    SELECT count(*) INTO total FROM audit_log;
    RETURN total;

END $fn$;

-- Conditional, as every grant in these migrations is: the roles are created by
-- tools/roles.sql outside Flyway, and a throwaway database that applies the
-- migrations without them must still apply this one.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_app') THEN
        GRANT EXECUTE ON FUNCTION audit_readable() TO sies_app;
        GRANT EXECUTE ON FUNCTION audit_recent(integer, timestamptz, bigint) TO sies_app;
        GRANT EXECUTE ON FUNCTION audit_size() TO sies_app;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_auditor') THEN
        GRANT EXECUTE ON FUNCTION audit_readable() TO sies_auditor;
        GRANT EXECUTE ON FUNCTION audit_recent(integer, timestamptz, bigint) TO sies_auditor;
        GRANT EXECUTE ON FUNCTION audit_size() TO sies_auditor;
    END IF;
END $$;
