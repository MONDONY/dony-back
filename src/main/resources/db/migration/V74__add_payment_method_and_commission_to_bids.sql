ALTER TABLE bids ADD COLUMN IF NOT EXISTS payment_method VARCHAR(10) NOT NULL DEFAULT 'STRIPE'
    CHECK (payment_method IN ('STRIPE', 'CASH'));

ALTER TABLE bids ADD COLUMN IF NOT EXISTS commission_payment_intent_id VARCHAR(255);

ALTER TABLE bids ADD COLUMN IF NOT EXISTS commission_status VARCHAR(20)
    CHECK (commission_status IN ('PENDING', 'REQUIRES_3DS', 'CHARGED', 'FAILED', 'REFUNDED', 'REFUND_FAILED'));

ALTER TABLE bids ADD COLUMN IF NOT EXISTS commission_retry_count INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_bids_commission_requires_3ds ON bids(updated_at)
    WHERE commission_status = 'REQUIRES_3DS';

CREATE INDEX IF NOT EXISTS idx_bids_payment_method ON bids(payment_method);
