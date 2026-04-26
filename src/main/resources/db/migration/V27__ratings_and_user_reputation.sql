-- Epic 9 — Profils & Évaluations
-- Ajoute les champs de réputation sur users, la table ratings et les champs de refus sur bids

ALTER TABLE users
    ADD COLUMN average_rating   DECIMAL(3,2),
    ADD COLUMN kilo_pro         BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN kilo_pro_granted_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN no_show_count    INT NOT NULL DEFAULT 0,
    ADD COLUMN refused_count    INT NOT NULL DEFAULT 0;

CREATE TABLE ratings (
    id              UUID PRIMARY KEY,
    rater_id        UUID,
    rated_user_id   UUID NOT NULL,
    bid_id          UUID NOT NULL,
    tracking_token  VARCHAR(36),
    stars           SMALLINT NOT NULL CHECK (stars BETWEEN 1 AND 5),
    comment         TEXT,
    excluded_from_average BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    deleted_at      TIMESTAMP WITH TIME ZONE
);

-- Un expéditeur ne peut noter qu'une fois par bid
CREATE UNIQUE INDEX idx_ratings_bid_rater
    ON ratings (bid_id, rater_id)
    WHERE rater_id IS NOT NULL AND deleted_at IS NULL;

-- Un destinataire (par trackingToken) ne peut noter qu'une fois par bid
CREATE UNIQUE INDEX idx_ratings_bid_tracking_token
    ON ratings (bid_id, tracking_token)
    WHERE tracking_token IS NOT NULL AND deleted_at IS NULL;

CREATE INDEX idx_ratings_rated_user_id ON ratings (rated_user_id);

-- Champs de refus de colis sur bids (Story 9.4)
ALTER TABLE bids
    ADD COLUMN refusal_reason     TEXT,
    ADD COLUMN refusal_photo_url  TEXT,
    ADD COLUMN no_show_at         TIMESTAMP WITH TIME ZONE;
