-- V156 : add flagged column to ratings
ALTER TABLE ratings
    ADD COLUMN IF NOT EXISTS flagged BOOLEAN NOT NULL DEFAULT FALSE;
