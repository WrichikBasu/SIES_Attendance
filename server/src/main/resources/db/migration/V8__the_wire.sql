-- =====================================================================
-- V8 — What a request has to carry, and what each caller may export.
--
-- Additive. Nothing from V1 to V7 is altered.
--
-- Phases 4 and 5 built an identity model and an audit trail that only a
-- test or a command-line tool could reach: the actor every policy reads
-- was set by hand, because nothing spoke HTTP. This migration is the
-- database half of closing that -- the session a bearer token names, the
-- device a session is bound to, and the one authority question an
-- endpoint has to ask that no policy can answer for it.
--
--
-- WHY A SESSION TABLE AND NOT A SIGNED TOKEN
--
-- A self-contained signed token (a JWT, or anything shaped like one) is
-- a claim the server cannot take back. The usual answer is to make it
-- short-lived, so the window a stolen one buys is small -- but short
-- lifetimes are exactly wrong here: a professor in a basement laboratory
-- with no signal must still be able to mark a register, and the phone
-- cannot refresh a token it cannot reach the server to refresh.
--
-- So the token is opaque and the session is a row. Revoking is an
-- UPDATE that takes effect on the next request, which means the token
-- can be long-lived without the risk a long-lived signed token carries.
-- There is also no signing key to store, rotate, or get wrong, and no
-- algorithm field for somebody to confuse.
--
-- What is stored is the SHA-256 of the token, never the token. The table
-- is therefore not a list of credentials: somebody who reads every row
-- cannot sign in with any of them.
--
--
-- WHY app_session HAS NO ROW-LEVEL SECURITY
--
-- The same reason app_account has none, and V6's header says it at
-- length: verifying a token is what establishes the actor, so a policy
-- keyed on the current actor would deny the one lookup that creates
-- one. Routing it through a SECURITY DEFINER function would change
-- nothing, because the row has to come back either way. What protects
-- this table is privilege and the service's own code -- and saying so is
-- better than a policy that looks like protection.
--
-- device_registration is different and does have policies: resolving an
-- install to its node happens through a definer function, so the table
-- itself can stay closed.
--
--
-- NEITHER TABLE IS AUDITED BY TRIGGER
--
-- Deliberate, and for two different reasons.
--
--   * app_session would write an entry per login, per logout and per
--     last_seen_at touch -- and audit_row() redacts password_hash and
--     nothing else, so every one of those entries would carry the token
--     hash. LOGIN and LOGIN_FAILED already record what matters.
--
--   * device_registration is written once per install, and the service
--     writes a DEVICE_REGISTER entry naming the node, the label and the
--     provisional node it replaced. A trigger would log the same insert
--     a second time with less in it.
-- =====================================================================


-- ── Node numbers ─────────────────────────────────────────────────────
--
-- The server's half of StructuredId's split node space. 1 .. 32767 are
-- assigned from here, one per install; 32768 .. 65535 are what an
-- install picks for itself before it has ever spoken to a server. Zero
-- is the server's own, which is why this sequence starts at one.
--
-- NO CYCLE, and nothing reuses a node after a device is revoked. The
-- keys that install wrote carry its node for ever, and handing the same
-- number to a second phone would make two devices' rows indistinguishable
-- in exactly the place that distinction is load-bearing. 32767 installs
-- is some decades of a college; exhaustion is an error, loudly.
--
-- PostgresSchemaDriftTest holds these two bounds against
-- StructuredId.FIRST_ASSIGNABLE_NODE_ID and MAX_ASSIGNED_NODE_ID.

CREATE SEQUENCE device_node_seq AS integer
    START WITH 1 MINVALUE 1 MAXVALUE 32767 NO CYCLE;

COMMENT ON SEQUENCE device_node_seq IS
    'Device node numbers, 1..32767, never reused. The upper half of the 16-bit space is what an unregistered install picks for itself; 0 is the server''s own.';


-- ── device_registration ──────────────────────────────────────────────
--
-- One row per installation, not per person. A node identifies the phone
-- that issued a key, and a departmental tablet that two professors sign
-- in on is one phone -- so the account here is who registered it, not
-- who may use it.

CREATE TABLE device_registration (
    device_id        bigserial PRIMARY KEY,

    -- The installation's own identifier, generated on the device the
    -- first time it opens the database. What makes registration
    -- idempotent: a retried request after a lost response must return
    -- the node already issued rather than burn a second one.
    install_id       uuid    NOT NULL,

    node             integer NOT NULL DEFAULT nextval('device_node_seq'),

    -- The node this install had picked for itself before it registered,
    -- kept because two installs may have picked the same one and each
    -- already written rows under it. This column is the only record of
    -- which install meant what by a provisional node, and registration
    -- is the last moment it can be collected: once those rows arrive,
    -- their keys say nothing about which phone wrote them.
    provisional_node integer,

    label            text    NOT NULL,
    app_version      text,

    registered_by    bigint      NOT NULL,
    registered_at    timestamptz NOT NULL DEFAULT now(),
    last_seen_at     timestamptz,

    revoked_at       timestamptz,
    revoked_by       bigint,
    reason           text,

    CONSTRAINT device_registration_install_uq UNIQUE (install_id),
    CONSTRAINT device_registration_node_uq    UNIQUE (node),

    CONSTRAINT device_registration_node_is_assignable CHECK (
        node BETWEEN 1 AND 32767),

    -- A provisional node is from the half of the space a device picks
    -- from. A value in the assignable half would mean the install had
    -- somehow been given one already, which is a different situation
    -- and should not be filed as this one.
    CONSTRAINT device_registration_provisional_is_self_picked CHECK (
        provisional_node IS NULL OR provisional_node BETWEEN 32768 AND 65535),

    CONSTRAINT device_registration_revocation_complete CHECK (
        (revoked_at IS NULL) = (revoked_by IS NULL)),

    CONSTRAINT device_registration_registered_by_fkey FOREIGN KEY (registered_by)
        REFERENCES app_account (account_id) ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT device_registration_revoked_by_fkey FOREIGN KEY (revoked_by)
        REFERENCES app_account (account_id) ON UPDATE CASCADE ON DELETE RESTRICT
);

-- Deliberately NOT unique: a clash is the thing being looked for, and a
-- unique index here would refuse the second install's registration
-- instead of recording the collision it came to report.
CREATE INDEX device_registration_provisional_idx
    ON device_registration (provisional_node)
    WHERE provisional_node IS NOT NULL;

CREATE INDEX device_registration_account_idx
    ON device_registration (registered_by) WHERE revoked_at IS NULL;

COMMENT ON TABLE device_registration IS
    'One row per installation. node is assigned from device_node_seq and never reused; provisional_node records what the install had picked for itself, which is the only way a clash between two installs can be told apart afterwards.';
COMMENT ON COLUMN device_registration.install_id IS
    'The device''s own identifier, so a retried registration returns the node already issued instead of consuming another.';


-- ── app_session ──────────────────────────────────────────────────────

CREATE TABLE app_session (
    -- Also what goes into audit_log.session_id, so a run of entries can
    -- be read as one sitting.
    session_id   uuid PRIMARY KEY DEFAULT gen_random_uuid(),

    account_id   bigint NOT NULL,

    -- SHA-256 of the bearer token. The token itself is never stored, so
    -- this table is not a list of credentials.
    token_hash   bytea  NOT NULL,

    -- Null for a session that is not on a registered device -- office
    -- staff at a desk. When it is set, every request on this session has
    -- to present the matching install, which is what makes a token
    -- lifted from one phone useless on another.
    device_id    bigint,

    issued_at    timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL,

    -- Updated at most every few minutes rather than on every request: a
    -- write per read would double the cost of the quiet endpoints for a
    -- column nothing decides anything on.
    last_seen_at timestamptz,

    client_ip    inet,
    app_version  text,

    revoked_at   timestamptz,
    reason       text,

    CONSTRAINT app_session_token_uq UNIQUE (token_hash),
    CONSTRAINT app_session_expires_after_issue CHECK (expires_at > issued_at),

    CONSTRAINT app_session_account_id_fkey FOREIGN KEY (account_id)
        REFERENCES app_account (account_id) ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT app_session_device_id_fkey FOREIGN KEY (device_id)
        REFERENCES device_registration (device_id) ON UPDATE CASCADE ON DELETE RESTRICT
);

-- "Every live session of this account", which is what a password change
-- has to revoke and what a person asking "where am I signed in" reads.
CREATE INDEX app_session_account_idx
    ON app_session (account_id) WHERE revoked_at IS NULL;

COMMENT ON TABLE app_session IS
    'One row per bearer token. Stores the token''s SHA-256, never the token. Revoked by UPDATE, which is what lets a token be long-lived: the window a stolen one buys is bounded by the revoke rather than by the expiry.';


-- ── Resolving an install without being able to read the table ────────
--
-- Registration has to know whether this install already has a node,
-- including when another account registered the phone. The policies
-- below do not allow that read, and should not -- so it happens here,
-- narrowly: one install in, one node out, nothing else about the row.

CREATE FUNCTION node_for_install(install uuid) RETURNS integer
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN (SELECT d.node FROM device_registration d
            WHERE d.install_id = install AND d.revoked_at IS NULL);

CREATE FUNCTION device_for_install(install uuid) RETURNS bigint
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN (SELECT d.device_id FROM device_registration d
            WHERE d.install_id = install AND d.revoked_at IS NULL);

-- How many OTHER installs claim the same provisional node as this one.
-- Not an error and not a refusal: the clash has already happened, out in
-- the field, and this is how the registration that discovers it can say
-- so. Takes the install rather than the number, so the caller does not
-- need to be able to read the row to ask -- which, under the policies
-- below, it often cannot.
--
-- An install with no provisional node joins to nothing and the count is
-- zero, which is the right answer rather than a special case.
CREATE FUNCTION provisional_node_clashes(install uuid) RETURNS bigint
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN (SELECT count(*)
            FROM device_registration mine
            JOIN device_registration other
              ON other.provisional_node = mine.provisional_node
             AND other.install_id <> mine.install_id
            WHERE mine.install_id = install);

-- What verifying a token needs to know about the device a session is
-- bound to: the node for the audit entry, the install the request has to
-- name, and whether the device has been revoked.
--
-- A function, and this one is not a nicety. The verification query runs
-- BEFORE there is an actor -- working out who the actor is, is what it
-- is for -- so a plain join to device_registration is filtered by the
-- policies below to nothing at all. Under a LEFT JOIN that is silent:
-- every column comes back NULL, the session looks like one with no
-- device, and `d.revoked_at IS NULL` is true of a revoked device as
-- readily as of a live one. Joining this instead keeps the query one
-- statement and makes the answer the row rather than the caller's
-- visibility of it.
CREATE FUNCTION device_session_binding(device bigint)
    RETURNS TABLE (node integer, install_id uuid, revoked boolean)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    BEGIN ATOMIC
        SELECT d.node, d.install_id, d.revoked_at IS NOT NULL
        FROM device_registration d
        WHERE d.device_id = device;
    END;

COMMENT ON FUNCTION device_session_binding(bigint) IS
    'The node, install and revocation state of a bound device, for token verification -- which happens before there is an actor, so the policies on device_registration would otherwise filter the join to nothing and make a revoked device look like no device.';

-- Marks a device as heard from. A definer function rather than an UPDATE
-- from the service, because the only place this is called from is token
-- verification -- which happens BEFORE there is an actor, so an ordinary
-- UPDATE would match no row under the policies below and quietly do
-- nothing for ever.
--
-- It writes now() on a row the caller already holds a session for, so
-- there is nothing here for a caller to gain by calling it.
CREATE FUNCTION device_seen(device bigint) RETURNS void
    LANGUAGE sql SECURITY DEFINER
    SET search_path = pg_catalog, public
    BEGIN ATOMIC
        UPDATE device_registration SET last_seen_at = now()
        WHERE device_id = device;
    END;

COMMENT ON FUNCTION node_for_install(uuid) IS
    'The node already issued to an install, or NULL. SECURITY DEFINER so registration is idempotent even for a shared device somebody else registered.';


-- ── Who may see a device row ─────────────────────────────────────────

ALTER TABLE device_registration ENABLE ROW LEVEL SECURITY;

CREATE POLICY device_registration_own ON device_registration
    FOR SELECT USING (registered_by = current_account_id());

-- Anybody with an account may register their own phone, and the row has
-- to name them as the one who did: registered_by = current_account_id()
-- is false rather than null-and-permissive when there is no actor,
-- because NULL = NULL is not true.
CREATE POLICY device_registration_register ON device_registration
    FOR INSERT WITH CHECK (registered_by = current_account_id());

CREATE POLICY device_registration_own_revoke ON device_registration
    FOR UPDATE
    USING      (registered_by = current_account_id())
    WITH CHECK (registered_by = current_account_id());

CREATE POLICY device_registration_readers ON device_registration
    FOR SELECT USING (actor_has_role('OFFICE') OR actor_has_role('AUDITOR'));

CREATE POLICY device_registration_admin_select ON device_registration
    FOR SELECT USING (actor_has_role('ADMIN'));

CREATE POLICY device_registration_admin_register ON device_registration
    FOR INSERT WITH CHECK (actor_has_role('ADMIN'));

CREATE POLICY device_registration_admin_revoke ON device_registration
    FOR UPDATE
    USING      (actor_has_role('ADMIN'))
    WITH CHECK (actor_has_role('ADMIN'));

-- No DELETE policy, for anybody. A device is revoked, never deleted: its
-- node is in the primary key of every row it ever wrote, and a deleted
-- registration turns those into keys nobody can account for. Verbs are
-- spelled out above for exactly this reason -- FOR ALL would have
-- included DELETE, which is the mistake V6 made and
-- RowLevelSecurityTest.aGrantIsNeverDeleted caught.


-- ── What this caller may export ──────────────────────────────────────
--
-- The one authority question the policies cannot answer. An export
-- reads courses and student_year, and V6 left both without read
-- policies on purpose (see its header), so there is nothing to filter
-- the roster half of the export down to the caller -- and without that,
-- a professor's export would list every student in the college with
-- zeroes against them.
--
-- The rule therefore has to be applied by the endpoint. It is written
-- HERE rather than as a WHERE clause in Java so that it sits beside the
-- policies that do the same job for attendance, is readable by anybody
-- auditing what an export may contain, and cannot drift from the three
-- authorities V6 already defines.
--
-- Not SECURITY DEFINER, unlike the functions above: every authority test
-- it makes is already a definer function, and courses has no policies to
-- bypass. If courses ever gets a read policy, this narrows rather than
-- widens -- it fails closed -- but it should be revisited then.

CREATE FUNCTION exportable_courses(session_year integer) RETURNS SETOF bigint
    LANGUAGE sql STABLE
    BEGIN ATOMIC
        SELECT c.course_id
        FROM courses c
        WHERE actor_has_role('ADMIN')
           OR actor_has_role('OFFICE')
           OR actor_has_role('AUDITOR')
           OR actor_instructs(c.course_id)
           OR actor_is_hod_of(c.offering_department, session_year)
           OR actor_is_cr_of(c.course_id, session_year);
    END;

COMMENT ON FUNCTION exportable_courses(integer) IS
    'The courses the current actor may export in a session: everything for an admin, office or auditor; a professor''s own; a head''s department; a CR''s granted courses. The export endpoint intersects the requested course list with this.';


-- ── Who may load a roster ────────────────────────────────────────────
--
-- students and student_year have no policies either, so this is the
-- same shape of rule as above and the same caveat applies: the endpoint
-- enforces it. When those two tables do get policies, this function is
-- what they should be written in terms of, so that there is one answer
-- to "who may edit the roll" rather than two that have to agree.

CREATE FUNCTION actor_may_manage_roster() RETURNS boolean
    LANGUAGE sql STABLE
    RETURN actor_has_role('OFFICE') OR actor_has_role('ADMIN');

COMMENT ON FUNCTION actor_may_manage_roster() IS
    'Whether the current actor may load a roster CSV. Office and admin only: a professor may not rewrite the roll of their own class, because a roll number is what every other session''s records are matched by.';


-- ── Privileges ───────────────────────────────────────────────────────
--
-- Conditional, like V7's: tools/verify-schema.sh applies these
-- migrations to a bare container where the roles do not exist, so a
-- bare REVOKE would fail there.

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_app') THEN

        -- A session is revoked and a device is revoked; neither is ever
        -- deleted. On device_registration this is belt and braces over
        -- the absent DELETE policy; on app_session, which has no
        -- policies at all, it is the only thing saying so.
        EXECUTE 'REVOKE DELETE ON app_session FROM sies_app';
        EXECUTE 'REVOKE DELETE ON device_registration FROM sies_app';
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_auditor') THEN
        -- The auditor reads both: "which device wrote this" and "who was
        -- signed in when" are audit questions. SELECT came from the
        -- default privileges; this only takes away what it should not
        -- have had.
        EXECUTE 'REVOKE INSERT, UPDATE, DELETE ON app_session FROM sies_auditor';
        EXECUTE 'REVOKE INSERT, UPDATE, DELETE ON device_registration FROM sies_auditor';
    END IF;
END $$;
