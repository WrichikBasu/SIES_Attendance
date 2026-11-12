-- ════════════════════════════════════════════════════════════════════
-- V66 — A row knows when it last changed (R19)
-- ════════════════════════════════════════════════════════════════════
--
-- updated_at has been on every synced table since V1, and was set only
-- by the code paths that remembered to: a push, a roster import. A
-- correction made any other way -- the console, a script, an
-- administrator's UPDATE -- left it where it was, so nothing could say
-- whether a row had changed since a given moment.
--
-- The backup importer needs exactly that. It used to let the backup
-- win every time, so a backup of a phone as it was on Monday, imported
-- on Wednesday, put Monday's values back over a correction the office
-- made on Tuesday. It now leaves alone a row the server has changed
-- since the backup was made, and to know that, the row has to say when
-- it last changed.
--
-- The trigger sets updated_at to now() whenever an UPDATE changes the
-- row and the statement did not set updated_at itself. A statement that
-- does set it keeps its own value: the push and the roster import say
-- now() anyway, and the backup importer says "as of when this backup
-- was made", which is what an imported row's last change really was.
-- An UPDATE that changes nothing is left as it was, as the audit
-- trigger already leaves it out of the record.

CREATE FUNCTION touch_updated_at() RETURNS trigger
    LANGUAGE plpgsql AS $fn$
BEGIN
    IF NEW IS DISTINCT FROM OLD
       AND NEW.updated_at IS NOT DISTINCT FROM OLD.updated_at THEN
        NEW.updated_at := now();
    END IF;
    RETURN NEW;
END $fn$;

COMMENT ON FUNCTION touch_updated_at() IS
    'Stamps updated_at on an UPDATE that changes the row without setting updated_at itself (V66), so a row can say whether it has changed since a backup was made.';

-- On every synced table that carries updated_at; the three membership
-- tables are nothing but their keys and have none.
DO $$
DECLARE
    stamped text;
BEGIN
    FOR stamped IN
        SELECT c.table_name
        FROM information_schema.columns c
        WHERE c.table_schema = 'public'
          AND c.column_name = 'updated_at'
          AND c.table_name = ANY (synced_tables())
    LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE UPDATE ON %I '
            'FOR EACH ROW EXECUTE FUNCTION touch_updated_at()',
            stamped || '_touch_updated_at', stamped);
    END LOOP;
END $$;
