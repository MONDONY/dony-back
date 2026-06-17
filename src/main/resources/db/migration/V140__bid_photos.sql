-- Photos optionnelles du colis jointes à la création d'un bid.
-- Cycle de vie : ACTIVE (visible) -> DELETING (caché, en attente) -> purge cron (suppression S3 + ligne).
CREATE TABLE bid_photos (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    bid_id         UUID          NOT NULL REFERENCES bids(id),
    object_key     VARCHAR(1024) NOT NULL,
    position       INT           NOT NULL DEFAULT 0,
    status         VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE'
                       CONSTRAINT chk_bid_photos_status CHECK (status IN ('ACTIVE', 'DELETING')),
    deleting_since TIMESTAMP,
    created_at     TIMESTAMP     NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_bid_photos_bid ON bid_photos(bid_id);
CREATE INDEX idx_bid_photos_status ON bid_photos(status);
