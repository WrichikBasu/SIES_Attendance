-- ════════════════════════════════════════════════════════════════════
-- V67 — One class starts once (R15)
-- ════════════════════════════════════════════════════════════════════
--
-- A lecture was unique on its course, batch, start AND end, so the same
-- class could be filed twice at the same moment as long as the two
-- ended apart: 09:00-10:00 and 09:00-10:30 both went in, and every
-- student on the roll was counted at both. A batch cannot sit two
-- lectures of one course that start together; two such rows are one
-- class recorded twice with a typo in the end time.
--
-- The slot is now the course, the batch and the start. The end time is
-- an attribute of the lecture rather than part of what identifies it,
-- and correcting it is an edit. The whole-class partial index follows,
-- for the reason V2 gives: NULL batches are distinct in a unique index.
--
-- The old indexes are dropped rather than kept beside the new ones:
-- each is implied by its replacement, and the lookup they served is
-- served by the new ones' leading columns.
--
-- A database holding two lectures of one class that start together
-- refuses this migration, naming the index; they are one lecture, and
-- which of the two to keep is a person's decision. To find them:
--
--   SELECT course_id, batch_id, start_at, array_agg(lecture_id)
--   FROM lectures GROUP BY 1, 2, 3 HAVING count(*) > 1;

CREATE UNIQUE INDEX lectures_start_uq
    ON lectures (course_id, batch_id, start_at);

CREATE UNIQUE INDEX lectures_whole_class_start_uq
    ON lectures (course_id, start_at)
    WHERE batch_id IS NULL;

DROP INDEX lectures_slot_uq;
DROP INDEX lectures_whole_class_slot_uq;
