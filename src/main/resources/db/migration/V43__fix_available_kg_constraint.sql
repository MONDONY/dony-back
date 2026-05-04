-- Allow available_kg = 0 for FULL announcements
ALTER TABLE announcements
    DROP CONSTRAINT chk_announcements_available_kg;

ALTER TABLE announcements
    ADD CONSTRAINT chk_announcements_available_kg CHECK (available_kg >= 0);
