-- V15: Add departure/arrival time and location to announcements

ALTER TABLE announcements
    ADD COLUMN IF NOT EXISTS departure_time     TIME,
    ADD COLUMN IF NOT EXISTS arrival_time       TIME,
    ADD COLUMN IF NOT EXISTS departure_location VARCHAR(255),
    ADD COLUMN IF NOT EXISTS arrival_location   VARCHAR(255);