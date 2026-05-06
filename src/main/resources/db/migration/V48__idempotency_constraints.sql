-- Unique on stripe_verification_session_id to deduplicate KYC webhooks
ALTER TABLE kyc_schema.kyc_verifications
    ADD CONSTRAINT uq_kyc_stripe_session_id
    UNIQUE (stripe_verification_session_id);

-- Replace full unique on kyc user_id with partial (excludes soft-deleted rows)
ALTER TABLE kyc_schema.kyc_verifications DROP CONSTRAINT IF EXISTS uq_kyc_user_id;
CREATE UNIQUE INDEX IF NOT EXISTS uq_kyc_user_id_active
    ON kyc_schema.kyc_verifications (user_id)
    WHERE deleted_at IS NULL;

-- Unique on stripe_account_id to prevent orphan Stripe Connect accounts
ALTER TABLE users
    ADD CONSTRAINT uq_users_stripe_account_id
    UNIQUE (stripe_account_id);

-- Prevent duplicate DEPART events per bid (stops double confirmation code generation)
CREATE UNIQUE INDEX IF NOT EXISTS uq_tracking_one_depart_per_bid
    ON tracking_events (bid_id)
    WHERE event_type = 'DEPART';

-- captured_at column on payments for atomic capture-once guard
ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS captured_at TIMESTAMPTZ;

-- Processed Stripe events table for webhook deduplication
CREATE TABLE IF NOT EXISTS processed_stripe_events (
    event_id     VARCHAR(255) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_processed_stripe_events PRIMARY KEY (event_id)
);
