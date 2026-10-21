-- ════════════════════════════════════════════════════════════════════
-- V28 — An administrator ends somebody's sessions
-- ════════════════════════════════════════════════════════════════════
--
-- POST /api/v1/accounts/{id}/sessions/end revokes every live session of
-- an account: a lost phone, or a phone being handed to somebody else.
-- app_session is not audited by trigger (V8 says why), so the service
-- writes one SESSIONS_END entry naming the account and how many ended.
--
-- Appended, as V1's contract on enums requires; AuditAction gains the
-- same constant last.

ALTER TYPE audit_action ADD VALUE 'SESSIONS_END';
