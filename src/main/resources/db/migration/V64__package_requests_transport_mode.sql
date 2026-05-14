-- V64__package_requests_transport_mode.sql
-- Add transport_mode column to package_requests so a sender can specify
-- which mode of transport they want (PLANE/CAR/TRAIN/BUS/BOAT/OTHER).
-- Required for the "create dedicated trip" flow where the traveler's
-- new trip must match the sender's transport mode.

ALTER TABLE package_requests
    ADD COLUMN transport_mode VARCHAR(20);

-- Backfill existing rows: most flows are diaspora air-cargo, default to PLANE.
UPDATE package_requests
SET transport_mode = 'PLANE'
WHERE transport_mode IS NULL;

ALTER TABLE package_requests
    ALTER COLUMN transport_mode SET NOT NULL;

ALTER TABLE package_requests
    ADD CONSTRAINT chk_pkg_req_transport_mode CHECK (
        transport_mode IN ('PLANE', 'CAR', 'TRAIN', 'BUS', 'BOAT', 'OTHER')
    );
