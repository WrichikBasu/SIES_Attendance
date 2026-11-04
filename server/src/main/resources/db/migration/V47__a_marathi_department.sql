-- ════════════════════════════════════════════════════════════════════
-- V47 — A Marathi department
-- ════════════════════════════════════════════════════════════════════
--
-- Appended, as V1's contract on enums requires; Department gains the same
-- constant last. On its own, because PostgreSQL will not let a value be
-- used in the transaction that added it: V48 says which stream it offers.

ALTER TYPE department ADD VALUE 'MARATHI';
