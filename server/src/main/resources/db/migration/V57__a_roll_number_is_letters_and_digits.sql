-- ════════════════════════════════════════════════════════════════════
-- V57 — A roll number or a course code is letters and digits, in capitals
-- ════════════════════════════════════════════════════════════════════
--
-- Roll numbers and course codes were stored as typed, and matched in
-- three different ways: exactly (the roster import, and the unique
-- indices), with case ignored (the attendance import's course lookup),
-- and with some separators stripped (the attendance import's students).
-- So "cs-fy-002" beside "CS-FY-002" was a second student in the same
-- session, and an attendance sheet listing "CS-FY-002" and "CS/FY/002"
-- passed the duplicate check and then wrote one student's mark twice.
--
-- Asked for: these are ASCII letters and digits only, upper-cased. One
-- spelling, so every comparison is an exact one.
--
--   * canonical_code() is the rule, the same as CanonicalCode.of() in the
--     shared module. A test runs both over the same inputs.
--   * Rows already stored are rewritten, after a check that no two of
--     them become the same value, which the unique indices would refuse
--     part-way through. Those are listed and the migration stops; they
--     need a person to decide which is which.
--   * A BEFORE trigger on each table canonicalises every write: a phone
--     on an older build pushing "cs-fy-002", a backup import, anything
--     that reaches the table without passing through Java.
--   * A CHECK on each is the backstop, and what refuses a value with no
--     letters or digits in it at all, which the trigger turns into ''.
--
-- The rewrite goes through the audit triggers like any other UPDATE, so
-- it is in the feed and every device pulls the new spelling.

-- ── The rule ─────────────────────────────────────────────────────────

-- translate() rather than upper(): upper() follows the database's
-- LC_CTYPE, and under a Turkish one 'i' becomes 'İ', which is not ASCII
-- and would fail the CHECK below. CanonicalCode uses Locale.ROOT for the
-- same reason.
CREATE FUNCTION canonical_code(raw text) RETURNS text
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
    SET search_path = pg_catalog
    RETURN translate(regexp_replace(raw, '[^A-Za-z0-9]', '', 'g'),
                     'abcdefghijklmnopqrstuvwxyz',
                     'ABCDEFGHIJKLMNOPQRSTUVWXYZ');

COMMENT ON FUNCTION canonical_code(text) IS
    'A roll number or course code in its one stored spelling: ASCII letters and digits only, letters in capitals. CanonicalCode.of() in the shared module, in SQL (V57).';

-- ── What is already stored ───────────────────────────────────────────

DO $$
DECLARE
    clashes text;
BEGIN
    SELECT string_agg(format('%s in %s (from %s)', canon, academic_year, spellings), '; ')
    INTO clashes
    FROM (
        SELECT canonical_code(roll_no) AS canon, academic_year,
               string_agg(quote_literal(roll_no), ', ' ORDER BY roll_no) AS spellings
        FROM student_year
        GROUP BY 1, 2
        HAVING count(*) > 1 OR canonical_code(roll_no) = ''
    ) c;

    IF clashes IS NOT NULL THEN
        RAISE EXCEPTION 'V57: these roll numbers become the same once only letters and '
                        'digits are kept, or become nothing: %', clashes
            USING HINT = 'Change one of each pair so they differ in a letter or a digit, '
                         'then apply the migrations again.';
    END IF;

    SELECT string_agg(format('%s (from %s)', canon, spellings), '; ')
    INTO clashes
    FROM (
        SELECT canonical_code(course_code) AS canon,
               string_agg(quote_literal(course_code), ', ' ORDER BY course_code) AS spellings
        FROM courses
        GROUP BY 1
        HAVING count(*) > 1 OR canonical_code(course_code) = ''
    ) c;

    IF clashes IS NOT NULL THEN
        RAISE EXCEPTION 'V57: these course codes become the same once only letters and '
                        'digits are kept, or become nothing: %', clashes
            USING HINT = 'Change one of each pair so they differ in a letter or a digit, '
                         'then apply the migrations again.';
    END IF;
END $$;

UPDATE student_year
SET roll_no     = canonical_code(roll_no),
    updated_at  = now(),
    row_version = row_version + 1
WHERE roll_no IS DISTINCT FROM canonical_code(roll_no);

UPDATE courses
SET course_code = canonical_code(course_code),
    updated_at  = now(),
    row_version = row_version + 1
WHERE course_code IS DISTINCT FROM canonical_code(course_code);

-- ── Every write from here on ─────────────────────────────────────────

CREATE FUNCTION student_year_canonical_roll_no() RETURNS trigger
    LANGUAGE plpgsql AS $fn$
BEGIN
    NEW.roll_no := canonical_code(NEW.roll_no);
    RETURN NEW;
END $fn$;

CREATE TRIGGER student_year_canonical_roll_no
    BEFORE INSERT OR UPDATE ON student_year
    FOR EACH ROW EXECUTE FUNCTION student_year_canonical_roll_no();

CREATE FUNCTION courses_canonical_code() RETURNS trigger
    LANGUAGE plpgsql AS $fn$
BEGIN
    NEW.course_code := canonical_code(NEW.course_code);
    RETURN NEW;
END $fn$;

CREATE TRIGGER courses_canonical_code
    BEFORE INSERT OR UPDATE ON courses
    FOR EACH ROW EXECUTE FUNCTION courses_canonical_code();

ALTER TABLE student_year
    ADD CONSTRAINT student_year_roll_no_canonical CHECK (roll_no ~ '^[A-Z0-9]+$');

ALTER TABLE courses
    ADD CONSTRAINT courses_course_code_canonical CHECK (course_code ~ '^[A-Z0-9]+$');

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_app') THEN
        GRANT EXECUTE ON FUNCTION canonical_code(text) TO sies_app;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_auditor') THEN
        GRANT EXECUTE ON FUNCTION canonical_code(text) TO sies_auditor;
    END IF;
END $$;
