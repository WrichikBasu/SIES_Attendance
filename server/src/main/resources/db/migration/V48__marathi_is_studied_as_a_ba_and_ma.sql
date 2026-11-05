-- ════════════════════════════════════════════════════════════════════
-- V48 — Marathi is studied as a BA and an MA
-- ════════════════════════════════════════════════════════════════════
--
-- Like every other arts subject. The rows V2 would have seeded had the
-- department been there from the start; AcademicRules.STREAMS gives it the
-- same two by default. PostgresSchemaDriftTest holds the two together.

INSERT INTO department_stream (department, stream, ordinal) VALUES
    ('MARATHI', 'BA', 1), ('MARATHI', 'MA', 2);
