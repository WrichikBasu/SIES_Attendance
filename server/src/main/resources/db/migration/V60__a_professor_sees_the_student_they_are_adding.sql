-- ════════════════════════════════════════════════════════════════════
-- V60 — A professor sees the student they are adding
-- ════════════════════════════════════════════════════════════════════
--
-- V27 let a professor add students to their own department, and the
-- policy test proved it with a plain INSERT. No phone sends a plain
-- INSERT. The sync path writes every row as INSERT ... ON CONFLICT DO
-- UPDATE, and for that statement PostgreSQL holds the new row to the
-- table's SELECT policies as well as its INSERT policy: the statement
-- could have landed on an existing row, and it may not land on one the
-- actor cannot read.
--
-- A professor reads a student through students_professor_read, which
-- asks for an enrolment in their department. A student being added has
-- no enrolment yet -- the enrolment is the next row in the push, and
-- its foreign key needs the student first. So every student a
-- professor added from a phone was refused as insufficient privilege,
-- and every enrolment after it then failed its foreign key. A head
-- never met this: students_head is FOR ALL and admits a student with no
-- enrolment, so its USING is the SELECT that passes.
--
-- The rule now: a professor also reads a student every enrolment of
-- whom is in their department, or who has none. That is
-- actor_prof_holds_student, the rule V27 already writes by, so what a
-- professor may read now covers what they may write. Against the reads
-- they already had, it adds exactly one thing: students with no
-- enrolment at all. Such a row is one being added, or one whose
-- enrolment was refused -- the roll is never kept that way on purpose,
-- and nothing in it is more than a name and a contact.
--
-- student_year is ENABLED and not FORCED (V11), so the SECURITY DEFINER
-- helper reads every department's enrolments, not only the actor's: a
-- Chemistry-only student is not mistaken for an unenrolled one by a
-- Computer Science professor who cannot see the Chemistry row.

CREATE POLICY students_professor_unenrolled ON students
    FOR SELECT
    USING (actor_prof_holds_student(student_id));
