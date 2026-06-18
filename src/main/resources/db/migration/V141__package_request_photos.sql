-- Photos multiples du colis pour une demande d'envoi (jusqu'à 4 par demande).
-- Miroir simplifié de bid_photos (V140) — pas de lifecycle DELETING : ces photos
-- sont la source, elles sont copiées vers bids/ à la matérialisation du bid.
-- La colonne package_requests.photo_url (V57) reste = 1ère photo (rétro-compat lecture).
CREATE TABLE package_request_photos (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    package_request_id  UUID          NOT NULL REFERENCES package_requests(id),
    object_key          VARCHAR(1024) NOT NULL,
    position            INT           NOT NULL DEFAULT 0,
    created_at          TIMESTAMP     NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_prp_request ON package_request_photos(package_request_id);
