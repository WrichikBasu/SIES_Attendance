-- =====================================================================
-- V1 — Baseline: the Room schema at version 10, translated to PostgreSQL.
--
-- This is a TRANSLATION, not a redesign. Every table, column, index and
-- foreign key here exists in the SQLite database today, with the same
-- semantics. The source of truth is the DDL Room generates into
--   app/build/generated/ap_generated_sources/debug/out/.../MainDatabase_Impl.java
-- which is byte-for-byte what the app creates on a device.
--
-- New constraints that SQLite could not express, and the invariants that
-- AcademicRules currently enforces in Java only, are deliberately NOT
-- here. They are in V2, so that this file can be diffed against the Room
-- DDL by eye and the two kept honest.
--
-- Three deviations from a literal transcription, each deliberate:
--
--   1. Identifiers are snake_case. Unquoted identifiers fold to lower
--      case in PostgreSQL, so `studentID` would silently become
--      `studentid`; quoting every identifier forever is worse. The
--      column map at the foot of this file is authoritative for the
--      importer.
--   2. Enum columns become native enum types whose values are the Java
--      name() strings exactly. PostgresSchemaDriftTest fails the build
--      if the two ever diverge.
--   3. Three indexes Room creates are subsumed by a primary key and are
--      omitted; each omission is noted where the table is defined.
--
-- Provenance columns (created_at/by, updated_at/by, row_version) are new
-- and all nullable. Rows imported from device history have no honest
-- value for them and must be left NULL rather than given a plausible one.
-- =====================================================================


-- ── Enum types ───────────────────────────────────────────────────────
--
-- Values are the Java name() strings, in declaration order, because
-- TypeConvertors stores name() and never toString(). Declaration order
-- is preserved so that ORDER BY on one of these columns reproduces the
-- order the app's dropdowns use.
--
-- A value may be APPENDED later (ALTER TYPE ... ADD VALUE). A value must
-- never be renamed or removed once rows reference it — the same contract
-- the Java enums already carry.

CREATE TYPE department AS ENUM (
    'PHYSICS',
    'CHEMISTRY',
    'MATHEMATICS',
    'BOTANY',
    'ZOOLOGY',
    'BIOCHEMISTRY',
    'STATISTICS',
    'BIOANALYTICAL_SCIENCES',
    'MICROBIOLOGY',
    'BIOTECHNOLOGY',
    'FTN',
    'COMPUTER_SCIENCE',
    'DATA_SCIENCE',
    'INFORMATION_TECHNOLOGY',
    'ENVIRONMENTAL_SCIENCE',
    'ENGLISH',
    'HINDI',
    'POLITICAL_SCIENCE',
    'ECONOMICS',
    'MANAGEMENT_STUDIES',
    'MASS_MEDIA',
    'HISTORY',
    'PHILOSOPHY',
    'PSYCHOLOGY',
    'COMMERCE'
);

-- Named degree_stream rather than stream, so a column declaration does
-- not read "stream stream NOT NULL".
CREATE TYPE degree_stream AS ENUM (
    'BA',
    'BSc',
    'BSc_unaided',
    'BCom',
    'BMS',
    'BAMMC',
    'MA',
    'MSc',
    'MSc_unaided'
);

CREATE TYPE study_year AS ENUM (
    'FY',
    'SY',
    'TY',
    'Masters_P1',
    'Masters_P2'
);

CREATE TYPE class_division AS ENUM (
    'A',
    'B',
    'C',
    'D'
);

CREATE TYPE course_type AS ENUM (
    'MAJOR',
    'MINOR',
    'MAJOR_PRACTICALS',
    'MINOR_PRACTICALS',
    'DSE',
    'DSE_PRACTICALS',
    'OE',
    'VSC',
    'VSC_PRACTICALS',
    'SEC',
    'SEC_PRACTICALS',
    'AEC',
    'IKS'
);


-- ── The ID scheme, readable from SQL ─────────────────────────────────
--
-- A direct port of IdGenerator's layout, so the server can reason about
-- a key without re-deriving the arithmetic in three places:
--
--   [ 41 bits: ms since 2024-01-01Z ][ 6 bits: sequence ][ 16 bits: node ]
--
-- These are IMMUTABLE, so they may be used in indexes and CHECK
-- constraints. Phase 4 uses id_node() to reject an uploaded row whose
-- key claims a device other than the one presenting the token.

CREATE FUNCTION id_is_structured(id bigint) RETURNS boolean
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    -- 1 << 55: IdGenerator.FIRST_STRUCTURED_ID. Below this are keys
    -- inherited from databases old enough to have let SQLite count, or
    -- to have used a raw millisecond clock. They carry no node and are
    -- unique only within the device that issued them.
    RETURN id >= 36028797018963968;

CREATE FUNCTION id_node(id bigint) RETURNS integer
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    -- -1 for a key that predates node numbers, matching
    -- IdGenerator.nodeOf(). Node 0..32767 is server-issued;
    -- 32768..65535 is what an unregistered install draws for itself.
    RETURN CASE WHEN id_is_structured(id) THEN (id & 65535)::integer ELSE -1 END;

CREATE FUNCTION id_created_at(id bigint) RETURNS timestamptz
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    -- An indication of when the row appeared, not a timestamp to store:
    -- a long burst or a slow clock pushes it forward. 1704067200000 is
    -- IdGenerator.EPOCH_MILLIS (2024-01-01T00:00:00Z).
    RETURN CASE
        WHEN id_is_structured(id)
            THEN to_timestamp((((id >> 16) >> 6) + 1704067200000) / 1000.0)
        ELSE NULL
    END;

COMMENT ON FUNCTION id_is_structured(bigint) IS
    'True when the key was issued by IdGenerator rather than inherited from a pre-v7 database.';
COMMENT ON FUNCTION id_node(bigint) IS
    'The 16-bit node (device) field of a structured key, or -1 for a legacy key.';
COMMENT ON FUNCTION id_created_at(bigint) IS
    'Approximate creation instant encoded in a structured key, or NULL for a legacy key.';


-- ── academic_years ───────────────────────────────────────────────────
--
-- Created first: student_year, batch_desc and batch_student_map all
-- carry an academic_year, and V2 turns those into real foreign keys.
--
-- start_epoch and end_epoch are DROPPED, not translated. They were
-- derived from start_date/end_date at the writing DEVICE's time zone, so
-- two phones in different zones produce different values for the same
-- session and the stored number may be either. They existed only so
-- SQLite could compare a lecture's text timestamp without calendar
-- arithmetic; timestamptz needs no such help. V2 replaces them with a
-- daterange exclusion constraint.

CREATE TABLE academic_years (
    academic_year integer PRIMARY KEY,
    start_date    date NOT NULL,
    end_date      date NOT NULL,

    created_at    timestamptz,
    created_by    bigint,
    updated_at    timestamptz,
    updated_by    bigint,
    row_version   bigint NOT NULL DEFAULT 1
);

COMMENT ON TABLE academic_years IS
    'One academic session. academic_year is the starting calendar year: 2025 is the 2025-26 session.';


-- ── students ─────────────────────────────────────────────────────────

CREATE TABLE students (
    student_id  bigint PRIMARY KEY,
    name        text NOT NULL,
    pronouns    text NOT NULL,
    email       text,

    created_at  timestamptz,
    created_by  bigint,
    updated_at  timestamptz,
    updated_by  bigint,
    row_version bigint NOT NULL DEFAULT 1
);

-- Nullable and unique: PostgreSQL and SQLite agree that NULLs are
-- distinct in a unique index, so many students may have no address.
-- Compared case-sensitively, exactly as today. Folding to lower case, or
-- moving to citext, would be a behaviour change and belongs in a later
-- migration with its own review.
CREATE UNIQUE INDEX students_email_uq ON students (email);

COMMENT ON COLUMN students.name IS
    'Stored exactly as it arrived; an imported roster is usually all capitals. NameFormat.properCase tidies at display time.';


-- ── professors ───────────────────────────────────────────────────────
--
-- Omitted from the Room index set, both subsumed:
--   index_professors_profID             — unique on the primary key
--   index_professors_name_department_email — implied by the unique index
--                                         on email alone

CREATE TABLE professors (
    prof_id     bigint PRIMARY KEY,
    name        text NOT NULL,
    email       text NOT NULL,
    department  department NOT NULL,
    designation text NOT NULL,

    created_at  timestamptz,
    created_by  bigint,
    updated_at  timestamptz,
    updated_by  bigint,
    row_version bigint NOT NULL DEFAULT 1
);

CREATE INDEX        professors_name_department_idx ON professors (name, department);
CREATE UNIQUE INDEX professors_email_uq           ON professors (email);
CREATE INDEX        professors_department_idx      ON professors (department);

COMMENT ON COLUMN professors.email IS
    'Uniquely indexed, and therefore the account identity in Phase 4. Also the natural key the merge matches on.';


-- ── courses ──────────────────────────────────────────────────────────
--
-- dateIntroduced and dateDiscontinued were ZonedDateTime stored as
-- "epochSecond|nano|zone". Each becomes an instant plus the zone it was
-- recorded in; see the note on lectures for why the zone is kept.

CREATE TABLE courses (
    course_id              bigint PRIMARY KEY,
    prof_id                bigint NOT NULL,
    course_code            text NOT NULL,
    course_topic           text NOT NULL,
    stream                 degree_stream NOT NULL,
    offering_department    department NOT NULL,
    year                   study_year NOT NULL,
    semester               smallint NOT NULL,
    course_type            course_type NOT NULL,
    remarks                text,
    date_introduced        timestamptz,
    date_introduced_zone   text,
    date_discontinued      timestamptz,
    date_discontinued_zone text,

    created_at             timestamptz,
    created_by             bigint,
    updated_at             timestamptz,
    updated_by             bigint,
    row_version            bigint NOT NULL DEFAULT 1,

    CONSTRAINT courses_prof_id_fkey FOREIGN KEY (prof_id)
        REFERENCES professors (prof_id) ON UPDATE CASCADE ON DELETE RESTRICT,

    -- Both halves of a converted ZonedDateTime are present or neither is.
    CONSTRAINT courses_date_introduced_zone_paired CHECK (
        (date_introduced IS NULL) = (date_introduced_zone IS NULL)),
    CONSTRAINT courses_date_discontinued_zone_paired CHECK (
        (date_discontinued IS NULL) = (date_discontinued_zone IS NULL))
);

CREATE UNIQUE INDEX courses_course_code_uq ON courses (course_code);
CREATE INDEX        courses_prof_id_idx    ON courses (prof_id);

COMMENT ON COLUMN courses.prof_id IS
    'The course COORDINATOR, not the whole answer to who teaches it. course_instructor is that.';
COMMENT ON COLUMN courses.date_discontinued IS
    'Non-null keeps the course out of the active lists. Courses are never deleted: every lecture and attendance row filed under one would lose the thing it describes.';


-- ── batch_desc ───────────────────────────────────────────────────────
--
-- Omitted from the Room index set, subsumed:
--   index_batch_desc_batchID_academicYear — unique on (batchID,
--     academicYear), but batchID is already the primary key, so this
--     index guarantees nothing the key does not.
--
-- Note what that means: there is NO uniqueness today on
-- (batch_name, academic_year), even though DatabaseMerger matches
-- batches on exactly that pair. V2 adds it, because the importer cannot
-- be correct without it.

CREATE TABLE batch_desc (
    batch_id      bigint PRIMARY KEY,
    batch_name    text NOT NULL,
    batch_desc    text,
    academic_year integer NOT NULL,

    created_at    timestamptz,
    created_by    bigint,
    updated_at    timestamptz,
    updated_by    bigint,
    row_version   bigint NOT NULL DEFAULT 1
);

COMMENT ON TABLE batch_desc IS
    'A named group of students for one session. Nothing here ties a batch to a course; course_batch_map does that. Batches do not carry forward.';


-- ── student_year ─────────────────────────────────────────────────────

CREATE TABLE student_year (
    student_id    bigint NOT NULL,
    academic_year integer NOT NULL,
    roll_no       text NOT NULL,
    year          study_year NOT NULL,
    stream        degree_stream NOT NULL,
    major_dept    department NOT NULL,
    minor_dept    department,
    oe_dept       department,
    semester      smallint,
    division      class_division,

    created_at    timestamptz,
    created_by    bigint,
    updated_at    timestamptz,
    updated_by    bigint,
    row_version   bigint NOT NULL DEFAULT 1,

    PRIMARY KEY (student_id, academic_year),

    CONSTRAINT student_year_student_id_fkey FOREIGN KEY (student_id)
        REFERENCES students (student_id) ON UPDATE CASCADE ON DELETE RESTRICT
);

CREATE UNIQUE INDEX student_year_roll_no_academic_year_uq
    ON student_year (roll_no, academic_year);
CREATE INDEX student_year_class_idx
    ON student_year (academic_year, year, stream, major_dept);
CREATE INDEX student_year_student_id_idx
    ON student_year (student_id);

COMMENT ON COLUMN student_year.roll_no IS
    'Reissued when a student is promoted, which is the whole reason this table is separate from students.';
COMMENT ON COLUMN student_year.semester IS
    'NULL on rows predating the column. The year already narrows it to a pair, so NULL is a legitimate value, not a gap to fill in.';
COMMENT ON COLUMN student_year.division IS
    'NULL where the stream has no divisions at all, and also where a divided class simply has not had one recorded.';


-- ── batch_student_map ────────────────────────────────────────────────

CREATE TABLE batch_student_map (
    batch_id      bigint NOT NULL,
    student_id    bigint NOT NULL,
    academic_year integer NOT NULL,

    PRIMARY KEY (batch_id, student_id, academic_year),

    CONSTRAINT batch_student_map_batch_id_fkey FOREIGN KEY (batch_id)
        REFERENCES batch_desc (batch_id) ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT batch_student_map_student_id_fkey FOREIGN KEY (student_id)
        REFERENCES students (student_id) ON UPDATE CASCADE ON DELETE RESTRICT
);

CREATE INDEX batch_student_map_student_id_idx ON batch_student_map (student_id);

-- No provenance columns on the three map tables. Membership is rewritten
-- wholesale rather than diffed (DaoBatch.updateBatch clears and
-- reinserts), so per-row created_by would be destroyed on every edit and
-- would mean "whoever last saved the batch" rather than anything about
-- the row. The audit log in Phase 5 records these changes instead.
COMMENT ON TABLE batch_student_map IS
    'Batch membership. Rewritten wholesale on edit; see the audit log for who changed it.';


-- ── course_batch_map ─────────────────────────────────────────────────

CREATE TABLE course_batch_map (
    course_id bigint NOT NULL,
    batch_id  bigint NOT NULL,

    PRIMARY KEY (course_id, batch_id),

    CONSTRAINT course_batch_map_course_id_fkey FOREIGN KEY (course_id)
        REFERENCES courses (course_id) ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT course_batch_map_batch_id_fkey FOREIGN KEY (batch_id)
        REFERENCES batch_desc (batch_id) ON UPDATE CASCADE ON DELETE RESTRICT
);

CREATE INDEX course_batch_map_batch_id_idx ON course_batch_map (batch_id);

COMMENT ON TABLE course_batch_map IS
    'A record of which batches a course is taught to, NOT a constraint on taking attendance: the batch picker lists every batch in the session.';


-- ── course_instructor ────────────────────────────────────────────────

CREATE TABLE course_instructor (
    course_id bigint NOT NULL,
    prof_id   bigint NOT NULL,

    PRIMARY KEY (course_id, prof_id),

    CONSTRAINT course_instructor_course_id_fkey FOREIGN KEY (course_id)
        REFERENCES courses (course_id) ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT course_instructor_prof_id_fkey FOREIGN KEY (prof_id)
        REFERENCES professors (prof_id) ON UPDATE CASCADE ON DELETE RESTRICT
);

CREATE INDEX course_instructor_prof_id_idx ON course_instructor (prof_id);

COMMENT ON TABLE course_instructor IS
    'Who actually teaches a course. The coordinator named by courses.prof_id is always one of these rows, so a "who teaches this" query never has to join courses to catch a missing coordinator. This is what the Phase 4 RLS policy keys on.';


-- ── lectures ─────────────────────────────────────────────────────────
--
-- startDateTime and endDateTime were ZonedDateTime stored as the text
-- "epochSecond|nano|zone", which every date filter had to slice apart
-- (SqlBits.EPOCH_START) because comparing the string directly sorts
-- lexicographically. Each becomes a real instant.
--
-- The zone is kept in its own column rather than discarded. timestamptz
-- stores an instant and renders it in the READER's zone; a lecture has
-- to be shown in the zone it was held in, or a report read from
-- elsewhere silently moves every class by hours.
--
-- IMPORTER NOTE: timestamptz has microsecond resolution and
-- ZonedDateTime has nanosecond. Every value the app writes comes from a
-- millisecond clock or a time picker, so no truncation is expected — but
-- the importer must assert that each nano field is a whole number of
-- microseconds and report any row that is not, rather than rounding it.
--
-- There is still no academic_year column, by design: a lecture's session
-- is derived from its start instant, so it cannot be filed under a
-- session its date contradicts. V2 adds session_year_of() for that.

CREATE TABLE lectures (
    lecture_id bigint PRIMARY KEY,
    course_id  bigint NOT NULL,
    batch_id   bigint,
    start_at   timestamptz NOT NULL,
    start_zone text NOT NULL,
    end_at     timestamptz NOT NULL,
    end_zone   text NOT NULL,
    notes      text,

    created_at  timestamptz,
    created_by  bigint,
    updated_at  timestamptz,
    updated_by  bigint,
    row_version bigint NOT NULL DEFAULT 1,

    CONSTRAINT lectures_course_id_fkey FOREIGN KEY (course_id)
        REFERENCES courses (course_id) ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT lectures_batch_id_fkey FOREIGN KEY (batch_id)
        REFERENCES batch_desc (batch_id) ON UPDATE CASCADE ON DELETE RESTRICT
);

-- The direct equivalent of Room's index. NULLs compare as distinct here
-- exactly as in SQLite, so this still does not catch two whole-class
-- lectures in the same slot — the gap DaoLecture.findExisting patches in
-- Java. V2 closes it with a partial index rather than replacing this one,
-- which also serves the slot lookup.
CREATE UNIQUE INDEX lectures_slot_uq
    ON lectures (course_id, batch_id, start_at, end_at);
CREATE INDEX lectures_batch_id_idx  ON lectures (batch_id);
CREATE INDEX lectures_course_id_idx ON lectures (course_id);

COMMENT ON COLUMN lectures.batch_id IS
    'NULL means the lecture was held for the course''s whole class rather than one batch.';
COMMENT ON COLUMN lectures.start_zone IS
    'The zone the lecture was recorded in, e.g. Asia/Kolkata. start_at alone is an instant and would render in the reader''s zone.';


-- ── attendance ───────────────────────────────────────────────────────
--
-- The composite key is what makes marking idempotent: saving a sheet
-- twice overwrites the same rows. A MISSING row is not an absence — it
-- means the student was never on the sheet, which is why the export
-- counts lectures held rather than attendance rows for a denominator.
--
-- There is no author and no timestamp in the source schema. The
-- provenance columns are added here so that marks made from the server
-- onward carry one; every row imported from device history must leave
-- them NULL. Attributing old marks to a plausible professor would be
-- fabricating an audit record.

CREATE TABLE attendance (
    lecture_id  bigint NOT NULL,
    student_id  bigint NOT NULL,
    present     boolean NOT NULL,

    created_at  timestamptz,
    created_by  bigint,
    updated_at  timestamptz,
    updated_by  bigint,
    row_version bigint NOT NULL DEFAULT 1,

    PRIMARY KEY (lecture_id, student_id),

    -- ON UPDATE NO ACTION here, unlike the CASCADE on every other
    -- foreign key: that is what Room generates for this table, and it is
    -- preserved rather than tidied.
    CONSTRAINT attendance_lecture_id_fkey FOREIGN KEY (lecture_id)
        REFERENCES lectures (lecture_id) ON DELETE RESTRICT,
    CONSTRAINT attendance_student_id_fkey FOREIGN KEY (student_id)
        REFERENCES students (student_id) ON DELETE RESTRICT
);

-- The composite key indexes lecture_id first, so a lookup by student
-- alone could not use it. Statistics filter on student_id constantly.
CREATE INDEX attendance_student_id_idx ON attendance (student_id);

COMMENT ON COLUMN attendance.present IS
    'No third state: a sheet starts with everyone present and the professor taps the absentees, so every student on a saved sheet carries a definite answer.';
COMMENT ON COLUMN attendance.created_by IS
    'NULL for every row imported from device history: the source schema recorded no author. Do not backfill.';


-- ── Column map, for the importer ─────────────────────────────────────
--
-- Authoritative mapping from the Room column to the column here. Only
-- the renames and conversions are listed; everything else is the same
-- name in snake_case with the same type.
--
--   students.studentID               -> students.student_id
--   student_year.rollNo              -> student_year.roll_no
--   student_year.majorDept           -> student_year.major_dept
--   student_year.minorDept           -> student_year.minor_dept
--   student_year.oeDept              -> student_year.oe_dept
--   courses.courseCode               -> courses.course_code
--   courses.courseTopic              -> courses.course_topic
--   courses.offeringDepartment       -> courses.offering_department
--   courses.courseType               -> courses.course_type
--   courses.dateIntroduced           -> courses.date_introduced   + _zone
--   courses.dateDiscontinued         -> courses.date_discontinued + _zone
--   batch_desc.batchName             -> batch_desc.batch_name
--   batch_desc.batchDesc             -> batch_desc.batch_desc
--   lectures.startDateTime           -> lectures.start_at + lectures.start_zone
--   lectures.endDateTime             -> lectures.end_at   + lectures.end_zone
--   attendance.present (INTEGER 0/1) -> attendance.present (boolean)
--   academic_years.startDate         -> academic_years.start_date (epoch day -> date)
--   academic_years.endDate           -> academic_years.end_date
--   academic_years.startEpoch        -> DROPPED, recompute, never copy
--   academic_years.endEpoch          -> DROPPED, recompute, never copy
