-- =====================================================================
-- V2 — Constraints the device database could not express.
--
-- Everything here is additive. Nothing created in V1 is dropped or
-- altered, so V1 stays diffable against the Room DDL.
--
-- Two sources:
--
--   * Invariants AcademicRules.validate() enforces in Java today. They
--     hold only on paths that remember to call it — and CsvImporter,
--     FragmentAddStudent and FragmentAddCourse each call it separately.
--     As constraints they hold for the importer, for an admin fix
--     applied with psql at midnight, and for every client yet to be
--     written.
--
--   * Guarantees SQLite had no way to state: a partial unique index, a
--     range exclusion, a composite foreign key onto a lookup table.
--
-- APPLIED TO AN EMPTY DATABASE, so every statement here succeeds. The
-- consequence lands in Phase 3 instead: the importer will be REJECTED by
-- these constraints on any historical row that violates one. That is the
-- intent. Such a row is a real defect in the data, and the import report
-- must surface it for review rather than the constraint being relaxed to
-- let it through.
--
-- Known candidates to expect at import:
--   * two batches sharing a name within one academic year
--   * two whole-class lectures in the same slot for one course
--   * a student_year row whose stream the major department does not offer
--   * an academic_year referenced by a student but with no row of its own
-- =====================================================================


-- ── Academic sessions cannot overlap ─────────────────────────────────
--
-- This replaces academic_years.startEpoch/endEpoch, dropped in V1.
-- Those were a derived pair of integers, recomputed at whichever
-- device's time zone last wrote the row, and nothing stopped two
-- sessions covering the same week.

ALTER TABLE academic_years
    ADD CONSTRAINT academic_years_dates_ordered
        CHECK (end_date >= start_date);

ALTER TABLE academic_years
    ADD CONSTRAINT academic_years_no_overlap
        EXCLUDE USING gist ((daterange(start_date, end_date, '[]')) WITH &&);


-- ── academic_year is a real reference, not a loose integer ───────────
--
-- Three tables carry an academic_year that the entity documentation
-- describes as referring to academic_years, but which Room never
-- enforced. The 2->3 migration seeded academic_years from every value
-- already referenced by students, batches and batch membership, so these
-- should hold on existing data. If one does not, the import has found a
-- row that drops out of every report — which is exactly what
-- DaoAcademicYear.countOrphanLectures exists to surface for lectures.

ALTER TABLE student_year
    ADD CONSTRAINT student_year_academic_year_fkey
        FOREIGN KEY (academic_year) REFERENCES academic_years (academic_year)
        ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE batch_desc
    ADD CONSTRAINT batch_desc_academic_year_fkey
        FOREIGN KEY (academic_year) REFERENCES academic_years (academic_year)
        ON UPDATE CASCADE ON DELETE RESTRICT;

ALTER TABLE batch_student_map
    ADD CONSTRAINT batch_student_map_academic_year_fkey
        FOREIGN KEY (academic_year) REFERENCES academic_years (academic_year)
        ON UPDATE CASCADE ON DELETE RESTRICT;


-- ── Which streams a department offers ────────────────────────────────
--
-- A lookup table seeded from AcademicRules.STREAMS. A department does
-- not offer every stream: Commerce is BCom and nothing else, Management
-- Studies has no postgraduate course, FTN is taught at master's level
-- only, and Physics runs an aided BSc alongside an unaided MSc.
--
-- This is a table rather than a CHECK because it is reference data that
-- changes when the college changes, not a rule about the schema. A new
-- course the college starts offering is one INSERT.
--
-- PostgresSchemaDriftTest asserts these rows match
-- AcademicRules.streamsFor() exactly, in order, so the two cannot drift.

CREATE TABLE department_stream (
    department department    NOT NULL,
    stream     degree_stream NOT NULL,
    ordinal    smallint      NOT NULL,

    PRIMARY KEY (department, stream),
    UNIQUE (department, ordinal)
);

COMMENT ON TABLE department_stream IS
    'Which streams each department can be studied under. Seeded from AcademicRules.STREAMS; ordinal preserves the undergraduate-then-postgraduate order the dropdowns use.';

INSERT INTO department_stream (department, stream, ordinal) VALUES
    -- Sciences aided at both levels.
    ('CHEMISTRY',              'BSc',         1), ('CHEMISTRY',              'MSc',         2),
    ('MATHEMATICS',            'BSc',         1), ('MATHEMATICS',            'MSc',         2),
    ('BIOCHEMISTRY',           'BSc',         1), ('BIOCHEMISTRY',           'MSc',         2),
    ('STATISTICS',             'BSc',         1), ('STATISTICS',             'MSc',         2),

    -- Aided undergraduate course, unaided master's.
    ('PHYSICS',                'BSc',         1), ('PHYSICS',                'MSc_unaided', 2),
    ('BOTANY',                 'BSc',         1), ('BOTANY',                 'MSc_unaided', 2),
    ('ZOOLOGY',                'BSc',         1), ('ZOOLOGY',                'MSc_unaided', 2),
    ('BIOANALYTICAL_SCIENCES', 'BSc',         1), ('BIOANALYTICAL_SCIENCES', 'MSc_unaided', 2),
    ('MICROBIOLOGY',           'BSc',         1), ('MICROBIOLOGY',           'MSc_unaided', 2),

    -- Unaided at both levels; shown as plain BSc and MSc, stored apart.
    ('COMPUTER_SCIENCE',       'BSc_unaided', 1), ('COMPUTER_SCIENCE',       'MSc_unaided', 2),
    ('DATA_SCIENCE',           'BSc_unaided', 1), ('DATA_SCIENCE',           'MSc_unaided', 2),
    ('INFORMATION_TECHNOLOGY', 'BSc_unaided', 1), ('INFORMATION_TECHNOLOGY', 'MSc_unaided', 2),
    ('ENVIRONMENTAL_SCIENCE',  'BSc_unaided', 1), ('ENVIRONMENTAL_SCIENCE',  'MSc_unaided', 2),
    ('BIOTECHNOLOGY',          'BSc_unaided', 1), ('BIOTECHNOLOGY',          'MSc_unaided', 2),

    -- The one department with no undergraduate stream at all.
    ('FTN',                    'MSc_unaided', 1),

    -- Single-stream departments, none of which run a master's course.
    ('MANAGEMENT_STUDIES',     'BMS',         1),
    ('MASS_MEDIA',             'BAMMC',       1),
    ('COMMERCE',               'BCom',        1),

    -- Arts subjects: BA and MA.
    ('ENGLISH',                'BA',          1), ('ENGLISH',                'MA',          2),
    ('HINDI',                  'BA',          1), ('HINDI',                  'MA',          2),
    ('POLITICAL_SCIENCE',      'BA',          1), ('POLITICAL_SCIENCE',      'MA',          2),
    ('ECONOMICS',              'BA',          1), ('ECONOMICS',              'MA',          2),
    ('HISTORY',                'BA',          1), ('HISTORY',                'MA',          2),
    ('PHILOSOPHY',             'BA',          1), ('PHILOSOPHY',             'MA',          2),
    ('PSYCHOLOGY',             'BA',          1), ('PSYCHOLOGY',             'MA',          2);

-- A course's offering department must actually offer the stream it is
-- taught under. This is the check behind the message
-- "COMMERCE is not offered as BSc".
ALTER TABLE courses
    ADD CONSTRAINT courses_department_offers_stream_fkey
        FOREIGN KEY (offering_department, stream)
        REFERENCES department_stream (department, stream)
        ON UPDATE CASCADE ON DELETE RESTRICT;

-- The same for a student, against their MAJOR department: that is the
-- one FragmentAddStudent and CsvImporter pass to validate(). minor_dept
-- and oe_dept are deliberately NOT constrained this way — a student's
-- minor or open elective is a course from another department and says
-- nothing about the stream they are enrolled in.
ALTER TABLE student_year
    ADD CONSTRAINT student_year_major_offers_stream_fkey
        FOREIGN KEY (major_dept, stream)
        REFERENCES department_stream (department, stream)
        ON UPDATE CASCADE ON DELETE RESTRICT;

CREATE INDEX courses_department_stream_idx      ON courses (offering_department, stream);
CREATE INDEX student_year_major_dept_stream_idx ON student_year (major_dept, stream);


-- ── A year of study belongs to one level ─────────────────────────────
--
-- AcademicRules: "an MSc student in their second year is Masters Part 2,
-- never SY". The two sets of years are otherwise interchangeable as far
-- as the type system is concerned, which is exactly how a stale dropdown
-- value gets saved.

ALTER TABLE courses
    ADD CONSTRAINT courses_year_matches_stream_level
        CHECK ((year IN ('Masters_P1', 'Masters_P2'))
             = (stream IN ('MA', 'MSc', 'MSc_unaided')));

ALTER TABLE student_year
    ADD CONSTRAINT student_year_year_matches_stream_level
        CHECK ((year IN ('Masters_P1', 'Masters_P2'))
             = (stream IN ('MA', 'MSc', 'MSc_unaided')));


-- ── A semester belongs to its year ───────────────────────────────────
--
-- AcademicRules.semestersFor(): semesters run consecutively through a
-- degree, so the year fixes the pair. A master's course restarts the
-- count, so Part 1 is 1 and 2 again and there is no fifth master's
-- semester.
--
-- NULL is permitted on student_year and only there: the column was added
-- at schema version 6 and rows predating it have no honest value.
-- courses.semester is NOT NULL and always has been.

ALTER TABLE courses
    ADD CONSTRAINT courses_semester_matches_year
        CHECK (
               (year IN ('FY', 'Masters_P1') AND semester IN (1, 2))
            OR (year IN ('SY', 'Masters_P2') AND semester IN (3, 4))
            OR (year = 'TY'                  AND semester IN (5, 6)));

ALTER TABLE student_year
    ADD CONSTRAINT student_year_semester_matches_year
        CHECK (
            semester IS NULL
            OR (year IN ('FY', 'Masters_P1') AND semester IN (1, 2))
            OR (year IN ('SY', 'Masters_P2') AND semester IN (3, 4))
            OR (year = 'TY'                  AND semester IN (5, 6)));


-- ── A division exists only on a divided stream ───────────────────────
--
-- Stream.hasDivisions(): only the aided BSc and BA are large enough to
-- be split. On every other stream a division is not a value left blank
-- but one that does not exist. NULL stays valid everywhere, including on
-- a divided class whose division has simply not been recorded.

ALTER TABLE student_year
    ADD CONSTRAINT student_year_division_needs_divided_stream
        CHECK (division IS NULL OR stream IN ('BA', 'BSc'));


-- ── A student's three departments are distinct ───────────────────────
--
-- From FragmentAddStudent.combinationProblem(), which calls these "a
-- rule about one student rather than about what the college offers".
-- They belong here because CsvImporter does not apply them: a hand-written
-- roster naming the same department twice imports today.

ALTER TABLE student_year
    ADD CONSTRAINT student_year_minor_not_major
        CHECK (minor_dept IS NULL OR minor_dept <> major_dept);

ALTER TABLE student_year
    ADD CONSTRAINT student_year_oe_not_major
        CHECK (oe_dept IS NULL OR oe_dept <> major_dept);

ALTER TABLE student_year
    ADD CONSTRAINT student_year_oe_not_minor
        CHECK (oe_dept IS NULL OR minor_dept IS NULL OR oe_dept <> minor_dept);


-- ── A batch name identifies a batch within its session ───────────────
--
-- DatabaseMerger matches batches on batch_name + academic_year. Nothing
-- in the device schema makes that pair unique, so two batches called
-- "Batch A" in 2025-26 are possible today and would make the merge
-- ambiguous — it would fold one into the other, taking their membership
-- and their lectures with it.
--
-- This is the constraint the importer depends on most, and the one most
-- likely to reject real data. Review any violation by hand; do not
-- relax it.

ALTER TABLE batch_desc
    ADD CONSTRAINT batch_desc_name_academic_year_uq
        UNIQUE (batch_name, academic_year);


-- ── A whole-class lecture cannot be recorded twice ───────────────────
--
-- lectures_slot_uq in V1 treats NULL batch_id values as distinct, as
-- SQLite does, so it catches a duplicated batch lecture but not a
-- duplicated whole-class one. DaoLecture.findExisting handles that case
-- explicitly in Java. This index closes it in the database, and is
-- purely additive: lectures_slot_uq still serves the slot lookup.

CREATE UNIQUE INDEX lectures_whole_class_slot_uq
    ON lectures (course_id, start_at, end_at)
    WHERE batch_id IS NULL;

ALTER TABLE lectures
    ADD CONSTRAINT lectures_ends_after_start
        CHECK (end_at > start_at);


-- ── Which session a lecture falls in ─────────────────────────────────
--
-- Lectures carry no academic_year column, by design: the session is
-- derived from the start instant so that it cannot contradict the date.
-- In SQLite that meant comparing against academic_years.startEpoch,
-- which was computed at the writing device's time zone.
--
-- The zone is a PARAMETER here rather than an ambient setting, which is
-- the whole point. Pass the lecture's own lectures.start_zone; the
-- default is the app's real deployment zone and exists so that an
-- interactive query is not forced to spell it out.
--
-- STABLE, not IMMUTABLE, because it reads a table — so it cannot be used
-- in an index or a CHECK constraint. Phase 4's HOD policy calls it.

CREATE FUNCTION session_year_of(at timestamptz, zone text DEFAULT 'Asia/Kolkata')
    RETURNS integer
    LANGUAGE sql STABLE
    RETURN (
        SELECT ay.academic_year
        FROM academic_years ay
        WHERE (at AT TIME ZONE zone)::date BETWEEN ay.start_date AND ay.end_date
        LIMIT 1
    );

COMMENT ON FUNCTION session_year_of(timestamptz, text) IS
    'The academic session containing an instant, or NULL when it falls outside every defined session. Pass the lecture''s own start_zone.';

-- The server-side equivalent of DaoAcademicYear.countOrphanLectures: a
-- lecture outside every defined session belongs to none of them and
-- drops out of every report. Keeping it as a view means the condition is
-- stated once.
CREATE VIEW lectures_without_session AS
    SELECT l.lecture_id,
           l.course_id,
           l.batch_id,
           l.start_at,
           l.start_zone
    FROM lectures l
    WHERE session_year_of(l.start_at, l.start_zone) IS NULL;

COMMENT ON VIEW lectures_without_session IS
    'Lectures falling outside every defined academic session. Must be empty after import, or those lectures are invisible to every report.';
