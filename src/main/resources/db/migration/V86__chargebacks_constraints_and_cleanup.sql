-- Drop the legacy processed_stripe_events table (entity deleted in Task 7, data migrated in V84)
DROP TABLE IF EXISTS processed_stripe_events;

-- Referential integrity: chargebacks.bid_id → bids.id
ALTER TABLE chargebacks
    ADD CONSTRAINT fk_chargebacks_bid FOREIGN KEY (bid_id) REFERENCES bids(id);

-- Index for admin list endpoint and future payment-level queries
CREATE INDEX idx_chargebacks_payment_id ON chargebacks(payment_id);
