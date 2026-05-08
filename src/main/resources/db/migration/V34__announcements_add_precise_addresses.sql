-- V34__announcements_add_precise_addresses.sql

-- 1. NETTOYAGE (ordre FK : feuilles → parents)
DELETE FROM conversations;
DELETE FROM payments;
DELETE FROM tracking_events;
DELETE FROM ratings;
DELETE FROM cancellations;
DELETE FROM rematch_suggestions;
DELETE FROM announcement_accepted_types;
DELETE FROM announcement_refused_types;
DELETE FROM bids;
DELETE FROM announcements;

-- 2. SUPPRESSION DES ANCIENS CHAMPS
ALTER TABLE announcements
    DROP COLUMN IF EXISTS departure_location,
    DROP COLUMN IF EXISTS arrival_location;

-- 3. AJOUT DES NOUVEAUX CHAMPS
ALTER TABLE announcements
    ADD COLUMN pickup_address_label   VARCHAR(500)  NOT NULL,
    ADD COLUMN pickup_lat             DECIMAL(9,6)  NOT NULL,
    ADD COLUMN pickup_lng             DECIMAL(9,6)  NOT NULL,
    ADD COLUMN delivery_address_label VARCHAR(500)  NOT NULL,
    ADD COLUMN delivery_lat           DECIMAL(9,6)  NOT NULL,
    ADD COLUMN delivery_lng           DECIMAL(9,6)  NOT NULL;

-- 4. INDEX SPATIAUX (pour Feature 2 — recherche proximité)
CREATE INDEX idx_announcements_pickup_coords
    ON announcements (pickup_lat, pickup_lng);

CREATE INDEX idx_announcements_delivery_coords
    ON announcements (delivery_lat, delivery_lng);
