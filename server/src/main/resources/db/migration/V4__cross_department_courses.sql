-- =====================================================================
-- V4 — A course taught by a department other than the students' own.
--
-- This is the first migration that CORRECTS an earlier one rather than
-- adding to it. V2's courses_department_offers_stream_fkey is too
-- strict, and the finding is written up in server/README.md.
--
--
-- WHAT V2 GOT WRONG
--
-- V2 said: a course's offering department must offer the course's
-- stream. That is true of a major. The roster query admits a student to
-- a major only when major_dept = offering_department, so the department
-- teaching it is the students' own and necessarily runs their stream.
--
-- It is NOT true of the other nine types, and the streams are
-- partitioned in a way that makes the difference total rather than
-- marginal. department_stream pairs MATHEMATICS with BSc and MSc only,
-- because the aided and unaided courses are stored as separate streams.
-- So a Mathematics minor read by a BSc_unaided Computer Science class
-- needs the row (MATHEMATICS, BSc_unaided), which V2 rejects — and the
-- roster query's minor branch, matching minor_dept against the offering
-- department, can therefore only ever reach another UNAIDED department.
--
-- Open electives come off worse. A BCom student's open elective must
-- carry stream BCom, which only COMMERCE offers, so oe_dept is forced
-- to COMMERCE: an open elective that cannot leave its own department.
-- Same for BAMMC and BMS, and across the aided/unaided line for every
-- science stream.
--
-- VSC, SEC, AEC and IKS are taken by a whole year and stream with no
-- department condition at all, so their offering department records
-- only who teaches them. An AEC read to a BSc_unaided class by the
-- English department is ordinary, and V2 rejects it too.
--
--
-- WHY A GENERATED COLUMN
--
-- The constraint is wanted for four of the thirteen course types and
-- wrong for the other nine, and PostgreSQL has no partial foreign key.
-- A generated column that is NULL for the exempt types gives one,
-- because a foreign key under the default MATCH SIMPLE is not checked
-- at all when any of its columns is null. The alternative — a trigger,
-- or a CHECK calling a function — would be enforced on the same rows
-- but would no longer be a foreign key, so the planner would stop
-- knowing about it and the referenced rows would stop being protected
-- from deletion.
--
-- The column is derived, never written. DeviceTables does not mention
-- it and the importer never supplies it, which is what keeps it out of
-- every INSERT the importer builds. PostgreSQL enforces that too:
-- writing a GENERATED ALWAYS column explicitly raises 428C9.
--
-- The one thing a generated column costs is ON UPDATE CASCADE, which
-- V2 had. PostgreSQL rejects the constraint outright — "invalid ON
-- UPDATE action for foreign key constraint containing generated
-- column" — because cascading an update would mean writing a column
-- that may not be written. RESTRICT in its place, which on this table
-- is no loss worth the name: department_stream is keyed by two enum
-- values, so an UPDATE to one of them would be a renamed enum label,
-- and PostgresSchemaDriftTest holds that seed to AcademicRules
-- constant by constant. Blocking such an update is the better answer
-- than quietly rewriting courses underneath it.
--
-- APPLIED TO AN EMPTY DATABASE, as V1 to V3 were. It would also apply
-- cleanly to a populated one: the new constraint accepts every row the
-- old one did and more, so no existing course can be caught by it.
-- =====================================================================


-- ── The stream, but only where the department must offer it ──────────
--
-- The type list is CourseType.followingOfferingDepartment(), and
-- PostgresSchemaDriftTest fails if the two drift apart. A course type
-- added to the enum and not considered here keeps the lenient
-- behaviour, which is the safe direction: it admits a row a stricter
-- reading would have refused, rather than refusing one that is correct.

ALTER TABLE courses
    ADD COLUMN own_department_stream degree_stream
        GENERATED ALWAYS AS (
            CASE WHEN course_type IN ('MAJOR', 'MAJOR_PRACTICALS', 'DSE', 'DSE_PRACTICALS')
                 THEN stream
            END
        ) STORED;

COMMENT ON COLUMN courses.own_department_stream IS
    'The course stream for the types whose offering department is the students'' own major department, and null for the rest. Derived; exists only to carry courses_department_offers_stream_fkey, which PostgreSQL cannot make partial. See CourseType.followsOfferingDepartment().';


-- ── Repoint the foreign key at it ────────────────────────────────────
--
-- Dropped and recreated under the SAME NAME. The constraint still says
-- the same thing and still produces "COMMERCE is not offered as BSc";
-- it just no longer says it about a minor. Keeping the name means the
-- error a client already handles does not change identity.

ALTER TABLE courses
    DROP CONSTRAINT courses_department_offers_stream_fkey;

ALTER TABLE courses
    ADD CONSTRAINT courses_department_offers_stream_fkey
        FOREIGN KEY (offering_department, own_department_stream)
        REFERENCES department_stream (department, stream)
        ON UPDATE RESTRICT ON DELETE RESTRICT;

-- V2 indexed (offering_department, stream) so that a change to a
-- referenced row did not have to scan courses. The constraint now
-- reads the generated pair, so that is the pair to index; the V2 index
-- stays, because it is also what the roster and the course lists filter
-- on.
CREATE INDEX courses_own_department_stream_idx
    ON courses (offering_department, own_department_stream);
