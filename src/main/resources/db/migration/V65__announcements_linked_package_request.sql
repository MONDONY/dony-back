-- V65__announcements_linked_package_request.sql
-- Add linked_package_request_id to announcements so a trip can be marked
-- as "private/dedicated" to a specific package_request — created via the
-- POST /negotiations/{id}/create-dedicated-trip endpoint when no existing
-- trip matches.
--
-- Trips with linked_package_request_id IS NOT NULL must be excluded from
-- the public announcements search (only the negotiating sender ever sees them).

ALTER TABLE announcements
    ADD COLUMN linked_package_request_id UUID;

ALTER TABLE announcements
    ADD CONSTRAINT fk_announcements_linked_request
    FOREIGN KEY (linked_package_request_id)
    REFERENCES package_requests (id)
    ON DELETE SET NULL;

CREATE INDEX idx_announcements_linked_request
    ON announcements (linked_package_request_id)
    WHERE linked_package_request_id IS NOT NULL;
