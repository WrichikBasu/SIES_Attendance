-- ════════════════════════════════════════════════════════════════════
-- V51 — A pull says what decides its access
-- ════════════════════════════════════════════════════════════════════
--
-- A pull after the first is the audit log replayed: the rows that
-- changed. Access can change without the rows it governs changing. A
-- professor moved to another department loses that department's batches,
-- and nothing about the batches or their members is written, so the feed
-- says nothing and the phone keeps them for ever. The same is true of
-- gaining access: rows that become readable without being written never
-- arrive.
--
-- So every pull carries a fingerprint of what decides the actor's own
-- access, and a phone that sees it change downloads everything it may
-- read again and removes what did not come back (see OutboxDrain.sweep).
--
-- What goes in is what the policies ask about the actor rather than
-- about the rows: who they are, their live roles (an HOD role carries a
-- department and a session), their department and designation, the
-- courses they coordinate or teach, and their live CR grants. A grant
-- that expires changes it too, because it is read against now().
--
-- What does not go in is everything else a policy reads -- a batch's
-- department, which batches a course is linked to, which students a
-- course admits. Those are other rows, and when one of them changes the
-- feed carries that change: SyncReader sends a deletion for an updated
-- row the actor can no longer read, and the phone falls back to a sweep
-- when such a deletion cannot be applied. A weekly sweep covers what
-- neither catches.
--
-- A policy that starts asking about something new about the actor has to
-- be added here as well, or a change to it reaches the phone only at the
-- weekly sweep.

CREATE FUNCTION actor_access_fingerprint() RETURNS text
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    RETURN md5(concat_ws('|',
        current_account_id(),
        current_prof_id(),
        current_student_id(),

        (SELECT string_agg(concat_ws(':', ur.role, ur.department, ur.academic_year), ','
                           ORDER BY ur.role, ur.department, ur.academic_year)
         FROM user_role ur
         WHERE ur.account_id = current_account_id()
           AND ur.revoked_at IS NULL),

        (SELECT concat_ws(':', p.department, designation_is_heads(p.designation))
         FROM professors p
         WHERE p.prof_id = current_prof_id()),

        (SELECT string_agg(ci.course_id::text, ',' ORDER BY ci.course_id)
         FROM course_instructor ci
         WHERE ci.prof_id = current_prof_id()),

        (SELECT string_agg(c.course_id::text, ',' ORDER BY c.course_id)
         FROM courses c
         WHERE c.prof_id = current_prof_id()),

        (SELECT string_agg(concat_ws(':', d.course_id, d.academic_year), ','
                           ORDER BY d.course_id, d.academic_year)
         FROM attendance_delegate d
         WHERE d.student_id = current_student_id()
           AND d.revoked_at IS NULL
           AND (d.expires_at IS NULL OR d.expires_at > now()))));

COMMENT ON FUNCTION actor_access_fingerprint() IS
    'A digest of what decides the current actor''s own access: account, roles, department, designation, courses coordinated and taught, live CR grants. Sent with every pull; a phone that sees it change downloads everything again and removes what it may no longer read (V51).';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_app') THEN
        GRANT EXECUTE ON FUNCTION actor_access_fingerprint() TO sies_app;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_auditor') THEN
        GRANT EXECUTE ON FUNCTION actor_access_fingerprint() TO sies_auditor;
    END IF;
END $$;
