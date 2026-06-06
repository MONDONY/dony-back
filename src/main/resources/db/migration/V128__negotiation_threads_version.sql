-- Optimistic-lock guard for negotiation_threads.
-- Serializes the AWAITING_PAYMENT -> ACCEPTED finalize between the synchronous
-- /checkout call and the Stripe webhook so the loser's commit fails (409) instead
-- of double-publishing PackageRequestAcceptedEvent (duplicate bid/QR/tracking).
ALTER TABLE negotiation_threads ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
