-- V100__user_business_preferences.sql
CREATE TABLE user_business_preferences (
    user_id                    UUID        PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    weight_unit                VARCHAR(3)  NOT NULL DEFAULT 'kg',
    currency_code              VARCHAR(3)  NOT NULL DEFAULT 'EUR',
    pickup_radius_km           INTEGER     NOT NULL DEFAULT 10,
    default_package_weight_kg  INTEGER     NOT NULL DEFAULT 23,
    min_bid_price_eur          INTEGER     NOT NULL DEFAULT 0,
    contact_mode               VARCHAR(10) NULL,
    response_delay_hours       INTEGER     NULL,
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE user_business_preferences
    ADD CONSTRAINT chk_weight_unit    CHECK (weight_unit IN ('kg', 'lbs')),
    ADD CONSTRAINT chk_currency       CHECK (currency_code IN ('EUR', 'XOF', 'XAF')),
    ADD CONSTRAINT chk_pickup_radius  CHECK (pickup_radius_km BETWEEN 1 AND 50),
    ADD CONSTRAINT chk_pkg_weight     CHECK (default_package_weight_kg BETWEEN 1 AND 50),
    ADD CONSTRAINT chk_min_bid        CHECK (min_bid_price_eur BETWEEN 0 AND 50),
    ADD CONSTRAINT chk_contact_mode   CHECK (contact_mode IN ('call', 'message', 'both')),
    ADD CONSTRAINT chk_response_delay CHECK (response_delay_hours >= 1);
