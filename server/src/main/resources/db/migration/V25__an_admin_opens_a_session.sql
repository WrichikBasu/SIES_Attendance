-- Only an administrator adds an academic session.
--
-- A session is the calendar every register in the college is filed against:
-- which year a lecture belongs to is decided by the session whose dates
-- contain it. Until now academic_years was written FOR ALL by
-- actor_may_manage_roster(), the office or an administrator, so either could
-- open a new year. Opening one is now an administrator's alone. Correcting a
-- session's dates and removing one stay as they were, with the office and an
-- administrator.
--
-- FOR ALL is split into the three verbs rather than narrowed, because it is
-- also SELECT, and reading the calendar is academic_years_read's: everyone
-- signed in.

DROP POLICY academic_years_office ON academic_years;

CREATE POLICY academic_years_insert ON academic_years
    FOR INSERT
    WITH CHECK (actor_has_role('ADMIN'));

CREATE POLICY academic_years_update ON academic_years
    FOR UPDATE
    USING      (actor_may_manage_roster())
    WITH CHECK (actor_may_manage_roster());

CREATE POLICY academic_years_delete ON academic_years
    FOR DELETE
    USING (actor_may_manage_roster());
