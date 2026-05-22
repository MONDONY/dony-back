CREATE TABLE traveler_price_grid_items (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    traveler_id     UUID         NOT NULL REFERENCES users(id),
    label           VARCHAR(100) NOT NULL,
    unit_price_net  DECIMAL(10,2) NOT NULL CHECK (unit_price_net > 0),
    position        INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMP
);
CREATE INDEX idx_traveler_price_grid_items_traveler ON traveler_price_grid_items(traveler_id)
    WHERE deleted_at IS NULL;
