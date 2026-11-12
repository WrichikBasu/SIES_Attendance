-- ════════════════════════════════════════════════════════════════════
-- V65 — The auditor does not read secrets (C9)
-- ════════════════════════════════════════════════════════════════════
--
-- tools/roles.sql gives sies_auditor SELECT on every table the owner
-- makes, which is right for the attendance and the roster: an auditor
-- has to be able to check the record against itself. It was not right
-- for two columns:
--
--   app_account.password_hash  every account's Argon2 hash. Slow to
--                              attack, and still the one thing the
--                              sign-in rests on.
--   app_session.token_hash     every live session's token hash, which
--                              is what a bearer token is checked
--                              against.
--
-- Neither table has row-level security, so the auditor read them all.
-- Nothing an audit needs is in either column: who the account is, when
-- it signed in, which sessions it had and from where are all in the
-- other columns, and stay readable.
--
-- So the table-wide grant goes, and the auditor is granted every other
-- column by name. A column added to either table later is not granted
-- until a migration says so, which is the safe default for the two
-- tables that hold credentials.

DO $$
DECLARE
    columns text;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_auditor') THEN
        RETURN;
    END IF;

    EXECUTE 'REVOKE ALL ON app_account FROM sies_auditor';

    SELECT string_agg(quote_ident(column_name), ', ' ORDER BY ordinal_position)
    INTO columns
    FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name = 'app_account'
      AND column_name <> 'password_hash';

    EXECUTE format('GRANT SELECT (%s) ON app_account TO sies_auditor', columns);

    EXECUTE 'REVOKE ALL ON app_session FROM sies_auditor';

    SELECT string_agg(quote_ident(column_name), ', ' ORDER BY ordinal_position)
    INTO columns
    FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name = 'app_session'
      AND column_name <> 'token_hash';

    EXECUTE format('GRANT SELECT (%s) ON app_session TO sies_auditor', columns);
END $$;
