ALTER TABLE bids
    ADD COLUMN pricing_mode VARCHAR(10) NOT NULL DEFAULT 'KG'
        CONSTRAINT chk_bids_pricing_mode CHECK (pricing_mode IN ('KG', 'GRID', 'MIXED')),
    ALTER COLUMN weight_kg DROP NOT NULL;

CREATE TABLE bid_grid_items (
    id                          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    bid_id                      UUID         NOT NULL REFERENCES bids(id),
    announcement_grid_item_id   UUID         NOT NULL REFERENCES announcement_price_grid_items(id),
    label_snapshot              VARCHAR(100) NOT NULL,
    unit_price_net_snapshot     DECIMAL(10,2) NOT NULL,
    quantity                    INT          NOT NULL CHECK (quantity > 0),
    created_at                  TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_bid_grid_items_bid ON bid_grid_items(bid_id);
