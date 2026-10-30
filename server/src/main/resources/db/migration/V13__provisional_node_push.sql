-- =====================================================================
-- V13 — The node a device used before it had one.
--
-- Additive: one function.
--
--
-- WHAT WAS WRONG
--
-- A phone issues keys from the moment it is installed, long before it
-- has ever spoken to a server. Until it registers it has no assigned
-- node, so IdGenerator picks one for itself out of the self-picked half
-- of the range -- 32768 and up -- and every row created in that time
-- carries it. Registration then assigns a real node, 1 to 32767, and
-- V8 records the provisional one on device_registration because, as that
-- file says, registration is the only chance to record it.
--
-- The push then checked the key's node against the device's ASSIGNED
-- node and nothing else. So every row a phone created before it
-- registered -- which, for a device that has been in use for a term and
-- is only now being pointed at a server, is all of them -- was refused
-- as "issued by another node". V11 made that refusal final, so the
-- device dropped the intents, and the symptom was a sync that reported
-- success and moved nothing.
--
-- Measured on a real handset: a Samsung SM-M315F registered as node 1
-- with provisional node 37498. One row reached the server, and it was
-- academic_years -- the single synced table whose key is not issued by
-- a device, so the node check never applied to it. Everything else was
-- silently dropped.
--
--
-- WHY A DEFINER FUNCTION AND NOT A QUERY
--
-- device_registration's policies admit the account that registered the
-- device, which is usually the account pushing. Usually is not good
-- enough here: an admin may register a device for somebody else, and
-- then the row is invisible to the pusher and reads as "no provisional
-- node" -- which is the LEFT-JOIN-onto-an-RLS-table trap that already
-- cost this project a day, in the form of a revoked device looking like
-- no device at all. A definer function cannot be filtered into a wrong
-- answer, and device_session_binding() and device_seen() are here for
-- exactly the same reason.
--
--
-- WHAT THIS DOES NOT FIX
--
-- Two installs may have picked the same provisional node, which is why
-- V8 counts the clashes at registration. If both then push rows under
-- it, nothing in the data tells them apart, and this function makes the
-- server accept both. That was already true of the rows themselves and
-- is recorded rather than prevented: provisional_node_clashes on
-- device_registration is the number to look at when a key's history is
-- ever in question.
-- =====================================================================

CREATE FUNCTION device_provisional_node(device bigint) RETURNS integer
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = pg_catalog, public
    BEGIN ATOMIC
        SELECT d.provisional_node
        FROM device_registration d
        WHERE d.device_id = device
          AND d.revoked_at IS NULL;
    END;

COMMENT ON FUNCTION device_provisional_node(bigint) IS
    'The node this device issued keys under before it was assigned one, or null. The push accepts a key carrying it as well as one carrying the assigned node, because the rows a phone made before it registered are still its own. SECURITY DEFINER so a device registered by somebody else does not read as having no provisional node.';


-- ── Privileges ───────────────────────────────────────────────────────

DO $$
BEGIN
    EXECUTE 'REVOKE ALL ON FUNCTION device_provisional_node(bigint) FROM PUBLIC';

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sies_app') THEN
        EXECUTE 'GRANT EXECUTE ON FUNCTION device_provisional_node(bigint) TO sies_app';
    END IF;
END $$;
