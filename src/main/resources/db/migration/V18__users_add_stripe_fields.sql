-- Story 6.2 — Onboarding Stripe Connect voyageur
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS stripe_account_id  VARCHAR(64),
    ADD COLUMN IF NOT EXISTS stripe_onboarded   BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_users_stripe_account_id ON users(stripe_account_id);
