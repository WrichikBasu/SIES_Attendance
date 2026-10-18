-- =====================================================================
-- V7 — The audit trail.
--
-- Additive. No table or column from V1 to V6 is altered.
--
--
-- WHAT MAKES IT EVIDENCE RATHER THAN A TABLE
--
-- Three things, and all three are privilege rather than convention:
--
--   * The service has NO privilege on audit_log at all. Not INSERT, not
--     SELECT. Row entries are written by triggers and application
--     entries by audit_event(), both SECURITY DEFINER and owned by the
--     migration role. So there is no statement the service can issue
--     that touches this table directly, which is a stronger claim than
--     "the service has INSERT and nothing else".
--
--   * audit_event() does not take an actor. It reads one from the
--     session settings the policies already use. An application that
--     could name the actor on an audit row could name somebody else.
--
--   * Nobody has UPDATE, DELETE or TRUNCATE, including the auditor. The
--     owner does, because an owner always does; that is what the
--     separation of sies_owner from sies_app is for, and why neither
--     the service nor a person logs in as the owner.
--
--
-- WHY clock_timestamp() AND NOT now()
--
-- now() is the transaction's start time, identical for every statement
-- in it. Marking a register is one transaction containing sixty inserts,
-- and with now() all sixty would carry the same instant and their order
-- would be unrecoverable. clock_timestamp() advances.
--
--
-- WHY PARTITIONED, AND WHY THERE IS A DEFAULT PARTITION
--
-- Monthly ranges, so a retention decision later is a DETACH rather than
-- a DELETE of millions of rows while the college is using the system.
--
-- The DEFAULT partition is the part worth arguing about. Without one, an
-- insert past the last pre-created month FAILS -- and because these
-- inserts come from triggers, the failure is not "the log is behind", it
-- is "attendance cannot be marked". An audit log that can stop the
-- college taking attendance is worse than one that is untidy. So there
-- is a default, and a row landing in it is a signal that nobody has run
-- audit_log_add_months() for two years, not a disaster.
-- =====================================================================


-- ── The vocabulary ───────────────────────────────────────────────────
--
-- AuditAction exactly, in order; PostgresSchemaDriftTest fails if they
-- drift. The first three are named for PostgreSQL's TG_OP so the trigger
-- can cast it straight across.

CREATE TYPE audit_action AS ENUM (
    'INSERT',
    'UPDATE',
    'DELETE',
    'LOGIN',
    'LOGIN_FAILED',
    'ACCOUNT_LOCKED',
    'PASSWORD_ROTATE',
    'EXPORT',
    'CSV_IMPORT',
    'BACKUP_IMPORT',
    'ROLE_GRANT',
    'ROLE_REVOKE',
    'DELEGATE_GRANT',
    'DELEGATE_REVOKE',
    'DEVICE_REGISTER'
);


-- ── audit_log ────────────────────────────────────────────────────────

CREATE TABLE audit_log (
    audit_id        bigint GENERATED ALWAYS AS IDENTITY,
    occurred_at     timestamptz NOT NULL DEFAULT clock_timestamp(),

    -- WHO.
    --
    -- An ACCOUNT and not a professor. A class representative is a
    -- student, and an actor column that only reaches professors cannot
    -- name the person who actually marked the register -- which would
    -- make this log useless for the one case that most needs it.
    --
    -- Null is honest rather than missing: imported history has no
    -- author, because the device schema recorded none. actor_label says
    -- which kind of null it is.
    actor_account   bigint,
    actor_label     text NOT NULL,

    -- AND UNDER WHAT AUTHORITY. Set when the actor was acting on a CR
    -- grant rather than as the course's own professor, so "what allowed
    -- this" is answerable instead of being inferred from whatever roles
    -- that person happens to hold by the time somebody asks.
    delegate_course bigint,

    session_id      uuid,
    device_node     integer,
    client_ip       inet,
    app_version     text,

    -- WHAT.
    action          audit_action NOT NULL,
    table_name      text,
    row_key         jsonb,
    before_image    jsonb,
    after_image     jsonb,
    detail          jsonb,

    -- The partition key has to be in the primary key. Identity columns
    -- on a partitioned table need PostgreSQL 17 or later, which is why
    -- this is not a bigserial.
    PRIMARY KEY (audit_id, occurred_at),

    -- A row change must name its table. Everything else MAY: an export of
    -- attendance, a roster import, a password change all have a table
    -- worth naming, and the first draft of this constraint used = rather
    -- than an implication and so forbade exactly that.
    CONSTRAINT audit_log_row_change_has_a_table CHECK (
        action NOT IN ('INSERT', 'UPDATE', 'DELETE') OR table_name IS NOT NULL),

    CONSTRAINT audit_log_images_match_the_action CHECK (
        CASE action
            WHEN 'INSERT' THEN before_image IS NULL     AND after_image IS NOT NULL
            WHEN 'UPDATE' THEN before_image IS NOT NULL AND after_image IS NOT NULL
            WHEN 'DELETE' THEN before_image IS NOT NULL AND after_image IS NULL
            ELSE before_image IS NULL AND after_image IS NULL
        END),

    CONSTRAINT audit_log_device_node_range CHECK (
        device_node IS NULL OR device_node BETWEEN 0 AND 65535),

    -- Deliberately NOT a foreign key onto app_account or courses.
    --
    -- A foreign key would mean an audit entry could block the deletion
    -- of what it describes, or be cascaded away with it. Both are wrong:
    -- the log has to be able to outlive its subject, since the last
    -- thing it records about an account is often the account going
    -- away. The integrity wanted here is "this is what happened", not
    -- "this still refers to something".
    CONSTRAINT audit_log_actor_or_label CHECK (
        actor_account IS NOT NULL OR actor_label <> '')
) PARTITION BY RANGE (occurred_at);

COMMENT ON TABLE audit_log IS
    'Append-only by privilege, not by convention: the service has no rights on this table at all and writes only through SECURITY DEFINER triggers and audit_event().';
COMMENT ON COLUMN audit_log.actor_account IS
    'The app_account that acted, or null for imported history, which has no author. Not a foreign key: the log must outlive what it describes.';
COMMENT ON COLUMN audit_log.actor_label IS
    'Who, in words, for when actor_account is null or the account is later gone. "pre-migration" for imported rows.';
COMMENT ON COLUMN audit_log.delegate_course IS
    'Set when the authority came from a CR grant on this course rather than from the actor being its professor.';


-- ── Partitions ───────────────────────────────────────────────────────

-- Makes the monthly partitions covering a span, skipping any that are
-- already there. Run it from a scheduled job; running it twice is
-- harmless, which is the point.
--
--   SELECT audit_log_add_months(date_trunc('month', now())::date, 24);
CREATE FUNCTION audit_log_add_months(start_month date, how_many integer)
    RETURNS integer
    LANGUAGE plpgsql AS $fn$
DECLARE
    made   integer := 0;
    month  date;
    name   text;
BEGIN
    FOR offset_months IN 0 .. how_many - 1 LOOP

        month := date_trunc('month', start_month + (offset_months || ' months')::interval);
        name  := 'audit_log_' || to_char(month, 'YYYY_MM');

        CONTINUE WHEN EXISTS (
            SELECT 1 FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relname = name AND n.nspname = 'public');

        EXECUTE format(
            'CREATE TABLE %I PARTITION OF audit_log FOR VALUES FROM (%L) TO (%L)',
            name, month, month + interval '1 month');

        made := made + 1;
    END LOOP;

    RETURN made;
END $fn$;

COMMENT ON FUNCTION audit_log_add_months(date, integer) IS
    'Creates monthly audit_log partitions, skipping those that exist. Idempotent, so a scheduled job can simply run it.';

-- Two years from the month this is applied in. Generous on purpose: the
-- failure mode of running out is an insert landing in the default
-- partition, and the failure mode of noticing late is nothing at all.
SELECT audit_log_add_months(date_trunc('month', now())::date, 24);

-- The safety net. See the header for why it exists rather than letting
-- an insert fail.
CREATE TABLE audit_log_overflow PARTITION OF audit_log DEFAULT;

COMMENT ON TABLE audit_log_overflow IS
    'Catches entries past the last monthly partition, so a trigger can never fail for want of one. A row in here means audit_log_add_months has not been run recently.';

-- What every audit query filters on: a time range, then an actor or a
-- row. Indexed on the parent, so each partition gets its own.
CREATE INDEX audit_log_occurred_at_idx ON audit_log (occurred_at DESC);
CREATE INDEX audit_log_actor_idx       ON audit_log (actor_account, occurred_at DESC);
CREATE INDEX audit_log_table_row_idx   ON audit_log (table_name, occurred_at DESC);

-- "Everything a CR did under a grant", which is the query the decision
-- to let a CR edit their own attendance makes load-bearing. Partial, so
-- it costs nothing on the overwhelming majority of entries.
CREATE INDEX audit_log_delegate_idx
    ON audit_log (delegate_course, occurred_at DESC)
    WHERE delegate_course IS NOT NULL;


-- ── Who may read it, and who may write it ────────────────────────────
--
-- roles.sql set ALTER DEFAULT PRIVILEGES granting sies_app every verb on
-- tables the owner creates later, which includes this one. That has to
-- be undone, and undone conditionally: tools/verify-schema.sh applies
-- these migrations to a bare container where the roles do not exist, so
-- a bare REVOKE would fail there.

DO $$
BEGIN
    -- Nobody at all, to start with.
    EXECUTE 'REVOKE ALL ON audit_log FROM PUBLIC';

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_app') THEN
        -- Not even INSERT. The triggers and audit_event() run as the
        -- owner, so the service needs no privilege here whatsoever --
        -- and therefore has no statement it can aim at this table.
        EXECUTE 'REVOKE ALL ON audit_log FROM sies_app';
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_auditor') THEN
        -- Reading is the whole job. No UPDATE and no DELETE: an auditor
        -- who can tidy the record is not an auditor.
        EXECUTE 'REVOKE ALL ON audit_log FROM sies_auditor';
        EXECUTE 'GRANT SELECT ON audit_log TO sies_auditor';
    END IF;
END $$;


-- ── Writing an application entry ─────────────────────────────────────
--
-- The actor is NOT a parameter. It comes from the same session settings
-- the policies read, so a caller cannot write an entry naming somebody
-- else -- which is the difference between a log and a story.

CREATE FUNCTION audit_event(
        for_action  audit_action,
        for_table   text    DEFAULT NULL,
        for_row     jsonb   DEFAULT NULL,
        for_detail  jsonb   DEFAULT NULL)
    RETURNS bigint
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = pg_catalog, public AS $fn$
DECLARE
    written bigint;
BEGIN
    IF for_action IN ('INSERT', 'UPDATE', 'DELETE') THEN
        RAISE EXCEPTION
            'Row changes are recorded by triggers, not by audit_event(): % would have no '
            'before or after image and would look like a change nobody can see',
            for_action
            USING ERRCODE = 'check_violation';
    END IF;

    INSERT INTO audit_log (
        actor_account, actor_label, delegate_course,
        session_id, device_node, client_ip, app_version,
        action, table_name, row_key, detail)
    VALUES (
        current_account_id(),
        coalesce(nullif(current_setting('app.actor_label', true), ''), 'system'),
        nullif(current_setting('app.delegate_course', true), '')::bigint,
        nullif(current_setting('app.session_id', true), '')::uuid,
        nullif(current_setting('app.device_node', true), '')::integer,
        nullif(current_setting('app.client_ip', true), '')::inet,
        nullif(current_setting('app.app_version', true), ''),
        for_action, for_table, for_row, for_detail)
    RETURNING audit_id INTO written;

    RETURN written;
END $fn$;

COMMENT ON FUNCTION audit_event(audit_action, text, jsonb, jsonb) IS
    'Records something no trigger can see: a login, an export, a role change. Takes no actor -- it reads one from the session, so a caller cannot name somebody else.';

DO $$
BEGIN
    EXECUTE 'REVOKE ALL ON FUNCTION audit_event(audit_action, text, jsonb, jsonb) '
            'FROM PUBLIC';

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_app') THEN
        EXECUTE 'GRANT EXECUTE ON FUNCTION audit_event(audit_action, text, jsonb, jsonb) '
                'TO sies_app';
    END IF;
END $$;


-- ── Writing a row entry ──────────────────────────────────────────────

CREATE FUNCTION audit_row() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = pg_catalog, public AS $fn$
DECLARE
    before_row jsonb;
    after_row  jsonb;
    key_row    jsonb;
    redacted   text[];
BEGIN
    before_row := CASE WHEN TG_OP IN ('UPDATE', 'DELETE') THEN to_jsonb(OLD) END;
    after_row  := CASE WHEN TG_OP IN ('INSERT', 'UPDATE') THEN to_jsonb(NEW) END;

    -- An UPDATE that changed nothing is not an event. Room's merge and
    -- the importer both write rows back unchanged as a matter of course,
    -- and an entry per no-op would bury the real ones.
    --
    -- COMPARED BEFORE REDACTING, and the order is the whole point. The
    -- first draft redacted first, which meant a change to nothing but
    -- the password hash left two identical images, looked like a no-op,
    -- and produced NO ENTRY AT ALL -- so the one column that must never
    -- be logged was also the one change that went unrecorded.
    IF TG_OP = 'UPDATE' AND before_row = after_row THEN
        RETURN NULL;
    END IF;

    -- NEVER log a password hash. app_account is audited because an
    -- account appearing, being disabled, or changing which person it
    -- points at are all things a dispute turns on -- but the auditor can
    -- read this table, and an unredacted log would hand them every hash
    -- the college has ever stored, including ones since rotated. What
    -- the entry keeps is that the column changed and when; the hash
    -- itself is not evidence of anything.
    redacted := ARRAY['password_hash'];
    before_row := before_row - redacted;
    after_row  := after_row  - redacted;

    -- The key columns, named by the trigger's own arguments, so the
    -- entry can be found again without knowing the table's shape.
    key_row := (
        SELECT jsonb_object_agg(column_name, coalesce(after_row, before_row) -> column_name)
        FROM unnest(TG_ARGV) AS column_name);

    INSERT INTO audit_log (
        actor_account, actor_label, delegate_course,
        session_id, device_node, client_ip, app_version,
        action, table_name, row_key, before_image, after_image)
    VALUES (
        current_account_id(),
        coalesce(nullif(current_setting('app.actor_label', true), ''), 'system'),
        nullif(current_setting('app.delegate_course', true), '')::bigint,
        nullif(current_setting('app.session_id', true), '')::uuid,
        nullif(current_setting('app.device_node', true), '')::integer,
        nullif(current_setting('app.client_ip', true), '')::inet,
        nullif(current_setting('app.app_version', true), ''),
        TG_OP::audit_action, TG_TABLE_NAME, key_row, before_row, after_row);

    -- AFTER trigger: the return value is discarded.
    RETURN NULL;
END $fn$;

COMMENT ON FUNCTION audit_row() IS
    'The row-level audit trigger. Takes the key column names as trigger arguments. Redacts password_hash, and skips an UPDATE that changed nothing.';


-- ── What it is attached to ───────────────────────────────────────────
--
-- The rows a dispute will be about, plus the three that say how somebody
-- came to be allowed to touch them.
--
-- The three map tables are here too, which is wider than the plan
-- listed: batch_student_map in particular is the answer to "I was in
-- Batch A", and a membership change that leaves no trace is exactly the
-- argument this log exists to settle.
--
-- NOT audited: reads. Logging every list view buries the entries that
-- matter and costs write throughput in the five minutes between periods
-- when every professor in the college is marking a register at once.

CREATE TRIGGER attendance_audit AFTER INSERT OR UPDATE OR DELETE ON attendance
    FOR EACH ROW EXECUTE FUNCTION audit_row('lecture_id', 'student_id');

CREATE TRIGGER lectures_audit AFTER INSERT OR UPDATE OR DELETE ON lectures
    FOR EACH ROW EXECUTE FUNCTION audit_row('lecture_id');

CREATE TRIGGER courses_audit AFTER INSERT OR UPDATE OR DELETE ON courses
    FOR EACH ROW EXECUTE FUNCTION audit_row('course_id');

CREATE TRIGGER students_audit AFTER INSERT OR UPDATE OR DELETE ON students
    FOR EACH ROW EXECUTE FUNCTION audit_row('student_id');

CREATE TRIGGER student_year_audit AFTER INSERT OR UPDATE OR DELETE ON student_year
    FOR EACH ROW EXECUTE FUNCTION audit_row('student_id', 'academic_year');

CREATE TRIGGER professors_audit AFTER INSERT OR UPDATE OR DELETE ON professors
    FOR EACH ROW EXECUTE FUNCTION audit_row('prof_id');

CREATE TRIGGER batch_desc_audit AFTER INSERT OR UPDATE OR DELETE ON batch_desc
    FOR EACH ROW EXECUTE FUNCTION audit_row('batch_id');

CREATE TRIGGER academic_years_audit AFTER INSERT OR UPDATE OR DELETE ON academic_years
    FOR EACH ROW EXECUTE FUNCTION audit_row('academic_year');

CREATE TRIGGER batch_student_map_audit
    AFTER INSERT OR UPDATE OR DELETE ON batch_student_map
    FOR EACH ROW EXECUTE FUNCTION audit_row('batch_id', 'student_id', 'academic_year');

CREATE TRIGGER course_batch_map_audit AFTER INSERT OR UPDATE OR DELETE ON course_batch_map
    FOR EACH ROW EXECUTE FUNCTION audit_row('course_id', 'batch_id');

CREATE TRIGGER course_instructor_audit AFTER INSERT OR UPDATE OR DELETE ON course_instructor
    FOR EACH ROW EXECUTE FUNCTION audit_row('course_id', 'prof_id');

CREATE TRIGGER app_account_audit AFTER INSERT OR UPDATE OR DELETE ON app_account
    FOR EACH ROW EXECUTE FUNCTION audit_row('account_id');

CREATE TRIGGER user_role_audit AFTER INSERT OR UPDATE OR DELETE ON user_role
    FOR EACH ROW EXECUTE FUNCTION audit_row('user_role_id');

CREATE TRIGGER attendance_delegate_audit
    AFTER INSERT OR UPDATE OR DELETE ON attendance_delegate
    FOR EACH ROW EXECUTE FUNCTION audit_row('student_id', 'course_id', 'academic_year');
