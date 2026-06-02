-- Phase 2 — Codes promo réduisant le taux de commission Dony.
-- Cf. docs/specs/commission-rate-overrides-and-promo.md §5

CREATE TABLE IF NOT EXISTS promo_codes (
    id              UUID PRIMARY KEY,
    code            VARCHAR(40)  NOT NULL UNIQUE,
    rate            DECIMAL(4,3) NOT NULL
        CHECK (rate >= 0 AND rate < 1),
    target          VARCHAR(10)  NOT NULL DEFAULT 'ANY'
        CHECK (target IN ('SENDER', 'TRAVELER', 'ANY')),
    valid_from      TIMESTAMP,
    valid_to        TIMESTAMP,
    max_redemptions INTEGER,
    per_user_limit  INTEGER      NOT NULL DEFAULT 1,
    redeemed_count  INTEGER      NOT NULL DEFAULT 0,
    status          VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP,
    deleted_at      TIMESTAMP
);

CREATE TABLE IF NOT EXISTS promo_redemptions (
    id             UUID PRIMARY KEY,
    promo_code_id  UUID         NOT NULL REFERENCES promo_codes(id),
    user_id        UUID         NOT NULL,
    bid_id         UUID         NOT NULL,
    applied_rate   DECIMAL(4,3) NOT NULL,
    redeemed_at    TIMESTAMP    NOT NULL,
    UNIQUE (promo_code_id, bid_id)
);

-- Code promo entré à la création du bid (string brut, résolu en promo_code_id au paiement).
ALTER TABLE bids ADD COLUMN IF NOT EXISTS promo_code    VARCHAR(40);
-- FK figé au moment du paiement (dans la même tx que bids.commission_rate).
ALTER TABLE bids ADD COLUMN IF NOT EXISTS promo_code_id UUID REFERENCES promo_codes(id);
