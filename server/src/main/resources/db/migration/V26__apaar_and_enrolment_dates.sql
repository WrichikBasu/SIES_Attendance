-- A student's APAAR ID, and the day they joined and left a session's class.
--
-- apaar_id is the national academic ID: one per person, for life. It is on
-- students -- the person -- and unique across them, which is what lets the
-- same student be recognised in a new session under a new roll number. It
-- may be null: not every student has one recorded.
--
-- join_date and leave_date are on student_year, because joining and leaving
-- happen to a session's enrolment. A null join_date means the start of the
-- session. A late joiner's lectures count from the day they joined: the
-- statistics and the register export compare each lecture's local date with
-- it (PgSqlBits.COUNTS_FOR_ENROLMENT, and SqlBits on the device). A non-null
-- leave_date takes the student off the session's rolls and out of the
-- exports; their attendance before it is kept.

ALTER TABLE students
    ADD COLUMN apaar_id bigint,
    ADD CONSTRAINT students_apaar_id_key UNIQUE (apaar_id),
    ADD CONSTRAINT students_apaar_id_positive CHECK (apaar_id > 0);

ALTER TABLE student_year
    ADD COLUMN join_date date,
    ADD COLUMN leave_date date,
    ADD CONSTRAINT student_year_leaves_after_joining
        CHECK (leave_date IS NULL OR join_date IS NULL OR leave_date >= join_date);

-- The roll as V23 defined it, less anybody who has left: a CR is appointed
-- from it and a student who has left cannot be one.
CREATE OR REPLACE FUNCTION roll_for_course(for_course bigint, session_year integer)
    RETURNS TABLE (student_id bigint, roll_no text)
    LANGUAGE sql STABLE
    BEGIN ATOMIC
        SELECT y.student_id, y.roll_no
        FROM student_year y
        WHERE y.academic_year = session_year
          AND y.leave_date IS NULL
          AND course_admits(for_course, session_year, y.student_id, y.year, y.stream,
                            y.major_dept, y.minor_dept, y.oe_dept);
    END;

-- A backup from a phone on this build is at schema 12. The importer's own
-- record refuses any version outside the range the shared migrations cover, so
-- the range moves with them, as V9 moved it to 11.
ALTER TABLE import_run
    DROP CONSTRAINT import_run_schema_range;

ALTER TABLE import_run
    ADD CONSTRAINT import_run_schema_range
        CHECK (backup_schema BETWEEN 1 AND 12);

COMMENT ON COLUMN import_run.backup_schema IS
    'The Room schema version the backup was written at. 1..12; the shared migrations bring anything in that range up to the current one before a row is read.';
