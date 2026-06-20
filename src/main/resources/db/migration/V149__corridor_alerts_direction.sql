-- Generalize corridor_alerts: owner may be a traveler (wants packages) or a sender (wants trips).
ALTER TABLE corridor_alerts RENAME COLUMN traveler_id TO owner_id;

ALTER TABLE corridor_alerts
    ADD COLUMN direction VARCHAR(32) NOT NULL DEFAULT 'TRAVELER_WANTS_PACKAGES';

-- Recreate the owner index under the new column name.
DROP INDEX IF EXISTS idx_corridor_alerts_traveler;
CREATE INDEX idx_corridor_alerts_owner ON corridor_alerts (owner_id);
