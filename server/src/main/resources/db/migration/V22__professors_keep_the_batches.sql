-- Batches, and which courses they are for, are the professors'.
--
-- A batch is how a course's roll gets said out loud: the practical half of a
-- class, the students who chose an open elective. The people who know that
-- are the ones teaching, so a batch is made, renamed and removed by
-- professors, and linked to a course by them -- any professor, for any
-- course, their own or a colleague's. An open elective is the reason it is
-- not narrower: the batch of students who chose one is often put together by
-- somebody who does not teach it, from students across the college.
--
-- Two changes, and both narrow as well as widen:
--
--   batch_desc        was any professor OR the office and the administrator.
--                     Now professors only.
--   course_batch_map  was whoever may write the course: its instructors, its
--                     coordinator, the head of its department, the office and
--                     the administrator. Now any professor, and only
--                     professors.
--
-- Reading is unchanged: everyone signed in reads batch_desc, and a link is
-- read by whoever may read its course.
--
-- batch_student_map is deliberately left alone. Membership is still written
-- by a professor for a student they can reach -- which is every student on
-- their phone, since that is what reached it -- and the office and a head
-- keep it because removing a student from the roll has to take them out of
-- their batches first, or the foreign key refuses a deletion they are
-- allowed to make. See V20.

DROP POLICY batch_desc_write ON batch_desc;

CREATE POLICY batch_desc_write ON batch_desc
    FOR ALL
    USING      (current_prof_id() IS NOT NULL)
    WITH CHECK (current_prof_id() IS NOT NULL);

DROP POLICY course_batch_map_write ON course_batch_map;

CREATE POLICY course_batch_map_write ON course_batch_map
    FOR ALL
    USING      (current_prof_id() IS NOT NULL)
    WITH CHECK (current_prof_id() IS NOT NULL);
