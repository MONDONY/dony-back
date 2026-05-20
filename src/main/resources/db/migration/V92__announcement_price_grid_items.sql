CREATE TABLE announcement_price_grid_items (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    announcement_id  UUID         NOT NULL REFERENCES announcements(id),
    label            VARCHAR(100) NOT NULL,
    unit_price_net   DECIMAL(10,2) NOT NULL CHECK (unit_price_net > 0),
    position         INT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ann_price_grid_items_ann ON announcement_price_grid_items(announcement_id);
