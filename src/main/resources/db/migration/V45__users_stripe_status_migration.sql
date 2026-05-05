-- V45: Add stripe_account_status enum column and timestamps, backfill from stripe_onboarded boolean.

ALTER TABLE users ADD COLUMN IF NOT EXISTS stripe_account_status VARCHAR(32) NOT NULL DEFAULT 'NOT_CREATED';
ALTER TABLE users ADD COLUMN IF NOT EXISTS stripe_account_created_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS stripe_onboarding_completed_at TIMESTAMPTZ;

-- Backfill from stripe_onboarded boolean (column added in V18)
UPDATE users SET stripe_account_status = 'ONBOARDING_COMPLETE' WHERE stripe_onboarded = TRUE;
UPDATE users SET stripe_account_status = 'PENDING_ONBOARDING'
    WHERE stripe_account_id IS NOT NULL AND stripe_onboarded = FALSE;

-- Note: stripe_onboarded boolean is kept for backward compatibility during transition (PR-1).
-- It will be dropped in a future migration once all code paths use stripe_account_status.
