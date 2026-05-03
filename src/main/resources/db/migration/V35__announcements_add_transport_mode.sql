-- V35: Add transport_mode to announcements
-- Phase A of map markers refactor — semantic info for the new pin design.

ALTER TABLE announcements ADD COLUMN transport_mode VARCHAR(20);

UPDATE announcements SET transport_mode = 'OTHER' WHERE transport_mode IS NULL;

ALTER TABLE announcements ALTER COLUMN transport_mode SET NOT NULL;

ALTER TABLE announcements
  ADD CONSTRAINT chk_announcements_transport_mode
  CHECK (transport_mode IN ('PLANE','CAR','TRAIN','BUS','BOAT','OTHER'));
