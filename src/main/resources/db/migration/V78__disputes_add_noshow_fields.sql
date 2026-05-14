ALTER TABLE disputes ALTER COLUMN payment_id   DROP NOT NULL;
ALTER TABLE disputes ALTER COLUMN reporter_id  DROP NOT NULL;
ALTER TABLE disputes ALTER COLUMN reason       DROP NOT NULL;

ALTER TABLE disputes ADD COLUMN IF NOT EXISTS bid_id        UUID REFERENCES bids(id);
ALTER TABLE disputes ADD COLUMN IF NOT EXISTS sender_id     UUID REFERENCES users(id);
ALTER TABLE disputes ADD COLUMN IF NOT EXISTS traveler_id   UUID REFERENCES users(id);
ALTER TABLE disputes ADD COLUMN IF NOT EXISTS type          VARCHAR(50);
ALTER TABLE disputes ADD COLUMN IF NOT EXISTS refund_frozen BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_disputes_bid_id  ON disputes (bid_id);
CREATE INDEX IF NOT EXISTS idx_disputes_type    ON disputes (type);
